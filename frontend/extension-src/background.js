// Fetches Codeforces pages for CPIntel, from this browser.
//
// Why a browser has to do it: Codeforces sits behind Cloudflare, which ties the clearance a
// browser earns to that browser. A server replaying the same cookies from its own address gets
// the challenge page, so CPIntel cannot fetch statements or submit on anyone's behalf from the
// server. This browser can: it is signed in, it has passed the check, and requests from here
// carry both.
//
// What it will do, and nothing else: GET or POST a URL on https://codeforces.com and hand back
// the status, final URL and body. The CPIntel server reads the pages and says what to post; this
// file knows nothing about Codeforces' pages. Only CPIntel's own pages can ask (see content.js
// and the matches in manifest.json).

const CF = 'https://codeforces.com'

// Requests from this service worker carry chrome-extension:// as their origin. Codeforces'
// forms expect to be posted from Codeforces, so those — and only those: tabId -1 is "not from
// any tab" — are sent with Codeforces' own Origin and Referer. Browsing codeforces.com in a tab
// is untouched.
async function installHeaderRule() {
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [1],
    addRules: [{
      id: 1,
      priority: 1,
      action: {
        type: 'modifyHeaders',
        requestHeaders: [
          { header: 'origin', operation: 'set', value: CF },
          { header: 'referer', operation: 'set', value: CF + '/' },
        ],
      },
      condition: {
        urlFilter: '|https://codeforces.com/',
        tabIds: [chrome.tabs.TAB_ID_NONE],
        resourceTypes: ['xmlhttprequest', 'other'],
      },
    }],
  })
}

chrome.runtime.onInstalled.addListener(installHeaderRule)
chrome.runtime.onStartup.addListener(installHeaderRule)

async function cfFetch(request) {
  const url = new URL(request.url)
  if (url.origin !== CF) throw new Error('Only https://codeforces.com can be fetched.')
  const method = request.method === 'POST' ? 'POST' : 'GET'

  let body
  if (method === 'POST') {
    body = new FormData()
    for (const [k, v] of Object.entries(request.form ?? {})) body.append(k, String(v))
  }

  await installHeaderRule()
  const res = await fetch(url.toString(), {
    method, body, credentials: 'include', redirect: 'follow', cache: 'no-store',
  })
  return { status: res.status, url: res.url, body: await res.text() }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  // Only this extension's own content script, which only runs on CPIntel's pages.
  if (sender.id !== chrome.runtime.id || !message) return false

  if (message.type === 'cf-open') {
    // For the browser check: a real tab on codeforces.com is how a person passes it.
    chrome.tabs.create({ url: CF + '/' })
    sendResponse({ ok: true })
    return false
  }
  if (message.type === 'cf-fetch') {
    cfFetch(message.request).then(sendResponse, e => sendResponse({ error: String(e?.message ?? e) }))
    return true   // answered asynchronously
  }
  return false
})
