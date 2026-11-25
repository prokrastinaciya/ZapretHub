package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class VpnStartupDeathTest {
    @Test fun deathBeforeReadyImmediatelyFailsAndLateReadyCannotResurrect() = runBlocking {
        var exits = 0
        val state = VpnStartupState { exits++ }
        state.onDeath()
        val result = withTimeout(500) { state.acknowledged.await() }
        assertTrue(result is DpiEngineResult.Failed)
        state.onReady()
        assertSame(result, state.acknowledged.await())
        assertEquals(0, exits)
        assertTrue(state.exited.isCompleted)
    }

    @Test fun deathAfterReadySignalsExit() = runBlocking {
        var exits = 0
        val state = VpnStartupState { exits++ }
        state.onReady()
        assertEquals(DpiEngineResult.Running, state.acknowledged.await())
        state.onDeath()
        assertEquals(1, exits)
        assertTrue(state.exited.isCompleted)
    }
}
