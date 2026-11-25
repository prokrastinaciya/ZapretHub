package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnStartAttemptTest {
    @Test
    fun timeoutCancelsOnlyItsAttemptAndWaitsForStopDespiteLateReady() = runBlocking {
        val attempt = VpnStartAttempt("first", deadline = 100)
        val ready = CompletableDeferred<DpiEngineResult>()
        var activeAttempt: String? = "first"
        val result = attempt.await(ready, now = { 100 }) { cancelledId ->
            assertEquals("first", cancelledId)
            ready.complete(DpiEngineResult.Running)
            assertEquals(cancelledId, activeAttempt)
            activeAttempt = null
            true
        }
        assertEquals(null, activeAttempt)
        assertEquals(DpiEngineResult.Failed("VPN startup timed out; stopped"), result)
    }

    @Test
    fun deadlineAndCancellationAreScopedToTheOriginalAttempt() {
        val attempt = VpnStartAttempt("first", deadline = 100)
        assertEquals(true, attempt.canStartTunnel(99, "first"))
        assertEquals(false, attempt.canStartTunnel(100, "first"))
        assertEquals(false, attempt.canStartTunnel(99, null))
        assertEquals(false, attempt.canStartTunnel(99, "second"))
    }
}
