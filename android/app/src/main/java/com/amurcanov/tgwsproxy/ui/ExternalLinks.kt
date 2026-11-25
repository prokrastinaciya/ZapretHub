package com.amurcanov.tgwsproxy.ui

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

fun openUrlInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
