/**
 * DOMjudge contests are named `<classroom>~<contest>` inside CPIntel, because a contest id is
 * only unique on its own judge. This is the judge's own id, for showing to people; the
 * qualified form is what routes and API calls take.
 */
export function judgeIdOf(ref: string): string {
  const at = ref.indexOf('~')
  return at > 0 && /^\d+$/.test(ref.slice(0, at)) ? ref.slice(at + 1) : ref
}
