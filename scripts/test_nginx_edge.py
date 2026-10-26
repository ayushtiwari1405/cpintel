#!/usr/bin/env python3
"""
Tests for the edge nginx (nginx/): security headers on proxied API responses, and the JSON
"restarting" answer when the backend is not there.

    python3 -I scripts/test_nginx_edge.py

The static checks read the config files and always run. The runtime checks start two throwaway
containers — the nginx image named in nginx/Dockerfile with this repository's config mounted in,
and a stub backend that sends what Spring Security sends — and remove them afterwards. They are
skipped without Docker or openssl, or with CPINTEL_NO_DOCKER=1. Either image is pulled first if
it is not already present (from Docker Hub, whose anonymous pulls are rate-limited). Standard
library only.
"""

import http.client
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NGINX = ROOT / "nginx"
TEMPLATE = (NGINX / "templates" / "cpintel.conf.template").read_text()
SNIPPET = (NGINX / "security-headers.conf").read_text()

# What the public response must carry, once each, with these values: nginx's own.
EDGE_HEADERS = {
    "Strict-Transport-Security": "max-age=31536000",
    "X-Frame-Options": "SAMEORIGIN",
    "X-Content-Type-Options": "nosniff",
}
# Set by both Spring Security and nginx, so the proxied copy has to be dropped.
BACKEND_DUPLICATES = list(EDGE_HEADERS)
ALSO_ONCE = ["Content-Security-Policy", "Referrer-Policy"]
# Spring's ForwardedHeaderFilter believes these, so a client's copies must never reach the backend.
CLEARED_FORWARDING = ["Forwarded", "X-Forwarded-Port", "X-Forwarded-Prefix"]


def api_locations(conf):
    """The bodies of the location blocks that proxy /api/ requests to the backend."""
    blocks = {}
    for m in re.finditer(r"^    location ([^{]+)\{\n(.*?)^    \}", conf, re.S | re.M):
        head, body = m.group(1).strip(), m.group(2)
        if "proxy_pass         http://backend" in body and "/api" in head:
            blocks[head] = body
    return blocks


def backend_locations(conf):
    """Every location block that proxies to the backend, whatever it serves."""
    return {m.group(1).strip(): m.group(2)
            for m in re.finditer(r"^    location ([^{]+)\{\n(.*?)^    \}", conf, re.S | re.M)
            if re.search(r"\n\s*proxy_pass\s+http://backend;", "\n" + m.group(2))}


class StaticConfig(unittest.TestCase):

    def test_every_backend_location_hides_the_backend_copies(self):
        locations = backend_locations(TEMPLATE)
        self.assertIn("= /actuator/health", locations)
        self.assertGreaterEqual(len(locations), 3)
        for head, body in locations.items():
            for name in BACKEND_DUPLICATES:
                self.assertRegex(body, rf"\n\s*proxy_hide_header\s+{name};", f"{head}: {name}")

    def test_every_backend_location_sets_the_forwarding_headers_itself(self):
        for head, body in backend_locations(TEMPLATE).items():
            for name in CLEARED_FORWARDING:
                self.assertRegex(body, rf'\n\s*proxy_set_header\s+{name}\s+"";', f"{head}: {name}")
            self.assertRegex(body, r"\n\s*proxy_set_header\s+X-Forwarded-For\s+\$remote_addr;", head)

    def test_both_api_locations_found(self):
        self.assertEqual(sorted(api_locations(TEMPLATE)),
                         ["/api/", "~ ^/api/v1/auth/(login|register|refresh)"])

    def test_backend_copies_of_edge_headers_are_hidden(self):
        for head, body in api_locations(TEMPLATE).items():
            for name in BACKEND_DUPLICATES:
                self.assertRegex(body, rf"\n\s*proxy_hide_header\s+{name};", f"{head}: {name}")

    def test_502_and_504_go_to_restarting(self):
        for head, body in api_locations(TEMPLATE).items():
            m = re.search(r"\n\s*error_page\s+([0-9 ]+)=\s*@restarting;", body)
            self.assertIsNotNone(m, head)
            self.assertEqual(set(m.group(1).split()), {"502", "504"}, head)

    def test_restarting_keeps_security_headers_and_retry_after(self):
        m = re.search(r"location @restarting \{(.*?)\n    \}", TEMPLATE, re.S)
        self.assertIsNotNone(m)
        self.assertIn("include /etc/nginx/security-headers.conf;", m.group(1))
        self.assertIn("add_header Retry-After 5 always;", m.group(1))
        self.assertIn('"code":"SERVER_RESTARTING"', m.group(1))

    def test_snippet_sets_each_header_once_with_its_value(self):
        for name, value in EDGE_HEADERS.items():
            lines = re.findall(rf'^add_header {name} "([^"]*)" always;$', SNIPPET, re.M)
            self.assertEqual(lines, [value], name)
        for name in ALSO_ONCE:
            self.assertEqual(len(re.findall(rf"^add_header {name} ", SNIPPET, re.M)), 1, name)


# ── runtime ──────────────────────────────────────────────────────────────────

STUB = r'''
import json, socket, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class H(BaseHTTPRequestHandler):
    def _answer(self):
        if "case=hangup" in self.path:          # closes without a response: nginx answers 502
            self.connection.shutdown(socket.SHUT_RDWR); self.close_connection = True; return
        if "case=slow" in self.path:            # slower than proxy_read_timeout: nginx answers 504
            time.sleep(8)
        body = b'{"ok":true}'
        if "case=echo" in self.path:            # the forwarding headers nginx passed on
            names = ("Forwarded", "X-Forwarded-For", "X-Forwarded-Port", "X-Forwarded-Prefix")
            body = json.dumps({n: self.headers.get(n) for n in names}).encode()
        self.send_response(200)
        # Exactly what Spring Security adds to an API response behind X-Forwarded-Proto: https.
        self.send_header("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)
    do_GET = do_POST = _answer
    def log_message(self, *a): pass
ThreadingHTTPServer(("127.0.0.1", 8080), H).serve_forever()
'''


def _docker_ok():
    if os.environ.get("CPINTEL_NO_DOCKER") == "1" or not shutil.which("docker") or not shutil.which("openssl"):
        return False
    return subprocess.run(["docker", "info"], capture_output=True).returncode == 0


def _nginx_image():
    m = re.search(r"^FROM\s+(\S+)", (NGINX / "Dockerfile").read_text(), re.M)
    return m.group(1)


def _docker(*args, timeout=300):
    """docker <args>, failing with Docker's own message (a pull refused by a rate limit says so)."""
    r = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"docker {args[0]} failed ({r.returncode}): {r.stderr.strip()[-500:]}")
    return r.stdout


@unittest.skipUnless(_docker_ok(), "needs docker and openssl (or CPINTEL_NO_DOCKER=1 was set)")
class Runtime(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # Cleanups are registered as each thing is created. unittest skips tearDownClass when
        # setUpClass raises, which used to leave the stub running whenever nginx failed to start.
        cls.tmp = Path(tempfile.mkdtemp(prefix="cpintel-edge-test-"))
        cls.addClassCleanup(shutil.rmtree, cls.tmp, ignore_errors=True)
        (cls.tmp / "templates").mkdir()
        (cls.tmp / "tls").mkdir()
        # The real template, with only the two read timeouts shortened so a slow reply reaches
        # 504 in seconds rather than minutes. Counted, so a renamed directive fails loudly.
        conf, n = re.subn(r"proxy_read_timeout\s+\d+s;", "proxy_read_timeout 3s;", TEMPLATE)
        assert n == 2, f"expected 2 proxy_read_timeout lines, found {n}"
        (cls.tmp / "templates" / "cpintel.conf.template").write_text(conf)
        (cls.tmp / "lab-networks.inc").write_text("")
        (cls.tmp / "stub.py").write_text(STUB)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=localhost", "-keyout", str(cls.tmp / "tls" / "privkey.pem"),
                        "-out", str(cls.tmp / "tls" / "fullchain.pem")], check=True, capture_output=True)
        for p in cls.tmp.rglob("*"):
            p.chmod(0o755 if p.is_dir() else 0o644)
        cls.tmp.chmod(0o755)

        tag = uuid.uuid4().hex[:10]
        cls.stub_name, cls.edge_name = f"cpintel-edge-test-stub-{tag}", f"cpintel-edge-test-nginx-{tag}"
        # The stub owns the network namespace and publishes nginx's 443 on a random loopback port;
        # nginx joins it, so "backend" and "frontend" are 127.0.0.1 and the stub is the backend.
        # Removed in reverse order of registration: nginx first, then the stub it shares a
        # namespace with. rm -f of a name that never started is a no-op.
        cls.addClassCleanup(subprocess.run, ["docker", "rm", "-f", cls.stub_name], capture_output=True)
        _docker("run", "-d", "--rm", "--name", cls.stub_name,
                "-p", "127.0.0.1::443", "--add-host", "backend:127.0.0.1", "--add-host", "frontend:127.0.0.1",
                "-v", f"{cls.tmp / 'stub.py'}:/stub.py:ro", "python:3.12-alpine", "python", "-I", "/stub.py")
        cls.addClassCleanup(subprocess.run, ["docker", "rm", "-f", cls.edge_name], capture_output=True)
        _docker("run", "-d", "--rm", "--name", cls.edge_name, "--network", f"container:{cls.stub_name}",
                "-e", "CPINTEL_DOMAIN=localhost",
                "-v", f"{NGINX / 'nginx.conf'}:/etc/nginx/nginx.conf:ro",
                "-v", f"{NGINX / 'security-headers.conf'}:/etc/nginx/security-headers.conf:ro",
                "-v", f"{cls.tmp / 'templates'}:/etc/nginx/templates:ro",
                "-v", f"{cls.tmp / 'tls'}:/etc/nginx/tls:ro",
                "-v", f"{cls.tmp / 'lab-networks.inc'}:/etc/nginx/lab-networks.inc:ro",
                _nginx_image())
        out = _docker("port", cls.stub_name, "443/tcp", timeout=30)
        cls.port = int(out.strip().splitlines()[0].rsplit(":", 1)[1])
        cls.ctx = ssl.create_default_context()
        cls.ctx.check_hostname = False
        cls.ctx.verify_mode = ssl.CERT_NONE  # the throwaway self-signed certificate above
        # Up means a request reaches the stub through nginx, not just that nginx answers: before
        # the stub listens every request is refused, which is a 502 whatever the case asked for.
        deadline = time.time() + 30
        while True:
            try:
                if cls.get("/api/v1/version")[0] == 200:
                    break
            except OSError:
                pass
            if time.time() > deadline:
                logs = subprocess.run(["docker", "logs", cls.edge_name], capture_output=True, text=True)
                raise RuntimeError("edge nginx did not come up:\n" + logs.stdout + logs.stderr)
            time.sleep(0.5)

    @classmethod
    def get(cls, path, method="GET", headers=None):
        conn = http.client.HTTPSConnection("127.0.0.1", cls.port, context=cls.ctx, timeout=30)
        try:
            conn.request(method, path, headers={"Host": "localhost", **(headers or {})})
            resp = conn.getresponse()
            return resp.status, resp.getheaders(), resp.read()
        finally:
            conn.close()

    def assertEdgeHeaders(self, headers, where):
        for name in list(EDGE_HEADERS) + ALSO_ONCE:
            values = [v for k, v in headers if k.lower() == name.lower()]
            self.assertEqual(len(values), 1, f"{where}: {name} = {values}")
            if name in EDGE_HEADERS:
                self.assertEqual(values[0], EDGE_HEADERS[name], f"{where}: {name}")

    def test_proxied_responses_carry_one_copy_of_each_header(self):
        for path, method in (("/api/v1/version", "GET"), ("/api/v1/auth/login", "POST"),
                             ("/actuator/health", "GET")):
            status, headers, _ = self.get(path, method)
            self.assertEqual(status, 200, path)
            self.assertEdgeHeaders(headers, path)

    def test_client_forwarding_headers_do_not_reach_the_backend(self):
        spoof = {"Forwarded": "for=10.20.0.5", "X-Forwarded-For": "10.20.0.6",
                 "X-Forwarded-Port": "1", "X-Forwarded-Prefix": "/evil"}
        for path, method in (("/api/v1/thing?case=echo", "GET"), ("/api/v1/auth/login?case=echo", "POST"),
                             ("/actuator/health?case=echo", "GET")):
            status, _, body = self.get(path, method, spoof)
            self.assertEqual(status, 200, path)
            seen = json.loads(body)
            for name in CLEARED_FORWARDING:
                self.assertIsNone(seen[name], f"{path}: {name}")
            self.assertNotIn("10.20.0.6", seen["X-Forwarded-For"] or "", path)

    def assertRestarting(self, path, method, cause):
        """The JSON restarting answer, and nginx's log saying it was for the intended reason."""
        status, headers, body = self.get(path, method)
        log = subprocess.run(["docker", "logs", self.edge_name], capture_output=True, text=True).stderr
        request = f'request: "{method} {path} HTTP/1.1"'
        self.assertTrue(any(cause in line and request in line for line in log.splitlines()),
                        f"{path}: expected '{cause}' in nginx's error log")
        self.assertEqual(status, 503, path)
        self.assertEqual(json.loads(body)["code"], "SERVER_RESTARTING", path)
        self.assertEqual([v for k, v in headers if k.lower() == "retry-after"], ["5"], path)
        self.assertEdgeHeaders(headers, path)

    def test_502_answers_restarting_json(self):
        self.assertRestarting("/api/v1/thing?case=hangup", "GET", "upstream prematurely closed")
        self.assertRestarting("/api/v1/auth/login?case=hangup", "POST", "upstream prematurely closed")

    def test_504_answers_restarting_json(self):
        self.assertRestarting("/api/v1/thing?case=slow", "GET", "upstream timed out")
        self.assertRestarting("/api/v1/auth/refresh?case=slow", "POST", "upstream timed out")


if __name__ == "__main__":
    unittest.main()
