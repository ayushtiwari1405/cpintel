# Connecting Codeforces

Linking a handle on `/platforms` is what reads your public history. Submitting from CPIntel
needs something further: a Codeforces *session*. Both live on the Codeforces row of that page.

Codeforces publishes no login API, and it serves its pages only to a client that has passed a
browser check. So CPIntel never asks for your password and never signs in on your behalf — it
reuses a session you created yourself, in a real browser.

**Desktop app.** Press *Sign in to Codeforces*. A window opens on codeforces.com, you sign in
there as you would anywhere, and the app then reads codeforces.com itself and tells the server
which handle is signed in. The server keeps the handle, never the cookies; statements and
submissions go through the app's own Codeforces session.

**Website, with the CPIntel extension** (the way on a hosted server; see `extension/README.md`).
Press *Connect with this browser*. It uses the Codeforces account signed in on that browser; if
none is, a small sign-in window opens and connecting finishes once you have signed in. Again
only the handle reaches the server.

**Submitting and the anti-bot check.** Codeforces' submit form now carries Cloudflare's
anti-bot check, which only a real page can pass. When a submission is refused for it, the app
or extension opens that submit page in a small window, the check fills in (usually with no
click), and the same submission is sent again; the window closes itself. After the first time,
each later submission passes the check first. A submission the server makes from stored cookies
cannot pass it.

**Local development without the extension.** A page cannot read an HttpOnly cookie belonging to
another origin, so the session has to be fetched by something outside the browser. Run the
helper, then press *Connect*:

```bash
./scripts/grab-cf-cookie.py --serve
```

It reads Codeforces cookies from whichever browser you are signed in with, posts them straight
to the CPIntel backend, and answers the page with nothing but the handle it connected. A manual
route — paste the `Cookie` request header from DevTools — is behind the same card if the helper
cannot run.

The helper answers only the development origins (`localhost:5173` and `:4173`) and posts the
session only to one of those origins or to the backend on `localhost:8080`. For a CPIntel
served anywhere else, name it: `--origin https://cpintel.example.edu` allows both the page and
its API. A request naming any other API address is refused before a cookie is read.

**Sessions go stale, and sooner than they look.** The cookie recording that you passed the
browser check lives hours; the session CPIntel stores lives a fortnight. When it expires, the
account still reads as connected while statements and submits start failing. Problem pages say
so and link back to `/platforms` to reconnect; the desktop app usually needs one click, since it
keeps you signed in to Codeforces in its own window. (This applies to cookie sessions; a session
connected through the app or the extension holds only the handle and lives in that browser.)

The stored cookie is encrypted at rest with `CPINTEL_SESSION_KEY`, kept in Redis only, never
returned by any endpoint, and dropped by *Disconnect* here or from Codeforces' own
Settings -> Sessions.
