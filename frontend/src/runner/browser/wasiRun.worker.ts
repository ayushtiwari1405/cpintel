/// <reference lib="webworker" />
/**
 * Runs one compiled C++ program on one test's input.
 *
 * One worker per test, and the page terminates it when the time limit passes: WebAssembly
 * cannot be interrupted from inside, so throwing the whole worker away is the only reliable
 * way to stop an infinite loop. The program sees stdin, stdout and stderr and nothing else —
 * no files, no network, no page.
 */
import { ConsoleStdout, File, OpenFile, WASI } from '@bjorn3/browser_wasi_shim'
import { CappedText, type RawExec } from '../judge'

export interface WasiRunRequest { module: WebAssembly.Module; input: string }
export type WasiRunMessage =
  | { kind: 'started' }
  | { kind: 'done'; exec: RawExec }

self.onmessage = async (e: MessageEvent<WasiRunRequest>) => {
  const stdout = new CappedText()
  const stderr = new CappedText()
  const wasi = new WASI(['program'], [], [
    new OpenFile(new File(new TextEncoder().encode(e.data.input))),
    new ConsoleStdout(b => stdout.push(b)),
    new ConsoleStdout(b => stderr.push(b)),
  ])

  let exitCode: number
  self.postMessage({ kind: 'started' } satisfies WasiRunMessage)
  const started = performance.now()
  try {
    const instance = await WebAssembly.instantiate(e.data.module, {
      wasi_snapshot_preview1: wasi.wasiImport,
    })
    exitCode = wasi.start(instance as unknown as {
      exports: { memory: WebAssembly.Memory; _start: () => unknown }
    })
  } catch (err: any) {
    // A trap: out-of-bounds access, abort(), stack overflow, running out of memory.
    stderr.push(new TextEncoder().encode(`\n${trapMessage(err)}\n`))
    exitCode = 134
  }
  const durationMs = Math.round(performance.now() - started)

  self.postMessage({
    kind: 'done',
    exec: {
      stdout: stdout.text(),
      stderr: stderr.text().replace(/^\n/, ''),
      exitCode,
      timedOut: false,
      durationMs,
      truncated: stdout.truncated || stderr.truncated,
    },
  } satisfies WasiRunMessage)
}

function trapMessage(err: any): string {
  const message = String(err?.message ?? err)
  if (/memory access out of bounds/i.test(message)) {
    return 'Runtime error: memory access out of bounds (an array index past the end, or a bad pointer).'
  }
  if (/unreachable/i.test(message)) return 'Runtime error: the program aborted (abort, a failed assert, or undefined behaviour).'
  if (/divide by zero|integer overflow/i.test(message)) return `Runtime error: ${message}.`
  if (/out of memory|Cannot allocate|could not allocate/i.test(message)) {
    return 'Runtime error: out of memory.'
  }
  return `Runtime error: ${message}`
}
