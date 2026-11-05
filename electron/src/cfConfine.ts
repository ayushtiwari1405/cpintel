/**
 * Where the app's Codeforces window may go while an examination is locked down.
 *
 * Connecting Codeforces from inside a Codeforces examination needs a sign-in page and, at times,
 * Cloudflare's browser check, and nothing else: a free Codeforces window is a browser, which is
 * what the lock is there to take away. The window closes itself once signed in or past the check.
 */
export function allowedWhileLocked(raw: string): boolean {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return false
  }
  if (url.protocol !== 'https:' || url.hostname !== 'codeforces.com') return false
  const path = url.pathname.replace(/\/+$/, '') || '/'
  return path === '/' || path === '/enter' || path.startsWith('/cdn-cgi/') || isSubmitPage(path)
}

/** A contest's or the problemset's submit page: where the anti-bot check is passed. */
function isSubmitPage(path: string): boolean {
  return path === '/problemset/submit'
    || /^\/(group\/[A-Za-z0-9]{1,40}\/)?(contest|gym)\/\d+\/submit$/.test(path)
}

/**
 * Run inside a Codeforces submit page: once Cloudflare's anti-bot widget has put its token on
 * the page, the submit form's fields with that token among them; null until then.
 */
export const READ_VERIFIED_FORM = `(() => {
  const named = /turnstile|captcha/i
  const fields = [...document.querySelectorAll('input[name], textarea[name]')]
  const token = fields.find(el => named.test(el.name) && el.value)
  if (!token) {
    // The window is small and the widget sits below the form: keep it in sight, for the times
    // it wants a click.
    const widget = document.querySelector('.cf-turnstile, iframe[src*="challenges.cloudflare.com"]')
      || fields.find(el => named.test(el.name))?.parentElement
    if (widget && !window.__cpintelScrolled) {
      widget.scrollIntoView({ block: 'center' })
      window.__cpintelScrolled = true
    }
    return null
  }
  const form = token.form || document.querySelector('form.submit-form')
    || [...document.forms].find(f => f.querySelector('[name=source], [name=sourceFile]'))
  const out = {}
  if (form) for (const [k, v] of new FormData(form)) if (typeof v === 'string') out[k] = v
  for (const el of fields) if (named.test(el.name) && el.value) out[el.name] = el.value
  return out
})()`
