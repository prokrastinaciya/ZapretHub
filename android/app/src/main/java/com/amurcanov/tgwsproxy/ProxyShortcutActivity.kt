package com.amurcanov.tgwsproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * External automation requests require confirmation. Android launches the
 * private launcher shortcut activity on behalf of the app.
 */
open class ProxyShortcutActivity : ComponentActivity() {
    protected open val requireConfirmation = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val title = when (action) {
            ACTION_START -> R.string.shortcut_start
            ACTION_STOP -> R.string.shortcut_stop
            ACTION_TOGGLE -> R.string.shortcut_toggle
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
        lifecycleScope.launch {
            val running = ProxyService.isRunning.value || ProxyService.isStarting.value
            when (action) {
                ACTION_START -> if (!running) start()
                ACTION_STOP -> if (running) ProxyController.stop(this@ProxyShortcutActivity)
                ACTION_TOGGLE -> if (running) ProxyController.stop(this@ProxyShortcutActivity) else start()
            }
            finishQuietly()
        }
    }

    private suspend fun start() {
        ProxyController.startFromSavedSettings(this, showInvalidPortToast = true)
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
        const val ACTION_START = "com.prokrastinaciya.zaprethub.action.START"
        const val ACTION_STOP = "com.prokrastinaciya.zaprethub.action.STOP"
        const val ACTION_TOGGLE = "com.prokrastinaciya.zaprethub.action.TOGGLE"
    }
}

class LauncherShortcutActivity : ProxyShortcutActivity() {
    override val requireConfirmation = false
}
