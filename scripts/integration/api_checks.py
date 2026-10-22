#!/usr/bin/env python3
"""
The representative API workflow scripts/integration-test.sh runs through nginx.

    IT_BASE_URL=https://127.0.0.1:<port> IT_ADMIN_EMAIL=... IT_ADMIN_PASSWORD=... \\
        python3 -I api_checks.py fresh|upgrade

The credentials come from the environment, never argv, and are never printed. Each check prints
one line; the first failure prints what was expected and exits 1. The certificate is nginx's own
temporary self-signed one, so it is not verified. Standard library only.

fresh    the whole workflow: the page, health, sign-in refused and accepted, the profile, a
         personal file written to and read back from MongoDB, a practice plan, a password reset
         asked for, sign-out, and the signed-out token and refresh cookie refused.
upgrade  the part that proves an upgraded database serves: sign-in, the profile, and a file
         written and read back.
"""
import http.cookiejar
import json
import os
import ssl
import sys
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("IT_BASE_URL", "").rstrip("/")
CONTEXT = ssl.create_default_context()
CONTEXT.check_hostname = False
CONTEXT.verify_mode = ssl.CERT_NONE

INTERNALS = ("Exception", "\tat ", "org.springframework", "java.", "SQL", "Caused by")


class Failed(Exception):
    pass


def request(method, path, body=None, token=None, headers=None, jar=None, raw=None, content_type=None):
    """(status, headers, text) for one request; HTTP errors are answers, not exceptions."""
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Host", "localhost")
    if data is not None:
        req.add_header("Content-Type", content_type or "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    handlers = [urllib.request.HTTPSHandler(context=CONTEXT)]
    if jar is not None:
        handlers.append(urllib.request.HTTPCookieProcessor(jar))
    opener = urllib.request.build_opener(*handlers)
    try:
        with opener.open(req, timeout=30) as r:
            return r.status, r.headers, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode("utf-8", "replace")


def expect(cond, what):
    if not cond:
        raise Failed(what)


def check(name, fn):
    try:
        fn()
    except Failed as e:
        print(f"  FAIL  {name}: {e}")
        raise SystemExit(1)
    print(f"  ok    {name}")


def json_of(text):
    try:
        return json.loads(text)
    except ValueError:
        raise Failed("the answer is not JSON")


def no_internals(text):
    for word in INTERNALS:
        expect(word not in text, f"the answer exposes {word!r}")


class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.token = None

    def sign_in(self):
        status, headers, text = request("POST", "/api/v1/auth/login", jar=self.jar,
            body={"identifier": os.environ["IT_ADMIN_EMAIL"], "password": os.environ["IT_ADMIN_PASSWORD"]})
        expect(status == 200, f"sign-in answered {status}")
        self.token = json_of(text)["data"]["accessToken"]
        cookie = headers.get("Set-Cookie", "")
        expect("cpintel_refresh=" in cookie, "no refresh cookie")
        for flag in ("HttpOnly", "Secure", "SameSite=Strict"):
            expect(flag.lower() in cookie.lower(), f"the refresh cookie is not {flag}")

    def profile(self):
        status, _, text = request("GET", "/api/v1/users/me", token=self.token)
        expect(status == 200, f"/users/me answered {status}")
        expect(json_of(text)["data"]["role"] == "SUPER_ADMIN", "the bootstrap admin is not SUPER_ADMIN")

    def file_round_trip(self):
        """A personal file - stored in MongoDB by the backend's own account - written and read back."""
        name = f"it-{uuid.uuid4().hex[:8]}.cpp"
        content = "#include <bits/stdc++.h>\nint main() { return 0; }\n"
        boundary = uuid.uuid4().hex
        payload = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
                   f"Content-Type: text/x-c++src\r\n\r\n{content}\r\n--{boundary}--\r\n").encode()
        status, _, text = request("POST", "/api/v1/files", token=self.token, raw=payload,
                                  content_type=f"multipart/form-data; boundary={boundary}")
        expect(status == 200, f"the upload answered {status}")
        file_id = json_of(text)["data"]["id"]
        status, _, text = request("GET", f"/api/v1/files/{file_id}", token=self.token)
        expect(status == 200, f"reading the file answered {status}")
        expect(json_of(text)["data"]["text"] == content, "the file came back changed")
        return name

    def files(self):
        status, _, text = request("GET", "/api/v1/files", token=self.token)
        expect(status == 200, f"the file list answered {status}")
        return json_of(text)["data"]["files"]


def fresh():
    s = Session()

    def page():
        status, headers, text = request("GET", "/")
        expect(status == 200, f"/ answered {status}")
        expect('<div id="root"' in text, "/ is not the app's page")
        expect("strict-transport-security" in {k.lower() for k in headers.keys()}, "no HSTS header")
    check("nginx serves the app's page over https", page)

    def health():
        status, _, text = request("GET", "/actuator/health")
        expect(status == 200, f"/actuator/health answered {status}")
        body = json_of(text)
        expect(body.get("status") == "UP", "the backend is not UP")
        expect("components" not in body and "details" not in body, "health shows details to anyone")
    check("backend health is UP through nginx, with no details for an anonymous caller", health)

    def anonymous():
        status, _, text = request("GET", "/api/v1/users/me")
        expect(status == 401, f"answered {status}")
        expect(json_of(text).get("code") == "UNAUTHORIZED", "not UNAUTHORIZED")
    check("an anonymous request for a profile is a 401", anonymous)

    def wrong_password():
        wrong = "not-the-password-" + uuid.uuid4().hex
        status, _, text = request("POST", "/api/v1/auth/login",
                                  body={"identifier": os.environ["IT_ADMIN_EMAIL"], "password": wrong})
        expect(status == 401, f"answered {status}")
        no_internals(text)
        expect(wrong not in text, "the answer repeats the password")
    check("a wrong password is a 401 that says nothing more", wrong_password)

    check("the bootstrap admin signs in, with an HttpOnly, Secure, SameSite=Strict refresh cookie", s.sign_in)
    check("the profile is read with the token", s.profile)
    check("a personal file is stored in MongoDB and read back", s.file_round_trip)

    def plan():
        status, _, text = request("GET", "/api/v1/recommendations/daily", token=s.token)
        expect(status == 200, f"answered {status}")
        expect(json_of(text)["data"]["type"] == "DAILY", "not a daily plan")
    check("a practice plan is built for a user with no history", plan)

    def reset():
        status, _, text = request("POST", "/api/v1/auth/forgot-password", body={"email": os.environ["IT_ADMIN_EMAIL"]})
        expect(status == 200, f"answered {status}")
        expect("reset-password" not in text and "token" not in text.lower(), "the answer carries a reset link")
    check("a password reset is asked for (no SMTP here), and the answer carries no link", reset)

    def sign_out():
        token = s.token
        refresh = next((c.value for c in s.jar if c.name == "cpintel_refresh"), None)
        expect(refresh, "no refresh cookie was kept")
        status, _, _ = request("POST", "/api/v1/auth/logout", token=token, jar=s.jar)
        expect(status == 200, f"sign-out answered {status}")
        status, _, _ = request("GET", "/api/v1/users/me", token=token)
        expect(status == 401, f"the signed-out token still answers {status}")
        # The cookie as it was before signing out, sent back by hand: the browser would have
        # dropped it, but somebody who copied it should still be refused.
        status, _, _ = request("POST", "/api/v1/auth/refresh", headers={"Cookie": "cpintel_refresh=" + refresh})
        expect(status == 401, f"the old refresh cookie answered {status}")
    check("sign-out refuses the token and the refresh cookie afterwards", sign_out)


def upgrade():
    s = Session()
    check("the bootstrap admin signs in on the upgraded database", s.sign_in)
    check("the profile is read", s.profile)
    check("a personal file is written and read back by the backend's account", s.file_round_trip)


def main(argv):
    if len(argv) != 2 or argv[1] not in ("fresh", "upgrade") or not BASE:
        print("usage: IT_BASE_URL=... IT_ADMIN_EMAIL=... IT_ADMIN_PASSWORD=... api_checks.py fresh|upgrade")
        return 2
    (fresh if argv[1] == "fresh" else upgrade)()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
