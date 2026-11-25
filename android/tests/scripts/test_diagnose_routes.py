"""Stage-isolated field probe; fake sockets never contact an operator."""
import socket
import ssl
import unittest
from unittest.mock import patch

from scripts.diagnose_routes import probe, main


class FakeSocket:
    def __init__(self, *, connect_error=None, tls_error=None, response=b"HTTP/1.1 101 Switching Protocols\r\n\r\n"):
        self.connect_error = connect_error
        self.tls_error = tls_error
        self.response = response
        self.connected = None
        self.sni = None
        self.request = None
        self.closed = False
        self.family = None

    def settimeout(self, timeout):
        self.timeout = timeout

    def connect(self, address):
        self.connected = address
        if self.connect_error:
            raise self.connect_error

    def sendall(self, data):
        self.request = data

    def recv(self, size):
        data, self.response = self.response[:size], self.response[size:]
        return data

    def close(self):
        self.closed = True


class RouteProbeTests(unittest.TestCase):
    def run_probe(self, *, addresses=None, sockets=None, dns_error=None, family=socket.AF_UNSPEC):
        sockets = sockets or [FakeSocket()]
        addresses = addresses if addresses is not None else [
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("203.0.113.2", 443))]
        selected = []
        def resolve(host, port, requested_family, kind):
            self.assertEqual((host, port, kind), ("route.example", 443, socket.SOCK_STREAM))
            self.assertEqual(requested_family, family)
            if dns_error:
                raise dns_error
            return addresses
        def new_socket(af, kind, proto):
            selected.append((af, kind, proto))
            result = sockets[len(selected) - 1]
            result.family = af
            return result
        class Context:
            def wrap_socket(self, sock, server_hostname):
                sock.sni = server_hostname
                if sock.tls_error:
                    raise sock.tls_error
                return sock
        result = probe("route.example", "kws2.example", "/apiws", family=family,
                       resolver=resolve, socket_factory=new_socket, context_factory=Context)
        return result, selected

    def test_dns_error_is_not_reported_as_route_timeout(self):
        result, selected = self.run_probe(dns_error=socket.gaierror("no DNS"))
        self.assertEqual(result["stage"], "dns")
        self.assertFalse(result["ok"])
        self.assertEqual(selected, [])

    def test_tcp_timeout_is_distinct_from_tls_timeout(self):
        sock = FakeSocket(connect_error=TimeoutError("tcp blocked"))
        result, _ = self.run_probe(sockets=[sock])
        self.assertEqual(result["stage"], "tcp")
        self.assertTrue(sock.closed)
        result, _ = self.run_probe(sockets=[FakeSocket(tls_error=TimeoutError("tls blocked"))])
        self.assertEqual(result["stage"], "tls")

    def test_tls_certificate_failure_is_classified_without_disabling_verification(self):
        result, _ = self.run_probe(sockets=[FakeSocket(tls_error=ssl.SSLCertVerificationError("bad cert"))])
        self.assertEqual(result["stage"], "tls")
        self.assertIn("SSLCertVerificationError", result["error"])

    def test_ws_handshake_rejection_is_distinct_from_timeout(self):
        result, _ = self.run_probe(sockets=[FakeSocket(response=b"HTTP/1.1 403 Forbidden\r\n\r\n")])
        self.assertEqual(result["stage"], "ws")
        self.assertEqual(result["http_status"], 403)
        self.assertFalse(result["ok"])

    def test_ws_upgrade_timeout_is_classified_at_ws_stage(self):
        sock = FakeSocket(response=b"")
        result, _ = self.run_probe(sockets=[sock])
        self.assertEqual(result["stage"], "ws")
        self.assertFalse(result["ok"])
        self.assertTrue(sock.closed)

    def test_success_uses_route_host_but_separate_sni_and_host_header(self):
        sock = FakeSocket()
        result, _ = self.run_probe(sockets=[sock])
        self.assertEqual(result["stage"], "ready")
        self.assertTrue(result["ok"])
        self.assertEqual(sock.connected, ("203.0.113.2", 443))
        self.assertEqual(sock.sni, "kws2.example")
        self.assertIn(b"Host: kws2.example\r\n", sock.request)
        self.assertNotIn(b"secret", sock.request.lower())
        self.assertTrue(sock.closed)

    def test_ipv6_only_uses_ipv6_sockaddr_and_falls_through_unreachable_address(self):
        addresses = [(socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2001:db8::1", 443, 0, 0)),
                     (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2001:db8::2", 443, 0, 0))]
        result, selected = self.run_probe(addresses=addresses, family=socket.AF_INET6,
                                          sockets=[FakeSocket(connect_error=OSError("unreachable")), FakeSocket()])
        self.assertTrue(result["ok"])
        self.assertEqual(len(selected), 2)
        self.assertTrue(all(af == socket.AF_INET6 for af, _, _ in selected))
        self.assertEqual(result["attempts"][0]["stage"], "tcp")

    def test_ipv4_only_does_not_require_ipv6(self):
        result, selected = self.run_probe(family=socket.AF_INET)
        self.assertTrue(result["ok"])
        self.assertEqual(selected[0][0], socket.AF_INET)

    def test_worker_probe_builds_safe_dst_and_dc_query(self):
        with (patch("sys.argv", ["diagnose_routes.py", "--host", "worker.example",
                                 "--sni", "worker.example", "--worker-dst", "149.154.167.220", "--dc", "2"]),
              patch("scripts.diagnose_routes.probe", return_value={"ok": True}) as called,
              patch("builtins.print")):
            main()
        self.assertEqual(called.call_args.args[2], "/apiws?dst=149.154.167.220&dc=2")


if __name__ == "__main__":
    unittest.main()
