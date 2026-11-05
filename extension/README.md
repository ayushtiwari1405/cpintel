# CPIntel Codeforces Connector (browser extension)

Codeforces sits behind Cloudflare, and the clearance a browser earns is tied to that browser.
So a hosted CPIntel server cannot fetch problem statements or submit solutions for anyone:
it only ever gets the challenge page. This extension lets the user's own browser — signed in to
Codeforces, past the check — make those requests for CPIntel, which reads the pages and does the
rest. The desktop app does the same through its own Codeforces window and needs no extension.

It can only fetch `https://codeforces.com`, only runs on the CPIntel site it was built for, and
keeps nothing. CPIntel never receives the Codeforces cookies.

Since 0.3.0 it can also open two small Codeforces windows for CPIntel, each closing itself when
done: the **sign-in** page, when the browser is not signed in to Codeforces, and a **submit
page**, to pass Codeforces' anti-bot check (Cloudflare Turnstile) when a submission is refused
for it — the check's token and the page's form fields are read back and the submission sent
again. Either window closes as soon as it leaves Codeforces' home, sign-in, check or submit
pages, and gives up after three minutes. Time in them is not counted as away during an
examination. This needs the `scripting` permission, so an update asks for it.

## How users get it

They do not need anything from this folder. Somebody who tries to connect Codeforces on the
website without the extension is sent to the site's own **/extension** page, which packages the
extension for that site in the browser — stamped with the address it was opened on — offers it
as a download, and walks them through loading it (unzip, the browser's extensions page,
Developer mode, Load unpacked). The page notices once it is installed. Where the deployment has
a Chrome Web Store listing (`VITE_CF_EXTENSION_URL`), the page offers that first.

The source is in `frontend/extension-src`, so the website and this build script package the
same files.

## Build

Only needed for a store listing, or to load the extension while developing:

```bash
CPINTEL_SERVER_URL=https://cpintel.example.edu node build.mjs   # a deployment
node build.mjs --dev                                            # local development
```

Load `dist/` from `chrome://extensions` (Developer mode → Load unpacked), or publish
`cpintel-codeforces.zip` to the Chrome Web Store so users can install it in one click. Chrome,
Edge and Brave.

## Using it

Install it (see above), then press **Connect with this browser** on CPIntel's Platforms page,
or **Sign in to Codeforces** on an exam page; if the browser is not signed in to Codeforces, the
sign-in window opens. Older versions (before 0.3.0) need the browser signed in to Codeforces
first, and cannot pass the anti-bot check; CPIntel asks for an update then. If Codeforces shows its "checking your browser" page to
the extension, CPIntel offers to open codeforces.com in a tab; pass the check there once and
retry.
