package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Contract test for [DpiController]. The cases pin the behaviour the follow-up
 * VPN/UI layers rely on: a failed bind must never look like a running engine,
 * stop must be idempotent, a failing DPI engine must not disturb the Telegram
 * proxy, and a session that dies after Running must leave Running.
 */
class DpiControllerTest {

    /** Test double that performs a real loopback bind to mirror native ownership. */
    private class BindProbeEngine : DpiEngine {
        override var onExit: (() -> Unit)? = null

        override fun start(config: DpiConfig): DpiEngineResult = try {
            ServerSocket().use { it.bind(InetSocketAddress(config.host, config.port)) }
            DpiEngineResult.Running
        } catch (e: java.io.IOException) {
            DpiEngineResult.Failed("bind failed: ${e.message}")
        }

        override fun stop() = Unit
    }

    /** Test double that reports a ready engine and counts stop requests. */
    private class CountingEngine : DpiEngine {
        override var onExit: (() -> Unit)? = null

        @Volatile
        var stopCount = 0
            private set

        override fun start(config: DpiConfig): DpiEngineResult = DpiEngineResult.Running

        override fun stop() {
            stopCount++
        }
    }

    /** Test double that lets the test simulate an engine process dying after Running. */
    private class ExitSignallingEngine : DpiEngine {
        override var onExit: (() -> Unit)? = null

        override fun start(config: DpiConfig): DpiEngineResult = DpiEngineResult.Running

        override fun stop() = Unit

        fun simulateProcessDeath() {
            onExit?.invoke()
        }
    }

    /** Test double whose session dies during start, before Ready is published. */
    private class DeathDuringStartEngine : DpiEngine {
        override var onExit: (() -> Unit)? = null

        override fun start(config: DpiConfig): DpiEngineResult {
            // The engine process dies during startup; a stale Running still comes back.
            onExit?.invoke()
            return DpiEngineResult.Running
        }

        override fun stop() = Unit
    }

    /** Test double that blocks inside start so the test can fire the exit from another thread. */
    private class ThreadedExitEngine : DpiEngine {
        override var onExit: (() -> Unit)? = null
        val startEntered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)

        override fun start(config: DpiConfig): DpiEngineResult {
            startEntered.countDown()
            allowReturn.await(5, TimeUnit.SECONDS)
            return DpiEngineResult.Running
        }

        override fun stop() = Unit
    }

    @Test
    fun startWithOccupiedPortReportsFailedNotRunning() {
        val occupied = ServerSocket(0)
        try {
            val controller = DpiController(BindProbeEngine())
            controller.start(
                DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = occupied.localPort)
            )
            val state = runBlocking {
                controller.state.first { it is DpiState.Failed || it is DpiState.Running }
            }
            assertTrue("expected Failed, got $state", state is DpiState.Failed)
            assertTrue((state as DpiState.Failed).reason.isNotBlank())
        } finally {
            occupied.close()
        }
    }

    @Test
    fun doubleStopEndsOffAndStopsEngineOnce() {
        val engine = CountingEngine()
        val controller = DpiController(engine)
        controller.start(DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = 1080))
        runBlocking { controller.state.first { it is DpiState.Running } }
        assertEquals(DpiState.Running(DpiMode.SOCKS5), controller.state.value)

        controller.stop()
        runBlocking { controller.state.first { it is DpiState.Off } }
        assertEquals(DpiState.Off, controller.state.value)

        controller.stop()
        assertEquals(DpiState.Off, controller.state.value)
        assertEquals("engine must be stopped once for two stop commands", 1, engine.stopCount)
    }

    @Test
    fun siblingServiceStaysReachableWhenDpiFailsToBind() {
        val sibling = ServerSocket(0)
        val occupied = ServerSocket(0)
        try {
            val controller = DpiController(BindProbeEngine())
            controller.start(
                DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = occupied.localPort)
            )
            runBlocking {
                controller.state.first { it is DpiState.Failed || it is DpiState.Running }
            }
            assertTrue(controller.state.value is DpiState.Failed)

            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", sibling.localPort), 1000)
                assertTrue(socket.isConnected)
            }
        } finally {
            sibling.close()
            occupied.close()
        }
    }

    @Test
    fun foreignSocks5ResponderIsNotMistakenForOurEngine() {
        // A foreign SOCKS5 server answers the handshake correctly, so a naive
        // port probe would report success; only real ownership (a successful
        // bind by our engine) may yield Running.
        val responder = Socks5Responder()
        try {
            val controller = DpiController(BindProbeEngine())
            controller.start(
                DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = responder.port)
            )
            val state = runBlocking {
                controller.state.first { it is DpiState.Failed || it is DpiState.Running }
            }
            assertTrue("foreign responder must not yield Running, got $state", state is DpiState.Failed)
        } finally {
            responder.close()
        }
    }

    @Test
    fun engineDeathAfterReadyLeavesRunning() {
        val engine = ExitSignallingEngine()
        val controller = DpiController(engine)
        controller.start(DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = 1080))
        runBlocking { controller.state.first { it is DpiState.Running } }

        engine.simulateProcessDeath()

        val state = runBlocking {
            controller.state.first { it is DpiState.Failed || it is DpiState.Off }
        }
        assertTrue("expected Failed after death, got $state", state is DpiState.Failed)
    }

    @Test
    fun deathDuringStartupReconcilesToFailedNotRunning() {
        val controller = DpiController(DeathDuringStartEngine())
        controller.start(DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = 1080))
        val state = runBlocking {
            controller.state.first { it is DpiState.Failed || it is DpiState.Running }
        }
        assertTrue("death during startup must not yield Running, got $state", state is DpiState.Failed)
    }

    @Test
    fun exitFromAnotherThreadDuringStartupYieldsFailed() {
        val engine = ThreadedExitEngine()
        val controller = DpiController(engine)
        controller.start(DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = 1080))
        assertTrue(engine.startEntered.await(5, TimeUnit.SECONDS))
        // The exit lands on a different thread while the worker is still blocked
        // inside engine.start(); the controller must reconcile it, not publish Running.
        engine.onExit?.invoke()
        engine.allowReturn.countDown()

        val state = runBlocking {
            controller.state.first { it is DpiState.Failed || it is DpiState.Running }
        }
        assertTrue("cross-thread death during startup must not yield Running, got $state", state is DpiState.Failed)
    }

    @Test
    fun configOnlyAcceptsLoopbackAndCoercesWildcardToLoopback() {
        assertTrue(DpiConfig(DpiMode.SOCKS5, host = "127.0.0.1").isLoopback)
        assertTrue(DpiConfig(DpiMode.SOCKS5, host = "::1").isLoopback)
        assertTrue(DpiConfig(DpiMode.SOCKS5, host = "0.0.0.0").isLoopback)
        assertFalse(DpiConfig(DpiMode.SOCKS5, host = "192.168.1.10").isLoopback)

        val argv = DpiConfig(DpiMode.SOCKS5, host = "0.0.0.0", port = 1080).toArgv().toList()
        assertEquals(listOf("ciadpi", "--ip", "127.0.0.1", "--port", "1080"), argv)
    }

    /** Minimal SOCKS5 server that answers the no-auth handshake, to stand in for a foreign responder. */
    private class Socks5Responder {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private val thread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 1000
                    val input = socket.getInputStream()
                    val version = input.read()
                    val nmethods = input.read()
                    if (nmethods > 0) repeat(nmethods) { input.read() }
                    socket.getOutputStream().write(byteArrayOf(5, 0))
                }
            } catch (e: Exception) {
                // responder shutdown
            }
        }.apply { start() }

        fun close() {
            server.close()
        }
    }
}
