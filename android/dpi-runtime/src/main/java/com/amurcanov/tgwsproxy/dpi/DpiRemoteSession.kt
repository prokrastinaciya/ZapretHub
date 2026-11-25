package com.amurcanov.tgwsproxy.dpi

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Live session reported by the dedicated service process. */
class DpiRemoteSession private constructor(
    val mode: DpiMode,
    val endpoint: String,
    val ready: Boolean,
    private val binder: IBinder,
) {
    fun watchExit(onExit: () -> Unit): Boolean = try {
        binder.linkToDeath({ onExit() }, 0)
        true
    } catch (_: RemoteException) {
        false
    }

    class StatusUnavailableException(mode: DpiMode) :
        IllegalStateException("DPI status unavailable for $mode")

    companion object {
        internal const val ACTION_STATUS = "com.prokrastinaciya.zaprethub.dpi.STATUS"
        private const val QUERY = 1
        private const val STOP = 2
        private const val ENDPOINT = "endpoint"
        private const val READY = "ready"
        private const val QUERY_TIMEOUT_MS = 2_000L

        internal fun statusBinder(snapshot: () -> Pair<String?, Boolean>, stop: () -> Unit): IBinder =
            Messenger(Handler(Looper.getMainLooper()) { request ->
                when (request.what) {
                    STOP -> stop()
                    QUERY -> {
                        val (endpoint, ready) = snapshot()
                        val reply = Message.obtain(null, QUERY).apply {
                            data = Bundle().apply {
                                putString(ENDPOINT, endpoint)
                                putBoolean(READY, ready)
                            }
                        }
                        runCatching { request.replyTo.send(reply) }
                    }
                    else -> return@Handler false
                }
                true
            }).binder

        suspend fun inspect(context: Context, mode: DpiMode): DpiRemoteSession? {
            val intent = serviceIntent(context, mode).setAction(ACTION_STATUS)
            if (!isStarted(context, intent.component)) return null
            val result = CompletableDeferred<DpiRemoteSession?>()
            val unavailable = StatusUnavailableException(mode)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val receiver = Messenger(Handler(Looper.getMainLooper()) { reply ->
                        val endpoint = reply.data.getString(ENDPOINT)
                        // A successful bind proves the process exists. A missing endpoint
                        // is an incomplete status, not evidence that the slot is free.
                        if (endpoint.isNullOrBlank()) result.completeExceptionally(unavailable)
                        else result.complete(DpiRemoteSession(mode, endpoint, reply.data.getBoolean(READY), binder))
                        true
                    })
                    try {
                        Messenger(binder).send(Message.obtain(null, QUERY).apply { replyTo = receiver })
                    } catch (_: RemoteException) {
                        result.completeExceptionally(unavailable)
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) { result.completeExceptionally(unavailable) }
                override fun onBindingDied(name: ComponentName) { result.completeExceptionally(unavailable) }
                override fun onNullBinding(name: ComponentName) { result.completeExceptionally(unavailable) }
            }
            if (!context.bindService(intent, connection, 0)) {
                if (isStarted(context, intent.component)) throw unavailable
                return null
            }
            return try {
                val received = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                    result.await()
                    true
                } ?: false
                if (received) result.await()
                else throw unavailable
            } catch (e: StatusUnavailableException) {
                if (isStarted(context, intent.component)) throw e
                null
            } finally {
                context.unbindService(connection)
            }
        }

        /** Stops a started service even when the application has no local controller. */
        suspend fun stopExisting(context: Context, mode: DpiMode): Boolean {
            val session = try {
                inspect(context, mode)
            } catch (_: StatusUnavailableException) {
                null
            }
            if (session == null) {
                val intent = serviceIntent(context, mode)
                if (!isStarted(context, intent.component)) return true
                // A system binding can keep VpnService alive after stopService().
                // Deliver its explicit stop command if the status channel is unavailable.
                val action = when (mode) {
                    DpiMode.VPN -> DpiVpnService.ACTION_STOP
                    DpiMode.SOCKS5 -> DpiProxyService.ACTION_STOP
                }
                context.startService(intent.setAction(action))
                return withTimeoutOrNull(6_000) {
                    while (isStarted(context, intent.component)) delay(50)
                    true
                } ?: false
            }
            val exited = CompletableDeferred<Unit>()
            if (!session.watchExit { exited.complete(Unit) }) return !session.binder.isBinderAlive
            try {
                Messenger(session.binder).send(Message.obtain(null, STOP))
            } catch (_: RemoteException) {
                return !session.binder.isBinderAlive
            }
            return withTimeoutOrNull(6_000) { exited.await(); true } ?: false
        }

        // Avoid creating idle engine processes on app launch. Android still lists the
        // caller's own services on API 26+, and readiness comes only from the IPC reply.
        @Suppress("DEPRECATION")
        private fun isStarted(context: Context, component: ComponentName?): Boolean =
            context.getSystemService(ActivityManager::class.java)
                ?.getRunningServices(Int.MAX_VALUE)
                ?.any { it.service == component && it.started } ?: false

        private fun serviceIntent(context: Context, mode: DpiMode): Intent =
            Intent(context, when (mode) {
                DpiMode.VPN -> DpiVpnService::class.java
                DpiMode.SOCKS5 -> DpiProxyService::class.java
            })
    }
}

internal fun dpiEndpoint(host: String, port: Int): String =
    if (':' in host) "[$host]:$port" else "$host:$port"
