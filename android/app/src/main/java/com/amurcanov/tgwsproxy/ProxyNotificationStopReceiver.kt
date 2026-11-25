package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Serializes notification stops with starts that are still reading settings. */
class ProxyNotificationStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ProxyService.ACTION_STOP) ProxyController.stop(context)
    }
}
