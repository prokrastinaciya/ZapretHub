package com.amurcanov.tgwsproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private fun engine() = PythonProxyEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun config(port: Int) = ProxyConfiguration("127.0.0.1", port, "", "1234567890abcdef1234567890abcdef", 0, false, "example.com")
    @Test fun nativePageSizeMatchesDeviceMatrix() {
        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        val expected = InstrumentationRegistry.getArguments().getString("expectedPageSize")?.toLong()
        if (expected != null) assertEquals(expected, pageSize)
        else assertTrue(pageSize == 4096L || pageSize == 16384L)
    }
    @Test fun cryptoAndCoreImports() {
        engine()
        val python = Python.getInstance()
        val smoke = JSONObject(python.getModule("android_proxy").callAttr("self_test").toString())
        assertTrue(smoke.getBoolean("ok"))
        assertEquals(BuildConfig.CORE_VERSION, smoke.getString("core_version"))
        assertTrue(smoke.getString("openssl").startsWith("OpenSSL "))
        assertNull(python.getModule("importlib.util").callAttr("find_spec", "cryptography"))
        python.getModule("proxy.h2_transport")
        python.getModule("proxy.fake_tls")
        python.getModule("proxy.raw_websocket")
    }
    @Test fun repeatedStartStopAndPortConflict() {
        val runtime = engine()
        try {
            ServerSocket(0).use { occupied ->
                val failed = runtime.start(config(occupied.localPort))
                assertNull(failed.connection)
                assertNotNull(failed.error)
                runtime.stop()
            }
            repeat(3) {
                val port = ServerSocket(0).use { it.localPort }
                val started = runtime.start(config(port))
                assertEquals(started.error, "running", started.state)
                assertEquals(port, started.connection?.port)
                assertEquals("dd1234567890abcdef1234567890abcdef", started.connection?.secret)
                assertNotNull(runtime.snapshot().connection)
                assertEquals("stopped", runtime.stop().state)
                ServerSocket(port).close()
            }
        } finally { runtime.stop() }
    }
    @Test fun foregroundServicePublishesReadyConfigAndStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val port = ServerSocket(0).use { it.localPort }
        fun awaitCondition(check: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 20_000
            while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("Service state did not settle: starting=${ProxyService.isStarting.value}, running=${ProxyService.isRunning.value}, connection=${ProxyService.activeConnection.value?.port}", check())
        }
        try {
            repeat(2) {
                androidx.core.content.ContextCompat.startForegroundService(context,
                    android.content.Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START)
                        .putExtra(ProxyService.EXTRA_PORT, port)
                        .putExtra(ProxyService.EXTRA_POOL_SIZE, 0)
                        .putExtra(ProxyService.EXTRA_CFPROXY_ENABLED, false)
                        .putExtra(ProxyService.EXTRA_CFPROXY_DOMAIN, "example.com")
                        .putExtra(ProxyService.EXTRA_SECRET_KEY, "1234567890abcdef1234567890abcdef"))
                awaitCondition { ProxyService.isRunning.value }
                assertEquals(port, ProxyService.activeConnection.value?.port)
                assertEquals("dd1234567890abcdef1234567890abcdef", ProxyService.activeConnection.value?.secret)
                context.startService(android.content.Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
                awaitCondition { !ProxyService.isRunning.value && ProxyService.activeConnection.value == null }
                // Restart immediately while asynchronous cleanup may still be
                // in progress: the next request must be retained, not dropped.
            }
        } finally {
            context.startService(android.content.Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

}
