/**
 * Copies text, rejecting when it could not.
 *
 * <p>`navigator.clipboard` exists only in a secure context — https or localhost. A server reached
 * by a plain-http network address (a LAN or Tailscale test) has none, so this falls back to the
 * old select-and-`execCommand('copy')` route, which still works from a click there.
 */
export async function copyText(text: string): Promise<void> {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  try {
    if (!document.execCommand('copy')) throw new Error('copy refused')
  } finally {
    area.remove()
  }
}
