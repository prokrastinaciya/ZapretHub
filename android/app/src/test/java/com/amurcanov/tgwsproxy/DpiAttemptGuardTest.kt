package com.amurcanov.tgwsproxy

import org.junit.Assert.*
import org.junit.Test

class DpiAttemptGuardTest {
    @Test fun staleConsentCannotSurviveCancellationOrNewProcess() {
        val guard = DpiAttemptGuard()
        val attempt = guard.begin()
        assertTrue(guard.isCurrent(attempt))
        guard.invalidate()
        assertFalse(guard.isCurrent(attempt))
        val newer = guard.begin()
        assertFalse(guard.isCurrent(attempt))
        assertTrue(guard.isCurrent(newer))
        assertFalse(DpiAttemptGuard().isCurrent(newer))
    }
}
