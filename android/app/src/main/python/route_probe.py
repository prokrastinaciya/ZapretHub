"""Bounded on-device route probe; no MTProto secret or private host enters logs.

This is a diagnostic request, not a replacement for the Flowseal connection.
An HTTP 101 does not prove that MTProto traffic works. Every attempt closes.
"""
import asyncio
import base64
import os
import socket
import threading
import time
from urllib.parse import urlencode

from proxy.utils import DC_DEFAULT_IPS, WS_PATH, ws_domains, create_ssl_context


def probe(host, sni, path=WS_PATH, *, timeout=8.0, secure=True,
          resolver=socket.getaddrinfo, socket_factory=socket.socket,
          context_factory=create_ssl_context):
    """Try at most one IPv4 and one IPv6 address; return stage, never raw errors."""
    attempts = []
    deadline = time.monotonic() + timeout
    def remaining():
        left = deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError("route probe deadline")
        return left
    port = 443 if secure else 80
    try:
        addresses = resolver(host, port, socket.AF_UNSPEC, socket.SOCK_STREAM)
    except (OSError, TimeoutError) as exc:
        return {"ok": False, "stage": "dns", "error": type(exc).__name__, "attempts": attempts}
    selected = []
    seen = set()
    for address in addresses:
        if address[0] in (socket.AF_INET, socket.AF_INET6) and address[0] not in seen:
            selected.append(address)
            seen.add(address[0])
        if len(selected) == 2:
            break
    if not selected:
        return {"ok": False, "stage": "dns", "error": "NoAddresses", "attempts": attempts}
    for family, kind, protocol, _, sockaddr in selected:
        family_name = "ipv6" if family == socket.AF_INET6 else "ipv4"
        stage, raw, encrypted = "tcp", None, None
        try:
            raw = socket_factory(family, kind, protocol)
            raw.settimeout(remaining())
            raw.connect(sockaddr)
            if secure:
                stage = "tls"
                raw.settimeout(remaining())
                encrypted = context_factory().wrap_socket(raw, server_hostname=sni)
            else:
                encrypted = raw
            stage = "ws"
            key = base64.b64encode(os.urandom(16)).decode("ascii")
            request = (f"GET {path} HTTP/1.1\r\nHost: {sni}\r\n"
                       "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                       "Sec-WebSocket-Protocol: binary\r\n\r\n")
            encrypted.settimeout(remaining())
            encrypted.sendall(request.encode("ascii"))
            response = b""
            while b"\r\n" not in response and len(response) < 4096:
                encrypted.settimeout(remaining())
                chunk = encrypted.recv(4096 - len(response))
                if not chunk:
                    break
                response += chunk
            first_line = response.split(b"\r\n", 1)[0]
            parts = first_line.split(b" ", 2)
            status = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else 0
            result = {"ok": status == 101, "stage": "ready" if status == 101 else "ws",
                      "family": family_name, "http_status": status}
            if result["ok"]:
                return {**result, "attempts": attempts}
            attempts.append(result)
        except (OSError, TimeoutError, ValueError, UnicodeError) as exc:
            attempts.append({"stage": stage, "family": family_name,
                             "error": type(exc).__name__})
        finally:
            if encrypted is not None:
                encrypted.close()
            elif raw is not None:
                raw.close()
    return {"ok": False, **attempts[-1], "attempts": attempts}


def diagnostic_routes(config, cf_domains):
    """Probe one actual direct route and one configured CF/Worker route each."""
    routes = []
    dc = 2 if 2 in config["dc_redirects"] else next(iter(config["dc_redirects"]), None)
    if dc is None:
        return routes
    host = config["dc_redirects"][dc]
    routes.append((f"direct-dc{dc}", host, ws_domains(dc, False)[0], WS_PATH, True))
    cf_secure = not config.get("disable_secure", False)
    if config["cf_enabled"] and cf_domains:
        domain = f"kws{dc}.{cf_domains[0]}"
        routes.append((f"cf-dc{dc}", domain, domain, WS_PATH, cf_secure))
    worker = config["cf_worker_domain"].split(",")[0].strip()
    if worker and dc in DC_DEFAULT_IPS:
        path = WS_PATH + "?" + urlencode({"dst": DC_DEFAULT_IPS[dc], "dc": str(dc)})
        routes.append((f"worker#1-dc{dc}", worker, worker, path, cf_secure))
    return routes


# DNS may never return. Daemon workers are independent of asyncio's default
# executor, and the global cap prevents repeated restarts accumulating them.
_probe_slots = threading.BoundedSemaphore(3)


async def _run_probe(host, sni, path, secure):
    if not _probe_slots.acquire(blocking=False):
        return {"ok": False, "stage": "busy", "attempts": []}
    loop = asyncio.get_running_loop()
    future = loop.create_future()

    def complete(result, error):
        if not future.done():
            if error is None:
                future.set_result(result)
            else:
                future.set_exception(error)

    def worker():
        try:
            result, error = probe(host, sni, path, secure=secure), None
        except Exception as exc:
            result, error = None, exc
        finally:
            _probe_slots.release()
        try:
            loop.call_soon_threadsafe(complete, result, error)
        except RuntimeError:
            pass  # The owning listener loop has already closed.

    try:
        threading.Thread(target=worker, name="route-probe", daemon=True).start()
    except BaseException:
        _probe_slots.release()
        raise
    return await asyncio.wait_for(future, timeout=8.0)


async def run_diagnostics(config, cf_domains, logger):
    """Run independent probes concurrently, logging only labels and safe fields."""
    routes = diagnostic_routes(config, cf_domains)
    if config["cf_enabled"] and not cf_domains:
        logger.info("Probe cf: skipped (no domains loaded)")

    async def run_one(label, host, sni, path, secure):
        try:
            result = await _run_probe(host, sni, path, secure)
            attempts = ",".join(f"{a['family']}:{a['stage']}:{a.get('error', a.get('http_status', '-'))}"
                                for a in result.get("attempts", []))
            logger.info("Probe %s transport=%s stage=%s family=%s error=%s http=%s previous=%s",
                        label, "wss:443" if secure else "ws:80", result["stage"], result.get("family", "-"),
                        result.get("error", "-"), result.get("http_status", "-"), attempts or "-")
        except Exception as exc:
            logger.info("Probe %s stage=internal error=%s", label, type(exc).__name__)

    await asyncio.gather(*(run_one(*route) for route in routes))
