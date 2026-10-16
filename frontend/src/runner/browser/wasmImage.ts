/**
 * A WebAssembly program's initial linear memory, in bytes, from its memory section — or null
 * when the module defines none or cannot be read.
 *
 * <p>wasm-ld sizes the initial memory to hold the program's data, its global arrays and its
 * stack, so less the stack this is what the program needs before it starts: the browser's
 * counterpart of reading a native binary's headers (judge.ts, `staticKb`).
 */
export function wasmInitialMemoryBytes(bytes: Uint8Array): number | null {
  if (bytes.length < 8 || bytes[0] !== 0 || bytes[1] !== 0x61 || bytes[2] !== 0x73
      || bytes[3] !== 0x6d) {
    return null
  }
  let at = 8
  const leb = (): number => {
    let result = 0
    let shift = 0
    for (;;) {
      if (at >= bytes.length || shift > 35) throw new Error('bad LEB128')
      const b = bytes[at++]
      result += (b & 0x7f) * 2 ** shift
      if ((b & 0x80) === 0) return result
      shift += 7
    }
  }
  try {
    while (at < bytes.length) {
      const id = bytes[at++]
      const size = leb()
      const end = at + size
      if (id === 5) {                 // the memory section
        if (leb() === 0) return null  // no memory defined here
        leb()                         // limits flags
        return leb() * 65536          // initial size, in 64 KiB pages
      }
      at = end
    }
  } catch {
    return null
  }
  return null
}
