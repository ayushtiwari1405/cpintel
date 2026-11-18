/// <reference lib="webworker" />
/**
 * Runs an instrumented C++ program and collects its trace.
 *
 * The runtime (cpviz.h) writes each step to stderr as one line starting with \x1e. stdout and
 * stderr reach this worker in the order the program wrote them, and the runtime flushes stdout
 * before every step, so the amount of output already written when a step arrives is exactly
 * what the program had printed at that point. Anything else on stderr is the program's own and
 * is shown with its output.
 */
import { ConsoleStdout, File, OpenFile, WASI } from '@bjorn3/browser_wasi_shim'
import { trapMessage } from '@/runner/browser/trap'
import type { Step } from '../trace'

export interface CppRunRequest { module: WebAssembly.Module; stdin: string }
export interface CppRunResult {
  steps: Step[]
  stdout: string
  truncated: boolean
  error: string | null
}
export type CppRunMessage = { kind: 'started' } | { kind: 'done'; result: CppRunResult }

const OUTPUT_LIMIT = 64 * 1024

self.onmessage = async (e: MessageEvent<CppRunRequest>) => {
  const steps: Step[] = []
  let output = ''
  let truncated = false
  let pending = ''
  const outText = new TextDecoder()
  const errText = new TextDecoder()

  const append = (text: string) => {
    if (output.length < OUTPUT_LIMIT) output += text.slice(0, OUTPUT_LIMIT - output.length)
  }
  const onStderr = (bytes: Uint8Array) => {
    pending += errText.decode(bytes, { stream: true })
    let nl: number
    while ((nl = pending.indexOf('\n')) !== -1) {
      const lineText = pending.slice(0, nl)
      pending = pending.slice(nl + 1)
      if (lineText.startsWith('\x1e{')) {
        try {
          const step = JSON.parse(lineText.slice(1)) as Step
          step.out = output.length
          steps.push(step)
        } catch {
          // A step the runtime could not finish writing; the next one carries on.
        }
      } else if (lineText === '\x1eCPVIZ_TRUNCATED') {
        truncated = true
      } else {
        append(`${lineText}\n`)
      }
    }
    // Text without a newline that is not a step yet: the program's own stderr.
    if (pending && !pending.startsWith('\x1e')) { append(pending); pending = '' }
  }

  const wasi = new WASI(['program'], [], [
    new OpenFile(new File(new TextEncoder().encode(e.data.stdin))),
    new ConsoleStdout(b => append(outText.decode(b, { stream: true }))),
    new ConsoleStdout(onStderr),
  ])

  let error: string | null = null
  self.postMessage({ kind: 'started' } satisfies CppRunMessage)
  try {
    const instance = await WebAssembly.instantiate(e.data.module, {
      wasi_snapshot_preview1: wasi.wasiImport,
    })
    const code = wasi.start(instance as unknown as {
      exports: { memory: WebAssembly.Memory; _start: () => unknown }
    })
    if (code !== 0 && !truncated) error = `The program exited with code ${code}.`
  } catch (err) {
    error = trapMessage(err)
  }
  self.postMessage({
    kind: 'done',
    result: { steps, stdout: output, truncated, error },
  } satisfies CppRunMessage)
}
