import type { ProblemSample } from '@/types'

/**
 * Examples read out of a plain-text statement, for judges that publish no sample files.
 *
 * DOMjudge 8.0 exposes no testcase route to a team account, so for text statements the
 * examples written into the statement are the only samples there are. Two layouts are
 * recognised, since both turn up in hand-written problem sets:
 *
 *   side by side                    stacked
 *     Input:      Output:             Input:
 *     1432219     1219                1432219
 *     3                               3
 *                                     Output:
 *                                     1219
 *
 * Side by side is split at the column where "Output" starts in its heading line. Anything
 * that fits neither layout yields no sample rather than a guess — an empty case list is
 * obvious, a wrong expected output is not.
 */
export function parseSamples(text: string | null | undefined): ProblemSample[] {
  if (!text) return []
  const lines = text.replace(/\r\n?/g, '\n').split('\n').map(expandTabs)
  const samples: ProblemSample[] = []

  // A bare "Input:" heading is also how statements introduce the input format, so it only
  // counts as an example once an Example or Sample heading has been seen.
  let inExamples = false

  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (/^\s*(?:examples?|samples?)\b/i.test(line)) inExamples = true

    const side = SIDE_BY_SIDE.exec(line)
    if (side) {
      const column = side.index + side[0].indexOf(side[2])
      const block = takeBlock(lines, i + 1)
      const input = dedent(block.lines.map(l => l.slice(0, column).trimEnd()))
      const output = dedent(block.lines.map(l => l.slice(column)))
      if (input) samples.push({ input, output })
      i = block.end
      continue
    }

    const inputLine = INPUT_LINE.exec(line)
    if (inputLine && (inExamples || /sample|example/i.test(line))) {
      // "Input: RD   Output: Radiant" — both on one line.
      const both = /^(.*?)\s{2,}(?:sample\s+|example\s+)?output(?:\s*#?\d+)?\s*:\s*(.*)$/i
        .exec(inputLine[1] ?? '')
      if (both) {
        samples.push({ input: both[1].trim() + '\n', output: both[2].trim() + '\n' })
        i++
        continue
      }

      const input = section(lines, i, inputLine[1], l => OUTPUT_LINE.test(l) || INPUT_LINE.test(l))
      const outputLine = input.end < lines.length ? OUTPUT_LINE.exec(lines[input.end]) : null
      if (outputLine && input.text) {
        const output = section(lines, input.end, outputLine[1],
          l => SECTION.test(l) || INPUT_LINE.test(l))
        samples.push({ input: input.text, output: output.text })
        i = output.end
        continue
      }
    }

    i++
  }
  return samples
}

/** "Input:" and "Output:" as headings on one line, optionally "Sample Input 1" and the like. */
const SIDE_BY_SIDE = /^(\s*(?:sample\s+|example\s+)?input\b[^:\n]*:?)\s+((?:sample\s+|example\s+)?output\b[^:\n]*:?)\s*$/i
/**
 * An "Input" or "Output" label, alone on its line or followed by the value itself
 * ("Input:  RD"). A value on the same line needs the colon, so prose that merely starts with
 * the word — "Input size is small" — is not taken for a label.
 */
const INPUT_LINE = /^\s*(?:sample\s+|example\s+)?input(?:\s*#?\d+)?\s*(?::\s*(.*?))?\s*$/i
const OUTPUT_LINE = /^\s*(?:sample\s+|example\s+)?output(?:\s*#?\d+)?\s*(?::\s*(.*?))?\s*$/i
const INPUT_HEADING = /^\s*(?:sample\s+|example\s+)?input(?:\s*#?\d+)?\s*:?\s*$/i
/** What ends an example: the next example, or a new section of the statement. */
const SECTION = /^\s*(?:example|sample|explanation|note|constraints?)\b.*:?\s*$/i

/**
 * The value under a label at `at`: its inline text if any, plus the lines that follow until a
 * blank line or `stop`. Inline continuation lines are aligned under the value rather than
 * under the label, so they are stripped of indentation rather than dedented against it.
 */
function section(lines: string[], at: number, inline: string | undefined,
                 stop: (line: string) => boolean) {
  if (!inline) {
    const block = takeUntil(lines, at + 1, l => stop(l) || l.trim() === '')
    return { text: dedent(block.lines), end: block.end }
  }
  const out = [inline]
  let end = at + 1
  while (end < lines.length && lines[end].trim() !== '' && !stop(lines[end])) {
    out.push(lines[end++].trim())
  }
  return { text: dedent(out), end }
}

/** Lines up to the first blank line or section heading. */
function takeBlock(lines: string[], from: number) {
  return takeUntil(lines, from, l => l.trim() === '' || SECTION.test(l) || INPUT_HEADING.test(l))
}

function takeUntil(lines: string[], from: number, stop: (line: string) => boolean) {
  const out: string[] = []
  let end = from
  // Blank lines straight after a heading are layout, not data.
  while (end < lines.length && lines[end].trim() === '') end++
  while (end < lines.length && !stop(lines[end])) out.push(lines[end++])
  return { lines: out, end }
}

/** Strips the indentation the lines share, and trailing blank lines. */
function dedent(lines: string[]): string {
  const trimmed = lines.map(l => l.trimEnd())
  while (trimmed.length && trimmed[trimmed.length - 1] === '') trimmed.pop()
  const indents = trimmed.filter(l => l !== '').map(l => l.length - l.trimStart().length)
  const cut = indents.length ? Math.min(...indents) : 0
  const body = trimmed.map(l => l.slice(cut)).join('\n')
  return body ? body + '\n' : ''
}

function expandTabs(line: string): string {
  if (!line.includes('\t')) return line
  let out = ''
  for (const ch of line) out += ch === '\t' ? ' '.repeat(8 - (out.length % 8)) : ch
  return out
}
