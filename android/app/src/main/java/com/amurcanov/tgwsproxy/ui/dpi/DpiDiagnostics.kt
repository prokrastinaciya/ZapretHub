package com.amurcanov.tgwsproxy.ui.dpi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.KeyValueTable
import com.amurcanov.tgwsproxy.ui.MonoAction
import com.amurcanov.tgwsproxy.ui.SectionLabel

/**
 * ByeByeDPI diagnostics report (parity G-09): the effective endpoint, mode and
 * engine arguments, with a copy action. Never includes the Telegram secret.
 */
@Composable
internal fun DpiDiagnostics() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    rememberDpiDocument(store)
    val options = runCatching { store.diagnosticsSnapshot() }.getOrNull()
    val copied = stringResource(R.string.copied)

    if (options == null) return
    val endpointLabel = stringResource(R.string.dpi_diag_endpoint)
    val modeLabel = stringResource(R.string.dpi_diag_mode)
    val commandLabel = stringResource(R.string.dpi_cmd_use_command_line)
    val editorLabel = stringResource(R.string.dpi_strategy_editor)
    val ipv6Label = stringResource(R.string.dpi_diag_ipv6)
    val appsLabel = stringResource(R.string.dpi_diag_apps)
    fun lines(snapshot: com.amurcanov.tgwsproxy.dpi.DpiDiagnosticsSnapshot) = listOf(
        endpointLabel to "${snapshot.host}:${snapshot.port}",
        modeLabel to (if (snapshot.commandMode) commandLabel else editorLabel),
        ipv6Label to snapshot.ipv6.toString(),
        appsLabel to snapshot.appFilter.name,
    )

    SectionLabel(stringResource(R.string.dpi_diag_title))
    KeyValueTable(lines(options))
    MonoAction(
        text = stringResource(R.string.copy),
        onClick = {
            val snapshot = runCatching { store.diagnosticsSnapshot() }.getOrNull() ?: return@MonoAction
            val report = lines(snapshot).joinToString("\n") { "${it.first}: ${it.second}" }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ByeByeDPI diagnostics", report))
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        },
    )
}
