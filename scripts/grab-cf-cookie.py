#!/usr/bin/env python3
"""
Connect your Codeforces account to CPIntel without copying headers out of DevTools.

The cookie that matters (JSESSIONID) is HttpOnly, so it is invisible to document.cookie and
to anything running inside a page. It lives in the browser's cookie database, which is what
this reads. Your browser can stay open.

Two modes:

  ./scripts/grab-cf-cookie.py                 write ~/.cf_cookie and stop (CLI)
  ./scripts/grab-cf-cookie.py --serve         run as the local helper the web UI talks to

Helper mode is what the "Connect Codeforces" button in CPIntel uses. It listens on
127.0.0.1 only, accepts requests from the CPIntel origin only, and — importantly — never
returns the cookie to the browser. It posts the session to the CPIntel backend itself, so
the page only ever learns which handle got connected.

    ./scripts/grab-cf-cookie.py --serve --port 7717

Other flags:
  --browser firefox|chrome|chromium|brave|edge|vivaldi   force one browser
  --out PATH        CLI mode output file (default ~/.cf_cookie)
  --yes             skip the CLI consent prompt
  --no-verify       skip the codeforces.com check
  --origin URL      extra allowed origin for helper mode (repeatable)

Only codeforces.com cookies are read, minus third-party analytics; everything else in
your browser is ignored. Values are never printed and never logged.

Firefox needs nothing extra. Chrome-family cookies are encrypted; decrypting them needs
`cryptography` (or `pycryptodome`) and, on most desktops, unlocking your login keyring.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOME = Path.home()
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0 Safari/537.36")
DEFAULT_PORT = 7717
DEFAULT_ORIGINS = [
    "http://localhost:5173", "http://127.0.0.1:5173",
    "http://localhost:4173", "http://127.0.0.1:4173",
]


# Mirrors CfSessionStore.isRelevant() — keep the two in step.
# Third-party analytics. Everything else on codeforces.com is kept.
#
# This was an allowlist of the cookies we thought mattered, and it silently broke reading
# submission source: Codeforces guards those pages with a JS challenge whose answer is a `pow`
# cookie plus a token under a randomised hex name (e.g. 70a7c28f3de). Neither could ever have
# been on a hand-written list, so sessions were stored without the proof the challenge had been
# passed and those pages came back as the interstitial. The SQL already restricts rows to
# codeforces.com hosts, so dropping analytics is the only filtering that needs to happen here.
NOISE_EXACT = {"_GA", "_GID", "_FBP", "_CLCK", "_CLSK"}
NOISE_PREFIX = ("_GA_", "_GAT", "__UTM", "_HJ")


def is_relevant(name):
    n = name.upper()
    return n not in NOISE_EXACT and not n.startswith(NOISE_PREFIX)


def c(code, s):
    return s if not sys.stdout.isatty() else f"\033[{code}m{s}\033[0m"


bold = lambda s: c("1", s)
green = lambda s: c("32", s)
red = lambda s: c("31", s)
yellow = lambda s: c("33", s)


# --------------------------------------------------------------- profile discovery

FIREFOX_ROOTS = [
    HOME / ".mozilla/firefox",
    HOME / "snap/firefox/common/.mozilla/firefox",
    HOME / ".var/app/org.mozilla.firefox/.mozilla/firefox",
]

CHROME_ROOTS = {
    "chrome": [HOME / ".config/google-chrome",
               HOME / ".var/app/com.google.Chrome/config/google-chrome"],
    "chromium": [HOME / ".config/chromium",
                 HOME / "snap/chromium/common/chromium",
                 HOME / ".var/app/org.chromium.Chromium/config/chromium"],
    "brave": [HOME / ".config/BraveSoftware/Brave-Browser",
              HOME / ".var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser"],
    "edge": [HOME / ".config/microsoft-edge"],
    "vivaldi": [HOME / ".config/vivaldi"],
}


def firefox_dbs():
    out = []
    for root in FIREFOX_ROOTS:
        if not root.is_dir():
            continue
        for prof in root.iterdir():
            db = prof / "cookies.sqlite"
            if db.is_file():
                out.append(("firefox", db))
    return out


def chrome_dbs():
    out = []
    for flavour, roots in CHROME_ROOTS.items():
        for root in roots:
            if not root.is_dir():
                continue
            for prof in list(root.iterdir()) + [root]:
                if not prof.is_dir():
                    continue
                for rel in ("Cookies", "Network/Cookies"):
                    db = prof / rel
                    if db.is_file():
                        out.append((flavour, db))
    return out


CHROMIUM_UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
               "Chrome/{major}.0.0.0 Safari/537.36")
FIREFOX_UA = ("Mozilla/5.0 (X11; Linux x86_64; rv:{major}.0) "
              "Gecko/20100101 Firefox/{major}.0")


def browser_user_agent(flavour, db):
    """
    The User-Agent the browser these cookies came from sends.

    This is not cosmetic. Codeforces is behind Cloudflare, and the cf_clearance cookie that
    records "this client already passed the challenge" is bound to the exact User-Agent that
    earned it. Replay the cookie under a different string and Cloudflare serves the "Just a
    moment..." interstitial instead of the page — which the backend then reads as a signed-out
    session, and reports as "those cookies are not logged in to Codeforces". They are; they are
    just being presented by something that does not look like the browser that got them.

    Derived from the browser's own version file so it tracks updates instead of going stale.
    Returns None if the version cannot be read, and the backend then falls back to a default.
    """
    try:
        if flavour == "firefox":
            # <profile>/compatibility.ini carries LastVersion=143.0_20250101...
            ini = db.parent / "compatibility.ini"
            if not ini.is_file():
                return None
            for line in ini.read_text(errors="replace").splitlines():
                if line.lower().startswith("lastversion="):
                    major = line.split("=", 1)[1].strip().split(".")[0]
                    return FIREFOX_UA.format(major=major) if major.isdigit() else None
            return None

        # Chromium family: "Last Version" sits at the install root, above the profile dir.
        for parent in list(db.parents)[:4]:
            marker = parent / "Last Version"
            if marker.is_file():
                major = marker.read_text(errors="replace").strip().split(".")[0]
                return CHROMIUM_UA.format(major=major) if major.isdigit() else None
    except Exception:
        return None
    return None


def sources(browser=None):
    found = firefox_dbs() + chrome_dbs()
    if browser:
        found = [s for s in found if s[0] == browser.lower()]
    return found


# --------------------------------------------------------------- sqlite helpers

def open_copy(db):
    """Copy the db (plus WAL) aside so a running browser's lock doesn't block us."""
    tmp = Path(tempfile.mkdtemp(prefix="cfcookie-"))
    dst = tmp / db.name
    shutil.copy2(db, dst)
    for suffix in ("-wal", "-shm", "-journal"):
        side = db.with_name(db.name + suffix)
        if side.exists():
            shutil.copy2(side, dst.with_name(dst.name + suffix))
    return sqlite3.connect(f"file:{dst}?mode=ro", uri=True), tmp


def read_firefox(db):
    conn, tmp = open_copy(db)
    try:
        rows = conn.execute(
            "SELECT name, value, host, lastAccessed FROM moz_cookies "
            "WHERE host LIKE '%codeforces.com'").fetchall()
    except sqlite3.Error as e:
        raise RuntimeError(f"could not read {db}: {e}")
    finally:
        conn.close()
        shutil.rmtree(tmp, ignore_errors=True)

    jar, seen = {}, 0.0
    for name, value, _host, last in rows:
        if value and is_relevant(name):
            jar[name] = value
            seen = max(seen, (last or 0) / 1e6)
    return jar, seen


# --------------------------------------------------------------- chrome decryption

def aes_cbc_decrypt(key, iv, blob):
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        d = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        return d.update(blob) + d.finalize()
    except ImportError:
        pass
    try:
        from Crypto.Cipher import AES
        return AES.new(key, AES.MODE_CBC, iv).decrypt(blob)
    except ImportError:
        raise RuntimeError(
            "Chrome cookies are encrypted and no AES library is available. "
            "pip install cryptography (or apt install python3-cryptography). "
            "Firefox needs no such dependency, if you have it installed.")


def keyring_password(flavour):
    """The v11 key lives in the login keyring; fetching it may prompt for your password."""
    label = {"chrome": "Chrome", "chromium": "Chromium", "brave": "Brave",
             "edge": "Microsoft Edge", "vivaldi": "Vivaldi"}.get(flavour, "Chrome")
    try:
        import secretstorage
        bus = secretstorage.dbus_init()
        coll = secretstorage.get_default_collection(bus)
        if coll.is_locked():
            coll.unlock()
        for item in coll.get_all_items():
            if item.get_label() == f"{label} Safe Storage":
                return item.get_secret().decode("utf-8")
            attrs = item.get_attributes()
            if attrs.get("application", "").lower() == flavour:
                return item.get_secret().decode("utf-8")
    except Exception:
        pass
    try:
        import subprocess
        r = subprocess.run(["secret-tool", "lookup", "application", flavour],
                           capture_output=True, timeout=20)
        if r.returncode == 0 and r.stdout:
            return r.stdout.decode("utf-8")
    except Exception:
        pass
    return None


def read_chrome(flavour, db):
    conn, tmp = open_copy(db)
    try:
        rows = conn.execute(
            "SELECT name, value, encrypted_value, host_key, last_access_utc FROM cookies "
            "WHERE host_key LIKE '%codeforces.com'").fetchall()
    except sqlite3.Error as e:
        raise RuntimeError(f"could not read {db}: {e}")
    finally:
        conn.close()
        shutil.rmtree(tmp, ignore_errors=True)

    rows = [r for r in rows if is_relevant(r[0])]
    if not rows:
        return {}, 0.0

    keys = {}

    def key_for(version):
        if version in keys:
            return keys[version]
        pw = "peanuts"
        if version == b"v11":
            pw = keyring_password(flavour) or "peanuts"
        keys[version] = hashlib.pbkdf2_hmac("sha1", pw.encode("utf-8"), b"saltysalt", 1, 16)
        return keys[version]

    jar, seen = {}, 0.0
    for name, value, enc, host, last in rows:
        plain = value
        if not plain and enc:
            version, blob = enc[:3], enc[3:]
            if version not in (b"v10", b"v11"):
                continue
            raw = aes_cbc_decrypt(key_for(version), b" " * 16, blob)
            if raw and raw[-1] <= 16:            # strip PKCS#7 padding
                raw = raw[:-raw[-1]]
            # Chrome 130+ prefixes the plaintext with sha256(host_key).
            if raw[:32] == hashlib.sha256(host.encode("utf-8")).digest():
                raw = raw[32:]
            try:
                plain = raw.decode("utf-8")
            except UnicodeDecodeError:
                plain = raw[32:].decode("utf-8", "ignore")
        if plain:
            jar[name] = plain
            seen = max(seen, (last or 0) / 1e6 - 11644473600)
    return jar, seen


# --------------------------------------------------------------- collection

def collect(browser=None):
    """
    Best Codeforces cookie jar across every profile.

    Prefers a jar that actually carries JSESSIONID, then the most recently used, so having
    several browsers or profiles open does not pick a stale logged-out one.
    """
    found = sources(browser)
    if not found:
        raise RuntimeError("No browser cookie databases found under ~/.mozilla, ~/snap, "
                           "~/.var/app or ~/.config.")

    best, best_src, best_seen = {}, None, -1.0
    problems = []
    for flavour, db in found:
        try:
            jar, seen = (read_firefox(db) if flavour == "firefox" else read_chrome(flavour, db))
        except RuntimeError as e:
            problems.append(f"{flavour}: {e}")
            continue
        if not jar:
            continue
        rank = (1 if "JSESSIONID" in jar else 0, seen)
        best_rank = (1 if "JSESSIONID" in best else 0, best_seen)
        if rank > best_rank:
            best, best_src, best_seen = jar, (flavour, db), seen

    if not best:
        hint = (" (" + "; ".join(problems) + ")") if problems else ""
        raise RuntimeError("No Codeforces cookies found in any browser profile. Open "
                           "codeforces.com, sign in, then try again." + hint)
    if "JSESSIONID" not in best:
        raise RuntimeError("Found Codeforces cookies but no JSESSIONID — that browser has "
                           "visited Codeforces but is not signed in.")

    header = "; ".join(f"{k}={v}" for k, v in best.items())
    return (header, best_src[0], best_src[1].parent.name, sorted(best),
            browser_user_agent(best_src[0], best_src[1]))


def _get(url, header):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept-Language": "en", "Cookie": header,
        "Accept": "text/html,application/xhtml+xml",
    })
    resp = urllib.request.urlopen(req, timeout=25)
    return resp.geturl(), resp.read().decode("utf-8", "ignore")


def verify(header):
    """
    Confirm the cookies are a live, signed-in Codeforces session and return whose it is.

    Two traps this has to avoid, both of which previously produced a confident wrong answer:

    1. A logged-OUT homepage still contains ~350 /profile/ links (the rating sidebar). Taking
       "the first profile link on the page" reports whoever tops the ratings — which is how a
       dead cookie got connected as Benq. The handle is only ever read from the header.

    2. Rendering is not proof of a session. /settings/general is used as the liveness probe
       because Codeforces answers it with 403 -> /enter when the session is dead, which is
       unambiguous in a way that scraping chrome is not.
    """
    # 1. Is this session actually alive?
    try:
        final_url, _ = _get("https://codeforces.com/settings/general", header)
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            return None, "that browser is signed out of Codeforces — sign in and try again"
        return None, f"Codeforces returned {e.code} while checking the session"
    except (urllib.error.URLError, TimeoutError) as e:
        return None, f"could not reach Codeforces ({e})"

    if "/enter" in final_url:
        return None, "that browser is signed out of Codeforces — sign in and try again"

    # 2. Who is it? Header only.
    try:
        _, html = _get("https://codeforces.com/", header)
    except (urllib.error.URLError, TimeoutError, urllib.error.HTTPError) as e:
        return None, f"could not reach Codeforces ({e})"

    if "Just a moment" in html or "challenges.cloudflare.com" in html:
        return None, "Cloudflare interstitial — cannot verify from here"

    header_html = _header_block(html)
    if header_html is None:
        return None, "could not read the Codeforces page header"
    if re.search(r'href="/enter', header_html):
        return None, "that browser is signed out of Codeforces — sign in and try again"

    m = re.search(r'href="/profile/([^"/?]+)"', header_html)
    if not m:
        return None, "signed in, but Codeforces did not show a handle in the page header"
    return m.group(1), None


def _header_block(html):
    """
    The chunk of markup up to the end of the page header.

    Everything after it is content — including the rating sidebar full of other people's
    profile links — so the handle must never be searched for beyond this point.
    """
    start = html.find('id="header"')
    if start == -1:
        return None
    end = html.find('<div class="content', start)
    if end == -1:
        end = html.find('id="pageContent"', start)
    if end == -1:
        end = min(len(html), start + 20000)
    return html[start:end]


def push_to_backend(api_base, token, header, expected_handle=None, user_agent=None):
    """
    Hand the session to CPIntel and let the server verify it.

    Verification lives on the server because only the server can act on the answer: it holds
    the session afterwards and enforces expectedHandle, so a mismatch is refused before
    anything is stored rather than after.

    The browser's User-Agent goes with the cookies. Cloudflare ties cf_clearance to the
    User-Agent that solved its challenge, so the backend has to present the same one or it gets
    the interstitial and concludes the session is signed out. Nothing gets through this without
    it — not this script, and not the backend either.

    The cookie goes straight from here to the backend and is never returned to the page.
    """
    url = api_base.rstrip("/") + "/practice/cf-session"
    payload = {"cookieHeader": header}
    if expected_handle:
        payload["expectedHandle"] = expected_handle
    if user_agent:
        payload["userAgent"] = user_agent
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
    })
    try:
        with urllib.request.urlopen(req, timeout=40) as resp:
            return json.loads(resp.read().decode("utf-8", "ignore"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        # Surface the backend's own sentence ("signed in as X, not Y", "not logged in to
        # Codeforces") rather than a wall of JSON.
        try:
            msg = (json.loads(raw) or {}).get("message")
        except Exception:
            msg = None
        raise RuntimeError(msg or f"CPIntel rejected the session ({e.code}): {raw[:300]}")
    except urllib.error.URLError as e:
        raise RuntimeError(f"Could not reach CPIntel at {url}: {e}")


# --------------------------------------------------------------- helper server

class Handler(BaseHTTPRequestHandler):
    allowed_origins = set(DEFAULT_ORIGINS)
    browser = None
    server_version = "CPIntelCfHelper/1.0"

    def log_message(self, fmt, *args):
        # Keep the console quiet and, more importantly, never log request bodies.
        sys.stderr.write("  %s - %s\n" % (self.address_string(), fmt % args))

    def _origin_ok(self):
        origin = self.headers.get("Origin")
        # No Origin at all is a direct (curl/CLI) call, which is fine — it cannot be a
        # cross-site page. An Origin that is present must be on the allowlist.
        return origin is None or origin in self.allowed_origins

    def _cors(self, origin):
        if origin and origin in self.allowed_origins:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            # Chrome's Private Network Access preflight for localhost targets.
            self.send_header("Access-Control-Allow-Private-Network", "true")

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self._cors(self.headers.get("Origin"))
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors(self.headers.get("Origin"))
        self.end_headers()

    def do_GET(self):
        if not self._origin_ok():
            return self._send(403, {"error": "origin not allowed"})
        if self.path.rstrip("/") == "/health":
            try:
                found = [f"{flavour}:{db.parent.name}" for flavour, db in sources(self.browser)]
            except Exception:
                found = []
            return self._send(200, {"ok": True, "version": "1.0", "profiles": found})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._origin_ok():
            return self._send(403, {"error": "origin not allowed"})
        if self.path.rstrip("/") != "/connect":
            return self._send(404, {"error": "not found"})

        try:
            length = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
        except Exception:
            return self._send(400, {"error": "malformed request"})

        token = payload.get("token")
        api_base = payload.get("apiBase") or "http://localhost:8080/api"
        wanted = (payload.get("handle") or "").strip()
        if not token:
            return self._send(400, {"error": "missing CPIntel token"})

        try:
            header, flavour, profile, names, browser_ua = collect(self.browser)
        except RuntimeError as e:
            return self._send(409, {"error": str(e)})
        except Exception as e:
            return self._send(500, {"error": f"could not read browser cookies: {e}"})

        # The browser's own User-Agent wins over the one the page reported: the cookies belong
        # to that browser, and the page may well be open in a different one. The page's value is
        # only a fallback for when the version file cannot be read.
        user_agent = browser_ua or payload.get("userAgent")

        # No Codeforces call from here: Cloudflare answers this process with an interstitial
        # rather than the page, so any verdict reached locally would be a guess. The backend
        # verifies the session and enforces `wanted`, refusing before it stores anything.
        try:
            result = push_to_backend(api_base, token, header, wanted, user_agent)
        except RuntimeError as e:
            # The backend's message is the useful one (wrong account, signed out, expired).
            return self._send(409, {"error": str(e)})

        handle = (result.get("data") or {}).get("handle") or wanted
        # Note: the cookie itself is never part of this response.
        self._send(200, {"connected": True, "handle": handle,
                         "browser": flavour, "profile": profile, "cookies": names})


def serve(port, browser, extra_origins):
    Handler.allowed_origins = set(DEFAULT_ORIGINS) | set(extra_origins or [])
    Handler.browser = browser
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(bold(f"\nCPIntel Codeforces helper listening on http://127.0.0.1:{port}"))
    print("  allowed origins : " + ", ".join(sorted(Handler.allowed_origins)))
    print("  reads only codeforces.com cookies, and never returns them to the page —")
    print("  the session is posted to the CPIntel backend directly.")
    print(yellow("\n  Leave this running while you use Compete. Ctrl-C to stop.\n"))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped.")
    return 0


# --------------------------------------------------------------- CLI

def main():
    ap = argparse.ArgumentParser(description="Connect Codeforces to CPIntel.")
    ap.add_argument("--browser", help="firefox, chrome, chromium, brave, edge, vivaldi")
    ap.add_argument("--out", default=str(HOME / ".cf_cookie"), help="CLI mode output file")
    ap.add_argument("--yes", action="store_true", help="skip the consent prompt")
    ap.add_argument("--no-verify", action="store_true", help="don't call codeforces.com")
    ap.add_argument("--serve", action="store_true", help="run as the local helper")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--origin", action="append", help="extra allowed origin (repeatable)")
    args = ap.parse_args()

    if args.serve:
        return serve(args.port, args.browser, args.origin)

    found = sources(args.browser)
    if not found:
        print(red("No browser cookie databases found."))
        return 2

    print(bold("\nThis will read Codeforces cookies from:\n"))
    for flavour, db in found:
        print(f"  {flavour:9s} {db}")
    print(f"""
Only session-relevant Codeforces cookies are read (JSESSIONID, X-User-*, 39ce7, RCPC,
lgnin, evercookie*, cf_clearance). The result is a live credential for your account; it
goes to {args.out} (mode 0600) and is never printed.""")

    if not args.yes:
        try:
            if input(bold("\nProceed? type 'yes': ")).strip().lower() != "yes":
                print("Aborted.")
                return 1
        except (EOFError, KeyboardInterrupt):
            print("\nAborted.")
            return 1

    try:
        header, flavour, profile, names, browser_ua = collect(args.browser)
    except RuntimeError as e:
        print(red(f"\n{e}"))
        return 3

    print(f"\nfound in {bold(flavour)} — profile {profile}")
    for n in names:
        print(f"  {n}")

    if not args.no_verify:
        print("\nverifying against codeforces.com…")
        who, why = verify(header)
        if who:
            print(green(f"  logged in as {who}"))
        else:
            print(yellow(f"  could not verify: {why}"))

    out = Path(args.out)
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(header)
    print(green(f"\nwrote {out} (mode 0600)"))
    print(f"""
Next:
  cd backend && CF_COOKIE="$(cat {out})" ./mvnw test -Dtest=CfProbeTest

Or run the helper so the web UI can connect on its own:
  ./scripts/grab-cf-cookie.py --serve""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
