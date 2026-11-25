package com.amurcanov.tgwsproxy

import com.amurcanov.tgwsproxy.dpi.DpiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class DpiStrategyRestartTest {
    @Test fun restartOutlivesCallerAndRequiresConfirmedOff() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val states = MutableStateFlow<DpiState>(DpiState.Stopping)
        var starts = 0
        launchStrategyRestart(owner, states, { true }) { starts++ }
        states.value = DpiState.Off
        yield()
        assertEquals(1, starts)
        owner.cancel()
    }

    @Test fun failedStopOrNewCommandNeverRestarts() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val states = MutableStateFlow<DpiState>(DpiState.Stopping)
        var starts = 0
        launchStrategyRestart(owner, states, { true }) { starts++ }
        states.value = DpiState.Failed("unconfirmed")
        yield()
        states.value = DpiState.Off
        launchStrategyRestart(owner, states, { false }) { starts++ }
        yield()
        assertEquals(0, starts)
        owner.cancel()
    }
}
