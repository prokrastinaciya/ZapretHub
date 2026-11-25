"""Offline route-choice matrix: mocks do not establish carrier reachability."""
import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "upstream/flowseal"))
from proxy import bridge
from proxy.config import proxy_config
from proxy.pool import ws_pool


class RouteChoiceTests(unittest.TestCase):
    def setUp(self):
        self.old = (proxy_config.fallback_cfproxy, proxy_config.cfproxy_worker_domains,
                    proxy_config.pool_size, dict(proxy_config.dc_redirects))
        proxy_config.fallback_cfproxy = True
        proxy_config.cfproxy_worker_domains = ["private.workers.dev"]
        proxy_config.pool_size = 0
        proxy_config.dc_redirects = {2: "149.154.167.220"}

    def tearDown(self):
        (proxy_config.fallback_cfproxy, proxy_config.cfproxy_worker_domains,
         proxy_config.pool_size, redirects) = self.old
        proxy_config.dc_redirects = redirects

    def run_routes(self, *, worker=False, cf=False, tcp=False):
        calls = []
        def route(name, result):
            async def call(*args, **kwargs):
                calls.append(name)
                return result
            return call
        with (patch.object(bridge, "_cfproxy_worker_fallback", new=route("worker", worker)),
              patch.object(bridge, "_cfproxy_fallback", new=route("cf", cf)),
              patch.object(bridge, "_tcp_fallback", new=route("tcp", tcp))):
            result = asyncio.run(bridge.do_fallback(None, None, b"", "test", 2, False, False, "", None))
        return result, calls

    def test_wifi_routes_available_but_lte_all_routes_fail(self):
        self.assertEqual(self.run_routes(worker=True), (True, ["worker"]))
        self.assertEqual(self.run_routes(), (False, ["worker", "cf", "tcp"]))

    def test_only_user_worker_is_available(self):
        self.assertEqual(self.run_routes(worker=True), (True, ["worker"]))

    def test_all_cf_domains_unavailable_direct_tcp_still_attempted(self):
        self.assertEqual(self.run_routes(tcp=True), (True, ["worker", "cf", "tcp"]))

    def test_all_routes_fail_then_one_recovers_on_next_connection(self):
        self.assertFalse(self.run_routes()[0])
        self.assertEqual(self.run_routes(cf=True), (True, ["worker", "cf"]))

    def test_cf_off_keeps_custom_worker_and_tcp(self):
        proxy_config.fallback_cfproxy = False
        self.assertEqual(self.run_routes(tcp=True), (True, ["worker", "tcp"]))

    def test_direct_ws_pool_remains_configured_with_cf_enabled(self):
        proxy_config.pool_size = 1
        async def exercise():
            with patch.object(ws_pool, "_connect_one", new_callable=AsyncMock) as connect:
                connect.return_value = None
                await ws_pool.warmup()
                await asyncio.sleep(0)
                keys = list(ws_pool._refilling)
                await ws_pool.close()
                return keys
        keys = asyncio.run(exercise())
        self.assertIn((2, False, False), keys)
        self.assertIn((2, True, False), keys)


if __name__ == "__main__":
    unittest.main()
