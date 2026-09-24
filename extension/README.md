# CPIntel Codeforces Connector (browser extension)

Codeforces sits behind Cloudflare, and the clearance a browser earns is tied to that browser.
So a hosted CPIntel server cannot fetch problem statements or submit solutions for anyone:
it only ever gets the challenge page. This extension lets the user's own browser — signed in to
Codeforces, past the check — make those requests for CPIntel, which reads the pages and does the
rest. The desktop app does the same through its own Codeforces window and needs no extension.

It can only fetch `https://codeforces.com`, only runs on the CPIntel site it was built for, and
keeps nothing. CPIntel never receives the Codeforces cookies.

## Build

```bash
CPINTEL_SERVER_URL=https://cpintel.example.edu node build.mjs   # a deployment
node build.mjs --dev                                            # local development
```

Load `dist/` from `chrome://extensions` (Developer mode → Load unpacked), or publish
`cpintel-codeforces.zip` to the Chrome Web Store so users can install it in one click. Chrome,
Edge and Brave.

## Using it

Install it, sign in at codeforces.com in the same browser, then press **Connect with this
browser** on CPIntel's Platforms page. If Codeforces shows its "checking your browser" page to
the extension, CPIntel offers to open codeforces.com in a tab; pass the check there once and
retry.
