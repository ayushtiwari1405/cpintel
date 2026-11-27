import { describe, expect, it } from 'vitest'

import {
  allSheets, checkSheets, fillSubject, mailLines, sheetPages, usedVariables,
} from '@/utils/passwordSheet'
import { PAGE_HEIGHT, PAGE_WIDTH, pdf, textWidth, unsupported, type Run } from '@/utils/pdf'

const VARIABLES = ['name', 'username', 'password'].map(key => ({ key, label: key }))
const ASHA = { username: 'asha', name: 'Asha Rao', password: 'Ab3d-Ef6g-Hj8k-Mn2p' }

const text = (runs: Run[]) => runs.map(r => r.text).join('|')
const bytes = (data: Uint8Array) => Array.from(data, b => String.fromCharCode(b)).join('')

describe('filling in a sheet', () => {
  it('puts each value in, with headings, bold words and a fixed-width password', () => {
    const [page] = sheetPages(
      '# Account\n\nDear {{ name }}, **keep this**: {{password}}', ASHA)

    expect(page.map(r => [r.text, r.font, r.size])).toEqual([
      ['Account', 'bold', 16],
      ['Dear Asha Rao, ', 'regular', 11],
      ['keep this', 'bold', 11],
      [': ', 'regular', 11],
      [ASHA.password, 'mono', 11],
    ])
  })

  it('prints values as they are, never as markup', () => {
    const [page] = sheetPages('{{name}} {{password}}',
      { name: '**{{password}}**', password: 'a  **b**  {{name}}' })

    expect(text(page)).toBe('**{{password}}** |a  **b**  {{name}}')
  })

  it('leaves a lone pair of asterisks alone', () => {
    expect(text(sheetPages('2 ** 3', ASHA)[0])).toBe('2 ** 3')
  })

  it('keeps every line inside the margins, even one with no space to break at', () => {
    const pages = sheetPages(`${'word '.repeat(80)}\nhttps://${'x'.repeat(400)}`, ASHA)
    const runs = pages.flat()

    for (const run of runs) {
      expect(run.x + textWidth(run.text, run.font, run.size)).toBeLessThanOrEqual(PAGE_WIDTH - 55)
    }
    expect(runs.map(r => r.text).join('').replace(/ /g, ''))
      .toBe(`${'word'.repeat(80)}https://${'x'.repeat(400)}`)
  })

  it('carries a long message on to further pages, and starts each person on a new one', () => {
    const long = Array.from({ length: 70 }, (_, i) => `Line ${i}`).join('\n')

    expect(sheetPages(long, ASHA)).toHaveLength(2)
    expect(sheetPages(long, ASHA).flat().every(r => r.y < PAGE_HEIGHT - 55)).toBe(true)
    expect(allSheets('{{username}}', [ASHA, { username: 'ben' }]).map(p => text(p)))
      .toEqual(['asha', 'ben'])
  })
})

describe('the same draft as mail', () => {
  it('keeps the lines unwrapped and says what is a heading, bold or the password', () => {
    expect(mailLines('# Hi {{name}}\n\n**{{username}}**: {{password}}\n',
      { username: 'ravi', name: 'रवि', password: 'p  w' })).toEqual([
      { heading: true, spans: [
        { text: 'Hi ', style: 'REGULAR' }, { text: 'रवि', style: 'REGULAR' },
      ] },
      { heading: false, spans: [] },
      { heading: false, spans: [
        { text: 'ravi', style: 'BOLD' }, { text: ': ', style: 'REGULAR' },
        { text: 'p  w', style: 'PASSWORD' },
      ] },
    ])
  })

  it('fills a subject on one line, without reading it as markup', () => {
    expect(fillSubject('** {{ name }}\nnow', { name: 'Asha' })).toBe('** Asha now')
  })
})

describe('checking a draft', () => {
  it('names values that do not exist and values that are blank', () => {
    const result = checkSheets('{{name}} {{pasword}} {{password}}', VARIABLES,
      [ASHA, { username: 'ben', name: '', password: 'x' }])

    expect(result.unknown).toEqual(['pasword'])
    expect(result.blank).toEqual([{ key: 'name', count: 1 }])
    expect(usedVariables('# {{exam}}\n{{ name }} {{name}}')).toEqual(['exam', 'name'])
  })

  it('says whose characters cannot be printed, and only for values the draft uses', () => {
    const people = [{ username: 'ravi', name: 'रवि', password: 'x' }]

    expect(checkSheets('{{name}}', VARIABLES, people).unprintable)
      .toEqual([{ where: 'ravi', characters: ['र', 'व', 'ि'] }])
    expect(checkSheets('{{username}}', VARIABLES, people).unprintable).toEqual([])
    expect(checkSheets('Marks → here', VARIABLES, people).unprintable)
      .toEqual([{ where: '', characters: ['→'] }])
  })

  it('accepts western European text, smart punctuation and a decomposed accent', () => {
    const result = checkSheets('“Zoë” — €5 • {{name}}', VARIABLES,
      [{ username: 'z', name: 'José', password: 'x' }])

    expect(result.unprintable).toEqual([])
    expect(unsupported('€’–…ÿ')).toEqual([])
  })
})

describe('the PDF', () => {
  const file = bytes(pdf(allSheets('# Hi (there) \\ José\n{{password}}', [ASHA, ASHA])))

  it('has a page per sheet and a cross-reference table pointing at every object', () => {
    expect(file.startsWith('%PDF-1.4\n')).toBe(true)
    expect(file.endsWith('%%EOF\n')).toBe(true)
    expect(file).toContain('/Count 2 ')

    const xref = Number(/startxref\n(\d+)/.exec(file)![1])
    expect(file.slice(xref, xref + 4)).toBe('xref')
    const offsets = [...file.slice(xref).matchAll(/(\d{10}) 00000 n/g)].map(m => Number(m[1]))
    expect(offsets).toHaveLength(9)
    offsets.forEach((offset, i) =>
      expect(file.slice(offset).startsWith(`${i + 1} 0 obj\n`)).toBe(true))
  })

  it('escapes brackets and backslashes and writes accents as single bytes', () => {
    expect(file).toContain('(Hi \\(there\\) \\\\ Jos\xE9) Tj')
    expect(file).toContain(`(${ASHA.password}) Tj`)
  })

  it('declares each stream at its real length', () => {
    for (const match of file.matchAll(/\/Length (\d+) >>\nstream\n([\s\S]*?)\nendstream/g)) {
      expect(match[2].length).toBe(Number(match[1]))
    }
  })

  it('refuses to write a character it cannot draw', () => {
    expect(() => pdf([[{ x: 0, y: 0, text: 'र', font: 'regular', size: 11 }]]))
      .toThrow('cannot draw')
  })
})
