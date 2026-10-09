// Puts the compilers the desktop app ships with into electron/toolchain, for electron-builder
// to package as an extra resource (see "extraResources" in package.json).
//
// The desktop app runs code on the student's computer outside examinations, and must do so
// whether or not anything is installed there, so it carries its own toolchains:
//
//   Windows        C++  GCC (WinLibs MinGW-w64, UCRT) — what Codeforces' G++ compilers are
//   macOS, Linux   C++  Zig's Clang + libc++ (on a Mac, "g++" is Clang anyway)
//   everywhere     Python  CPython from python-build-standalone
//
// Every download is pinned and checked against its SHA-256 before it is unpacked. Archives are
// kept in .toolchain-cache, so a rebuild does not download them again.
//
//   node scripts/fetch-toolchains.mjs                 # for this machine
//   CPINTEL_TOOLCHAIN_TARGET=win32-x64 node ...       # for another platform (testing)
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  cpSync, createReadStream, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync,
  writeFileSync,
  renameSync,
} from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const CACHE = join(ROOT, '.toolchain-cache')
const OUT = join(ROOT, 'toolchain')
const TARGET = process.env.CPINTEL_TOOLCHAIN_TARGET ?? `${process.platform}-${process.arch}`

const PBS = 'https://github.com/astral-sh/python-build-standalone/releases/download/20260901'
const py = (triple, sha256) => ({
  url: `${PBS}/cpython-3.13.15%2B20260901-${triple}-install_only_stripped.tar.gz`, sha256,
})
const zig = (name, sha256) => ({
  url: `https://ziglang.org/download/0.16.0/zig-${name}-0.16.0.tar.xz`, sha256,
})

const TARGETS = {
  'win32-x64': {
    cpp: {
      kind: 'gcc',
      url: 'https://github.com/brechtsanders/winlibs_mingw/releases/download/'
        + '16.2.0posix-14.0.0-ucrt-r1/winlibs-x86_64-posix-seh-gcc-16.2.0-mingw-w64ucrt-14.0.0-r1.zip',
      sha256: 'c1f52294597c0b73786b2a78eb5d176d89226d2f21875eab75e783a8b1cefcc4',
    },
    python: py('x86_64-pc-windows-msvc',
      '63d263ab0162f34a241a56dc5b283c22d6e131f5516117e6a921350c69ba7d4f'),
  },
  'linux-x64': {
    cpp: { kind: 'zig', ...zig('x86_64-linux',
      '70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00') },
    python: py('x86_64-unknown-linux-gnu',
      '8a689a077337bea6d1c4bc0b7df1d52fcaa28f5f67e50df8bf417c1e3f9d8874'),
  },
  'linux-arm64': {
    cpp: { kind: 'zig', ...zig('aarch64-linux',
      'ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17') },
    python: py('aarch64-unknown-linux-gnu',
      '01ce0ce9189feaead3298abf10d4efe998c55a489b3d5d38ca4f83dda7e7977e'),
  },
  'darwin-x64': {
    cpp: { kind: 'zig', ...zig('x86_64-macos',
      '0387557ed1877bc6a2e1802c8391953baddba76081876301c522f52977b52ba7') },
    python: py('x86_64-apple-darwin',
      'f712a9143c8a5d248438ec7921a0b48d548bca4f1337d33c690d28c2d0504137'),
  },
  'darwin-arm64': {
    cpp: { kind: 'zig', ...zig('aarch64-macos',
      'b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489') },
    python: py('aarch64-apple-darwin',
      'd3904bd6a072246e07aa0bdadee9a14e80521e42a943c0848059feb16a2816dc'),
  },
}

const spec = TARGETS[TARGET]
if (!spec) {
  console.error(`No toolchains are pinned for ${TARGET}. Known: ${Object.keys(TARGETS).join(', ')}`)
  process.exit(1)
}

async function sha256Of(file) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}

async function download({ url, sha256 }) {
  mkdirSync(CACHE, { recursive: true })
  const file = join(CACHE, decodeURIComponent(url.split('/').pop()))
  if (!existsSync(file) || (await sha256Of(file)) !== sha256) {
    console.log(`Downloading ${url}`)
    const res = await fetch(url)
    if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`)
    const { writeFile } = await import('node:fs/promises')
    await writeFile(file, Buffer.from(await res.arrayBuffer()))
  }
  const actual = await sha256Of(file)
  if (actual !== sha256) {
    rmSync(file, { force: true })
    throw new Error(`${url}: SHA-256 is ${actual}, expected ${sha256}. Refusing to use it.`)
  }
  return file
}

/** Unpacks into a fresh directory and returns it. */
function extract(archive, into) {
  rmSync(into, { recursive: true, force: true })
  mkdirSync(into, { recursive: true })
  if (archive.endsWith('.zip') && process.platform !== 'win32') {
    execFileSync('unzip', ['-q', archive, '-d', into], { stdio: 'inherit' })
  } else {
    // bsdtar on Windows and macOS reads zip too; GNU tar on Linux handles .tar.xz and .tar.gz.
    // Windows' own, by path: Git's GNU tar can come first on a CI runner's PATH.
    const tar = process.platform === 'win32'
      ? join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe')
      : 'tar'
    execFileSync(tar, ['-xf', archive, '-C', into], { stdio: 'inherit' })
  }
  return into
}

function copy(from, to) {
  if (!existsSync(from)) throw new Error(`Expected ${from} in the toolchain archive`)
  cpSync(from, to, { recursive: true, verbatimSymlinks: true })
}

/**
 * Only what g++ needs, out of WinLibs' 950 MB: the driver, cc1plus, binutils, the MinGW headers
 * and import libraries, and libstdc++. Fortran, Objective-C, cmake, gdb and the rest stay out.
 */
function stageGcc(unpacked, to) {
  const src = join(unpacked, 'mingw64')
  const gccVersion = '16.2.0'
  const triple = 'x86_64-w64-mingw32'
  mkdirSync(join(to, 'bin'), { recursive: true })
  copy(join(src, 'bin', 'g++.exe'), join(to, 'bin', 'g++.exe'))
  // The driver's own DLLs. bin/ also holds cmake's, gdb's, Python 3.9's and more.
  for (const name of ['libgcc_s_seh-1.dll', 'libgmp-10.dll', 'libiconv-2.dll', 'libintl-8.dll',
    'libisl-23.dll', 'libmpc-3.dll', 'libmpfr-6.dll', 'libstdc++-6.dll', 'libwinpthread-1.dll',
    'libzstd.dll', 'zlib1.dll']) {
    copy(join(src, 'bin', name), join(to, 'bin', name))
  }
  copy(join(src, 'include', 'c++'), join(to, 'include', 'c++'))
  copy(join(src, triple), join(to, triple))

  const libexec = join('libexec', 'gcc', triple, gccVersion)
  const skip = new Set(['cc1.exe', 'cc1obj.exe', 'cc1objplus.exe', 'f951.exe', 'lto1.exe',
    'gnat1.exe', 'd21.exe', 'go1.exe', 'cc1gm2.exe', 'rust1.exe', 'crab1.exe', 'algol681.exe',
    'cobol1.exe', 'install-tools', 'plugin'])
  mkdirSync(join(to, libexec), { recursive: true })
  for (const name of readdirSync(join(src, libexec))) {
    if (!skip.has(name)) copy(join(src, libexec, name), join(to, libexec, name))
  }

  const libgcc = join('lib', 'gcc', triple, gccVersion)
  mkdirSync(join(to, libgcc), { recursive: true })
  for (const name of readdirSync(join(src, libgcc))) {
    if (!['plugin', 'finclude', 'install-tools'].includes(name) && !name.startsWith('libcaf')
        && !name.startsWith('libgfortran')) {
      copy(join(src, libgcc, name), join(to, libgcc, name))
    }
  }
  for (const name of readdirSync(join(src, 'lib'))) {
    if (/^lib(stdc\+\+|supc\+\+|atomic|quadmath|ssp|gcc_s|gomp)[^/]*\.a$/.test(name)
        && !name.includes('nvptx')) {
      copy(join(src, 'lib', name), join(to, 'lib', name))
    }
  }
  return { kind: 'gcc', compiler: join('cpp', 'bin', 'g++.exe') }
}

/**
 * Zig, plus the stand-in <bits/stdc++.h> that libc++ lacks. The header is the website's own
 * (frontend/src/runner/browser/stdcxxShim.ts), read from there so there is one copy.
 */
function stageZig(unpacked, to) {
  const [dir] = readdirSync(unpacked).filter(n => n.startsWith('zig-'))
  renameSync(join(unpacked, dir), to)
  const shim = readFileSync(join(ROOT, '..', 'frontend', 'src', 'runner', 'browser',
    'stdcxxShim.ts'), 'utf8').match(/BITS_STDCXX = `([\s\S]*?)`/)
  if (!shim) throw new Error('Could not find BITS_STDCXX in stdcxxShim.ts')
  mkdirSync(join(OUT, 'cpp-include', 'bits'), { recursive: true })
  writeFileSync(join(OUT, 'cpp-include', 'bits', 'stdc++.h'), shim[1])
  return {
    kind: 'zig',
    compiler: join('cpp', TARGET.startsWith('win32') ? 'zig.exe' : 'zig'),
    include: 'cpp-include',
  }
}

/** CPython minus what a solution never imports: the test suite, Tk, IDLE and pip. */
function stagePython(unpacked, to) {
  renameSync(join(unpacked, 'python'), to)
  const win = TARGET.startsWith('win32')
  const lib = win ? join(to, 'Lib') : join(to, 'lib', 'python3.13')
  for (const name of ['test', 'idlelib', 'tkinter', 'turtledemo', 'ensurepip', 'lib2to3',
    'pydoc_data', join('site-packages', 'pip')]) {
    rmSync(join(lib, name), { recursive: true, force: true })
  }
  if (win) rmSync(join(to, 'tcl'), { recursive: true, force: true })
  return { interpreter: win ? join('python', 'python.exe') : join('python', 'bin', 'python3') }
}

function sizeOf(path) {
  const s = statSync(path)
  if (!s.isDirectory()) return s.size
  return readdirSync(path).reduce((n, name) => n + sizeOf(join(path, name)), 0)
}

const work = join(CACHE, 'unpacked')
rmSync(OUT, { recursive: true, force: true })
mkdirSync(OUT, { recursive: true })

const cppArchive = await download(spec.cpp)
const cppUnpacked = extract(cppArchive, join(work, 'cpp'))
const cpp = spec.cpp.kind === 'gcc'
  ? stageGcc(cppUnpacked, join(OUT, 'cpp'))
  : stageZig(cppUnpacked, join(OUT, 'cpp'))

const pyArchive = await download(spec.python)
const python = stagePython(extract(pyArchive, join(work, 'python')), join(OUT, 'python'))
rmSync(work, { recursive: true, force: true })

// Read by electron/src/localRunner.ts; paths are relative to this directory.
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ target: TARGET, cpp, python }, null, 2))
console.log(`Toolchains for ${TARGET} in ${OUT} (${(sizeOf(OUT) / 1048576).toFixed(0)} MB)`)
