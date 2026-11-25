package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

internal data class SocksStartAttempt(val id: String, val deadline: Long) {
    fun active(now: Long, activeId: String?): Boolean = id == activeId && now < deadline
    suspend fun await(result: Deferred<DpiEngineResult>, now: () -> Long, cancel: suspend (String) -> Boolean): DpiEngineResult {
        val outcome = withTimeoutOrNull(deadline - now()) { result.await() }
        if (outcome != null && now() < deadline) return outcome
        return DpiEngineResult.Failed(if (cancel(id)) "SOCKS5 startup timed out; stopped" else "SOCKS5 startup timed out; stop could not be confirmed")
    }
}
