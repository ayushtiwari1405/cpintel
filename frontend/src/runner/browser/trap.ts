/** What a WebAssembly trap means, in words a student can act on. */
export function trapMessage(err: any): string {
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
