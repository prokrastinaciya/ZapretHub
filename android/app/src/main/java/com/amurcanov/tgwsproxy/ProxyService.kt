package com.amurcanov.tgwsproxy

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProxyService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    @Volatile private var engine: ProxyEngine? = null
    private var configuration: ProxyConfiguration? = null
    private var statistics: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var generation = 0
    private var starting = false
        set(value) {
            field = value
            preparing.value = value
            ProxyTileService.requestSync(this)
        }
    private var stopping = false
    private var pendingStart: ProxyConfiguration? = null
    private var lastCommandId = 0
    private var needsEngineCleanup = true
    private var lastText = ""
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkReset: Job? = null

    companion object {
        const val ACTION_START = "com.prokrastinaciya.zaprethub.START"
        const val ACTION_STOP = "com.prokrastinaciya.zaprethub.STOP"
        const val ACTION_RESTART = "com.prokrastinaciya.zaprethub.RESTART"
        const val EXTRA_BIND_IP = "EXTRA_BIND_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_IPS = "EXTRA_IPS"
        const val EXTRA_POOL_SIZE = "EXTRA_POOL_SIZE"
        const val EXTRA_CFPROXY_ENABLED = "EXTRA_CFPROXY_ENABLED"
        const val EXTRA_CF_DISABLE_TLS = "EXTRA_CF_DISABLE_TLS"
        const val EXTRA_CFPROXY_PRIORITY = "EXTRA_CFPROXY_PRIORITY"
        const val EXTRA_CFPROXY_DOMAIN = "EXTRA_CFPROXY_DOMAIN"
        const val EXTRA_CF_WORKER_DOMAIN = "EXTRA_CF_WORKER_DOMAIN"
        const val EXTRA_SECRET_KEY = "EXTRA_SECRET_KEY"
        private const val CHANNEL = "TG_WS_Proxy_Service_v5"
        private const val NOTIFICATION = 101
        /** How long the CPU stays awake after the last relayed byte. */
        private const val TRAFFIC_WAKE_MS = 60_000L
        /** Networks flap while switching; act once they settle. */
        private const val NETWORK_SETTLE_MS = 2_000L
        private val preparing = MutableStateFlow(false)
        val isStarting: StateFlow<Boolean> = preparing
        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running
        val isVerifiedRunning: StateFlow<Boolean> = running
        private val connection = MutableStateFlow<ActiveProxyConnection?>(null)
        val activeConnection: StateFlow<ActiveProxyConnection?> = connection
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastCommandId = startId
        when (intent?.action) {
            ACTION_START -> start(ProxyConfiguration(
                intent.getStringExtra(EXTRA_BIND_IP) ?: "127.0.0.1",
                intent.getIntExtra(EXTRA_PORT, 1443), intent.getStringExtra(EXTRA_IPS) ?: "",
                intent.getStringExtra(EXTRA_SECRET_KEY) ?: "", intent.getIntExtra(EXTRA_POOL_SIZE, 4),
                intent.getBooleanExtra(EXTRA_CFPROXY_ENABLED, true), intent.getStringExtra(EXTRA_CFPROXY_DOMAIN) ?: "",
                intent.getStringExtra(EXTRA_CF_WORKER_DOMAIN) ?: "",
                intent.getBooleanExtra(EXTRA_CF_DISABLE_TLS, false)))
            ACTION_RESTART -> configuration?.let { restart(it) } ?: stopSelfResult(startId)
            ACTION_STOP -> stop()
            else -> stopSelfResult(startId)
        }
        return START_REDELIVER_INTENT
    }
    private fun foreground(text: String) {
        lastText = text
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification(text))
    }
    private fun start(config: ProxyConfiguration) {
        if (stopping) {
            // The UI can request another start as soon as running becomes
            // false, while the previous listener is still being closed.
            pendingStart = config
            Log.i("TgWsProxy", "Proxy start queued until cleanup completes")
            return
        }
        if (starting || running.value) return
        configuration = config
        needsEngineCleanup = true
        starting = true
        val current = ++generation
        foreground(getString(R.string.notification_starting))
        holdAwake(TRAFFIC_WAKE_MS)
        scope.launch {
            operations.withLock {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val runtime = engine ?: PythonProxyEngine(this@ProxyService).also { engine = it }
                        // Engine initialization can outlive a stop/destroy
                        // request. Do not open a listener after that request.
                        if (generation != current) runtime.stop() else runtime.start(config)
                    }
                    if (generation != current) return@withLock
                    starting = false
                    if (result.connection == null) {
                        Log.e("TgWsProxy", "Proxy start failed: ${result.error}")
                        journal("error", "start failed: ${result.error ?: result.state}")
                        update(getString(R.string.notification_error, result.error ?: result.state))
                        android.widget.Toast.makeText(this@ProxyService, getString(R.string.notification_error, result.error ?: result.state), android.widget.Toast.LENGTH_LONG).show()
                        stop()
                    } else {
                        connection.value = result.connection
                        running.value = true
                        ProxyTileService.requestSync(this@ProxyService)
                        update(getString(R.string.notification_running))
                        journal("info", "started on ${config.host}:${config.port}, pool ${config.poolSize}, cf ${config.cfEnabled}, cf_tls ${!config.disableSecure}")
                        watchNetwork()
                        statistics = scope.launch { pollStatistics() }
                    }
                } catch (e: Exception) {
                    if (generation == current) {
                        Log.e("TgWsProxy", "Proxy startup failed", e)
                        starting = false
                        stop()
                    }
                }
            }
        }
    }
    /**
     * The CPU is kept awake only while bytes move, plus [TRAFFIC_WAKE_MS]. An idle
     * proxy lets the phone sleep: incoming packets wake it, and Telegram holds its
     * own wake lock while it talks to us. The delay below does not tick in deep
     * sleep, so polling stops by itself when nothing happens.
     */
    private suspend fun pollStatistics() {
        var lastBytes = -1L
        while (currentCoroutineContext().isActive) {
            delay(3000)
            val result = try { withContext(Dispatchers.IO) { engine?.snapshot() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("TgWsProxy", "Proxy status check failed", e)
                journal("error", "status check failed: $e")
                stop()
                break
            } ?: break
            if (result.connection == null) {
                journal("error", "engine stopped by itself: ${result.error ?: result.state}")
                stop(); break
            }
            val bytes = result.bytesUp + result.bytesDown
            if (bytes != lastBytes) { holdAwake(TRAFFIC_WAKE_MS); lastBytes = bytes }
            val human = if (bytes < 1048576) "${bytes / 1024} KB" else "${bytes / 1048576} MB"
            update(getString(R.string.notification_traffic, human, result.activeConnections))
        }
    }

    /**
     * The core backs off failed routes for up to an hour and only a restart
     * cleared that. Reset it when Android switches networks or a network
     * regains validated internet, so recovery needs no manual restart.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            private var validated = false
            private var initialized = false
            override fun onAvailable(network: Network) {
                val changed = initialized && network != current
                current = network; initialized = true
                if (changed) scheduleNetworkReset("switched network")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network != current) return
                val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (now && !validated && initialized && seenValidation) scheduleNetworkReset("internet is back")
                if (now) seenValidation = true
                validated = now
            }
            override fun onLost(network: Network) {
                if (network == current) { current = null; validated = false }
            }
            private var seenValidation = false
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w("TgWsProxy", "Network callback unavailable", it) }
    }

    private fun scheduleNetworkReset(reason: String) {
        networkReset?.cancel()
        networkReset = scope.launch {
            delay(NETWORK_SETTLE_MS)
            if (!running.value) return@launch
            holdAwake(TRAFFIC_WAKE_MS)  // Let the pools refill before sleeping.
            val reset = withContext(Dispatchers.IO) {
                runCatching { engine?.note("info", "network: $reason"); engine?.networkChanged() }.getOrNull()
            }
            Log.i("TgWsProxy", "Network change ($reason): routes reset = $reset")
        }
    }

    private fun unwatchNetwork() {
        networkReset?.cancel(); networkReset = null
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun journal(level: String, message: String) {
        val runtime = engine ?: return
        CoroutineScope(Dispatchers.IO).launch { runCatching { runtime.note(level, message) } }
    }
    private fun restart(config: ProxyConfiguration) {
        if (starting || stopping) return
        stop(restart = config)
    }
    private fun stop(restart: ProxyConfiguration? = null) {
        // A later explicit stop also cancels a queued start/restart.
        pendingStart = restart
        if (stopping) return
        val stopCommandId = lastCommandId
        val interruptStartup = starting
        stopping = true
        ++generation
        starting = false
        statistics?.cancel(); statistics = null
        unwatchNetwork()
        if (running.value) journal("info", if (restart != null) "restart requested" else "stopped")
        connection.value = null; running.value = false
        ProxyTileService.requestSync(this)
        update(getString(R.string.notification_stopping))
        scope.launch {
            // Signal Python before waiting for the start operation's mutex.
            // The second stop below also covers initialization completing
            // after this first nullable engine read.
            if (interruptStartup) {
                try { withContext(Dispatchers.IO) { engine?.stop() } }
                catch (e: Exception) { Log.w("TgWsProxy", "Startup cancellation failed", e) }
            }
            operations.withLock {
                try {
                    val result = withContext(Dispatchers.IO) { engine?.stop() }
                    needsEngineCleanup = result != null && result.state != "stopped"
                } catch (e: Exception) { Log.e("TgWsProxy", "Proxy cleanup failed", e) }
            }
            releaseWakeLock()
            stopping = false
            val next = pendingStart
            pendingStart = null
            if (next != null) start(next)
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Do not destroy this service if Android has already accepted
                // a newer start command which has yet to reach onStartCommand.
                stopSelfResult(stopCommandId)
            }
        }
    }
    private fun update(text: String) {
        if (lastText == text) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }
    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        fun action(name: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, ProxyService::class.java).setAction(name), flags)
        val stop = PendingIntent.getBroadcast(this, 3,
            Intent(this, ProxyNotificationStopReceiver::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL).setContentTitle(getString(R.string.app_name))
            .setContentText(text).setSmallIcon(R.drawable.ic_mark_mono).setContentIntent(open)
            .addAction(android.R.drawable.ic_popup_sync, getString(R.string.notification_restart), action(ACTION_RESTART, 2))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_disconnect), stop)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }
    /** Re-arms one non-counted wake lock; it lapses on its own when traffic stops. */
    private fun holdAwake(millis: Long) {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGWS:Proxy")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(millis)
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        pendingStart = null
        ++generation
        scope.cancel()
        starting = false
        connection.value = null; running.value = false
        unwatchNetwork()
        releaseWakeLock()
        // Service teardown must also clean up when Android did not send ACTION_STOP.
        if (needsEngineCleanup) CoroutineScope(Dispatchers.IO).launch {
            runCatching { engine?.stop() }
            // Read engine again after initialization/start relinquishes the
            // mutex; capturing null before initialization would leak it.
            operations.withLock { runCatching { engine?.stop() } }
        }
        ProxyTileService.requestSync(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
