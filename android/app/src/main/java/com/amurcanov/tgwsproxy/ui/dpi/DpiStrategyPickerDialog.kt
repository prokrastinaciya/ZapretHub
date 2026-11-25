package com.amurcanov.tgwsproxy.ui.dpi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiCommand
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore

import com.amurcanov.tgwsproxy.ui.Brand
import com.amurcanov.tgwsproxy.ui.BrandDialog
import com.amurcanov.tgwsproxy.ui.BrandOutlineButton
import com.amurcanov.tgwsproxy.ui.MonoLabel


/**
 * "Change strategy" dialog (parity G-05): lists the pinned strategies and applies
 * the chosen one — switching to command mode, recording history and restarting
 * the running module through [DpiSession].
 */
@Composable
internal fun DpiStrategyPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }

    val pinned = remember { store.pinnedHistory() }

    fun apply(command: DpiCommand) {
        DpiSession.applyStrategy(context, command.text)
        onDismiss()
    }

    BrandDialog(onDismiss = onDismiss) {
        Text(
            stringResource(R.string.dpi_change_strategy),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = Brand.ink,
        )
        if (pinned.isEmpty()) {
            MonoLabel(stringResource(R.string.dpi_change_strategy_empty), Modifier.padding(vertical = 12.dp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                pinned.forEach { command ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(command.name ?: command.text, color = Brand.ink)
                            if (command.name != null) MonoLabel(command.text)
                        }
                        BrandOutlineButton(
                            text = stringResource(R.string.dpi_strategy_apply),
                            onClick = { apply(command) },
                        )
                    }
                }
            }
        }
        BrandOutlineButton(stringResource(R.string.vpn_offer_cancel), onClick = onDismiss)
    }
}
