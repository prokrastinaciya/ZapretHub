"""Offline tests for bounded, secret-free diagnostics inside the APK."""
import asyncio
import logging
import socket
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "app/src/main/python"), str(ROOT / "upstream/flowseal"),
                str(ROOT / "tests/scripts")]

from test_diagnose_routes import FakeSocket


class OnDeviceProbeTests(unittest.TestCase):
    def test_stage_and_address_family_are_reported_without_private_addresses(self):
        from route_probe import probe
        addresses = [(socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2001:db8::1", 443, 0, 0)),
                     (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("203.0.113.2", 443))]
        sockets = [FakeSocket(connect_error=TimeoutError("private.example")), FakeSocket()]
        class Context:
            def wrap_socket(self, sock, server_hostname):
                return sock
        result = probe("private.example", "private.example", resolver=lambda *args: addresses,
                       socket_factory=lambda *args: sockets.pop(0), context_factory=Context)
        self.assertEqual(result["stage"], "ready")
        self.assertEqual(result["attempts"], [{"family": "ipv6", "stage": "tcp", "error": "TimeoutError"}])
        self.assertNotIn("private.example", str(result))
        self.assertNotIn("2001:db8", str(result))

    def test_tcp_tls_ws_and_dns_errors_are_separate(self):
        from route_probe import probe
        addr = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("203.0.113.2", 443))]
        class Context:
            def wrap_socket(self, sock, server_hostname):
                if sock.tls_error:
                    raise sock.tls_error
                return sock
        for sock, stage in ((FakeSocket(connect_error=TimeoutError()), "tcp"),
                            (FakeSocket(tls_error=TimeoutError()), "tls"),
                            (FakeSocket(response=b"HTTP/1.1 403 Forbidden\r\n\r\n"), "ws")):
            result = probe("example.test", "example.test", resolver=lambda *args: addr,
                           socket_factory=lambda *args: sock, context_factory=Context)
            self.assertEqual(result["stage"], stage)
            self.assertTrue(sock.closed)
        result = probe("example.test", "example.test", resolver=lambda *args: (_ for _ in ()).throw(socket.gaierror()))
        self.assertEqual(result["stage"], "dns")

    def test_one_address_per_family_and_no_more_than_two_sockets(self):
        from route_probe import probe
        addresses = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (f"203.0.113.{i}", 443)) for i in range(5)]
        opened = []
        def make_socket(*args):
            opened.append(1)
            return FakeSocket(connect_error=TimeoutError())
        result = probe("example.test", "example.test", resolver=lambda *args: addresses,
                       socket_factory=make_socket)
        self.assertLessEqual(len(opened), 2)
        self.assertEqual(result["stage"], "tcp")

    def test_routes_use_actual_dc_and_user_worker_without_logging_host(self):
        from route_probe import diagnostic_routes
        config = {"dc_redirects": {2: "149.154.167.220"}, "cf_enabled": True,
                  "cf_worker_domain": "private.worker.example"}
        routes = diagnostic_routes(config, ["cf.example"])
        self.assertEqual([route[0] for route in routes], ["direct-dc2", "cf-dc2", "worker#1-dc2"])
        self.assertEqual(routes[0][1:3], ("149.154.167.220", "kws2.web.telegram.org"))
        self.assertEqual(routes[1][1:3], ("kws2.cf.example", "kws2.cf.example"))
        self.assertEqual(routes[2][3], "/apiws?dst=149.154.167.51&dc=2")

    def test_cleartext_ws_uses_port_80_without_tls(self):
        from route_probe import probe, diagnostic_routes
        sock = FakeSocket()
        class Context:
            def wrap_socket(self, *_args, **_kwargs):
                self.fail("TLS must not run")
        def resolver(host, port, *_):
            self.assertEqual(port, 80)
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("203.0.113.2", port))]
        result = probe("cf.example", "cf.example", secure=False, resolver=resolver,
                       socket_factory=lambda *args: sock, context_factory=Context)
        self.assertEqual(result["stage"], "ready")
        self.assertEqual(sock.connected, ("203.0.113.2", 80))
        self.assertIsNone(sock.sni)
        routes = diagnostic_routes({"dc_redirects": {2: "149.154.167.220"},
                                    "cf_enabled": True, "cf_worker_domain": "",
                                    "disable_secure": True}, ["cf.example"])
        self.assertTrue(routes[0][-1])
        self.assertFalse(routes[1][-1])

    def test_cancelled_hung_probes_remain_bounded_across_restarts(self):
        from route_probe import _run_probe
        release = threading.Event()
        lock = threading.Lock()
        started = []
        exited = []

        def stuck(*_args, **_kwargs):
            with lock:
                started.append(1)
            try:
                release.wait(5)  # Test workers always terminate, even on assertion failure.
                return {"ok": True}
            finally:
                with lock:
                    exited.append(1)

        async def exercise():
            for expected in range(1, 4):
                task = asyncio.create_task(_run_probe("host", "sni", "/", True))
                deadline = time.monotonic() + 2
                while len(started) < expected and time.monotonic() < deadline:
                    await asyncio.sleep(.01)
                self.assertEqual(len(started), expected)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
            for _ in range(30):
                result = await _run_probe("host", "sni", "/", True)
                self.assertEqual(result["stage"], "busy")
            self.assertEqual(len(started), 3)

        try:
            with patch("route_probe.probe", side_effect=stuck):
                asyncio.run(exercise())
        finally:
            release.set()
            deadline = time.monotonic() + 2
            while len(exited) < len(started) and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertEqual(len(exited), len(started))

    def test_run_logs_only_safe_result_and_does_not_raise_on_probe_error(self):
        from route_probe import run_diagnostics
        config = {"dc_redirects": {2: "149.154.167.220"}, "cf_enabled": False,
                  "cf_worker_domain": ""}
        fake_logger = logging.getLogger("probe-test")
        with (patch("route_probe.probe", side_effect=RuntimeError("private.worker.example")),
              patch.object(fake_logger, "info") as info):
            asyncio.run(run_diagnostics(config, [], fake_logger))
        text = " ".join(str(call) for call in info.call_args_list)
        self.assertIn("Probe", text)
        self.assertIn("RuntimeError", text)
        self.assertNotIn("private.worker.example", text)


if __name__ == "__main__":
    unittest.main()
