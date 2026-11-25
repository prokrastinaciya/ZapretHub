"""Android lifecycle boundary around the unmodified Flowseal proxy package.

Only this module knows about upstream lifecycle details. The Kotlin boundary is
three JSON functions: start(config_json), stop(), and snapshot(). Network work
runs on a dedicated asyncio loop; no caller ever runs the proxy loop itself.
"""
from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import logging.handlers
import os
import re
import ssl
import threading
import time
from collections import deque

from proxy import __version__ as CORE_VERSION
from proxy import bridge as core_bridge
from proxy import config as core_config
from proxy import raw_websocket
from proxy import tg_ws_proxy as core
from proxy.balancer import balancer
from proxy.stats import stats as core_stats
import route_probe

START_TIMEOUT = 15.0
STOP_TIMEOUT = 15.0
_SECRET_PATTERN = re.compile(r"(?i)(?:dd|ee)?[0-9a-f]{32,}")
_logs = deque(maxlen=100)
# A user's Worker spends that user's quota; the journal and logcat name it by index.
_private_domains = []
LOG_FILE_BYTES = 512 * 1024
LOG_FILE_BACKUPS = 2
_LOG_LEVELS = {"debug": logging.DEBUG, "info": logging.INFO,
               "warn": logging.WARNING, "error": logging.ERROR}


class _TelegramFrontingSocket(ssl.SSLObject):
    def do_handshake(self):
        super().do_handshake()
        # The alternate SNI can return Telegram's apex certificate. Check
        # Telegram's identity in addition to the existing CA-chain validation.
        names = ("telegram.org", "web.telegram.org", "*.web.telegram.org")
        if not any(kind == "DNS" and name.lower() in names
                   for kind, name in self.getpeercert().get("subjectAltName", ())):
            raise ssl.SSLCertVerificationError("Fronted WebSocket certificate does not identify Telegram")


# Only Flowseal's alternate-SNI path uses this context. Ordinary WSS retains
# the TLS library's standard hostname check.
raw_websocket._ssl_ctx_fronting.sslobject_class = _TelegramFrontingSocket


CF_BACKOFF_INITIAL = 30.0
CF_BACKOFF_MAX = 300.0


class _CfRouteBreaker:
    """Skip the shared CF WebSocket route for a while after it fails completely.

    Upstream walks every CF domain with a 10-second timeout per domain for every
    client connection. When the domains are unreachable (blocked or down), each
    connection hangs for minutes before the direct TCP fallback, and Telegram
    never connects. After one full miss the route is skipped with exponential
    backoff; a success or a network change re-enables it at once.
    """

    def __init__(self, original):
        self._original = original
        self.reset()

    def reset(self):
        self.failures = 0
        self.retry_after = 0.0

    async def __call__(self, *args, **kwargs):
        if time.monotonic() < self.retry_after:
            _logger.debug("CF proxy skipped: all domains failed recently")
            return False
        ok = await self._original(*args, **kwargs)
        if ok:
            self.reset()
        elif time.monotonic() >= self.retry_after:
            # Concurrent connections that started before the first miss finish
            # their own walk later; count one outage, not one per connection.
            self.failures += 1
            delay = min(CF_BACKOFF_INITIAL * 2 ** (self.failures - 1), CF_BACKOFF_MAX)
            self.retry_after = time.monotonic() + delay
            _logger.warning("CF proxy: no domain reachable; skipping CF route for %.0fs", delay)
        return ok


_cf_breaker = _CfRouteBreaker(core_bridge._cfproxy_fallback)
# do_fallback resolves this module global on every call.
core_bridge._cfproxy_fallback = _cf_breaker


def _redact(message):
    text = _SECRET_PATTERN.sub("[secret redacted]", str(message))
    for index, domain in enumerate(_private_domains, 1):
        text = re.sub(re.escape(domain), f"worker#{index}", text, flags=re.IGNORECASE)
    return text


class _RedactingFormatter(logging.Formatter):
    def format(self, record):
        return _redact(super().format(record))


class _AndroidLogHandler(logging.Handler):
    """Forward upstream records to the same tag as the Android log viewer."""

    def emit(self, record):
        try:
            text = _redact(self.format(record))
            _logs.append({"level": record.levelname, "message": text})
            try:
                from android.util import Log
            except ImportError:
                return  # Desktop test runner.
            priority = 6 if record.levelno >= 40 else 5 if record.levelno >= 30 else 4
            prefix = "[ERROR] " if priority == 6 else "[WARN] " if priority == 5 else ""
            Log.println(priority, "TgWsProxy", prefix + text)
        except Exception:
            self.handleError(record)


_logger = logging.getLogger("tg-mtproto-proxy")
if not any(isinstance(handler, _AndroidLogHandler) for handler in _logger.handlers):
    _logger.addHandler(_AndroidLogHandler())
_logger.setLevel(logging.INFO)
# Upstream logs contain connect links and raw secrets. Forward only the
# redacted handler, never a root logger installed by the embedding process.
_logger.propagate = False
_file_handler = None


def set_log_file(path):
    """Keep a timestamped journal that outlives the activity and logcat's ring buffer."""
    global _file_handler
    path = str(path)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    handler = logging.handlers.RotatingFileHandler(
        path, maxBytes=LOG_FILE_BYTES, backupCount=LOG_FILE_BACKUPS, encoding="utf-8")
    handler.setFormatter(_RedactingFormatter("%(asctime)s %(levelname)s %(message)s", "%Y-%m-%d %H:%M:%S"))
    previous, _file_handler = _file_handler, handler
    _logger.addHandler(handler)
    if previous is not None:
        _logger.removeHandler(previous)
        previous.close()


def note(level, message):
    """Service events (start, stop, network changes) go into the same journal."""
    _logger.log(_LOG_LEVELS.get(str(level).lower(), logging.INFO), "[service] %s", message)


def _validated_config(value):
    if not isinstance(value, dict):
        raise ValueError("Configuration must be an object")
    host = str(value.get("host", "127.0.0.1")).strip()
    ipaddress.ip_address(host)
    port = value.get("port", 1443)
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError("Port must be an integer from 1 to 65535")
    secret = str(value.get("secret", "")).strip().lower()
    if not re.fullmatch(r"[0-9a-f]{32}", secret):
        raise ValueError("Secret must contain 32 hexadecimal characters")
    pool_size = value.get("pool_size", 4)
    if isinstance(pool_size, bool) or not isinstance(pool_size, int) or not 0 <= pool_size <= 16:
        raise ValueError("Pool size must be an integer from 0 to 16")
    cf_enabled = value.get("cf_enabled", True)
    if not isinstance(cf_enabled, bool):
        raise ValueError("cf_enabled must be a boolean")
    disable_secure = value.get("disable_secure", False)
    if not isinstance(disable_secure, bool):
        raise ValueError("disable_secure must be a boolean")
    dc_value = value.get("dc_ips", "")
    if not isinstance(dc_value, str):
        raise ValueError("dc_ips must be a comma-separated DC:IP string")
    entries = [item.strip() for item in dc_value.replace(";", ",").split(",") if item.strip()]
    redirects = core_config.parse_dc_ip_list(entries)
    domains = _domain_list(value, "cf_domain", "Invalid Cloudflare domain")
    worker_domains = _domain_list(value, "cf_worker_domain", "Invalid Cloudflare Worker domain")
    return {
        "host": host,
        "port": port,
        "secret": secret,
        "full_secret": "dd" + secret,
        "dc_ips": ",".join(entries),
        "dc_redirects": redirects,
        "pool_size": pool_size,
        "cf_enabled": cf_enabled,
        "disable_secure": disable_secure,
        "cf_domain": ",".join(domains),
        # Independent of cf_enabled: upstream tries a user's Worker before
        # the shared CF proxy domains and the direct TCP fallback.
        "cf_worker_domain": ",".join(worker_domains),
    }


def _domain_list(value, key, message):
    raw = value.get(key, "")
    if not isinstance(raw, str):
        raise ValueError(f"{key} must be a string")
    domains = [domain.lower() for domain in core_config.coerce_domain_list(raw)]
    if any(not core_config._is_valid_domain(domain) for domain in domains):
        raise ValueError(message)
    return list(dict.fromkeys(domains))


class _Runtime:
    def __init__(self):
        self._control = threading.RLock()
        self._lock = threading.RLock()
        self._thread = None
        self._loop = None
        self._stop_event = None
        self._stop_requested = threading.Event()
        self._ready = threading.Event()
        self._state = "stopped"
        self._error = None
        self._config = None
        self._stats = {}
        self._refresh_threads = []
        self._diagnostic_task = None

    def _snapshot(self):
        with self._lock:
            config = None
            if self._config is not None:
                config = {key: value for key, value in self._config.items() if key != "dc_redirects"}
            return {
                "state": self._state,
                "running": self._state == "running",
                "error": self._error,
                "core_version": CORE_VERSION,
                "config": config,
                "stats": dict(self._stats),
            }

    def snapshot(self):
        return json.dumps(self._snapshot())

    def _fail(self, exc):
        with self._lock:
            self._error = _redact(exc)
            if self._state != "running":
                self._state = "error"
        _logger.error("Proxy lifecycle: %s", _redact(exc))
        return self.snapshot()

    def start(self, config_json):
        # stop() sets its event before waiting for this control lock, so it can
        # interrupt startup rather than waiting for START_TIMEOUT.
        with self._control:
            try:
                config = _validated_config(json.loads(str(config_json)))
            except (ValueError, TypeError) as exc:
                return self._fail(exc)
            with self._lock:
                if self._thread and self._thread.is_alive():
                    if self._state == "running" and self._config == config:
                        return self.snapshot()
                    return self._fail("Proxy is already active; stop it before changing settings")
                if any(thread.is_alive() for thread in self._refresh_threads):
                    return self._fail("Previous domain refresh is still stopping; retry shortly")
                self._config = config
                self._error = None
                self._stats = {}
                self._state = "starting"
                self._ready.clear()
                self._stop_requested.clear()
                self._thread = threading.Thread(target=self._thread_main, name="FlowsealProxy", daemon=True)
                self._thread.start()
            if not self._ready.wait(START_TIMEOUT):
                self._request_stop()
                self._thread.join(STOP_TIMEOUT)
                return self._fail("Timed out while opening the local proxy listener")
            result = self._snapshot()
            if result["state"] == "error":
                # The worker signals readiness just before exiting on a failed bind.
                # Reap it here so an immediate retry can start a new worker.
                self._thread.join(STOP_TIMEOUT)
            return json.dumps(result)

    def _request_stop(self):
        self._stop_requested.set()
        with self._lock:
            loop, event = self._loop, self._stop_event
        if loop and event and not loop.is_closed():
            try:
                loop.call_soon_threadsafe(event.set)
            except RuntimeError:
                pass  # Loop completed between the checks.

    def stop(self):
        self._request_stop()
        with self._control:
            self._request_stop()  # Also cover a stop racing with start's reset.
            with self._lock:
                thread = self._thread
                if not thread or not thread.is_alive():
                    self._state = "stopped"
                    self._config = None
                    self._error = None
                    return self.snapshot()
                self._state = "stopping"
            thread.join(STOP_TIMEOUT)
            if thread.is_alive():
                return self._fail("Proxy shutdown is still in progress")
            with self._lock:
                self._state = "stopped"
                self._config = None
                self._error = None
            return self.snapshot()

    def _apply_config(self):
        config = self._config
        # Update the existing dataclass, since upstream modules import the
        # singleton by reference. Reset every field to avoid stale options.
        defaults = core_config.ProxyConfig()
        for key, value in vars(defaults).items():
            setattr(core_config.proxy_config, key, value)
        target = core_config.proxy_config
        target.host = config["host"]
        target.port = config["port"]
        target.secret = config["secret"]
        target.dc_redirects = dict(config["dc_redirects"])
        target.pool_size = config["pool_size"]
        target.fallback_cfproxy = config["cf_enabled"]
        target.disable_secure = config["disable_secure"]
        target.cfproxy_user_domains = core_config.coerce_domain_list(config["cf_domain"])
        target.cfproxy_worker_domains = core_config.coerce_domain_list(config["cf_worker_domain"])
        _private_domains[:] = target.cfproxy_worker_domains
        _cf_breaker.reset()
        core_stats.__init__()

    async def _watch_listener(self, task):
        while not task.done():
            server = core._server_instance
            if server is not None and server.sockets and all(sock.fileno() >= 0 for sock in server.sockets):
                with self._lock:
                    if self._state == "starting" and not self._stop_requested.is_set():
                        self._state = "running"
                        self._ready.set()
            with self._lock:
                self._stats = dict(vars(core_stats))
                self._stats["summary"] = core_stats.summary()
            await asyncio.sleep(0.05)

    def network_changed(self):
        """Forget failures from the previous network without dropping live sessions.

        Upstream backs off the direct TCP route and pool refills for up to an hour
        after failures, and only a restart clears that. A phone that lost signal or
        switched networks would otherwise skip working routes long after recovery.
        """
        with self._lock:
            loop = self._loop if self._state == "running" else None
        if loop is None or loop.is_closed():
            return False
        try:
            future = asyncio.run_coroutine_threadsafe(self._reset_routes(), loop)
            future.result(timeout=5)
            return True
        except Exception as exc:
            _logger.warning("Network change handling failed: %r", exc)
            return False

    def _schedule_diagnostics(self, delay=0):
        """One small sample on the current network, independent of live sessions."""
        if self._diagnostic_task and not self._diagnostic_task.done():
            self._diagnostic_task.cancel()

        async def diagnostic_run():
            if delay:
                await asyncio.sleep(delay)  # Let the CF domain refresh finish.
            await route_probe.run_diagnostics(self._config, list(balancer.domains), _logger)

        self._diagnostic_task = asyncio.create_task(diagnostic_run(), name="route-diagnostics")

    async def _reset_routes(self):
        core.reset_tcp_backoff()
        _cf_breaker.reset()
        core.ws_pool.reset()
        core.cf_worker_pool.reset()
        _logger.info("Network changed: route backoff and idle pools reset")
        await core.ws_pool.warmup()
        await core.cf_worker_pool.warmup()
        self._schedule_diagnostics()

    async def _session(self):
        with self._lock:
            self._loop = asyncio.get_running_loop()
            self._stop_event = asyncio.Event()
            if self._stop_requested.is_set():
                self._stop_event.set()
        task = asyncio.create_task(core._run(self._stop_event), name="flowseal-core")
        watcher = asyncio.create_task(self._watch_listener(task), name="flowseal-status")
        self._schedule_diagnostics(delay=2)
        stop_waiter = asyncio.create_task(self._stop_event.wait(), name="flowseal-stop")
        try:
            done, _ = await asyncio.wait((task, stop_waiter), return_when=asyncio.FIRST_COMPLETED)
            if task in done:
                await task  # Propagate bind/import/runtime failures.
                if not self._stop_requested.is_set():
                    raise RuntimeError("Proxy listener terminated unexpectedly")
            else:
                # Upstream waits for stop_event only after pool warmup. Bound
                # that grace period, then cancel; _cleanup also handles the
                # upstream pre-warmup path which has no finally block.
                try:
                    await asyncio.wait_for(asyncio.shield(task), timeout=0.75)
                except asyncio.TimeoutError:
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)
        finally:
            for item in (watcher, stop_waiter, task):
                if not item.done():
                    item.cancel()
            await asyncio.gather(watcher, stop_waiter, task, return_exceptions=True)
            await self._cleanup()

    async def _cleanup(self):
        core_config._refresh_stop.set()
        server = core._server_instance
        if server is not None:
            server.close()
            await server.wait_closed()
        await core.ws_pool.close()
        await core.cf_worker_pool.close()
        if core.cf_h2_pool is not None:
            await core.cf_h2_pool.close()
            core.cf_h2_pool = None
        core._server_instance = None
        core._server_stop_event = None
        pending = [task for task in asyncio.all_tasks() if task is not asyncio.current_task()]
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        core._client_tasks.clear()

    def _thread_main(self):
        try:
            self._apply_config()
            asyncio.run(self._session())
        except Exception as exc:
            self._fail(exc)
        finally:
            core_config._refresh_stop.set()
            # Fetch has an upstream 10-second HTTP timeout. Reap its thread so
            # rapid start/stop never accumulates hourly refresh workers.
            self._refresh_threads = [thread for thread in threading.enumerate()
                                     if thread.name == "cfproxy-domains-refresh"]
            deadline = time.monotonic() + 11.0
            for thread in self._refresh_threads:
                thread.join(max(0, deadline - time.monotonic()))
            with self._lock:
                self._loop = None
                self._stop_event = None
                if self._state != "error":
                    self._state = "stopped"
                self._ready.set()


_runtime = _Runtime()


def start(config_json):
    """Start once; acknowledge only after the local listener is bound."""
    return _runtime.start(config_json)


def stop():
    """Stop and release listener, pools, tasks and domain refresh worker."""
    return _runtime.stop()


def snapshot():
    """Return a JSON snapshot; callers must never log its secret fields."""
    return _runtime.snapshot()


def network_changed():
    """Called by the service when Android's default network changes."""
    return _runtime.network_changed()


def self_test():
    """Offline native-dependency smoke check, also used on Android devices."""
    import httpx
    from proxy._aes import Cipher, algorithms, modes
    from proxy.utils import create_ssl_context

    key = bytes.fromhex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
    iv = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    plain = bytes.fromhex("6bc1bee22e409f96e93d7e117393172a")
    actual = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor().update(plain)
    if actual.hex() != "601ec313775789a5b7a7f504bbf3d228":
        raise RuntimeError("AES-256-CTR native smoke test failed")
    context = create_ssl_context()
    context.set_ecdh_curve("X25519")
    with httpx.Client(http2=True, verify=context):
        pass
    return json.dumps({"ok": True, "core_version": CORE_VERSION,
                       "openssl": ssl.OPENSSL_VERSION, "httpx": httpx.__version__})
