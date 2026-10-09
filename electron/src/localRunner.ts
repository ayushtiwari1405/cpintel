import { spawn } from 'child_process'
import fs from 'fs'
import os from 'os'
import path from 'path'

/**
 * Compiles and runs a solution on this computer, with the toolchains the app ships with.
 *
 * Outside examinations the desktop app runs code here rather than on the CPIntel server, whose
 * runner is kept for examinations. Nothing has to be installed: the build bundles its compilers
 * (scripts/fetch-toolchains.mjs) — GCC on Windows, Zig's Clang on macOS and Linux, and CPython
 * everywhere — so every student gets the same compiler whatever their machine has. It is the
 * student's own code on the student's own machine, so there is no sandbox, the same footing as
 * running it from a terminal; every run still gets a fresh temporary directory, a time limit and
 * an output cap, like the server's runner.
 *
 * This only executes. Verdicts are decided by the page (runner/judge.ts), so the desktop app, the
 * browser engine and the server compare output the same way.
 */

export interface RuntimeInfo {
  id: string
  displayName: string
  editorLanguage: string
  available: boolean
  unavailableReason: string | null
}

export interface RunRequest {
  language: string
  source: string
  inputs: string[]
  timeLimitMs: number
  compileTimeLimitMs: number
  outputLimitBytes: number
}

interface Exec {
  stdout: string
  stderr: string
  exitCode: number | null
  timedOut: boolean
  durationMs: number
  truncated: boolean
}

export interface RawRun {
  compiled: boolean
  compileOutput: string
  compileMs: number
  runs: Exec[]
  error?: string
}

/** What fetch-toolchains.mjs bundled, from toolchain/manifest.json. */
interface Manifest {
  cpp: { kind: 'gcc' | 'zig'; compiler: string; include?: string }
  python: { interpreter: string }
}

interface Toolchain {
  info: RuntimeInfo
  source: string
  build: string[]
  /** Takes the run's directory: Windows resolves a relative program path against ours. */
  run: (dir: string) => string[]
}

const IS_WIN = process.platform === 'win32'

/** Codeforces runs solutions with a 256 MB stack; deep recursion depends on it. */
const STACK_BYTES = 256 * 1024 * 1024

let toolchains = new Map<string, Toolchain>()
let env: NodeJS.ProcessEnv = process.env
let warming: Promise<void> | null = null

/**
 * Reads the bundled toolchains. A tree without them — a dev checkout that has not run
 * `npm run toolchain` — offers no languages, and the page runs code in its browser engine.
 *
 * @param toolchainDir where the build put toolchain/ (resources when packaged)
 * @param cacheDir somewhere writable that survives restarts, for Zig's build cache
 */
export function configure(toolchainDir: string, cacheDir: string) {
  // Absolute: every command runs from its own temporary directory.
  toolchainDir = path.resolve(toolchainDir)
  let manifest: Manifest
  try {
    manifest = JSON.parse(fs.readFileSync(path.join(toolchainDir, 'manifest.json'), 'utf8'))
  } catch {
    toolchains = new Map()
    return
  }
  const at = (p: string) => path.join(toolchainDir, p)
  const program = IS_WIN ? 'program.exe' : 'program'

  // Zig keeps compiled libc++ in its cache, which the first compile fills (~15 s); kept in the
  // app's data folder so that happens once per installation, not once per run.
  env = { ...process.env, ZIG_GLOBAL_CACHE_DIR: cacheDir, ZIG_LOCAL_CACHE_DIR: cacheDir,
    PYTHONIOENCODING: 'utf-8' }

  const cpp = manifest.cpp
  const cppBuild = cpp.kind === 'gcc'
    // Static, so the program does not need GCC's DLLs on PATH; the stack as on Codeforces,
    // which on Windows is fixed at link time.
    ? [at(cpp.compiler), '-std=c++20', '-O2', '-pipe', '-static',
      `-Wl,--stack=${STACK_BYTES}`, '-o', program, 'main.cpp']
    : [at(cpp.compiler), 'c++', '-std=c++20', '-O2', `-I${at(cpp.include ?? 'cpp-include')}`,
      '-o', program, 'main.cpp']

  // -I as on the server: no user site-packages or PYTHON* variables.
  const python = at(manifest.python.interpreter)

  toolchains = new Map([
    ['cpp', {
      info: { id: 'cpp', displayName: cpp.kind === 'gcc' ? 'C++ (g++, built in)' : 'C++ (Clang, built in)',
        editorLanguage: 'cpp', available: true, unavailableReason: null },
      source: 'main.cpp',
      build: cppBuild,
      run: dir => [path.join(dir, program)],
    }],
    ['python3', {
      info: { id: 'python3', displayName: 'Python 3.13 (built in)', editorLanguage: 'python',
        available: true, unavailableReason: null },
      source: 'main.py',
      build: [python, '-I', '-m', 'py_compile', 'main.py'],
      run: () => [python, '-I', 'main.py'],
    }],
  ])
}

/** Languages this build carries. */
export function languages(): RuntimeInfo[] {
  return [...toolchains.values()].map(t => t.info)
}

/**
 * Compiles a small program once in the background, so the student's first Run does not pay for
 * Zig building libc++, or for an antivirus scanning the compiler on first use.
 */
export function warmUp(): Promise<void> {
  warming ??= (async () => {
    const cpp = toolchains.get('cpp')
    if (!cpp) return
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cpintel-warm-'))
    try {
      fs.writeFileSync(path.join(dir, 'main.cpp'),
        '#include <bits/stdc++.h>\nint main() { std::cout << 1 << std::endl; }\n')
      await exec(cpp.build, dir, '', 180_000, 65_536)
    } finally {
      fs.rm(dir, { recursive: true, force: true }, () => {})
    }
  })()
  return warming
}

/**
 * The command as spawned. On macOS and Linux the stack limit is a process limit rather than a
 * property of the binary, so the program is started through sh with it raised as far as the
 * system allows (unlimited on Linux, 64 MB on macOS).
 */
function command(argv: string[]): [string, string[]] {
  if (IS_WIN) return [argv[0], argv.slice(1)]
  return ['/bin/sh', ['-c',
    'ulimit -s unlimited 2>/dev/null || ulimit -s "$(ulimit -H -s)" 2>/dev/null; exec "$@"',
    'sh', ...argv]]
}

function exec(argv: string[], cwd: string, input: string, timeoutMs: number, limit: number)
  : Promise<Exec> {
  return new Promise(resolve => {
    const started = Date.now()
    const out: Buffer[] = []
    const err: Buffer[] = []
    let outSize = 0
    let errSize = 0
    let truncated = false
    let timedOut = false

    const [file, args] = command(argv)
    const child = spawn(file, args, { cwd, env, windowsHide: true })
    const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL') }, timeoutMs)

    const take = (into: Buffer[], size: number, chunk: Buffer): number => {
      const room = limit - size
      if (chunk.length > room) truncated = true
      if (room > 0) into.push(chunk.subarray(0, room))
      return size + Math.min(chunk.length, Math.max(room, 0))
    }
    child.stdout.on('data', (c: Buffer) => { outSize = take(out, outSize, c) })
    child.stderr.on('data', (c: Buffer) => { errSize = take(err, errSize, c) })
    // A program that exits without reading its input closes the pipe under us.
    child.stdin.on('error', () => {})
    child.stdin.end(input)

    let done = false
    const finish = (exitCode: number | null, extra = '') => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve({
        stdout: Buffer.concat(out).toString('utf8'),
        stderr: Buffer.concat(err).toString('utf8') + extra,
        exitCode: timedOut ? null : exitCode,
        timedOut,
        durationMs: Date.now() - started,
        truncated,
      })
    }
    child.on('error', e => finish(127, e.message))
    child.on('close', (code, signal) => finish(code ?? (signal ? 128 : 1)))
  })
}

export async function run(request: RunRequest): Promise<RawRun> {
  const toolchain = toolchains.get(request.language)
  if (!toolchain) {
    return { compiled: false, compileOutput: '', compileMs: 0, runs: [],
      error: `This build of the app cannot run ${request.language}.` }
  }
  // The first compile after installing fills Zig's cache; let a warm-up in progress finish
  // rather than compile alongside it and run into the time limit.
  if (request.language === 'cpp' && warming) await warming

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cpintel-run-'))
  try {
    fs.writeFileSync(path.join(dir, toolchain.source), request.source)

    const built = await exec(toolchain.build, dir, '', request.compileTimeLimitMs,
      request.outputLimitBytes)
    const compileMs = built.durationMs
    const compileOutput = [built.stdout, built.stderr].filter(s => s.trim()).join('\n')
      .split(dir + path.sep).join('')
    if (built.timedOut) {
      return { compiled: false, compileMs, runs: [],
        compileOutput: `Compilation timed out after ${request.compileTimeLimitMs} ms.` }
    }
    if (built.exitCode !== 0) return { compiled: false, compileOutput, compileMs, runs: [] }

    const runs: Exec[] = []
    for (const input of request.inputs) {
      runs.push(await exec(toolchain.run(dir), dir, input, request.timeLimitMs,
        request.outputLimitBytes))
    }
    return { compiled: true, compileOutput, compileMs, runs }
  } finally {
    fs.rm(dir, { recursive: true, force: true }, () => {})
  }
}
