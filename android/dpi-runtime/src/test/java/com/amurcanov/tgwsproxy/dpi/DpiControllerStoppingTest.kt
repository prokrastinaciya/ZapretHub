package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression for the deferred task-2 rapid-restart race: a start issued while
 * the controller is already Stopping must be rejected at the controller
 * boundary (not deferred to the UI), so a queued start can never publish the
 * previous engine's config or resurrect a stopped engine.
 */
class DpiControllerStoppingTest {

    @Test
    fun failedStartRequiresConfirmedStopBeforeRetry() {
        val tasks = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        var starts = 0
        val engine = object : DpiEngine {
            override var onExit: (() -> Unit)? = null
            override fun start(config: DpiConfig): DpiEngineResult {
                starts++
                return DpiEngineResult.Failed("stop could not be confirmed")
            }
            override fun stop() = Unit
        }
        val controller = DpiController(engine, dispatcher)
        val config = DpiConfig(DpiMode.SOCKS5)
        controller.start(config); tasks.removeAt(0).run()
        val failed = controller.state.value
        controller.start(config)
        assertEquals(failed, controller.state.value)
        assertEquals(1, starts)
        controller.stop(); tasks.removeAt(0).run()
        assertEquals(DpiState.Off, controller.state.value)
        controller.start(config); tasks.removeAt(0).run()
        assertEquals(2, starts)
    }

    @Test
    fun failedStopBlocksRestartUntilStopIsConfirmed() {
        val tasks = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        var starts = 0
        var stops = 0
        val engine = object : DpiEngine {
            override var onExit: (() -> Unit)? = null
            override fun start(config: DpiConfig): DpiEngineResult { starts++; return DpiEngineResult.Running }
            override fun stop() { if (++stops == 1) error("unconfirmed") }
        }
        val controller = DpiController(engine, dispatcher)
        val config = DpiConfig(DpiMode.SOCKS5)
        controller.start(config); tasks.removeAt(0).run()
        controller.stop(); tasks.removeAt(0).run()
        val failed = controller.state.value
        controller.start(config)
        assertEquals(failed, controller.state.value)
        assertEquals(1, starts)
        controller.stop(); tasks.removeAt(0).run()
        assertEquals(DpiState.Off, controller.state.value)
        controller.start(config); tasks.removeAt(0).run()
        assertEquals(2, starts)
    }

    @Test
    fun stopOvertakesQueuedStartEvenWhenAnotherStartFollows() {
        val tasks = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        val ports = mutableListOf<Int>()
        val engine = object : DpiEngine {
            override var onExit: (() -> Unit)? = null
            override fun start(config: DpiConfig): DpiEngineResult {
                ports.add(config.port)
                return DpiEngineResult.Running
            }
            override fun stop() = Unit
        }
        val controller = DpiController(engine, dispatcher)
        controller.start(DpiConfig(DpiMode.SOCKS5, "127.0.0.1", 1080))
        controller.stop()
        tasks.removeAt(1).run()
        assertEquals(DpiState.Off, controller.state.value)
        controller.start(DpiConfig(DpiMode.SOCKS5, "127.0.0.1", 1081))
        tasks.removeAt(0).run()
        tasks.removeAt(0).run()
        assertEquals(listOf(1081), ports)
    }

    /** Engine that blocks inside stop() so the test can hold the Stopping window open. */
    private class BlockingStopEngine : DpiEngine {
        override var onExit: (() -> Unit)? = null

        @Volatile
        var startCount = 0
            private set

        private val stopEntered = CountDownLatch(1)
        private val allowStopReturn = CountDownLatch(1)

        override fun start(config: DpiConfig): DpiEngineResult {
            startCount++
            return DpiEngineResult.Running
        }

        override fun stop() {
            stopEntered.countDown()
            allowStopReturn.await(5, TimeUnit.SECONDS)
        }

        fun awaitStopEntered() {
            check(stopEntered.await(5, TimeUnit.SECONDS)) { "stop() never entered" }
        }

        fun releaseStop() {
            allowStopReturn.countDown()
        }
    }

    @Test
    fun startDuringStoppingIsRejectedAtControllerBoundary() {
        val engine = BlockingStopEngine()
        val controller = DpiController(engine)

        controller.start(DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = 1080))
        runBlocking { controller.state.first { it is DpiState.Running } }
        assertEquals(1, engine.startCount)

        controller.stop()
        engine.awaitStopEntered()
        assertEquals(DpiState.Stopping, controller.state.value)

        // A rapid re-start lands while stop is still cleaning up. It must be
        // rejected: the state must stay Stopping and the engine must not be
        // started a second time.
        controller.start(DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = 1081))
        assertEquals(DpiState.Stopping, controller.state.value)
        assertEquals(1, engine.startCount)

        engine.releaseStop()
        runBlocking { controller.state.first { it is DpiState.Off } }
        assertEquals(DpiState.Off, controller.state.value)
        assertEquals("a rejected start must never reach the engine", 1, engine.startCount)
    }
}
