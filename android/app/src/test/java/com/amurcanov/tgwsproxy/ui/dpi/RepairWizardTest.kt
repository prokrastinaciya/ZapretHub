package com.amurcanov.tgwsproxy.ui.dpi

import com.amurcanov.tgwsproxy.ServiceSelection
import com.amurcanov.tgwsproxy.dpi.*
import org.junit.Assert.*
import org.junit.Test

class RepairWizardTest {
    private val candidate = DpiStrategyCandidate("--test", listOf("--test"))

    @Test fun successRequiresAllCurrentTargetsAndDoesNotVerifyTelegram() {
        val youtube = ServiceSelection(telegram = false, youtube = true)
        val targets = dpiTargetsFor(youtube)
        val result = DpiStrategyResult(candidate, targets, targets = targets)
        assertTrue(verifiedDpiResult(youtube, listOf(result)) != null)
        assertNull(verifiedDpiResult(ServiceSelection(telegram = false, youtube = true, discord = true), listOf(result)))
        assertNull(verifiedDpiResult(ServiceSelection(telegram = true, youtube = false), listOf(result)))
    }

    @Test fun telegramFallbackOnlyAppliesToConfiguredTelegram() {
        assertTrue(showTelegramFallback(ServiceSelection(), true, false, false))
        assertFalse(showTelegramFallback(ServiceSelection(telegram = false, youtube = true), true, false, false))
        assertFalse(showTelegramFallback(ServiceSelection(), false, false, false))
        assertFalse(showTelegramFallback(ServiceSelection(), true, true, false))
        assertFalse(showTelegramFallback(ServiceSelection(), true, false, true))
    }
}
