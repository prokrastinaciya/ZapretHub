package com.amurcanov.tgwsproxy.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.TProxyService
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * System VPN for ByeByeDPI: establishes the TUN slot and drives the pinned
 * SOCKS5 engine + HEV tunnel in a dedicated `:vpn` process.
 *
 * Routing is conservative by construction:
 *  * the host package (Telegram engine + DPI engine share one applicationId)
 *    is ALWAYS excluded from the tunnel, so neither engine's outgoing
 *    connections loop back through the VPN and the engines never capture
 *    themselves;
 *  * all-apps is the default; [DpiVpnRouting] adds blacklist/whitelist modes;
 *  * [onRevoke] stops without restarting, so a system revocation or lost
 *    consent can never enter a restart loop.
 *
 * Commands are private (service not exported) and use a namespace distinct
 * from the Telegram proxy and the SOCKS5 [DpiProxyService]. The service never
 * trusts a start intent: it re-samples the slot and re-runs [VpnStartPolicy.decide]
 * immediately before `Builder.establish()`, so a raw (in-app) start intent cannot bypass
 * an authorization that is no longer valid. A stop/revoke kills the `:vpn`
 * process only, so the pinned engine's sticky native globals are reset without
 * ever touching the Telegram process.
 */
class DpiVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.prokrastinaciya.zaprethub.dpi.vpn.START"
        const val ACTION_STOP = "com.prokrastinaciya.zaprethub.dpi.vpn.STOP"
        const val ACTION_CANCEL = "com.prokrastinaciya.zaprethub.dpi.vpn.CANCEL"
        const val EXTRA_ATTEMPT_ID = "EXTRA_ATTEMPT_ID"
        const val EXTRA_DEADLINE = "EXTRA_DEADLINE"
        const val EXTRA_ARGS = "EXTRA_ARGS"
        const val EXTRA_HOST = "EXTRA_HOST"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_DNS = "EXTRA_DNS"
        const val EXTRA_IPV6 = "EXTRA_IPV6"
        const val EXTRA_BLOCK_UDP_443 = "EXTRA_BLOCK_UDP_443"
        const val EXTRA_APP_LIST_MODE = "EXTRA_APP_LIST_MODE"
        const val EXTRA_APPS = "EXTRA_APPS"
        const val EXTRA_REPLACEMENT_SLOT = "EXTRA_REPLACEMENT_SLOT"
        const val EXTRA_REPLACEMENT_NETWORKS = "EXTRA_REPLACEMENT_NETWORKS"
        const val EXTRA_RESULT_RECEIVER = "EXTRA_RESULT_RECEIVER"
        const val EXTRA_BINDER = "EXTRA_BINDER"
        const val EXTRA_ERROR = "EXTRA_ERROR"
        const val RESULT_SESSION = 3
        const val RESULT_STARTED = 1
        const val RESULT_FAILED = 2
        const val RESULT_STOPPED = 4
        const val START_TIMEOUT_MS = 20_000L
        const val STOP_TIMEOUT_MS = 6_000L

        private const val PROBE_TIMEOUT_MS = 250
        private const val POLL_MS = 50L
        private const val CHANNEL = "ByeDPI_Vpn_Service"
        private const val NOTIFICATION_ID = 103
        private const val TAG = "DpiVpnService"
    }

    private val proxy by lazy { ByeDpiProxy() }
    private val sessionBinder = Binder()
    private val statusBinder by lazy { DpiRemoteSession.statusBinder({ endpoint to tunnelReady }) { stop() } }
    @Volatile
    private var endpoint: String? = null

    @Volatile
    private var tunnelReady = false
    private val lifecycleLock = Any()

    @Volatile
    private var activeAttemptId: String? = null
    private var engineThread: Thread? = null
    private var tunnelThread: Thread? = null
    private var tunFd: ParcelFileDescriptor? = null

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
                    getString(R.string.dpi_vpn_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
            ACTION_CANCEL -> cancel(intent, startId)
            // API 24–26 ignore the manifest opt-out and can still deliver this action.
            // Only the in-app START command includes a configuration and consent proof.
            else -> if (activeAttemptId == null) stopSelfResult(startId)
        }
        // No automatic restart: a VPN that the user (or system) stopped must
        // stay stopped until an explicit, policy-gated start.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        if (stopping || activeAttemptId != null) {
            report(receiver, "VPN is already active")
            return
        }
        val id = intent.getStringExtra(EXTRA_ATTEMPT_ID)
        val deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L)
        if (id == null || deadline <= SystemClock.elapsedRealtime()) {
            report(receiver, "VPN startup deadline expired")
            stopSelf()
            return
        }
        val attempt = VpnStartAttempt(id, deadline)
        val args = intent.getStringArrayListExtra(EXTRA_ARGS)?.toTypedArray()
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 1080)
        val routing = DpiVpnRouting(
            dns = intent.getStringExtra(EXTRA_DNS).orEmpty(),
            ipv6 = intent.getBooleanExtra(EXTRA_IPV6, true),
            blockUdp443 = intent.getBooleanExtra(EXTRA_BLOCK_UDP_443, false),
            appListMode = runCatching {
                VpnAppListMode.valueOf(intent.getStringExtra(EXTRA_APP_LIST_MODE) ?: "")
            }.getOrDefault(VpnAppListMode.ALL_APPS),
            apps = intent.getStringArrayListExtra(EXTRA_APPS)?.toList().orEmpty(),
        )
        val replacement = intent.getStringExtra(EXTRA_REPLACEMENT_SLOT)?.let { slotName ->
            runCatching {
                VpnReplacement(
                    confirmedSlot = VpnSlotState.valueOf(slotName),
                    confirmedNetworks = intent.getLongArrayExtra(EXTRA_REPLACEMENT_NETWORKS)?.toSet() ?: emptySet(),
                )
            }.getOrNull()
        }
        if (args.isNullOrEmpty() || host.isBlank() || port !in 1..65535) {
            report(receiver, "invalid VPN engine configuration")
            terminate()
            return
        }

        endpoint = dpiEndpoint(host, port)
        activeAttemptId = attempt.id
        stopping = false
        ready = false
        engineError = null
        // Publish the session identity immediately so the caller can establish
        // death monitoring before the engine is ready.
        receiver?.send(RESULT_SESSION, Bundle().apply { putBinder(EXTRA_BINDER, sessionBinder) })
        startForeground(notification(getString(R.string.dpi_vpn_notification_running)))

        engineThread = thread(name = "ByeDPI") {
            try {
                val code = proxy.startProxy(args)
                if (code != 0) engineError = "engine exited with code $code"
            } catch (e: Throwable) {
                Log.e(TAG, "ByeDPI engine failed", e)
                engineError = "engine exception: ${e.message ?: e.javaClass.simpleName}"
            }
            if (!stopping && ready) terminate()
        }

        thread(name = "ByeDPIVpnMonitor") {
            while (!stopping && attempt.canStartTunnel(SystemClock.elapsedRealtime(), activeAttemptId)) {
                val error = engineError
                if (error != null) {
                    report(receiver, error)
                    terminate()
                    return@thread
                }
                if (DpiNative.isListenerReady() != 0 && socks5Ready(host, port)) {
                    ready = true
                    startTunnel(attempt, receiver, routing, host, port, replacement)
                    return@thread
                }
                Thread.sleep(POLL_MS)
            }
            if (stopping) return@thread
            report(receiver, "VPN engine not ready on $host:$port")
            terminate()
        }
    }

    /** Re-checks authorization immediately before establishing, then starts the HEV tunnel. */
    private fun startTunnel(
        attempt: VpnStartAttempt,
        receiver: ResultReceiver?,
        routing: DpiVpnRouting,
        host: String,
        port: Int,
        replacement: VpnReplacement?,
    ) {
        try {
            val builder = vpnBuilder(routing)
            // Immediately before establish: independently re-sample the slot and
            // re-decide with the attempt's replacement proof, so a raw (in-app)
            // start intent cannot bypass an authorization that is no longer
            // valid, and the proof is validated against the freshly observed
            // slot (attempt identity is preserved here).
            val fresh = VpnStartPolicy.decide(AndroidVpnSlotSampler(this).sample(replacement), replacement)
            if (fresh !is VpnStartDecision.Start) {
                report(receiver, "VPN authorization no longer valid: $fresh")
                terminate()
                return
            }

            val tunnelConfig = buildString {
                appendLine("tunnel:")
                appendLine("  mtu: 8500")
                appendLine("misc:")
                appendLine("  task-stack-size: 81920")
                appendLine("socks5:")
                appendLine("  address: $host")
                appendLine("  port: $port")
                appendLine("  udp: udp")
            }
            // Never hold the stop lock across a potentially blocked establish.
            if (stopping) return
            val fd = builder.establish() ?: error("VPN connection failed")
            synchronized(lifecycleLock) {
                if (stopping) {
                    fd.close()
                    return
                }
                check(attempt.canStartTunnel(SystemClock.elapsedRealtime(), activeAttemptId)) {
                    fd.close()
                    "VPN startup deadline expired"
                }
                tunFd = fd
                tunnelThread = thread(name = "HEV") {
                    // startTunnel blocks in the native event loop until stopTunnel;
                    // a return without an explicit stop is a native failure/early
                    // exit, which must end the session so Running is never stale.
                    HevNative.setBlockUdp443(routing.blockUdp443)
                    val code = TProxyService.startTunnel(tunnelConfig, fd.fd)
                    if (!stopping) {
                        Log.w(TAG, "HEV tunnel exited without stop (code $code)")
                        report(receiver, "HEV tunnel exited (code $code)")
                        terminate()
                    }
                }
            }
            // Wait for the genuine native-ready signal (hev_socks5_tunnel_run
            // reached, i.e. the event loop is about to run) — not thread
            // liveness. Thread death before ready is an early failure.
            while (!stopping && attempt.canStartTunnel(SystemClock.elapsedRealtime(), activeAttemptId)) {
                if (HevNative.isReady() != 0) {
                    synchronized(lifecycleLock) {
                        if (stopping) return
                        if (attempt.canStartTunnel(SystemClock.elapsedRealtime(), activeAttemptId)) {
                            tunnelReady = true
                            receiver?.send(RESULT_STARTED, Bundle())
                            return
                        }
                    }
                    break
                }
                if (tunnelThread?.isAlive != true) {
                    report(receiver, "HEV tunnel exited before ready")
                    terminate()
                    return
                }
                Thread.sleep(POLL_MS)
            }
            if (!stopping) {
                report(receiver, "HEV tunnel not ready")
                terminate()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VPN tunnel failed", e)
            report(receiver, "VPN tunnel failed: ${e.message ?: e.javaClass.simpleName}")
            terminate()
        }
    }

    @Suppress("DEPRECATION")
    private fun vpnBuilder(routing: DpiVpnRouting): Builder {
        val builder = Builder()
        builder.setSession("ByeDPI")
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (routing.ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }
        if (routing.dns.isNotBlank()) {
            builder.addDnsServer(routing.dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val hostPackage = applicationContext.packageName
        applyVpnApplicationRules(
            routing, hostPackage,
            addAllowed = { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                    true
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    false
                }
            },
            addDisallowed = { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (missing: android.content.pm.PackageManager.NameNotFoundException) {
                    if (pkg == hostPackage) throw missing
                }
            },
        )

        return builder
    }

    @Suppress("DEPRECATION")
    private fun cancel(intent: Intent, startId: Int) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val id = intent.getStringExtra(EXTRA_ATTEMPT_ID)
        if (id != null && id == activeAttemptId) {
            stop()
        } else {
            receiver?.send(RESULT_STOPPED, Bundle())
            if (activeAttemptId == null) stopSelfResult(startId)
        }
    }

    private fun stop() {
        synchronized(lifecycleLock) {
            if (stopping) return
            stopping = true
            endpoint = null
        }
        // A not-yet-ready HEV may not have initialized its event channel.
        // Never invoke its stop then; process death performs the cleanup.
        // Binder death, not a premature STOPPED reply, confirms the teardown.
        val hevReady = tunnelReady
        VpnStopCoordinator.begin(4_000, {
            if (hevReady) {
                runCatching { TProxyService.stopTunnel() }
                runCatching { proxy.stopProxy() }
                tunnelThread?.join()
                engineThread?.join()
                runCatching { tunFd?.close() }
            }
        }, { terminate() })
    }

    /** The system revoked the VPN (or consent was withdrawn): stop, do not restart. */
    override fun onRevoke() {
        Log.w(TAG, "VPN revoked; stopping without restart")
        stop()
    }

    override fun onDestroy() {
        if (endpoint != null) stop()
        super.onDestroy()
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

    /**
     * Ends the session and kills the `:vpn` process. Killing only this process
     * (never the Telegram process) is what resets the pinned engine's sticky
     * native globals (`proxy_started`/`params`) so the next VPN start gets a
     * fresh engine.
     */
    private fun terminate() {
        // Do not put framework IPC ahead of the kill: it too can block.
        // Android removes this process's service, notification and VPN on death.
        Process.killProcess(Process.myPid())
    }

    private fun startForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, flags)
        }
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.dpi_vpn_notification_channel_name))
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

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == DpiRemoteSession.ACTION_STATUS) statusBinder else super.onBind(intent)
}

/** Independent watchdog remains runnable when native cleanup hangs. */
internal object VpnStopCoordinator {
    fun begin(timeoutMs: Long, cleanup: () -> Unit, terminate: () -> Unit) {
        val worker = thread(name = "ByeDPIVpnStop", isDaemon = true) { cleanup() }
        thread(name = "ByeDPIVpnStopWatchdog", isDaemon = true) {
            try {
                worker.join(timeoutMs)
            } finally {
                terminate()
            }
        }
    }
}
