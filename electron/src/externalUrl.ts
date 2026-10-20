/**
 * Whether a URL may be handed to shell.openExternal.
 *
 * <p>shell.openExternal starts whatever the operating system has registered for a scheme, so a
 * link to file:, smb:, or an application's own protocol would have the page choose a program to
 * run. Only web addresses go to the system browser; everything else is dropped. Parsed rather
 * than matched with a pattern, so the scheme tested is the one the browser would act on.
 */
export function isOpenableExternal(url: string): boolean {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return false
  }
  return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && parsed.hostname !== ''
}
