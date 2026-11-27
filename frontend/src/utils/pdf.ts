/**
 * A minimal PDF writer: A4 pages of positioned text in the fonts every PDF reader already has.
 *
 * <p>Enough for password sheets, which are built in the browser because the passwords on them
 * exist only there. Nothing is embedded or compressed, so there is no PDF library in the bundle.
 *
 * <p>The cost of the built-in fonts is their character set, Windows-1252: English and western
 * European text, the euro and typographic punctuation. {@link unsupported} names anything else,
 * so a caller can refuse rather than print a password or a name with letters missing.
 */

/** A4, in points. */
export const PAGE_WIDTH = 595.28
export const PAGE_HEIGHT = 841.89

export type Font = 'regular' | 'bold' | 'mono'

/** One piece of text; {@code y} is the baseline, measured down from the top of the page. */
export interface Run {
  x: number
  y: number
  text: string
  font: Font
  size: number
}

const BASE_FONTS: Record<Font, string> = {
  regular: 'Helvetica', bold: 'Helvetica-Bold', mono: 'Courier-Bold',
}

/** Advance widths in thousandths of the size, for bytes 32–255. Courier is 600 throughout. */
const widths = (list: string) => list.split(',').map(Number)
const REGULAR = widths(
  '278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,'
  + '556,556,278,278,584,584,584,556,1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,'
  + '778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,333,556,556,500,556,556,'
  + '278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,'
  + '334,584,0,556,0,222,556,333,1000,556,556,333,1000,667,333,1000,0,611,0,0,222,222,333,333,350,'
  + '556,1000,333,1000,500,333,944,0,500,667,278,333,556,556,556,556,260,556,333,737,370,556,584,0,'
  + '737,333,400,584,333,333,333,556,537,278,333,333,365,556,834,834,834,611,667,667,667,667,667,'
  + '667,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,'
  + '722,667,667,611,556,556,556,556,556,556,889,500,556,556,556,556,278,278,278,278,556,556,556,'
  + '556,556,556,556,584,611,556,556,556,556,500,556,500')
const BOLD = widths(
  '278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,'
  + '556,556,333,333,584,584,584,611,975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,'
  + '778,667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,333,556,611,556,611,556,'
  + '333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500,389,280,'
  + '389,584,0,556,0,278,556,500,1000,556,556,333,1000,667,333,1000,0,611,0,0,278,278,500,500,350,'
  + '556,1000,333,1000,556,333,944,0,500,667,278,333,556,556,556,556,280,556,333,737,370,556,584,0,'
  + '737,333,400,584,333,333,333,611,556,278,333,333,365,556,834,834,834,611,722,722,722,722,722,'
  + '722,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,'
  + '722,667,667,611,556,556,556,556,556,556,889,556,556,556,556,556,278,278,278,278,611,611,611,'
  + '611,611,611,611,584,611,611,611,611,611,556,611,556')

/** Where Windows-1252 differs from Latin-1: these characters live at bytes 128–159. */
const HIGH: Record<string, number> = {
  '€': 128, '‚': 130, 'ƒ': 131, '„': 132, '…': 133, '†': 134, '‡': 135, 'ˆ': 136, '‰': 137,
  'Š': 138, '‹': 139, 'Œ': 140, 'Ž': 142, '‘': 145, '’': 146, '“': 147, '”': 148, '•': 149,
  '–': 150, '—': 151, '˜': 152, '™': 153, 'š': 154, '›': 155, 'œ': 156, 'ž': 158, 'Ÿ': 159,
}

function byteFor(char: string): number | null {
  const code = char.codePointAt(0)!
  if ((code >= 32 && code <= 126) || (code >= 160 && code <= 255 && code !== 173)) return code
  return HIGH[char] ?? null
}

/** The characters in {@code text} that these fonts cannot draw, each once. */
export function unsupported(text: string): string[] {
  return [...new Set([...text].filter(c => byteFor(c) === null))]
}

export function textWidth(text: string, font: Font, size: number): number {
  let units = 0
  for (const char of text) {
    const byte = byteFor(char) ?? 63
    units += font === 'mono' ? 600 : (font === 'bold' ? BOLD : REGULAR)[byte - 32]
  }
  return units * size / 1000
}

/** One byte per character, as a string of char codes below 256. */
function literal(text: string): string {
  let out = ''
  for (const char of text) {
    const byte = byteFor(char)
    if (byte === null) throw new Error(`The PDF fonts cannot draw “${char}”`)
    out += byte === 40 || byte === 41 || byte === 92
      ? '\\' + char : String.fromCharCode(byte)
  }
  return `(${out})`
}

const number = (value: number) => String(Math.round(value * 100) / 100)

/** The .pdf bytes for these pages. */
export function pdf(pages: Run[][]): Uint8Array {
  const fonts = Object.keys(BASE_FONTS) as Font[]
  // Objects are numbered from 1: catalogue, page tree, the fonts, then a page and its content.
  const firstPage = 3 + fonts.length
  const pageIds = pages.map((_, i) => firstPage + i * 2)
  const resources = fonts.map((_, i) => `/F${i} ${3 + i} 0 R`).join(' ')

  const objects: string[] = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Count ${pages.length} /Kids [${pageIds.map(id => `${id} 0 R`).join(' ')}] >>`,
    ...fonts.map(font => `<< /Type /Font /Subtype /Type1 /BaseFont /${BASE_FONTS[font]} `
      + '/Encoding /WinAnsiEncoding >>'),
  ]
  pages.forEach((runs, i) => {
    const content = runs.map(run =>
      `BT /F${fonts.indexOf(run.font)} ${number(run.size)} Tf `
      + `1 0 0 1 ${number(run.x)} ${number(PAGE_HEIGHT - run.y)} Tm ${literal(run.text)} Tj ET`)
      .join('\n')
    objects.push(
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PAGE_WIDTH} ${PAGE_HEIGHT}] `
        + `/Resources << /Font << ${resources} >> >> /Contents ${pageIds[i] + 1} 0 R >>`,
      `<< /Length ${content.length} >>\nstream\n${content}\nendstream`)
  })

  // The comment's high bytes tell programs that move files about that this one is binary.
  let file = '%PDF-1.4\n%\xE2\xE3\xCF\xD3\n'
  const offsets = objects.map((body, i) => {
    const offset = file.length
    file += `${i + 1} 0 obj\n${body}\nendobj\n`
    return offset
  })
  const xref = file.length
  file += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
    + offsets.map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')
    + `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`

  // Every character above is below 256, so lengths and offsets are already byte counts.
  return Uint8Array.from(file, c => c.charCodeAt(0))
}

export function downloadPdf(fileName: string, pages: Run[][]) {
  const blob = new Blob([pdf(pages).buffer as ArrayBuffer], { type: 'application/pdf' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName.endsWith('.pdf') ? fileName : `${fileName}.pdf`
  link.click()
  URL.revokeObjectURL(url)
}
