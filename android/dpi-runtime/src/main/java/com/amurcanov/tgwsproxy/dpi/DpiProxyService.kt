package com.amurcanov.tgwsproxy.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.core.ByeDpiProxy
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Foreground service that hosts the pinned ByeByeDPI SOCKS5 engine in its own
 * `:dpi` process. Commands are private (the service is not exported) and use a
 * namespace distinct from the Telegram proxy's.
 *
 * The service sends its Binder token back with a session acknowledgment before
 * the engine is ready, so the caller can watch for process death during startup;
 * it acknowledges STARTED only on the native-owned listener signal (our engine
 * called listen() after a successful bind). Every stop ends the process to give
 * the next start fresh native globals (the pinned engine keeps sticky
 * `proxy_started`/`params` statics).
 */
class DpiProxyService : Service() {
    companion object {
        const val ACTION_START = "com.prokrastinaciya.zaprethub.dpi.START"
        internal const val ACTION_BIND_PROBE = "com.prokrastinaciya.zaprethub.dpi.BIND_PROBE"
        const val ACTION_STOP = "com.prokrastinaciya.zaprethub.dpi.STOP"
        internal const val ACTION_CANCEL = "com.prokrastinaciya.zaprethub.dpi.CANCEL"
        internal const val EXTRA_ATTEMPT_ID = "EXTRA_ATTEMPT_ID"
        internal const val EXTRA_DEADLINE = "EXTRA_DEADLINE"
        const val EXTRA_ARGS = "EXTRA_ARGS"
        const val EXTRA_HOST = "EXTRA_HOST"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_RESULT_RECEIVER = "EXTRA_RESULT_RECEIVER"
        internal const val EXTRA_OWNER = "EXTRA_OWNER"
        const val EXTRA_BINDER = "EXTRA_BINDER"
        const val EXTRA_ERROR = "EXTRA_ERROR"
        const val RESULT_SESSION = 3
        const val RESULT_STARTED = 1
        const val RESULT_FAILED = 2
        const val START_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 250
        private const val POLL_MS = 50L
        private const val CHANNEL = "ByeDPI_Proxy_Service"
        private const val NOTIFICATION_ID = 102
        private const val TAG = "DpiProxyService"
    }

    /** Stable per-process identity; a fresh process (after stop) gets a fresh Binder. */
    private val sessionBinder = Binder()
    private val statusBinder by lazy { DpiRemoteSession.statusBinder({ endpoint to ready }, ::stop) }
    private var endpoint: String? = null
    private val proxy by lazy { ByeDpiProxy() }
    private var engineThread: Thread? = null
    @Volatile private var activeAttemptId: String? = null
    private val startup = SocksStartupState()

    @Volatile
    private var engineError: String? = null

    @Volatile
    private var ready = false

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.dpi_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder =
        when (intent?.action) {
            DpiRemoteSession.ACTION_STATUS -> statusBinder
            ACTION_BIND_PROBE -> {
                start(intent, foreground = false)
                sessionBinder
            }
            else -> sessionBinder
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent, foreground = true)
            ACTION_CANCEL -> {
                if (intent.getStringExtra(EXTRA_ATTEMPT_ID) == activeAttemptId && activeAttemptId != null) stop()
                else if (activeAttemptId == null) stopSelfResult(startId)
            }
            ACTION_STOP -> stop()
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent, foreground: Boolean) {
        if (engineThread != null) return
        val args = intent.getStringArrayListExtra(EXTRA_ARGS)?.toTypedArray()
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val id = intent.getStringExtra(EXTRA_ATTEMPT_ID)
        val deadline = intent.getLongExtra(EXTRA_DEADLINE, 0)
        if (args.isNullOrEmpty() || host.isNullOrBlank() || port !in 1..65535 || id.isNullOrBlank()) {
            report(receiver, "invalid engine configuration")
            terminate()
            return
        }

        activeAttemptId = id
        val attempt = SocksStartAttempt(id, deadline)
        // A temporary strategy probe must end when the process running it dies.
        val owner = intent.extras?.getBinder(EXTRA_OWNER)
        if (owner != null) {
            val main = Handler(Looper.getMainLooper())
            try {
                owner.linkToDeath({ main.post { if (endpoint != null) stop() } }, 0)
            } catch (_: RemoteException) {
                terminate()
                return
            }
        }
        endpoint = dpiEndpoint(host, port)
        stopping = false
        ready = false
        engineError = null
        // Publish the session identity immediately so the caller can establish
        // death monitoring before the engine is ready.
        receiver?.send(RESULT_SESSION, Bundle().apply { putBinder(EXTRA_BINDER, sessionBinder) })
        if (foreground) startForeground(notification(getString(R.string.dpi_notification_running)))
        if (!attempt.active(SystemClock.elapsedRealtime(), activeAttemptId)) {
            report(receiver, "SOCKS5 startup timed out")
            terminate()
            return
        }

        engineThread = thread(name = "ByeDPI") {
            try {
                val code = proxy.startProxy(args)
                engineError = "engine exited with code $code"
            } catch (e: Throwable) {
                Log.e(TAG, "ByeDPI engine failed", e)
                engineError = "engine exception: ${e.message ?: e.javaClass.simpleName}"
            }
            // Engine stopped on its own after being reported ready: end the
            // process so the caller's binder-death callback fires.
            startup.exited(0)
            if (!stopping) terminate()
        }

        thread(name = "ByeDPIMonitor") {
            while (attempt.active(SystemClock.elapsedRealtime(), activeAttemptId)) {
                val error = engineError
                if (error != null) {
                    report(receiver, error)
                    terminate()
                    return@thread
                }
                // Positive ownership: OUR engine called listen() (native signal)
                // and answers the SOCKS5 handshake.
                if (DpiNative.isListenerReady() != 0 && socks5Ready(host, port)) {
                    if (attempt.active(SystemClock.elapsedRealtime(), activeAttemptId)) {
                        startup.publishReady {
                            if (attempt.active(SystemClock.elapsedRealtime(), activeAttemptId)) {
                                ready = true
                                receiver?.send(RESULT_STARTED, Bundle())
                            }
                        }
                        if (ready) return@thread
                    }
                }
                Thread.sleep(POLL_MS)
            }
            report(receiver, "SOCKS5 proxy not ready on $host:$port")
            runCatching { proxy.stopProxy() }
            terminate()
        }
    }

    private fun stop() {
        stopping = true
        startup.stop()
        activeAttemptId = null
        endpoint = null
        runCatching { proxy.stopProxy() }
        engineThread?.join(2_000)
        engineThread = null
        terminate()
    }

    private fun socks5Ready(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            val input = socket.getInputStream()
            input.read() == 5 && input.read() == 0
        }
    } catch (e: IOException) {
        false
    }

    private fun report(receiver: ResultReceiver?, reason: String) {
        receiver?.send(RESULT_FAILED, Bundle().apply { putString(EXTRA_ERROR, reason) })
    }

    private fun terminate() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, flags)
        }
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, DpiProxyService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.dpi_notification_channel_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_dpi_notification)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.dpi_notification_disconnect),
                stopAction
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        if (endpoint != null) stop()
        super.onDestroy()
    }
}
