#!/usr/bin/env python3
"""Opt-in loopback host for Embabel's experimental CLEF protocol (Apache-2.0).

Explicitly start with required bridge flags (see --help) and optional --port
(default 8765). No daemon, download or model load at import/startup/health.
POST /v1/decisions uses the bridge envelope. POST /v1/systemone provides the
SystemOne 0.2.0 string subset with one joint bridge call and bounded flat
X-Embabel-Clef-Provenance metadata; /v1/models catalogs its experimental model.
GET /health reports readiness without inference. No vendor HTTP, confidence,
billing, error-shape or universal SDK compatibility is claimed.
Request JSON <=16384 bytes, response <=65536, headers <=16384, input deadline
5 seconds by default, native deadline <=45 seconds, 16 accepted connections,
one native checker across runtimes sharing a scratch directory. Losers get
409 busy. Responses close connections; no chunked bodies or pipelining.

HTTP failures are sanitized {ok:false,error:{code,message}}: 400 invalid
request/input_too_long, 409 busy, 503 artifacts/platform/cancelled, 504 timeout,
502 invalid_output/inference_failed/bridge_failure. All errors are terminal
for that request; callers choose fallback. Stop with Ctrl-C or SIGTERM;
shutdown terminates and reaps the active process group. Native/model assets
stay external; fresh source symlinks isolate each compilation cache.
"""
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import signal
import socket
from socketserver import ThreadingMixIn
import threading

from clef_bridge import (BridgeError, ClefRuntime, MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES,
                         config_from_args, configuration_parser, encoded_json, load_json)

from clef_systemone import PROVENANCE_HEADER, compile_systemone, model_catalog, systemone_response

STATUS = {"invalid_request": 400, "input_too_long": 400, "busy": 409,
          "missing_artifact": 503, "unsupported_platform": 503, "cancelled": 503,
          "timeout": 504, "invalid_output": 502, "inference_failed": 502, "bridge_failure": 502}
MAX_HEADER_BYTES = 16_384


class HeaderReader:
    def __init__(self, stream):
        self.stream = stream
        self.remaining = MAX_HEADER_BYTES

    def readline(self, maximum=-1):
        limit = self.remaining + 1
        if maximum >= 0:
            limit = min(limit, maximum)
        line = self.stream.readline(limit)
        self.remaining -= len(line)
        if self.remaining < 0:
            raise http.client.LineTooLong("bounded request headers")
        return line

    def read(self, amount):
        return self.stream.read(amount)

    def close(self):
        self.stream.close()


class DecisionHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def setup(self):
        self.request.settimeout(self.server.socket_timeout)
        super().setup()
        self.rfile = HeaderReader(self.rfile)
        self._input_timer = threading.Timer(self.server.socket_timeout, self._end_input)
        self._input_timer.daemon = True
        self._input_timer.start()

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except http.client.LineTooLong:
            self.request_version = "HTTP/1.0"
            self._reply(400, BridgeError("invalid_request").response())

    def _end_input(self):
        try:
            self.connection.shutdown(socket.SHUT_RD)
        except OSError:
            pass

    def finish(self):
        self._input_timer.cancel()
        super().finish()

    def log_message(self, format, *args):
        pass

    def send_error(self, code, message=None, explain=None):
        self._reply(405 if code == 501 else 400, BridgeError("invalid_request").response())

    def _reply(self, status, value, headers=None):
        self._input_timer.cancel()
        try:
            data = encoded_json(value, MAX_RESPONSE_BYTES)
        except BridgeError:
            status, data = 502, encoded_json(BridgeError("invalid_output").response())
            headers = None
        self.close_connection = True
        try:
            self.send_response_only(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            for name, header in (headers or {}).items():
                self.send_header(name, header)
            self.end_headers()
            self.wfile.write(data)
        except OSError:
            pass

    def do_GET(self):
        if self.path == "/v1/models":
            self._reply(200, model_catalog())
            return
        if self.path != "/health":
            self._reply(404, BridgeError("invalid_request").response())
            return
        self._reply(200, self.server.runtime.health())

    def do_POST(self):
        if self.path not in ("/v1/decisions", "/v1/systemone"):
            self._reply(404, BridgeError("invalid_request").response())
            return
        try:
            lengths = self.headers.get_all("Content-Length", [])
            types = self.headers.get_all("Content-Type", [])
            if self.headers.get_all("Transfer-Encoding") or self.headers.get_all("Expect") or len(lengths) != 1 or len(types) != 1:
                raise BridgeError("invalid_request")
            if types[0].lower().strip() not in ("application/json", "application/json; charset=utf-8"):
                raise BridgeError("invalid_request")
            length = lengths[0]
            if not length.isascii() or not length.isdecimal() or len(length) > 8:
                raise BridgeError("invalid_request")
            amount = int(length)
            if not 1 <= amount <= MAX_REQUEST_BYTES:
                raise BridgeError("invalid_request")
            try:
                data = self.rfile.read(amount)
            except (OSError, TimeoutError):
                raise BridgeError("invalid_request") from None
            if len(data) != amount:
                raise BridgeError("invalid_request")
            self._input_timer.cancel()
            value = load_json(data)
            if self.path == "/v1/systemone":
                owned, bindings = compile_systemone(value)
                result = self.server.runtime.infer(owned)
                converted, header = systemone_response(result, bindings)
                self._reply(200, converted, {PROVENANCE_HEADER: header})
            else:
                result = self.server.runtime.infer(value)
                self._reply(200, result)
        except BridgeError as error:
            self._reply(STATUS[error.code], error.response())
        except Exception:
            self._reply(502, BridgeError("bridge_failure").response())


class ClefHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = False
    block_on_close = True
    allow_reuse_address = True
    request_queue_size = 64

    def __init__(self, address, runtime, socket_timeout=5.0, max_connections=16):
        if address[0] != "127.0.0.1":
            raise ValueError("explicit IPv4 loopback binding required")
        if not 0 < socket_timeout <= 30 or not 1 <= max_connections <= 64:
            raise ValueError("invalid connection bounds")
        self.runtime = runtime
        self.socket_timeout = socket_timeout
        self._connections = threading.BoundedSemaphore(max_connections)
        super().__init__(address, DecisionHandler)

    def process_request(self, request, client_address):
        if not self._connections.acquire(blocking=False):
            data = encoded_json(BridgeError("busy").response())
            try:
                request.settimeout(self.socket_timeout)
                request.sendall(b"HTTP/1.0 409 Conflict\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " + str(len(data)).encode("ascii") + b"\r\n\r\n" + data)
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._connections.release()

    def handle_error(self, request, client_address):
        # Standard server tracebacks contain input/native exceptions and are private.
        pass

    def server_close(self):
        self.runtime.close()
        super().server_close()


def _stop(signum, frame):
    raise KeyboardInterrupt()


def main(argv=None):
    parser = configuration_parser(__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--socket-timeout", type=float, default=5.0)
    parser.add_argument("--max-connections", type=int, default=16)
    args = parser.parse_args(argv)
    runtime = ClefRuntime(config_from_args(args))
    server = ClefHTTPServer(("127.0.0.1", args.port), runtime, args.socket_timeout, args.max_connections)
    previous = signal.signal(signal.SIGTERM, _stop)
    try:
        server.serve_forever(poll_interval=.1)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    main()
