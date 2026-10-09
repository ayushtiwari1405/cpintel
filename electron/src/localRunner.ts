import { spawn } from 'child_process'
import fs from 'fs/promises'
import os from 'os'
import path from 'path'

/**
 * Compiles and runs a solution with the toolchains installed on this computer.
 *
 * Outside examinations the desktop app runs code here rather than on the CPIntel server, whose
 * runner is kept for examinations. It is the student's own code on the student's own machine,
 * so there is no sandbox — the same footing as running it from a terminal — but every run gets
 * a fresh temporary directory, a time limit and an output cap, like the server's runner.
 *
 * This only executes. Verdicts are decided by the page (runner/judge.ts), so the desktop app,
 * the browser engine and the server compare output the same way.
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

interface Toolchain {
  info: Omit<RuntimeInfo, 'available' | 'unavailableReason'>
  source: string
  build: ((dir: string) => string[]) | null
  run: (dir: string) => string[]
}

const IS_WIN = process.platform === 'win32'
const IS_MAC = process.platform === 'darwin'

/** Runs a command to see whether it exists and answers; never throws. */
function probe(command: string, args: string[]): Promise<string | null> {
  return new Promise(resolve => {
    let out = ''
    let child
    try {
      child = spawn(command, args, { windowsHide: true })
    } catch {
      resolve(null)
      return
    }
    const timer = setTimeout(() => { child.kill(); resolve(null) }, 5000)
    child.stdout?.on('data', d => { out += d })
    child.stderr?.on('data', d => { out += d })
    child.on('error', () => { clearTimeout(timer); resolve(null) })
    child.on('close', code => { clearTimeout(timer); resolve(code === 0 ? out : null) })
  })
}

async function findCpp(): Promise<Toolchain | null> {
  // On a Mac without the command line tools, merely running g++ pops up an installer. Ask
  // xcode-select first, which only answers.
  if (IS_MAC && (await probe('xcode-select', ['-p'])) === null) return null
  if ((await probe('g++', ['--version'])) === null) return null

  const program = IS_WIN ? 'program.exe' : 'program'
  return {
    info: { id: 'cpp', displayName: 'C++ (g++ on this computer)', editorLanguage: 'cpp' },
    source: 'main.cpp',
    // The server runner's flags. Static runtime libraries where the compiler supports them,
    // so a MinGW build does not depend on finding libstdc++'s DLL; Apple's g++ is Clang,
    // which does not take them.
    build: () => ['g++', '-std=c++20', '-O2', '-pipe',
      ...(IS_MAC ? [] : ['-static-libstdc++', '-static-libgcc']),
      '-o', program, 'main.cpp'],
    run: dir => [path.join(dir, program)],
  }
}

async function findPython(): Promise<Toolchain | null> {
  // "python" on Windows may be the Microsoft Store placeholder, which fails --version and is
  // skipped; "py -3" is the launcher the python.org installer puts on PATH.
  const candidates: string[][] = IS_WIN
    ? [['python'], ['py', '-3'], ['python3']]
    : [['python3'], ['python']]
  for (const [command, ...pre] of candidates) {
    const version = await probe(command, [...pre, '--version'])
    if (version && /Python 3\./.test(version)) {
      return {
        info: { id: 'python3', displayName: 'Python 3 (on this computer)', editorLanguage: 'python' },
        source: 'main.py',
        // -I as on the server: no user site-packages or PYTHON* variables, so a run does not
        // depend on what this machine happens to have installed.
        build: () => [command, ...pre, '-I', '-m', 'py_compile', 'main.py'],
        run: () => [command, ...pre, '-I', 'main.py'],
      }
    }
  }
  return null
}

let toolchains: Promise<Map<string, Toolchain>> | null = null

function detect(): Promise<Map<string, Toolchain>> {
  toolchains ??= Promise.all([findCpp(), findPython()]).then(found => {
    const map = new Map<string, Toolchain>()
    for (const t of found) if (t) map.set(t.info.id, t)
    return map
  })
  return toolchains
}

/** Languages this computer can build. Missing ones are left to the page's browser engine. */
export async function languages(): Promise<RuntimeInfo[]> {
  const found = await detect()
  return [...found.values()].map(t => ({ ...t.info, available: true, unavailableReason: null }))
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

    const child = spawn(argv[0], argv.slice(1), {
      cwd,
      windowsHide: true,
      env: { ...process.env, PYTHONIOENCODING: 'utf-8' },
    })
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

    const finish = (exitCode: number | null, extra = '') => {
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
  const toolchain = (await detect()).get(request.language)
  if (!toolchain) {
    return { compiled: false, compileOutput: '', compileMs: 0, runs: [],
      error: `There is no ${request.language} toolchain on this computer.` }
  }

  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'cpintel-run-'))
  try {
    await fs.writeFile(path.join(dir, toolchain.source), request.source)

    let compileMs = 0
    let compileOutput = ''
    if (toolchain.build) {
      const built = await exec(toolchain.build(dir), dir, '', request.compileTimeLimitMs,
        request.outputLimitBytes)
      compileMs = built.durationMs
      compileOutput = [built.stdout, built.stderr].filter(s => s.trim()).join('\n')
      if (built.timedOut) {
        return { compiled: false, compileMs, runs: [],
          compileOutput: `Compilation timed out after ${request.compileTimeLimitMs} ms.` }
      }
      if (built.exitCode !== 0) return { compiled: false, compileOutput, compileMs, runs: [] }
    }

    const runs: Exec[] = []
    for (const input of request.inputs) {
      runs.push(await exec(toolchain.run(dir), dir, input, request.timeLimitMs,
        request.outputLimitBytes))
    }
    return { compiled: true, compileOutput, compileMs, runs }
  } finally {
    fs.rm(dir, { recursive: true, force: true }).catch(() => {})
  }
}
