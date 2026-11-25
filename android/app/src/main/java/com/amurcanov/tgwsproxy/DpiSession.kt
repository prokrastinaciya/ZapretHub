package com.amurcanov.tgwsproxy

import android.content.Context
import com.amurcanov.tgwsproxy.dpi.AndroidVpnSlotSampler
import com.amurcanov.tgwsproxy.dpi.DpiConfig
import com.amurcanov.tgwsproxy.dpi.DpiController
import com.amurcanov.tgwsproxy.dpi.DpiEngine
import com.amurcanov.tgwsproxy.dpi.DpiRemoteSession
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.dpi.DpiVpnEngine
import com.amurcanov.tgwsproxy.dpi.NativeDpiEngine
import com.amurcanov.tgwsproxy.dpi.VpnAuthorizer
import com.amurcanov.tgwsproxy.dpi.VpnReplacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal val DpiSession = DpiSessionHolder()

/** A consent belongs to one user command, never to a later command or process. */
internal class DpiAttemptGuard {
    private var current: String? = null
    fun begin(): String = java.util.UUID.randomUUID().toString().also { current = it }
    fun invalidate() { current = null }
    fun isCurrent(token: String?): Boolean = token != null && token == current
}

internal fun launchStrategyRestart(
    scope: CoroutineScope,
    states: StateFlow<DpiState>,
    stillCurrent: () -> Boolean,
    restart: () -> Unit,
): Job = scope.launch {
    val terminal = states.first { it is DpiState.Off || it is DpiState.Failed }
    if (terminal is DpiState.Off && stillCurrent()) restart()
}

/**
 * Process-wide holder for the ByeByeDPI module: one [DpiController] for the
 * currently selected [DpiMode], the controller being the only source of the
 * Running/Failed verdict. The UI sends commands and observes [state]; it never
 * infers "running" from a delivered command.
 *
 * VPN is the default. A mode switch is only accepted while [DpiState.Off], so a
 * Running/Starting/Stopping session is never torn down by a UI navigation
 * change, and a start command is never duplicated during a transition (the
 * controller itself rejects a second start while Starting/Running/Stopping).
 *
 * The VPN consent/replacement flow is deliberately owned by the UI: this holder
 * only exposes [vpnAuthorizer] (the same fail-closed sampler seam the
 * [DpiVpnEngine] uses) and forwards a captured [VpnReplacement] proof to the
 * engine for the single start attempt. A proof is minted by the UI only after a
 * visible offer is decided, and reused only if the fresh re-check still matches.
 */
internal class DpiSessionHolder(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val inspect: suspend (Context, DpiMode) -> DpiRemoteSession? = DpiRemoteSession::inspect,
    private val stopRemote: suspend (Context, DpiMode) -> Boolean = DpiRemoteSession::stopExisting,
    private val restorationTimeoutMs: Long = 8_000,
) {

    private val attempts = DpiAttemptGuard()
    fun beginInteractiveStart(): String = attempts.begin()
    fun isInteractiveStartCurrent(token: String?): Boolean =
        attempts.isCurrent(token) && _mode.value == DpiMode.VPN
    fun cancelInteractiveStart() = attempts.invalidate()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _mode = MutableStateFlow(DpiMode.VPN)
    val mode: StateFlow<DpiMode> = _mode.asStateFlow()

    private val _state = MutableStateFlow<DpiState>(DpiState.Off)
    val state: StateFlow<DpiState> = _state.asStateFlow()

    private var controller: DpiController? = null

    private var engine: DpiEngine? = null

    private var stateJob: Job? = null
    private var restoration: Job? = null
    private var stopBeforeRestoration: Job? = null
    private var commandVersion = 0L
    private var strategyTest: Job? = null
    private val journal = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = journal.asStateFlow()
    private var nextLogId = 0L

    fun clearLogs() { journal.value = emptyList() }

    fun applyStrategy(context: Context, command: String) {
        val appContext = context.applicationContext
        DpiSettingsStore(appContext).applyStrategy(command)
        val wasRunning = _state.value is DpiState.Running || _state.value is DpiState.Starting
        if (!wasRunning) return
        val oldMode = _mode.value
        stop(appContext)
        val version = commandVersion
        launchStrategyRestart(scope, state, { commandVersion == version && _mode.value == oldMode }) {
            start(appContext)
        }
    }

    private fun journalState(context: Context, state: DpiState) {
        val label = when (state) {
            DpiState.Off -> R.string.status_disconnected
            DpiState.Starting -> R.string.status_connecting
            DpiState.Stopping -> R.string.dpi_status_stopping
            is DpiState.Running -> if (state.mode == DpiMode.VPN) R.string.dpi_status_vpn_running else R.string.dpi_status_socks5_running
            is DpiState.Failed -> R.string.dpi_log_failed
        }
        val mode = context.getString(if (_mode.value == DpiMode.VPN) R.string.dpi_mode_vpn else R.string.dpi_mode_socks5)
        val error = state is DpiState.Failed
        val entry = LogEntry("dpi-${nextLogId++}", "ByeByeDPI $mode: ${context.getString(label)}", 1, error, if (error) 6 else 4)
        journal.update { (it + entry).takeLast(50) }
    }

    /** Endpoint for the current attempt, not the next edited/imported document. */
    private val _endpoint = MutableStateFlow<String?>(null)
    val endpoint = _endpoint.asStateFlow()

    /**
     * Fail-closed VPN authorization seam for the UI. It re-samples the slot on
     * every call, so a consent/replacement decision is never a cached grant.
     */
    fun vpnAuthorizer(context: Context): VpnAuthorizer =
        VpnAuthorizer(AndroidVpnSlotSampler(context.applicationContext))

    /** Switches mode only while Off; rejects (and reports) otherwise. */
    fun switchMode(context: Context, newMode: DpiMode): Boolean {
        if (strategyTest != null || stopBeforeRestoration?.isActive == true) return false
        initialize(context)
        val current = controller?.state?.value ?: return false
        if (current !is DpiState.Off || _state.value !is DpiState.Off) return false
        if (newMode == _mode.value) return true
        attempts.invalidate()
        commandVersion++
        _mode.value = newMode
        rebuild(context.applicationContext)
        return true
    }

    /**
     * Starts the current controller. For VPN the [replacement] proof (if any)
     * is attached to the engine for this single attempt and consumed by the
     * engine's own authorize-then-establish re-check.
     */
    fun start(context: Context, replacement: VpnReplacement? = null) {
        if (strategyTest != null || stopBeforeRestoration?.isActive == true) return
        initialize(context)
        if (controller == null) {
            val pending = restoration ?: return
            val version = commandVersion
            scope.launch {
                pending.join()
                if (commandVersion == version && controller != null) start(context, replacement)
            }
            return
        }
        val activeController = checkNotNull(controller)
        val current = activeController.state.value
        if (current !is DpiState.Off || _state.value is DpiState.Failed) return
        val options = try {
            DpiSettingsStore(context).connectionOptions()
        } catch (_: Exception) {
            journal.update {
                (it + LogEntry(
                    "dpi-${nextLogId++}", context.getString(R.string.dpi_backup_error), 1,
                    isError = true, priority = 6,
                )).takeLast(50)
            }
            return
        }
        val hostLabel = if (':' in options.host) "[${options.host}]" else options.host
        _endpoint.value = "$hostLabel:${options.port}"
        (engine as? DpiVpnEngine)?.replacement = replacement
        activeController.start(DpiConfig(_mode.value, options.host, options.port, args = options.args, vpnRouting = options.routing))
    }

    fun stop(context: Context) {
        attempts.invalidate()
        commandVersion++
        strategyTest?.let {
            it.cancel()
            return
        }
        val local = controller
        val current = local?.state?.value
        if (local != null && current != DpiState.Off && current !is DpiState.Failed) {
            local.stop()
            return
        }
        if (stopBeforeRestoration?.isActive == true) return
        val appContext = context.applicationContext
        val pending = restoration
        stateJob?.cancel()
        _state.value = DpiState.Stopping
        stopBeforeRestoration = scope.launch {
            pending?.cancelAndJoin()
            restoration = null
            val stopped = withTimeoutOrNull(10_000) {
                coroutineScope {
                    DpiMode.entries.map { mode ->
                        async {
                            try { stopRemote(appContext, mode) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { false }
                        }
                    }.map { it.await() }.all { it }
                }
            } ?: false
            if (stopped) rebuild(appContext)
            else _state.value = DpiState.Failed("DPI stop could not be confirmed")
        }
    }

    suspend fun runStrategyTest(context: Context, test: suspend () -> Unit): Boolean {
        awaitRestoration(context)
        if (strategyTest != null || state.value != DpiState.Off || controller?.state?.value != DpiState.Off) {
            return false
        }
        strategyTest = coroutineContext.job
        return try {
            test()
            true
        } finally {
            strategyTest = null
        }
    }

    fun initialize(context: Context) {
        if (controller != null || restoration != null || stopBeforeRestoration?.isActive == true) return
        val appContext = context.applicationContext
        _state.value = DpiState.Starting
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val active = withTimeout(restorationTimeoutMs) {
                    val remote = coroutineScope {
                        val vpn = async { inspect(appContext, DpiMode.VPN) }
                        val socks5 = async { inspect(appContext, DpiMode.SOCKS5) }
                        val vpnSession = vpn.await()
                        val socksSession = socks5.await()
                        if (vpnSession != null && socksSession != null) {
                            throw IllegalStateException("Multiple DPI sessions found")
                        }
                        vpnSession ?: socksSession
                    }
                    var found = remote
                    if (remote != null) {
                        _mode.value = remote.mode
                        _endpoint.value = remote.endpoint
                        while (found != null && !found.ready) {
                            delay(100)
                            found = inspect(appContext, remote.mode)
                        }
                    }
                    found
                }
                rebuild(appContext, active)
            } catch (e: DpiRemoteSession.StatusUnavailableException) {
                _state.value = DpiState.Failed(e.message ?: "DPI state could not be restored")
            } catch (e: TimeoutCancellationException) {
                _state.value = DpiState.Failed("DPI state reconciliation timed out")
            } catch (e: IllegalStateException) {
                _state.value = DpiState.Failed(e.message ?: "DPI state could not be restored")
            }
        }
        restoration = job
        job.start()
    }

    suspend fun awaitRestoration(context: Context) {
        initialize(context)
        restoration?.join()
        stopBeforeRestoration?.join()
    }

    private fun rebuild(context: Context, active: DpiRemoteSession? = null) {
        stateJob?.cancel()
        val newEngine: DpiEngine = when (_mode.value) {
            DpiMode.VPN -> DpiVpnEngine(context, VpnAuthorizer(AndroidVpnSlotSampler(context)))
            DpiMode.SOCKS5 -> NativeDpiEngine(context)
        }
        val newController = DpiController(newEngine)
        engine = newEngine
        controller = newController
        if (active != null) newController.restoreRunning(active.mode, active::watchExit)
        _endpoint.value = active?.endpoint
        stateJob = scope.launch {
            newController.state.collect { state ->
                if (state != _state.value) journalState(context, state)
                _state.value = state
            }
        }
    }
}
