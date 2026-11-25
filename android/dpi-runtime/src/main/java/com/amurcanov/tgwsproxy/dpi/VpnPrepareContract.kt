package com.amurcanov.tgwsproxy.dpi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContract

/** Launches the Android consent dialog if permission is still required. */
class VpnPrepareContract : ActivityResultContract<Unit, Boolean>() {
    override fun getSynchronousResult(context: Context, input: Unit): SynchronousResult<Boolean>? =
        if (VpnService.prepare(context) == null) SynchronousResult(true) else null

    override fun createIntent(context: Context, input: Unit): Intent =
        VpnService.prepare(context)
            ?: error("VPN is already prepared; no consent dialog is needed")

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}
