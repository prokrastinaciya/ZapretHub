package com.amurcanov.tgwsproxy.ui.dpi

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
import com.amurcanov.tgwsproxy.ui.BrandMultilineField
import com.amurcanov.tgwsproxy.ui.SectionLabel
import com.amurcanov.tgwsproxy.ui.SwitchRow

/**
 * Command-line mode editor (parity rows S-11, C-01..C-02). The enable switch
 * chooses between the raw command string and the standard UI editor; editing the
 * raw string persists through [DpiSettingsStore.updateSettings].
 */
@Composable
internal fun DpiCommandEditor() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val document = rememberDpiDocument(store)
    var saveError by remember(document) { mutableStateOf(false) }

    var enabled by remember(document) {
        mutableStateOf(((store.currentSettings()["byedpi_enable_cmd_settings"] as? Boolean) ?: true))
    }
    var args by remember(document) { mutableStateOf((store.currentSettings()["byedpi_cmd_args"] as? String) ?: "") }

    if (saveError) Text(stringResource(R.string.dpi_edit_unsaved))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SwitchRow(
            title = stringResource(R.string.dpi_cmd_use_command_line),
            checked = enabled,
            onCheckedChange = { value ->
                enabled = value
                saveError = runCatching { store.updateSettings(mapOf("byedpi_enable_cmd_settings" to value)) }.isFailure
            },
        )
        if (enabled) {
            SectionLabel(stringResource(R.string.dpi_cmd_args))
            BrandMultilineField(
                value = args,
                onValueChange = { value ->
                    args = value
                    saveError = runCatching { store.updateSettings(mapOf("byedpi_cmd_args" to value)) }.isFailure
                },
                placeholder = stringResource(R.string.dpi_cmd_args_hint),
            )
        }
    }
}
