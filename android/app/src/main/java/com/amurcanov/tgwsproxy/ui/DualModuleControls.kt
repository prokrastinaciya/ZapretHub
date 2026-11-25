package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.ServiceSelection

@Composable
internal fun DualModuleControls(
    selection: ServiceSelection,
    onSelectionChange: (ServiceSelection) -> Unit,
    visualState: CursorVisualState,
    status: String,
    powerDescription: String,
    onPower: () -> Unit,
    showVpnWarning: Boolean,
    socksMode: Boolean,
    powerEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Brand.surface,
            border = BorderStroke(1.dp, Brand.line),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = status,
                    color = if (visualState == CursorVisualState.On) Brand.accent else Brand.muted,
                    style = MaterialTheme.typography.titleMedium,
                )
                CursorSwitch(visualState, powerDescription, status, onPower, enabled = powerEnabled)
                if (selection.needsDpi) {
                    Text(
                        text = stringResource(if (socksMode) R.string.home_socks_warning else R.string.home_vpn_selection_warning),
                        color = Brand.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (showVpnWarning) {
                    Text(stringResource(R.string.home_vpn_warning), color = Brand.alert, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SectionLabel(stringResource(R.string.home_services))
        ServiceRow(stringResource(R.string.home_telegram), stringResource(R.string.home_tg_subtitle), selection.telegram) {
            onSelectionChange(selection.copy(telegram = it))
        }
        ServiceRow(stringResource(R.string.home_youtube), stringResource(R.string.home_vpn_subtitle), selection.youtube) {
            onSelectionChange(selection.copy(youtube = it))
        }
        ServiceRow(stringResource(R.string.home_discord), stringResource(R.string.home_vpn_subtitle), selection.discord) {
            onSelectionChange(selection.copy(discord = it))
        }
    }
}

@Composable
private fun ServiceRow(name: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val shape = MaterialTheme.shapes.extraSmall
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .border(1.dp, if (checked) Brand.accent else Brand.line, shape)
                .background(if (checked) Brand.fill else Brand.surface, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = Brand.onFill, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = Brand.ink, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = Brand.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
