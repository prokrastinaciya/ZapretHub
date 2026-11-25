package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.*
import org.junit.Test

class SocksStartupStateTest {
    @Test fun exitBeforeReadyPreventsPublicationForAllExitCodes() {
        for (code in listOf(0, 1)) {
            val state = SocksStartupState()
            state.exited(code)
            assertFalse(state.publishReady { fail("ready after exit") })
        }
    }
    @Test fun readyBeforeExitRequiresExitNotification() {
        val state = SocksStartupState()
        var published = false
        assertTrue(state.publishReady { published = true })
        assertTrue(published)
        assertTrue(state.exited(0))
    }
    @Test fun stopPreventsLateReady() {
        val state = SocksStartupState()
        state.stop()
        assertFalse(state.publishReady { fail("ready after stop") })
    }
}
