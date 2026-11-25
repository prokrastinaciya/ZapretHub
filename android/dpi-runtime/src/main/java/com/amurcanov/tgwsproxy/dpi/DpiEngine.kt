package com.amurcanov.tgwsproxy.dpi

/** Outcome of a single engine command. */
sealed interface DpiEngineResult {
    data object Running : DpiEngineResult
    data class Failed(val reason: String) : DpiEngineResult
}

/**
 * Abstraction over the pinned native ByeByeDPI engine. The controller only
 * depends on this interface, so its state machine is testable with an in-JVM
 * engine while the real [NativeDpiEngine] drives the native service.
 *
 * [start] must not return until the engine has positively confirmed ownership
 * of its listener — a returned [DpiEngineResult.Running] is proof that the
 * engine itself bound the port, never merely that some SOCKS5 endpoint is
 * reachable. [start] blocks, so callers run it on a worker thread (never the
 * main thread, which the acknowledgment callback also uses).
 */
interface DpiEngine {
    fun start(config: DpiConfig): DpiEngineResult

    fun stop()

    /**
     * Invoked when the running engine (its process) exits without an explicit
     * [stop]. The controller uses this to leave [DpiState.Running]. The engine
     * sets this to null once the session is gone; the callback may fire on any
     * thread.
     */
    var onExit: (() -> Unit)?
}
