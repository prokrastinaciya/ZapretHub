"""One-shot DNS/TCP/TLS/HTTP-Upgrade probe for *the device's current network*.

Run with Python 3 on the affected Android device (e.g. Termux), not a PC on
another network. This probe does not send a Telegram secret or MTProto traffic.
It does not prove Telegram connectivity; a 101 only proves the HTTP status.
"""
import argparse
import base64
import ipaddress
import json
import os
import socket
import ssl
from urllib.parse import urlencode


def probe(host, sni, path="/apiws", *, family=socket.AF_UNSPEC, timeout=8.0,
          resolver=socket.getaddrinfo, socket_factory=socket.socket,
          context_factory=ssl.create_default_context):
    """Probe each resolved address once, reporting the last failed stage."""
    attempts = []
    try:
        addresses = resolver(host, 443, family, socket.SOCK_STREAM)
    except (OSError, TimeoutError) as exc:
        return {"ok": False, "stage": "dns", "error": type(exc).__name__, "attempts": attempts}
    if not addresses:
        return {"ok": False, "stage": "dns", "error": "NoAddresses", "attempts": attempts}
    for af, kind, proto, _, sockaddr in addresses[:8]:
        stage = "tcp"
        raw = None
        encrypted = None
        try:
            raw = socket_factory(af, kind, proto)
            raw.settimeout(timeout)
            raw.connect(sockaddr)
            stage = "tls"
            encrypted = context_factory().wrap_socket(raw, server_hostname=sni)
            stage = "ws"
            # No authorization header, MTProto payload or user secret.
            ws_key = base64.b64encode(os.urandom(16)).decode("ascii")
            request = (f"GET {path} HTTP/1.1\r\nHost: {sni}\r\n"
                       "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {ws_key}\r\n"
                       "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n")
            encrypted.sendall(request.encode("ascii"))
            response = b""
            while b"\r\n" not in response and len(response) < 4096:
                chunk = encrypted.recv(4096 - len(response))
                if not chunk:
                    break
                response += chunk
            first_line = response.split(b"\r\n", 1)[0]
            parts = first_line.split(b" ", 2)
            status = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else 0
            result = {"ok": status == 101, "stage": "ready" if status == 101 else "ws",
                      "http_status": status}
            if result["ok"]:
                return {**result, "attempts": attempts}
            attempts.append(result)
        except (OSError, TimeoutError, ValueError, UnicodeError) as exc:
            attempts.append({"stage": stage, "error": type(exc).__name__})
        finally:
            if encrypted is not None:
                encrypted.close()
            elif raw is not None:
                raw.close()
    return {"ok": False, **attempts[-1], "attempts": attempts}


def main():
    parser = argparse.ArgumentParser(description="Probe a single WebSocket route without secrets")
    parser.add_argument("--host", required=True, help="DNS name or direct DC IP used for TCP")
    parser.add_argument("--sni", required=True, help="TLS SNI and HTTP Host, e.g. web.telegram.org")
    parser.add_argument("--path", default="/apiws", help="HTTP path without query; default /apiws")
    parser.add_argument("--worker-dst", help="Telegram DC IP for a user Worker (no secrets)")
    parser.add_argument("--dc", type=int, help="DC number for a user Worker")
    parser.add_argument("--family", choices=("any", "4", "6"), default="any")
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args()
    if not args.path.startswith("/") or "?" in args.path or any(c in args.path for c in "\r\n"):
        parser.error("path must be an absolute HTTP path without query or newline")
    if (args.worker_dst is None) != (args.dc is None):
        parser.error("--worker-dst and --dc must be specified together")
    path = args.path
    if args.worker_dst is not None:
        try:
            ipaddress.ip_address(args.worker_dst)
        except ValueError:
            parser.error("--worker-dst must be an IP address")
        if args.dc not in (1, 2, 3, 4, 5):
            parser.error("--dc must be 1..5")
        path = "/apiws?" + urlencode({"dst": args.worker_dst, "dc": args.dc})
    if not 0 < args.timeout <= 30:
        parser.error("timeout must be in (0, 30] seconds")
    family = {"any": socket.AF_UNSPEC, "4": socket.AF_INET, "6": socket.AF_INET6}[args.family]
    print(json.dumps(probe(args.host, args.sni, path, family=family,
                           timeout=args.timeout), ensure_ascii=False))


if __name__ == "__main__":
    main()
