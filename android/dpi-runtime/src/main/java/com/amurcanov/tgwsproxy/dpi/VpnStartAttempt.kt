package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

internal data class VpnStartAttempt(val id: String, val deadline: Long) {
    fun canStartTunnel(now: Long, activeId: String?): Boolean = now < deadline && activeId == id

    suspend fun await(
        result: Deferred<DpiEngineResult>,
        now: () -> Long,
        cancelAndConfirm: suspend (String) -> Boolean,
    ): DpiEngineResult {
        val started = withTimeoutOrNull(deadline - now()) { result.await() }
        if (started != null && now() < deadline) return started
        val stopped = cancelAndConfirm(id)
        return DpiEngineResult.Failed(
            if (stopped) "VPN startup timed out; stopped"
            else "VPN startup timed out; stop could not be confirmed"
        )
    }
}
