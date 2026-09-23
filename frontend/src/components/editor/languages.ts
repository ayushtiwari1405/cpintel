/**
 * Maps a Codeforces language option onto an editor syntax mode and, where we can run it,
 * a backend runtime id.
 *
 * Codeforces names its compilers, not its languages — "GNU G++20 13.2 (64 bit, winlibs)",
 * "PyPy 3.10 (7.3.15, 64bit)" — and the list changes as they upgrade. Matching on patterns
 * rather than ids keeps this working across those changes; an unmatched label simply means
 * plain text and no local run, never a wrong guess.
 */

export interface LanguageMatch {
  /** Monaco language id for syntax highlighting. */
  monaco: string
  /** Backend runtime id, or null when this language cannot be run locally. */
  runner: string | null
}

const PLAIN: LanguageMatch = { monaco: 'plaintext', runner: null }

/**
 * Ordered: the first pattern that matches a label wins, so put the specific ones first.
 * `runner` is null for languages the backend has no runtime for yet — the editor still
 * highlights them correctly and submitting to Codeforces still works, only Run is off.
 */
const RULES: Array<{ pattern: RegExp; match: LanguageMatch }> = [
  { pattern: /\bG\+\+|\bclang\+\+|\bC\+\+/i, match: { monaco: 'cpp', runner: 'cpp' } },
  // Must come after C++: "GNU GCC C11" contains no '+', but plain /C\b/ would over-match.
  { pattern: /\bGCC C\d|\bGNU GCC\b/i,       match: { monaco: 'c', runner: null } },
  // PyPy runs here as CPython, which is a deliberate approximation. The two agree on what a
  // program computes and disagree on how fast; the local runner checks answers against samples
  // and has never claimed to predict a judge's timing, so the useful thing is to run it.
  { pattern: /\bPyPy\b|\bPython\b/i,         match: { monaco: 'python', runner: 'python3' } },
  { pattern: /\bKotlin\b/i,                  match: { monaco: 'kotlin', runner: null } },
  // Before Java: "Java 21" and "JavaScript" both contain "Java".
  { pattern: /\bJavaScript\b|\bNode\.js\b/i, match: { monaco: 'javascript', runner: null } },
  { pattern: /\bJava\b/i,                    match: { monaco: 'java', runner: null } },
  { pattern: /\bC#\b|\bMono\b|\.NET\b/i,     match: { monaco: 'csharp', runner: null } },
  { pattern: /\bRust\b/i,                    match: { monaco: 'rust', runner: null } },
  { pattern: /\bGo\b/i,                      match: { monaco: 'go', runner: null } },
  { pattern: /\bRuby\b/i,                    match: { monaco: 'ruby', runner: null } },
  { pattern: /\bScala\b/i,                   match: { monaco: 'scala', runner: null } },
  { pattern: /\bHaskell\b/i,                 match: { monaco: 'plaintext', runner: null } },
  { pattern: /\bPascal\b|\bDelphi\b/i,       match: { monaco: 'pascal', runner: null } },
  { pattern: /\bPHP\b/i,                     match: { monaco: 'php', runner: null } },
  { pattern: /\bPerl\b/i,                    match: { monaco: 'perl', runner: null } },
  { pattern: /\bOCaml\b/i,                   match: { monaco: 'plaintext', runner: null } },
  { pattern: /\bSQL\b/i,                     match: { monaco: 'sql', runner: null } },
]

/** Resolve a Codeforces language label. Never throws; falls back to plain text. */
export function detectLanguage(label?: string | null): LanguageMatch {
  if (!label) return PLAIN
  for (const { pattern, match } of RULES) {
    if (pattern.test(label)) return match
  }
  return PLAIN
}

/** Starter file for a language, so an empty editor is not a blank page. */
export function starterFor(monacoLanguage: string): string {
  switch (monacoLanguage) {
    case 'cpp':
      return [
        '#include <bits/stdc++.h>',
        'using namespace std;',
        '',
        'int main() {',
        '    ios::sync_with_stdio(false);',
        '    cin.tie(nullptr);',
        '',
        '    ',
        '}',
        '',
      ].join('\n')
    case 'python':
      return 'import sys\ninput = sys.stdin.readline\n\n\n'
    case 'java':
      return [
        'import java.util.*;',
        'import java.io.*;',
        '',
        'public class Main {',
        '    public static void main(String[] args) throws IOException {',
        '        ',
        '    }',
        '}',
        '',
      ].join('\n')
    default:
      return ''
  }
}
