/// <reference lib="webworker" />
/**
 * Compiles C and C++ to WebAssembly in the browser, with Clang/LLD built for WebAssembly (YoWASP).
 *
 * The toolchain is about 27 MB compressed and comes from jsDelivr at a pinned version, so the
 * CPIntel server neither hosts nor runs any of it. The browser keeps it in its HTTP cache, so
 * that download happens once per student. This worker stays alive between runs, so the
 * compiler is only instantiated once per page.
 */

import { BITS_STDCXX } from './stdcxxShim'
import { wasmInitialMemoryBytes } from './wasmImage'

/** Also pinned in the site's Content-Security-Policy (nginx/security-headers.conf); change both. */
const CLANG_VERSION = '22.0.0-git20542-10'
const CLANG_URL = `https://cdn.jsdelivr.net/npm/@yowasp/clang@${CLANG_VERSION}/gen/bundle.js`

/** Codeforces runs with a 256 MB stack; 64 MB covers a million-deep DFS here. */
const STACK_BYTES = 64 * 1024 * 1024

type Tree = { [name: string]: Tree | string | Uint8Array }
interface Clang {
  runClang: (args: string[], files: Tree, options: Record<string, unknown>) => Promise<Tree>
}

let clang: Promise<Clang> | null = null

const load = () => {
  clang ??= import(/* @vite-ignore */ CLANG_URL) as Promise<Clang>
  // A failed download must not stick: the next Run should try again.
  clang.catch(() => { clang = null })
  return clang
}

export interface CompileRequest { source: string; language: 'cpp' | 'c' }
export type CompileMessage =
  | { kind: 'progress'; done: number; total: number }
  | { kind: 'done'; ok: true; module: WebAssembly.Module; output: string; ms: number
      /** Initial memory less the stack: the program's static size, or null if unreadable. */
      staticBytes: number | null }
  | { kind: 'done'; ok: false; output: string; ms: number }
  | { kind: 'failed'; error: string }

const post = (m: CompileMessage) => self.postMessage(m)

self.onmessage = async (e: MessageEvent<CompileRequest>) => {
  let api: Clang
  try {
    api = await load()
  } catch {
    post({ kind: 'failed', error: 'Could not download the compiler. Check the internet '
      + 'connection and try again.' })
    return
  }

  const decoder = new TextDecoder()
  let output = ''
  const collect = (bytes: Uint8Array | null) => { if (bytes) output += decoder.decode(bytes) }

  const isC = e.data.language === 'c'
  const file = isC ? 'main.c' : 'main.cpp'
  const args = isC
    // The maths functions are in WASI's libc itself, so there is no -lm to pass.
    ? ['clang', '-std=gnu11', '-O2']
    : ['clang++', '-std=c++20', '-O2',
      // The WebAssembly C++ library is built without exception support, and a clear
      // "exceptions are disabled" from the compiler beats a wall of undefined symbols from
      // the linker.
      '-fno-exceptions',
      '-Iinclude']

  const started = performance.now()
  try {
    const files = await api.runClang([
      ...args,
      `-Wl,-z,stack-size=${STACK_BYTES}`,
      file, '-o', 'program',
    ], {
      [file]: e.data.source,
      ...(isC ? {} : { include: { bits: { 'stdc++.h': BITS_STDCXX } } }),
    }, {
      stdout: collect,
      stderr: collect,
      decodeASCII: false,
      fetchProgress: ({ doneLength, totalLength }: { doneLength: number; totalLength: number }) =>
        post({ kind: 'progress', done: doneLength, total: totalLength }),
    })
    const ms = Math.round(performance.now() - started)
    const program = files.program as Uint8Array<ArrayBuffer>
    const memory = wasmInitialMemoryBytes(program)
    const staticBytes = memory == null ? null : Math.max(0, memory - STACK_BYTES)
    const module = await WebAssembly.compile(program)
    post({ kind: 'done', ok: true, module, output: output.split(`/${file}`).join(file), ms,
      staticBytes })
  } catch (err: any) {
    const ms = Math.round(performance.now() - started)
    // The YoWASP runtime reports a non-zero exit as an Exit error carrying the status.
    if (typeof err?.code === 'number') {
      post({ kind: 'done', ok: false, output: explain(output.split(`/${file}`).join(file)), ms })
    } else {
      post({ kind: 'failed', error: `The compiler crashed: ${err?.message ?? err}` })
    }
  }
}

/** Adds a line about the few things that differ from g++ when the compiler trips over one. */
function explain(output: string): string {
  const notes: string[] = []
  if (/exceptions disabled|cannot use '(try|throw)'/.test(output)) {
    notes.push('Note: C++ run in the browser cannot use exceptions (try/throw). Remove them '
      + 'to run here; the judge itself still accepts them.')
  }
  if (/ext\/pb_ds|bits\/extc\+\+/.test(output)) {
    notes.push('Note: GNU policy-based data structures (ext/pb_ds) are only in g++, so they '
      + 'cannot be run in the browser. The judge still accepts them.')
  }
  return notes.length ? `${output}\n${notes.join('\n')}` : output
}
