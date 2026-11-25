package com.amurcanov.tgwsproxy.ui

import com.amurcanov.tgwsproxy.ServiceSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePowerPlanTest {
    @Test fun telegramAloneStartsOnlyTelegram() {
        assertEquals(HomePowerPlan(startTelegram = true), homePowerPlan(ServiceSelection(), false, false, false))
    }
    @Test fun selectedVideoStartsDpiOnce() {
        assertEquals(HomePowerPlan(startTelegram = true, startDpi = true), homePowerPlan(ServiceSelection(true, true, true), false, false, false))
    }
    @Test fun stopTouchesOnlyActiveEngines() {
        assertEquals(HomePowerPlan(stopTelegram = true), homePowerPlan(ServiceSelection(), true, false, false))
        assertEquals(HomePowerPlan(stopDpi = true), homePowerPlan(ServiceSelection(), false, false, true))
    }
}
