import { PAGE_HEIGHT, PAGE_WIDTH, textWidth, unsupported, type Font, type Run } from '@/utils/pdf'

/**
 * A password sheet: one message an admin drafts, filled in once per person.
 *
 * <p>The draft is plain text. {@code {{name}}} stands for a value, a line starting {@code # } is
 * a heading, and {@code **words**} are bold. Passwords are always set in a fixed-width face, so
 * that a {@code 1} and an {@code l} cannot be mistaken for each other.
 *
 * <p>The draft is read first and the values put in afterwards, so a name or a password that
 * happens to contain {@code **} or {@code {{…}}} is printed as it is.
 */

export interface SheetVariable {
  key: string
  /** What it stands for, as the editor lists it. */
  label: string
}

/** One person's values, by variable key. */
export type SheetValues = Record<string, string>

const MARGIN = 56
const LINE_WIDTH = PAGE_WIDTH - MARGIN * 2
const BODY_SIZE = 11
const HEADING_SIZE = 16
const LEADING = 1.5

type Piece =
  | { type: 'text'; text: string; bold: boolean }
  | { type: 'value'; key: string; bold: boolean }

const TOKEN = /(\{\{[^{}]*\}\}|\*\*)/

function parseLine(line: string): Piece[] {
  const parts = line.split(TOKEN)
  const markers = parts.filter(p => p === '**').length
  // A lone "**" is just two asterisks; only pairs switch bold on and off.
  let paired = markers - (markers % 2)
  let bold = false
  const pieces: Piece[] = []
  parts.forEach(part => {
    if (part === '**' && paired > 0) {
      paired--
      bold = !bold
    } else if (part.startsWith('{{') && part.endsWith('}}')) {
      pieces.push({ type: 'value', key: part.slice(2, -2).trim(), bold })
    } else if (part) {
      pieces.push({ type: 'text', text: part, bold })
    }
  })
  return pieces
}

/** The variables a draft uses, in the order it first uses them. */
export function usedVariables(draft: string): string[] {
  return [...new Set(draft.split('\n').flatMap(parseLine)
    .flatMap(p => p.type === 'value' ? [p.key] : []))]
}

/** Typed text as it will be printed: composed accents, plain spaces, no invisible joiners. */
const tidy = (text: string) => text.normalize('NFC')
  .replace(/\t/g, '    ')
  .replace(/[\u200B-\u200D\u2060\uFEFF\r]/g, '')
  .replace(/[\u00A0\u2000-\u200A\u202F\u205F\u3000]/g, ' ')

/** A password is printed exactly as set; anything else is tidied like the draft. */
const valueText = (values: SheetValues, key: string) =>
  key === 'password' ? values[key] ?? '' : tidy(values[key] ?? '').replace(/\s*\n\s*/g, ' ')

export interface SheetProblems {
  /** Names in {{…}} that this sheet has no value for. */
  unknown: string[]
  /** Characters the PDF cannot draw, and who they belong to ("" for the draft itself). */
  unprintable: { where: string; characters: string[] }[]
  /** Variables the draft uses that are blank for some people. */
  blank: { key: string; count: number }[]
}

export function checkSheets(draft: string, variables: SheetVariable[],
                            people: SheetValues[]): SheetProblems {
  const known = new Set(variables.map(v => v.key))
  const used = usedVariables(draft)
  const keys = used.filter(k => known.has(k))
  const unprintable: SheetProblems['unprintable'] = []

  const typed = draft.split('\n').flatMap(parseLine)
    .map(p => p.type === 'text' ? tidy(p.text) : '').join('')
  const bad = unsupported(typed)
  if (bad.length > 0) unprintable.push({ where: '', characters: bad })

  people.forEach(values => {
    const characters = unsupported(keys.map(k => valueText(values, k)).join(''))
    if (characters.length > 0) unprintable.push({ where: values.username ?? '', characters })
  })

  return {
    unknown: used.filter(k => !known.has(k)),
    unprintable,
    blank: keys
      .map(key => ({ key, count: people.filter(v => !valueText(v, key).trim()).length }))
      .filter(b => b.count > 0),
  }
}

interface Span { text: string; font: Font }
/** Spans that must stay together, and whether a line may break here (spaces). */
interface Word { spans: Span[]; space: boolean }

function words(pieces: Piece[], values: SheetValues, heading: boolean): Word[] {
  const out: Word[] = []
  const push = (text: string, font: Font, space: boolean) => {
    if (!text) return
    const last = out[out.length - 1]
    if (last && last.space === space) last.spans.push({ text, font })
    else out.push({ spans: [{ text, font }], space })
  }
  pieces.forEach(piece => {
    const plain: Font = heading || piece.bold ? 'bold' : 'regular'
    if (piece.type === 'value' && piece.key === 'password') {
      // Never broken at a space of its own.
      push(valueText(values, piece.key), 'mono', false)
      return
    }
    const text = piece.type === 'text' ? tidy(piece.text) : valueText(values, piece.key)
    text.split(/( +)/).forEach(part => push(part, plain, part.startsWith(' ')))
  })
  return out
}

const widthOf = (spans: Span[], size: number) =>
  spans.reduce((total, s) => total + textWidth(s.text, s.font, size), 0)

/** Greedy wrapping. A word wider than the whole line is cut where it reaches the edge. */
function wrap(list: Word[], size: number): Span[][] {
  const lines: Span[][] = [[]]
  let used = 0
  const newLine = () => { lines.push([]); used = 0 }

  list.forEach(word => {
    const width = widthOf(word.spans, size)
    const line = lines[lines.length - 1]
    if (used + width <= LINE_WIDTH) {
      line.push(...word.spans)
      used += width
      return
    }
    // The space that would end a line is where it breaks, and is not carried over.
    if (word.space) { newLine(); return }
    if (used > 0) newLine()
    if (width <= LINE_WIDTH) {
      lines[lines.length - 1].push(...word.spans)
      used = width
      return
    }
    word.spans.forEach(span => {
      for (const char of span.text) {
        const w = textWidth(char, span.font, size)
        if (used + w > LINE_WIDTH) newLine()
        const current = lines[lines.length - 1]
        const tail = current[current.length - 1]
        if (tail && tail.font === span.font) tail.text += char
        else current.push({ text: char, font: span.font })
        used += w
      }
    })
  })
  return lines
}

export type MailStyle = 'REGULAR' | 'BOLD' | 'PASSWORD'

export interface MailLine {
  heading: boolean
  spans: { text: string; style: MailStyle }[]
}

/** The draft's lines, a heading's marker taken off. */
const sourceLines = (draft: string) => tidy(draft).replace(/\s+$/, '').split('\n')
  .map(source => {
    const heading = source.startsWith('# ')
    return { heading, pieces: parseLine(heading ? source.slice(2) : source) }
  })

/**
 * One person's message as the server mails it: the same reading of the draft as the PDF, left
 * unwrapped for the reader's own screen. Mail carries any script, so nothing is refused here.
 */
export function mailLines(draft: string, values: SheetValues): MailLine[] {
  return sourceLines(draft).map(({ heading, pieces }) => ({
    heading,
    spans: pieces.map(piece => piece.type === 'text'
      ? { text: piece.text, style: piece.bold ? 'BOLD' : 'REGULAR' }
      : {
          text: valueText(values, piece.key),
          style: piece.key === 'password' ? 'PASSWORD' : piece.bold ? 'BOLD' : 'REGULAR',
        }),
  }))
}

/** A subject line with its values put in. No markup, and never more than one line. */
export function fillSubject(subject: string, values: SheetValues): string {
  return tidy(subject).replace(/\{\{([^{}]*)\}\}/g,
    (_, key: string) => valueText(values, key.trim())).replace(/\s*\n\s*/g, ' ').trim()
}

/** One person's sheet: usually a single page, more if the message is long. */
export function sheetPages(draft: string, values: SheetValues): Run[][] {
  const pages: Run[][] = [[]]
  let y = MARGIN

  sourceLines(draft).forEach(({ heading, pieces }) => {
    const size = heading ? HEADING_SIZE : BODY_SIZE

    wrap(words(pieces, values, heading), size).forEach(spans => {
      if (y + size * LEADING > PAGE_HEIGHT - MARGIN) {
        pages.push([])
        y = MARGIN
      }
      let x = MARGIN
      const page = pages[pages.length - 1]
      let previous: Run | null = null
      spans.forEach(span => {
        if (previous && previous.font === span.font) previous.text += span.text
        else {
          previous = { x, y: y + size, text: span.text, font: span.font, size }
          page.push(previous)
        }
        x += textWidth(span.text, span.font, size)
      })
      y += size * LEADING
    })
  })
  return pages
}

/** Every person's sheet, each starting on a page of its own. */
export function allSheets(draft: string, people: SheetValues[]): Run[][] {
  return people.flatMap(values => sheetPages(draft, values))
}
