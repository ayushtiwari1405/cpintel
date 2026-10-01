// Runs on CPIntel's own pages only (manifest.json "matches"), and bridges the page to the
// extension's background worker: the page cannot talk to an extension directly without knowing
// its id, and should not need to.
//
// The page learns the extension is installed from the attribute set here, and sends requests
// as window messages; only messages from this very window and origin are passed on.

document.documentElement.setAttribute('data-cpintel-cf', chrome.runtime.getManifest().version)

window.addEventListener('message', (event) => {
  if (event.source !== window || event.origin !== window.location.origin) return
  const message = event.data
  if (!message || message.cpintelCf !== 'request') return

  const forward = message.kind === 'open'
    ? { type: 'cf-open' }
    : { type: 'cf-fetch', request: message.request }

  chrome.runtime.sendMessage(forward, (response) => {
    const failure = chrome.runtime.lastError
    window.postMessage({
      cpintelCf: 'response',
      id: message.id,
      ...(failure ? { error: failure.message } : response ?? { error: 'No answer from the extension.' }),
    }, window.location.origin)
  })
})
