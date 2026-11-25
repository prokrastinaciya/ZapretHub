package com.amurcanov.tgwsproxy.ui.dpi

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.BrandSegmented
import com.amurcanov.tgwsproxy.ui.SectionLabel
import com.amurcanov.tgwsproxy.ui.SwitchRow

private data class LaunchableApp(val packageName: String, val label: String)

private fun installedLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            LaunchableApp(pkg, info.loadLabel(pm)?.toString() ?: pkg)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

/**
 * VPN application filter (parity S-07/S-08). Lists launchable apps via the
 * Play-Protect-compliant `<queries>` launcher intent (no QUERY_ALL_PACKAGES) and
 * persists the selection through [DpiSettingsStore.setAppList].
 */
@Composable
internal fun DpiAppSelector() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val document = rememberDpiDocument(store)
    var saveError by remember(document) { mutableStateOf(false) }
    val apps = remember(context) { installedLaunchableApps(context) }
    var mode by remember(document) { mutableStateOf(store.connectionOptions().routing.appListMode.name.lowercase().let { if (it == "all_apps") "disable" else it }) }
    var selected by remember(document) {
        mutableStateOf(store.connectionOptions().routing.apps.toSet())
    }

    fun persist() {
        saveError = runCatching { store.setAppList(mode, selected.toList()) }.isFailure
    }

    if (saveError) Text(stringResource(R.string.dpi_edit_unsaved))
    SectionLabel(stringResource(R.string.dpi_app_filter))
    BrandSegmented(
        options = listOf("disable", "blacklist", "whitelist").map { it to stringResource(when (it) {
            "blacklist" -> R.string.dpi_app_filter_blacklist
            "whitelist" -> R.string.dpi_app_filter_whitelist
            else -> R.string.dpi_app_filter_disable
        }) },
        selected = mode,
        onSelect = { mode = it; persist() },
    )
    if (mode != "disable" && apps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            apps.forEach { app ->
                SwitchRow(
                    title = app.label,
                    checked = app.packageName in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + app.packageName else selected - app.packageName
                        persist()
                    },
                )
            }
        }
    }
}
