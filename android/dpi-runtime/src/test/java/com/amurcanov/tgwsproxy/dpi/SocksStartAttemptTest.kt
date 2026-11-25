package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SocksStartAttemptTest {
    @Test fun deadlineWinsOverLateReadyAndCancellationWaitsForExit() = runBlocking {
        val attempt = SocksStartAttempt("old", 100)
        val result = CompletableDeferred<DpiEngineResult>()
        var cancelled = false
        val outcome = attempt.await(result, { 100 }) { id ->
            assertEquals("old", id)
            result.complete(DpiEngineResult.Running)
            cancelled = true
            true
        }
        assertTrue(cancelled)
        assertEquals(DpiEngineResult.Failed("SOCKS5 startup timed out; stopped"), outcome)
    }
    @Test fun expiredOrDifferentAttemptCannotPublish() {
        val attempt = SocksStartAttempt("old", 100)
        assertTrue(attempt.active(99, "old"))
        assertFalse(attempt.active(100, "old"))
        assertFalse(attempt.active(99, "new"))
    }
}
