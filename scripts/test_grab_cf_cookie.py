#!/usr/bin/env python3
"""
Tests for grab-cf-cookie.py: where the helper is willing to send a Codeforces session.

    python3 -I scripts/test_grab_cf_cookie.py

Standard library only. No browser, no Codeforces and no backend: the cookie reader and the
upload are replaced, and the helper is started on a free loopback port for the request tests.
"""

import importlib.util
import json
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

# Loaded by path (the name has a hyphen); no .pyc left beside the script.
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "grab_cf_cookie", Path(__file__).with_name("grab-cf-cookie.py"))
helper = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(helper)

TRUSTED = set(helper.DEFAULT_ORIGINS) | set(helper.DEFAULT_API_ORIGINS) | {"https://cpintel.example.edu"}


class TrustedApiBase(unittest.TestCase):

    def assertTrusted(self, url):
        self.assertEqual(helper.trusted_api_base(url, TRUSTED), url, url)

    def assertRefused(self, url):
        self.assertIsNone(helper.trusted_api_base(url, TRUSTED), url)

    def test_page_and_backend_origins(self):
        self.assertTrusted("http://localhost:5173/api/v1")
        self.assertTrusted("http://127.0.0.1:4173/api/v1")
        self.assertTrusted("http://localhost:8080/api/v1")
        self.assertTrusted("https://cpintel.example.edu/api/v1")

    def test_spellings_of_a_trusted_origin(self):
        self.assertTrusted("HTTPS://CPINTEL.EXAMPLE.EDU/api/v1")
        self.assertTrusted("https://cpintel.example.edu:443/api/v1")
        self.assertTrusted("https://cpintel.example.edu/api/v1/")

    def test_other_hosts(self):
        self.assertRefused("https://attacker.example/api/v1")
        self.assertRefused("https://cpintel.example.edu.attacker.example/api/v1")
        self.assertRefused("https://attacker.example/cpintel.example.edu/api/v1")

    def test_same_host_other_port_or_scheme(self):
        self.assertRefused("http://cpintel.example.edu/api/v1")
        self.assertRefused("https://cpintel.example.edu:8443/api/v1")
        self.assertRefused("http://localhost:9999/api/v1")

    def test_disguised_hosts(self):
        self.assertRefused("https://cpintel.example.edu@attacker.example/api/v1")
        self.assertRefused("https://user:pw@cpintel.example.edu/api/v1")
        self.assertRefused("http://localhost:8080\\@attacker.example/api/v1")
        self.assertRefused("http://localhost:8080 /api/v1")
        self.assertRefused("http://localhost:8080/api/v1\r\nX-Injected: 1")

    def test_other_schemes(self):
        for url in ("file:///etc/passwd", "ftp://localhost:8080/api", "javascript:alert(1)",
                    "data:text/plain,x", "gopher://localhost:8080/"):
            self.assertRefused(url)

    def test_malformed(self):
        for url in ("", "localhost:8080/api/v1", "/api/v1", "http://", "http://localhost:99999/",
                    "http://[::1/", None, 42, ["http://localhost:8080"]):
            self.assertRefused(url)

    def test_query_or_fragment(self):
        self.assertRefused("http://localhost:8080/api/v1?next=https://attacker.example")
        self.assertRefused("http://localhost:8080/api/v1#x")

    def test_default_points_at_a_real_route(self):
        self.assertTrue(helper.DEFAULT_API_BASE.endswith("/api/v1"))
        self.assertEqual(helper.trusted_api_base(helper.DEFAULT_API_BASE, TRUSTED),
                         helper.DEFAULT_API_BASE)


class ConnectRequests(unittest.TestCase):
    """POST /connect against the real handler, with the cookie store and upload stubbed."""

    COOKIE = "JSESSIONID=secret-session-value"

    def setUp(self):
        helper.Handler.allowed_origins = set(helper.DEFAULT_ORIGINS) | {"https://cpintel.example.edu"}
        helper.Handler.browser = None
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), helper.Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/connect"
        self.collect = mock.patch.object(
            helper, "collect", return_value=(self.COOKIE, "firefox", "default", ["JSESSIONID"], "UA"))
        self.push = mock.patch.object(helper, "push_to_backend", return_value={"data": {"handle": "tourist"}})
        self.collect_mock = self.collect.start()
        self.push_mock = self.push.start()

    def tearDown(self):
        self.collect.stop()
        self.push.stop()
        self.server.shutdown()
        self.server.server_close()

    def post(self, payload, origin="https://cpintel.example.edu"):
        headers = {"Content-Type": "application/json"}
        if origin:
            headers["Origin"] = origin
        req = urllib.request.Request(self.url, data=json.dumps(payload).encode(), headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, resp.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    def test_trusted_destination_is_used_and_cookie_not_returned(self):
        status, body = self.post({"token": "t", "apiBase": "https://cpintel.example.edu/api/v1"})
        self.assertEqual(status, 200)
        self.assertEqual(self.push_mock.call_args.args[0], "https://cpintel.example.edu/api/v1")
        self.assertNotIn("secret-session-value", body)

    def test_untrusted_destination_is_refused_before_cookies_are_read(self):
        status, body = self.post({"token": "t", "apiBase": "https://attacker.example/api/v1"})
        self.assertEqual(status, 400)
        self.collect_mock.assert_not_called()
        self.push_mock.assert_not_called()
        self.assertNotIn("secret-session-value", body)

    def test_unexpected_scheme_is_refused(self):
        status, _ = self.post({"token": "t", "apiBase": "file:///tmp/x"})
        self.assertEqual(status, 400)
        self.push_mock.assert_not_called()

    def test_origin_less_caller_still_limited_to_trusted_destinations(self):
        status, _ = self.post({"token": "t", "apiBase": "https://attacker.example/api/v1"}, origin=None)
        self.assertEqual(status, 400)
        self.push_mock.assert_not_called()

    def test_missing_api_base_uses_the_default_backend(self):
        status, _ = self.post({"token": "t"}, origin=None)
        self.assertEqual(status, 200)
        self.assertEqual(self.push_mock.call_args.args[0], helper.DEFAULT_API_BASE)

    def test_untrusted_page_origin_is_still_refused(self):
        status, _ = self.post({"token": "t", "apiBase": "https://cpintel.example.edu/api/v1"},
                              origin="https://attacker.example")
        self.assertEqual(status, 403)
        self.push_mock.assert_not_called()


class Redirects(unittest.TestCase):

    def test_redirect_is_not_followed(self):
        handler = helper._NoRedirect()
        req = urllib.request.Request("http://localhost:8080/api/v1/practice/cf-session",
                                     data=b"{}", headers={"Authorization": "Bearer t"})
        with self.assertRaises(urllib.error.HTTPError):
            handler.redirect_request(req, None, 302, "Found", {}, "https://attacker.example/")


if __name__ == "__main__":
    unittest.main()
