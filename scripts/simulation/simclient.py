"""
A small HTTP client for the simulation, on the standard library only.

Two things it has to do that ``urllib`` does not do well enough for a load test:

* **Keep connections alive.** ``urllib`` opens a fresh socket per request. At 200 contestants
  polling every five seconds that is thousands of TCP handshakes a minute, most of the measured
  latency becomes connection setup, and the client ends up sitting on a pile of sockets in
  TIME_WAIT. Real browsers hold a connection open; so does this.
* **Time every request.** The numbers are the deliverable, so timing is not something bolted on
  at the call site where it is easy to forget.

Deliberately not a general HTTP library. One connection per thread, no redirects, no cookies,
JSON in and out.
"""

from __future__ import annotations

import http.client
import json
import socket
import ssl
import threading
import time
from dataclasses import dataclass
from typing import Any, Optional
from urllib.parse import urlparse


@dataclass
class Response:
    status: int
    body: bytes
    elapsed: float
    #: Parsed JSON, or None when the body was not JSON.
    json: Optional[Any] = None

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    def data(self) -> Any:
        """
        Unwraps CPIntel's ``{success, message, data}`` envelope.

        Returns the raw parsed body for anything that is not in that shape, so this stays
        usable against DOMjudge's API too.
        """
        if isinstance(self.json, dict) and "data" in self.json and "success" in self.json:
            return self.json["data"]
        return self.json

    def message(self) -> str:
        if isinstance(self.json, dict) and self.json.get("message"):
            return str(self.json["message"])
        text = self.body.decode("utf-8", "replace").strip()
        return text[:300] if text else f"HTTP {self.status}"


class HttpError(Exception):
    def __init__(self, response: Response, context: str = ""):
        self.response = response
        prefix = f"{context}: " if context else ""
        super().__init__(f"{prefix}HTTP {response.status} — {response.message()}")


class Client:
    """
    One base URL, one connection per thread.

    Thread-local rather than a shared pool: each simulated contestant is a thread and holds its
    own connection for the length of the contest, which is what a real browser does and what
    makes the latency numbers mean something.
    """

    def __init__(self, base_url: str, timeout: float = 30.0, verify_tls: bool = True):
        parsed = urlparse(base_url.rstrip("/"))
        if parsed.scheme not in ("http", "https"):
            raise ValueError(f"base URL must be http or https, got {base_url!r}")

        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.prefix = parsed.path or ""
        self.timeout = timeout
        self.verify_tls = verify_tls
        self._local = threading.local()

    def _connection(self) -> http.client.HTTPConnection:
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            return conn

        if self.scheme == "https":
            context = ssl.create_default_context()
            if not self.verify_tls:
                # A simulation commonly runs against a box with a self-signed certificate.
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE
            conn = http.client.HTTPSConnection(
                self.host, self.port, timeout=self.timeout, context=context)
        else:
            conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

        # Connect eagerly so Nagle can be turned off on the socket. Left on, small
        # request bodies interact with delayed ACK and every measurement picks up a
        # spurious ~40ms — which is larger than most of the latencies being measured, and
        # would be read as the server being slow.
        try:
            conn.connect()
            conn.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            # Let the first real request surface the connection failure with its own
            # retry, rather than failing here where there is no context.
            pass

        self._local.conn = conn
        return conn

    def close(self) -> None:
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            try:
                conn.close()
            finally:
                self._local.conn = None

    def request(self, method: str, path: str, *, token: str = None,
                body: Any = None, headers: dict = None,
                raw_body: bytes = None, content_type: str = None) -> Response:
        """
        One request, timed.

        A dropped keep-alive connection is retried exactly once. Servers close idle connections
        routinely and that is not a failure worth reporting as one — but a second failure is
        real and is surfaced, because silently retrying forever would turn an outage into a
        latency figure.
        """
        url = self.prefix + path
        send_headers = {"Accept": "application/json", "Connection": "keep-alive"}
        if token:
            send_headers["Authorization"] = f"Bearer {token}"
        if body is not None:
            raw_body = json.dumps(body).encode()
            send_headers["Content-Type"] = "application/json"
        elif content_type:
            send_headers["Content-Type"] = content_type
        if headers:
            send_headers.update(headers)

        started = time.perf_counter()
        for attempt in (0, 1):
            conn = self._connection()
            try:
                conn.request(method, url, body=raw_body, headers=send_headers)
                raw = conn.getresponse()
                payload = raw.read()
                status = raw.status
                break
            except (http.client.HTTPException, OSError):
                self.close()
                if attempt == 1:
                    raise
        elapsed = time.perf_counter() - started

        parsed = None
        if payload[:1] in (b"{", b"["):
            try:
                parsed = json.loads(payload.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                parsed = None

        return Response(status=status, body=payload, elapsed=elapsed, json=parsed)

    # ------------------------------------------------------------- convenience

    def get(self, path: str, **kw) -> Response:
        return self.request("GET", path, **kw)

    def post(self, path: str, **kw) -> Response:
        return self.request("POST", path, **kw)

    def expect(self, response: Response, context: str = "") -> Any:
        if not response.ok:
            raise HttpError(response, context)
        return response.data()
