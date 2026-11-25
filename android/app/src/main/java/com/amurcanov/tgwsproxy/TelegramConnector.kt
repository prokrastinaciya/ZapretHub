package com.amurcanov.tgwsproxy

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.security.MessageDigest

/** Uses Android URI handlers so site, Play Store and third-party clients all work. */
object TelegramConnector {
    fun localHost(host: String): String = when (host.trim()) {
        "0.0.0.0", "" -> "127.0.0.1"
        "::", "[::]" -> "::1"
        else -> host.trim()
    }

    fun proxyUri(connection: ActiveProxyConnection): Uri = Uri.Builder()
        .scheme("tg").authority("proxy")
        .appendQueryParameter("server", localHost(connection.host))
        .appendQueryParameter("port", connection.port.toString())
        .appendQueryParameter("secret", connection.secret)
        .build()

    fun open(context: Context, connection: ActiveProxyConnection, chooseAgain: Boolean = false) {
        val uri = proxyUri(connection)
        val base = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val clients = pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.enabled }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (clients.isEmpty()) {
            Toast.makeText(context, R.string.telegram_not_found, Toast.LENGTH_LONG).show()
            return
        }
        val preferences = context.getSharedPreferences("telegram_client", Context.MODE_PRIVATE)
        val savedPackage = preferences.getString("package", null)
        val savedSigner = preferences.getString("signer", null)
        val remembered = clients.firstOrNull { it.activityInfo.packageName == savedPackage }
        val currentSigner = remembered?.let { signer(pm, it.activityInfo.packageName) }

        fun launch(packageName: String, signer: String?) {
            try {
                context.startActivity(Intent(base).setPackage(packageName))
                preferences.edit().putString("package", packageName).putString("signer", signer).apply()
            } catch (_: ActivityNotFoundException) {
                preferences.edit().remove("package").remove("signer").apply()
                Toast.makeText(context, R.string.telegram_client_unavailable, Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, R.string.telegram_open_failed, Toast.LENGTH_LONG).show()
            }
        }
        if (!chooseAgain && remembered != null && currentSigner != null && currentSigner == savedSigner) {
            launch(remembered.activityInfo.packageName, currentSigner)
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.telegram_choose)
            .setItems(clients.map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.toTypedArray()) { _, index ->
                val packageName = clients[index].activityInfo.packageName
                launch(packageName, signer(pm, packageName))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun signer(pm: PackageManager, packageName: String): String? {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = try {
            pm.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        if (signatures.isNullOrEmpty()) return null
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }
}
