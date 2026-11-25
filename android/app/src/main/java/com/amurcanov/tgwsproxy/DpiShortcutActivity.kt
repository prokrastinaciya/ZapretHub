package com.amurcanov.tgwsproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiState

internal fun consumeDpiShortcutStart(intent: Intent): String? {
    val token = intent.getStringExtra(DpiShortcutActivity.EXTRA_INTERACTIVE_START)
    intent.removeExtra(DpiShortcutActivity.EXTRA_INTERACTIVE_START)
    return token?.takeIf { DpiSession.isInteractiveStartCurrent(it) }
}

/**
 * External automation for the ByeByeDPI module requires visible confirmation,
 * mirroring [ProxyShortcutActivity]. The VPN mode never captures the slot
 * silently: [DpiSession.start] re-samples and re-authorizes before establish, so
 * an unprepared or third-party slot is refused rather than taken over.
 */
open class DpiShortcutActivity : ComponentActivity() {
    protected open val requireConfirmation = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val title = when (action) {
            ACTION_START -> R.string.dpi_shortcut_start
            ACTION_STOP -> R.string.dpi_shortcut_stop
            ACTION_TOGGLE -> R.string.dpi_shortcut_toggle
            else -> {
                finishQuietly()
                return
            }
        }
        if (requireConfirmation) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(R.string.shortcut_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> applyAction(action) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finishQuietly() }
                .setOnCancelListener { finishQuietly() }
                .show()
                .getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
        } else {
            applyAction(action)
        }
    }

    private fun applyAction(action: String) {
        if (action == ACTION_STOP) {
            DpiSession.stop(this)
            finishQuietly()
            return
        }
        lifecycleScope.launch {
            DpiSession.awaitRestoration(this@DpiShortcutActivity)
            applyStartOrToggle(action)
        }
    }

    private fun applyStartOrToggle(action: String) {
        val state = DpiSession.state.value
        val active = state is DpiState.Running || state is DpiState.Starting
        when (action) {
            ACTION_START -> if (!active) requestStart()
            ACTION_TOGGLE -> if (active) DpiSession.stop(this) else requestStart()
        }
        finishQuietly()
    }

    private fun requestStart() {
        if (DpiSession.mode.value == DpiMode.VPN) {
            val token = DpiSession.beginInteractiveStart()
            startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_INTERACTIVE_START, token))
        } else DpiSession.start(this)
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        internal const val EXTRA_INTERACTIVE_START = "com.amurcanov.tgwsproxy.dpi.INTERACTIVE_START"
        const val ACTION_START = "com.prokrastinaciya.zaprethub.action.dpi.START"
        const val ACTION_STOP = "com.prokrastinaciya.zaprethub.action.dpi.STOP"
        const val ACTION_TOGGLE = "com.prokrastinaciya.zaprethub.action.dpi.TOGGLE"
    }
}

class DpiLauncherShortcutActivity : DpiShortcutActivity() {
    override val requireConfirmation = false
}
