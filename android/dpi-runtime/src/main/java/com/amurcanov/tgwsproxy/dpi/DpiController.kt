package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State machine over the DPI engine. The UI sends commands and observes [state];
 * the engine itself is the only source of the Running/Failed verdict, so a
 * `Running` state always means a confirmed listener, never a delivered intent.
 *
 * Start and stop are fire-and-forget (safe to call from the UI thread); the
 * blocking engine call runs on [Dispatchers.IO] and is serialized by [mutex] so
 * a stop never races an in-flight engine start. State transitions (including an
 * engine exit observed through [DpiEngine.onExit]) are serialized by a short
 * [stateLock] that is never held across the blocking engine call, so the
 * "publish Running" step and the "record/apply exit" step are mutually atomic —
 * both orders of a death just before or after Ready end in Failed, never a stale
 * Running.
 */
class DpiController internal constructor(
    private val engine: DpiEngine,
    dispatcher: CoroutineDispatcher,
) {
    constructor(engine: DpiEngine) : this(engine, Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val stateLock = Any()
    private val _state = MutableStateFlow<DpiState>(DpiState.Off)

    /** Current controller state; collectors see Off/Starting/Running/Stopping/Failed. */
    val state: StateFlow<DpiState> = _state.asStateFlow()

    /** Set when the engine exits while Starting; guarded by [stateLock]. */
    private var deathDuringStart = false
    // A failed stop leaves ownership unknown until a later stop succeeds.
    private var stopUnconfirmed = false

    init {
        engine.onExit = { onEngineExit() }
    }

    private var startAttempt = 0L

    /** Attaches to a listener already confirmed by the remote service. */
    fun restoreRunning(mode: DpiMode, watchExit: (() -> Unit) -> Boolean) {
        synchronized(stateLock) {
            check(_state.value == DpiState.Off)
            val attempt = ++startAttempt
            val watching = watchExit {
                synchronized(stateLock) {
                    if (startAttempt == attempt && _state.value is DpiState.Running) onEngineExit()
                }
            }
            _state.value = if (watching) DpiState.Running(mode)
            else DpiState.Failed("engine exited during restoration")
        }
    }

    fun start(config: DpiConfig) {
        val attempt = synchronized(stateLock) {
            val current = _state.value
            // Reject while Starting/Running (already started) and while
            // Stopping: a rapid re-start during stop cleanup must not queue a
            // new session that could publish the previous engine's config or
            // resurrect a stopped engine. The UI must wait for Off before a
            // fresh start.
            if (stopUnconfirmed || current is DpiState.Starting || current is DpiState.Running || current is DpiState.Stopping) return
            _state.value = DpiState.Starting
            deathDuringStart = false
            ++startAttempt
        }
        scope.launch {
            mutex.withLock {
                val current = synchronized(stateLock) {
                    _state.value is DpiState.Starting && startAttempt == attempt
                }
                if (!current) return@withLock
                val result = runCatching { engine.start(config) }.getOrElse {
                    DpiEngineResult.Failed(it.message ?: "engine start failed")
                }
                synchronized(stateLock) {
                    // Only publish if this start is still the active one. A stop
                    // (Stopping) or an exit that already failed the state must
                    // win; a death recorded during startup must never yield Running.
                    if (_state.value is DpiState.Starting) {
                        stopUnconfirmed = result is DpiEngineResult.Failed
                        _state.value = when (result) {
                            is DpiEngineResult.Running ->
                                if (deathDuringStart) DpiState.Failed("engine exited during startup")
                                else DpiState.Running(config.mode)
                            is DpiEngineResult.Failed -> DpiState.Failed(result.reason)
                        }
                    }
                }
            }
        }
    }

    /** Idempotent: a second stop while already Off is a no-op and never re-signals the engine. */
    fun stop() {
        synchronized(stateLock) {
            val current = _state.value
            if (current is DpiState.Off || current is DpiState.Stopping) return
            _state.value = DpiState.Stopping
        }
        scope.launch {
            mutex.withLock {
                val result = runCatching { engine.stop() }
                synchronized(stateLock) {
                    // Do not clobber a start that superseded this stop.
                    if (_state.value is DpiState.Stopping) {
                        stopUnconfirmed = result.isFailure
                        _state.value = result.fold(
                            onSuccess = { DpiState.Off },
                            onFailure = { DpiState.Failed(it.message ?: "engine stop failed") },
                        )
                    }
                }
            }
        }
    }

    private fun onEngineExit() {
        synchronized(stateLock) {
            when (val current = _state.value) {
                // Death during Starting: record it; start() reconciles before publishing.
                is DpiState.Starting -> deathDuringStart = true
                // Death after Running: leave Running immediately.
                is DpiState.Running -> _state.value = DpiState.Failed("engine process exited")
                // Off/Stopping/Failed are unrelated to this exit: no-op.
                else -> Unit
            }
        }
    }
}
