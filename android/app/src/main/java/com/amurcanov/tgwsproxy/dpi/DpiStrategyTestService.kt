package com.amurcanov.tgwsproxy.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.ServiceSelectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket

internal data class DpiStrategyTestState(
    val running: Boolean = false,
    val results: List<DpiStrategyResult> = emptyList(),
    val total: Int = 0,
    val message: Int = 0,
)

internal class DpiStrategyTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (runJob == null) stopSelf() else runJob?.cancel()
            }
            ACTION_START -> if (runJob == null) {
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        notifications.createNotificationChannel(
                            NotificationChannel(CHANNEL, getString(R.string.dpi_strategy_test), NotificationManager.IMPORTANCE_LOW)
                        )
                    }
                    val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
                    val task = scope.launch(start = CoroutineStart.LAZY) { runTest() }
                    runJob = task
                    task.start()
                } catch (_: RuntimeException) {
                    finish(R.string.dpi_strategy_test_failed)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun runTest() {
        var message = 0
        var wakeLock: PowerManager.WakeLock? = null
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:StrategyTest")
                .apply { setReferenceCounted(false) }
            wakeLock.acquire(RUN_TIMEOUT_MS + 20_000)
            withTimeout(RUN_TIMEOUT_MS) {
                val app = applicationContext
                val store = DpiSettingsStore(app)
                val targets = dpiTargetsFor(ServiceSelectionStore(app).read())
                if (targets.isEmpty()) return@withTimeout
                val started = DpiSession.runStrategyTest(app) {
                    val (candidates, port) = withContext(Dispatchers.IO) {
                        val candidates = app.assets.open("proxytest_strategies.list").bufferedReader().use {
                            store.strategyCandidates(it.lineSequence())
                        }
                        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                        candidates to port
                    }
                    mutableState.value = mutableState.value.copy(total = candidates.size)
                    val tester = DpiStrategyTester(NativeDpiEngine(app, owner = Binder()))
                    tester.test(candidates, port, targets) { result ->
                        store.saveSuccessfulStrategy(result)
                        withContext(Dispatchers.Main) {
                            val current = mutableState.value
                            mutableState.value = current.copy(results = current.results + result)
                            notifications.notify(NOTIFICATION_ID, notification())
                        }
                    }
                }
                if (!started) message = R.string.dpi_strategy_test_busy
                else if (rankDpiStrategies(mutableState.value.results).isEmpty()) message = R.string.dpi_strategy_test_empty
            }
        } catch (_: TimeoutCancellationException) {
            message = R.string.dpi_strategy_test_timeout
        } catch (e: CancellationException) {
            message = R.string.dpi_strategy_test_cancelled
            throw e
        } catch (_: Exception) {
            message = R.string.dpi_strategy_test_failed
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            finish(message)
        }
    }

    private fun finish(message: Int) {
        runJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        mutableState.value = mutableState.value.copy(running = false, message = message)
    }

    private fun notification(): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, pendingFlags)
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, javaClass).setAction(ACTION_STOP), pendingFlags,
        )
        val (running, results, total) = mutableState.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mark_mono)
            .setContentTitle(getString(R.string.dpi_strategy_test))
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.dpi_strategy_test_stop), stop)
            .apply {
                if (total > 0) {
                    setContentText(getString(R.string.dpi_strategy_test_progress, results.size, total))
                    setProgress(total, results.size, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dpi_strategy_test"
        private const val NOTIFICATION_ID = 104
        private const val RUN_TIMEOUT_MS = 30 * 60_000L
        private const val ACTION_START = "com.prokrastinaciya.zaprethub.dpi.TEST_START"
        private const val ACTION_STOP = "com.prokrastinaciya.zaprethub.dpi.TEST_STOP"
        private val mutableState = MutableStateFlow(DpiStrategyTestState())
        val state = mutableState.asStateFlow()

        fun start(context: Context) {
            if (state.value.running) return
            mutableState.value = DpiStrategyTestState(running = true)
            try {
                val intent = Intent(context, DpiStrategyTestService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                mutableState.value = DpiStrategyTestState(message = R.string.dpi_strategy_test_failed)
            }
        }

        fun stop(context: Context) {
            if (state.value.running) {
                context.startService(Intent(context, DpiStrategyTestService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
