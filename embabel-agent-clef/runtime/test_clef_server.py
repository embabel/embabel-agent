"""Real loopback HTTP/process probes; no CLEF model is loaded."""
import concurrent.futures
from dataclasses import replace
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import socket
import threading
import time
import unittest
from unittest import mock

import clef_bridge as bridge
from clef_server import ClefHTTPServer
from test_clef_bridge import FakeArtifacts, request


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.fixture = FakeArtifacts()
        self.server = ClefHTTPServer(("127.0.0.1", 0), self.fixture.runtime, socket_timeout=.3, max_connections=16)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .02})
        self.thread.start()
        self.addCleanup(self.finish)

    def finish(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(3)
        self.fixture.close()

    def http(self, path="/v1/decisions", body=None, method="POST", headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=4)
        try:
            connection.request(method, path, body=json.dumps(request()).encode() if body is None and method=="POST" else body,
                               headers={"Content-Type":"application/json", **(headers or {})})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_owned_protocol_health_without_load_and_joint_success(self):
        with mock.patch.object(self.fixture.runtime, "infer", side_effect=AssertionError("health must not infer")):
            status, health = self.http("/health", method="GET")
        self.assertEqual(status, 200)
        self.assertEqual(health["protocol"], "embabel-clef-decisions-v1")
        self.assertTrue(health["experimental"])
        self.assertFalse((self.fixture.root / "started").exists())
        status, response = self.http()
        self.assertEqual(status, 200)
        self.assertEqual([a["id"] for a in response["answers"]], ["usable","next","effort","setting"])
        self.assertEqual(len((self.fixture.root / "calls").read_text().splitlines()), 1)

    def test_exact_routes_content_type_unknown_fields_and_body_bounds(self):
        for path, method in [("/v1/systemone", "POST"), ("/v1/decisions?x=1", "POST"), ("/health", "POST"), ("/v1/decisions", "GET")]:
            status, _ = self.http(path, method=method)
            self.assertIn(status, [404,405])
        for body in [b'{"state":"SECRET_INPUT","questions":[],"unknown":1}', b"x" * (bridge.MAX_REQUEST_BYTES + 1), b'{"state":NaN}']:
            status, response = self.http(body=body)
            self.assertEqual(status, 400)
            self.assertNotIn("SECRET_INPUT", json.dumps(response))
        status, _ = self.http(headers={"Content-Type": "text/plain"})
        self.assertEqual(status, 400)
        self.assertFalse((self.fixture.root / "started").exists())

    def test_slow_incomplete_body_and_ambiguous_lengths_are_bounded(self):
        for tail in [b'Content-Length: 100\r\n\r\n{', b'Content-Length: 2\r\nContent-Length: 3\r\n\r\n{}', b'Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n', b'Content-Length: -1\r\n\r\n', b'Content-Length: 0\r\n\r\n']:
            client = socket.create_connection(self.server.server_address, timeout=3)
            try:
                started = time.monotonic()
                client.sendall(b'POST /v1/decisions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n' + tail)
                response = client.recv(8192)
                self.assertIn(b' 400 ', response)
                self.assertLess(time.monotonic()-started, 1.5)
            finally:
                client.close()
        self.assertFalse((self.fixture.root / "started").exists())

    def test_total_header_deadline_and_header_budget(self):
        client = socket.create_connection(self.server.server_address, timeout=2)
        started = time.monotonic()
        try:
            client.sendall(b'POST /v1/decisions HTTP/1.1\r\nHost: localhost\r\n')
            for _ in range(6):
                try:
                    client.sendall(b'x')
                except OSError:
                    break
                time.sleep(.08)
            client.recv(8192)
            self.assertLess(time.monotonic()-started, 1)
        finally:
            client.close()
        client = socket.create_connection(self.server.server_address, timeout=2)
        try:
            client.sendall(b'POST /' + b'x' * 18000 + b' HTTP/1.1\r\n\r\n')
            self.assertIn(b' 400 ', client.recv(8192))
        finally:
            client.close()
        self.assertFalse((self.fixture.root / "started").exists())

    def test_n_and_ten_n_callers_prove_one_native_in_flight_and_busy_losers(self):
        for count in [4, 40]:
            with self.subTest(count=count):
                self.fixture.mode("gate")
                started_path = self.fixture.root / "started"
                if started_path.exists(): started_path.unlink()
                release = self.fixture.root / "release"
                if release.exists(): release.unlink()
                with concurrent.futures.ThreadPoolExecutor(max_workers=count+1) as executor:
                    first = executor.submit(self.http)
                    self.fixture.wait_started()
                    losers = list(executor.map(lambda _: self.http(), range(count-1)))
                    self.assertTrue(all(status==409 and result["error"]["code"]=="busy" for status,result in losers), losers)
                    release.write_text("release")
                    self.assertEqual(first.result()[0], 200)
                self.assertEqual(len((self.fixture.root / "calls").read_text().splitlines()), 1 if count==4 else 2)

    def test_native_failure_status_privacy_response_limit_and_recovery(self):
        for mode in ["bad", "huge", "stderr", "exit"]:
            with self.subTest(mode=mode):
                self.fixture.mode(mode)
                status, response = self.http()
                self.assertEqual(status, 502)
                self.assertNotIn("SECRET_INPUT", json.dumps(response))
        self.fixture.mode("normal")
        self.assertEqual(self.http()[0], 200)
        with mock.patch.object(self.fixture.runtime, "infer", return_value={"ok": True, "oversize": "x" * 70000}):
            status, response = self.http()
        self.assertEqual(status, 502)
        self.assertEqual(response["error"]["code"], "invalid_output")
        self.fixture.checker.unlink()
        self.assertEqual(self.http()[0], 503)

    def test_special_and_symlink_lock_fail_within_deadline_and_shutdown_promptly(self):
        # Each probe lives in a subprocess with a hard timeout; the red FIFO case
        # cannot leave an uncontrolled HTTP worker or require a FIFO unblock.
        program = r'''
import http.client, json, os, pathlib, socket, sys, threading, time
from dataclasses import replace
sys.path.insert(0, sys.argv[1])
import clef_bridge as bridge
from clef_server import ClefHTTPServer
from test_clef_bridge import FakeArtifacts, request
fixture = FakeArtifacts()
fixture.runtime.close()
fixture.runtime = bridge.ClefRuntime(replace(fixture.config, timeout_seconds=.25))
lock = fixture.scratch / '.clef-native.lock'
mode = sys.argv[2]
if mode == 'fifo':
    os.mkfifo(lock)
elif mode == 'symlink':
    target = fixture.root / 'external-lock'
    target.write_text('private sentinel')
    lock.symlink_to(target)
else:
    special = socket.socket(socket.AF_UNIX)
    special.bind(str(lock))
server = ClefHTTPServer(('127.0.0.1', 0), fixture.runtime, socket_timeout=.25)
thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval':.01})
thread.start()
client = http.client.HTTPConnection(*server.server_address, timeout=.5)
try:
    started = time.monotonic()
    client.request('POST', '/v1/decisions', body=json.dumps(request()), headers={'Content-Type':'application/json'})
    response = client.getresponse()
    payload = json.loads(response.read())
    elapsed = time.monotonic() - started
    assert response.status == 503, response.status
    assert payload['error']['code'] == 'missing_artifact', payload
    assert elapsed < fixture.config.timeout_seconds, elapsed
    assert not (fixture.root / 'started').exists(), 'checker must not start'
    if mode == 'symlink':
        assert target.read_text() == 'private sentinel'
    started = time.monotonic()
    server.shutdown(); server.server_close(); thread.join(.5)
    shutdown = time.monotonic() - started
    assert not thread.is_alive() and shutdown < .5, shutdown
    print(json.dumps({'mode':mode,'request_ms':elapsed*1000,'shutdown_ms':shutdown*1000,'checker_started':False}))
finally:
    client.close()
    server.shutdown(); server.server_close(); thread.join(.5)
    fixture.close()
'''
        for mode in ["fifo", "symlink", "socket"]:
            with self.subTest(mode=mode):
                with tempfile.TemporaryDirectory() as probe_directory:
                    completed = subprocess.run([sys.executable, "-c", program, str(Path(__file__).resolve().parent), mode],
                                               capture_output=True, text=True, timeout=2,
                                               env={**os.environ, "TMPDIR": probe_directory})
                self.assertEqual(completed.returncode, 0, completed.stderr)
                proof = json.loads(completed.stdout)
                self.assertFalse(proof["checker_started"])
                print("R1 real HTTP lock probe: " + json.dumps(proof, sort_keys=True))

    def test_shutdown_cancels_active_checker_and_releases_scratch(self):
        self.fixture.mode("slow")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(self.http)
            self.fixture.wait_started("descendant")
            self.server.shutdown()
            self.server.server_close()
            status, response = future.result()
        self.assertEqual(status, 503)
        self.assertEqual(response["error"]["code"], "cancelled")
        self.assertEqual(list(self.fixture.scratch.glob("clef-request-*")), [])

    def test_timeout_status_and_nonloopback_refused(self):
        self.fixture.runtime.close()
        runtime = bridge.ClefRuntime(replace(self.fixture.config, timeout_seconds=.3))
        self.server.runtime = runtime; self.fixture.runtime = runtime
        self.fixture.mode("slow")
        status, response = self.http()
        self.assertEqual(status, 504)
        self.assertEqual(response["error"]["code"], "timeout")
        with self.assertRaises(ValueError):
            ClefHTTPServer(("0.0.0.0", 0), runtime)


if __name__ == "__main__":
    unittest.main()
