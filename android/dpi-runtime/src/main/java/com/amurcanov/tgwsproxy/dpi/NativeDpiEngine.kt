package com.amurcanov.tgwsproxy.dpi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import java.util.UUID
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real [DpiEngine]: starts [DpiProxyService] in the `:dpi` process and waits for
 * a positive, session-scoped acknowledgment. The service sends its Binder token
 * first (so death monitoring is established before Ready) and acknowledges
 * STARTED only on the native-owned listener signal — a foreign SOCKS5 responder
 * can never be mistaken for us. A generation counter scopes every acknowledgment
 * and death event to the current session, so a stale exit from a previous
 * session can never change a new one.
 */
class NativeDpiEngine(
    private val context: Context,
    private val owner: IBinder? = null,
) : DpiEngine {
    override var onExit: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var generation = 0

    private var probe: Pair<ServiceConnection, StartSession>? = null

    override fun start(config: DpiConfig): DpiEngineResult {
        if (!config.isLoopback) {
            return DpiEngineResult.Failed("SOCKS5 bind host must be loopback, got ${config.host}")
        }
        val myGeneration = ++generation
        val attempt = SocksStartAttempt(UUID.randomUUID().toString(), SystemClock.elapsedRealtime() + DpiProxyService.START_TIMEOUT_MS)
        val argv = config.toArgv()
        val session = StartSession(myGeneration)
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (myGeneration != generation) return // stale session
                when (resultCode) {
                    DpiProxyService.RESULT_SESSION ->
                        session.onSession(resultData?.getBinder(DpiProxyService.EXTRA_BINDER))
                    DpiProxyService.RESULT_STARTED -> session.onReady()
                    DpiProxyService.RESULT_FAILED ->
                        session.onFailure(resultData?.getString(DpiProxyService.EXTRA_ERROR) ?: "engine failed to start")
                }
            }
        }
        val intent = Intent(context, DpiProxyService::class.java)
            .putExtra(DpiProxyService.EXTRA_ATTEMPT_ID, attempt.id)
            .putExtra(DpiProxyService.EXTRA_DEADLINE, attempt.deadline)
            .putStringArrayListExtra(DpiProxyService.EXTRA_ARGS, ArrayList(argv.toList()))
            .putExtra(DpiProxyService.EXTRA_HOST, config.normalizedHost)
            .putExtra(DpiProxyService.EXTRA_PORT, config.port)
            .putExtra(DpiProxyService.EXTRA_RESULT_RECEIVER, receiver)
        if (owner == null) {
            ContextCompat.startForegroundService(context, intent.setAction(DpiProxyService.ACTION_START))
        } else {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) = session.onSession(binder)
                override fun onServiceDisconnected(name: ComponentName) = session.onDeath()
                override fun onBindingDied(name: ComponentName) = session.onDeath()
            }
            intent.setAction(DpiProxyService.ACTION_BIND_PROBE)
                .putExtras(Bundle().apply { putBinder(DpiProxyService.EXTRA_OWNER, owner) })
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                return DpiEngineResult.Failed("Could not bind DPI strategy probe")
            }
            probe = connection to session
        }
        val outcome = runBlocking {
            attempt.await(session.acknowledged, SystemClock::elapsedRealtime) { id ->
                session.cancelling = true
                runCatching {
                    context.startService(Intent(context, DpiProxyService::class.java)
                        .setAction(DpiProxyService.ACTION_CANCEL)
                        .putExtra(DpiProxyService.EXTRA_ATTEMPT_ID, id))
                }
                val exited = withTimeoutOrNull(6_000) { session.exited.await(); true } ?: false
                if (owner != null) {
                    probe?.let { context.unbindService(it.first) }
                    probe = null
                }
                generation++
                exited
            }
        }
        return outcome
    }

    override fun stop() {
        // Invalidate the current session before ending it, so its death cannot
        // fire onExit and a stale exit cannot leak into the next session.
        generation++
        if (owner != null) {
            val (connection, session) = probe ?: return
            probe = null
            context.unbindService(connection)
            val stopped = runBlocking {
                withTimeoutOrNull(6_000) {
                    session.exited.await()
                    true
                }
            }
            check(stopped == true) { "DPI strategy probe stop could not be confirmed" }
            return
        }
        check(runBlocking { DpiRemoteSession.stopExisting(context, DpiMode.SOCKS5) }) {
            "DPI stop could not be confirmed"
        }
    }

    /**
     * One start attempt. All methods run on the main thread (acknowledgments via
     * [ResultReceiver] and death via a posted [IBinder.DeathRecipient]), so its
     * state needs no extra synchronization.
     */
    private inner class StartSession(private val myGeneration: Int) {
        val acknowledged = CompletableDeferred<DpiEngineResult>()
        val exited = CompletableDeferred<Unit>()
        private var sessionBinder: IBinder? = null
        private var ready = false
        @Volatile var cancelling = false

        private val deathRecipient = IBinder.DeathRecipient {
            mainHandler.post { onDeath() }
        }

        fun onSession(binder: IBinder?) {
            if (binder == null) {
                onDeath()
                return
            }
            if (sessionBinder != null) return
            sessionBinder = binder
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                // The service died between sending its token and this link.
                onDeath()
            }
        }

        fun onReady() {
            if (cancelling) return
            ready = true
            if (exited.isCompleted) {
                acknowledged.complete(DpiEngineResult.Failed("engine exited during startup"))
            } else {
                acknowledged.complete(DpiEngineResult.Running)
            }
        }

        fun onFailure(reason: String) {
            acknowledged.complete(DpiEngineResult.Failed(reason))
        }

        fun onDeath() {
            if (!exited.complete(Unit) || myGeneration != generation) return
            if (!cancelling) {
                if (ready) onExit?.invoke() else onFailure("engine exited during startup")
            }
        }
    }
}
