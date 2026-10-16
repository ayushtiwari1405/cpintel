import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { staticImageKb } from './staticImage'

/*
 * Headers built by hand, the fields staticImage reads and nothing else, so the three parsers are
 * checked on every platform the tests run on. Checked against real binaries too (gcc, and Zig
 * for Windows and macOS): a 600 MB global array read as 614,4xx KB in each format.
 */

const MB = 1024 * 1024

function write(name: string, bytes: Buffer): string {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'cpintel-img-')), name)
  fs.writeFileSync(file, bytes)
  return file
}

function elf(segments: Array<[type: number, memsz: number]>): Buffer {
  const buf = Buffer.alloc(64 + 56 * segments.length)
  buf.writeUInt32BE(0x7f454c46, 0)
  buf[4] = 2 // 64-bit
  buf[5] = 1 // little-endian
  buf.writeBigUInt64LE(64n, 0x20)
  buf.writeUInt16LE(56, 0x36)
  buf.writeUInt16LE(segments.length, 0x38)
  segments.forEach(([type, memsz], i) => {
    buf.writeUInt32LE(type, 64 + i * 56)
    buf.writeBigUInt64LE(BigInt(memsz), 64 + i * 56 + 0x28)
  })
  return buf
}

function pe(sizeOfImage: number): Buffer {
  const buf = Buffer.alloc(0x40 + 24 + 240)
  buf.writeUInt16LE(0x5a4d, 0)
  buf.writeUInt32LE(0x40, 0x3c)
  buf.writeUInt32LE(0x00004550, 0x40)
  buf.writeUInt32LE(sizeOfImage, 0x40 + 24 + 56)
  return buf
}

function machO(segments: Array<[name: string, vmsize: number]>): Buffer {
  const buf = Buffer.alloc(32 + 72 * segments.length)
  buf.writeUInt32LE(0xfeedfacf, 0)
  buf.writeUInt32LE(segments.length, 16)
  buf.writeUInt32LE(72 * segments.length, 20)
  segments.forEach(([name, vmsize], i) => {
    const at = 32 + i * 72
    buf.writeUInt32LE(0x19, at)
    buf.writeUInt32LE(72, at + 4)
    buf.write(name, at + 8, 'latin1')
    buf.writeBigUInt64LE(BigInt(vmsize), at + 32)
  })
  return buf
}

test('ELF: the loadable segments are added up, and nothing else', () => {
  const file = write('program', elf([[1, 4096], [1, 600 * MB], [2, 999 * MB]]))
  assert.equal(staticImageKb(file), (4096 + 600 * MB) / 1024)
})

test('PE: SizeOfImage', () => {
  assert.equal(staticImageKb(write('program.exe', pe(600 * MB))), 600 * 1024)
})

test('Mach-O: the segments, less __PAGEZERO', () => {
  const file = write('program', machO([['__PAGEZERO', 4096 * MB], ['__TEXT', 16384],
    ['__DATA', 600 * MB]]))
  assert.equal(staticImageKb(file), (16384 + 600 * MB) / 1024)
})

test('anything else, or nothing at all, has no size', () => {
  assert.equal(staticImageKb(write('main.py', Buffer.from('print(1)\n'.repeat(10)))), null)
  assert.equal(staticImageKb(write('short', Buffer.from([0x7f, 0x45]))), null)
  assert.equal(staticImageKb(path.join(os.tmpdir(), 'cpintel-no-such-file')), null)
})
