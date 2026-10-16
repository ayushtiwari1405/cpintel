import fs from 'fs'

/**
 * How much memory a compiled program needs before its first instruction runs — its code, data
 * and global arrays — in KB, or null when it cannot be read.
 *
 * <p>Runs on the student's own machine have no memory limit, so a global array too big for the
 * judge (`static int a[1 << 28]`) simply runs here and fails there. This is how a Run can say so
 * anyway: the size is in the binary's headers. The same idea as the server's ElfImage, for the
 * three formats the bundled compilers produce — ELF on Linux, PE on Windows, Mach-O on macOS.
 * The stack is not counted in any of them: it is set aside separately.
 */
export function staticImageKb(file: string): number | null {
  let fd: number | null = null
  try {
    fd = fs.openSync(file, 'r')
    const read = (at: number, length: number): Buffer | null => {
      const buf = Buffer.alloc(length)
      return fs.readSync(fd!, buf, 0, length, at) === length ? buf : null
    }
    const head = read(0, 64)
    if (!head) return null

    let bytes: number | null = null
    if (head.readUInt32BE(0) === 0x7f454c46) bytes = elf(head, read)
    else if (head.readUInt16LE(0) === 0x5a4d) bytes = pe(head, read)
    else if (head.readUInt32LE(0) === 0xfeedfacf) bytes = machO(head, read)
    return bytes == null ? null : Math.floor(bytes / 1024)
  } catch {
    return null
  } finally {
    if (fd != null) fs.closeSync(fd)
  }
}

type Read = (at: number, length: number) => Buffer | null

/** 64-bit little-endian ELF: the PT_LOAD segments' sizes in memory. */
function elf(head: Buffer, read: Read): number | null {
  if (head[4] !== 2 || head[5] !== 1) return null
  const phoff = Number(head.readBigUInt64LE(0x20))
  const phentsize = head.readUInt16LE(0x36)
  const phnum = head.readUInt16LE(0x38)
  if (phentsize < 0x38 || phnum === 0 || phnum > 256) return null
  const table = read(phoff, phentsize * phnum)
  if (!table) return null
  let total = 0
  for (let i = 0; i < phnum; i++) {
    const at = i * phentsize
    if (table.readUInt32LE(at) === 1) total += Number(table.readBigUInt64LE(at + 0x28))
  }
  return total
}

/** PE (Windows): SizeOfImage, which spans every section as loaded, .bss included. */
function pe(head: Buffer, read: Read): number | null {
  const peAt = head.readUInt32LE(0x3c)
  const sig = read(peAt, 24 + 60)
  if (!sig || sig.readUInt32LE(0) !== 0x00004550) return null
  // The optional header follows the 4-byte signature and the 20-byte file header; SizeOfImage
  // is at the same offset in it for PE32 and PE32+.
  return sig.readUInt32LE(24 + 56)
}

/** 64-bit Mach-O: the segments' sizes in memory, less __PAGEZERO, which is not memory at all. */
function machO(head: Buffer, read: Read): number | null {
  const ncmds = head.readUInt32LE(16)
  const sizeofcmds = head.readUInt32LE(20)
  if (ncmds > 512 || sizeofcmds > 1 << 20) return null
  const cmds = read(32, sizeofcmds)
  if (!cmds) return null
  let total = 0
  let at = 0
  for (let i = 0; i < ncmds && at + 8 <= cmds.length; i++) {
    const cmd = cmds.readUInt32LE(at)
    const size = cmds.readUInt32LE(at + 4)
    if (size < 8) return null
    if (cmd === 0x19 && at + 48 <= cmds.length) { // LC_SEGMENT_64
      const name = cmds.subarray(at + 8, at + 24).toString('latin1').replace(/\0+$/, '')
      if (name !== '__PAGEZERO') total += Number(cmds.readBigUInt64LE(at + 32))
    }
    at += size
  }
  return total
}
