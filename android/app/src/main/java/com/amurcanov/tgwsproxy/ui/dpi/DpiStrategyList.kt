package com.amurcanov.tgwsproxy.ui.dpi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiCommand
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.MonoAction
import com.amurcanov.tgwsproxy.ui.MonoLabel
import com.amurcanov.tgwsproxy.ui.SectionLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Strategy history list (parity C-03/C-04): apply, pin/unpin, delete and clear
 * unpinned. Applying switches to command mode, records history and restarts the
 * running module through [DpiSession].
 */
@Composable
internal fun DpiStrategyList() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val commands = remember(revision) { store.history() }

    fun refresh() { revision++ }

    fun apply(command: DpiCommand) {
        store.applyStrategy(command.text)
        val wasRunning = DpiSession.state.value is com.amurcanov.tgwsproxy.dpi.DpiState.Running ||
            DpiSession.state.value is com.amurcanov.tgwsproxy.dpi.DpiState.Starting
        if (wasRunning) {
            DpiSession.stop(context)
            scope.launch {
                DpiSession.state.first { it is com.amurcanov.tgwsproxy.dpi.DpiState.Off || it is com.amurcanov.tgwsproxy.dpi.DpiState.Failed }
                DpiSession.start(context)
            }
        }
        refresh()
    }

    SectionLabel(stringResource(R.string.dpi_strategy_history))
    if (commands.isEmpty()) {
        MonoLabel(stringResource(R.string.dpi_strategy_history_empty), Modifier.padding(vertical = 4.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        commands.forEach { command ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = command.name ?: command.text,
                        color = com.amurcanov.tgwsproxy.ui.Brand.ink,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                    if (command.name != null) {
                        MonoLabel(command.text)
                    }
                }
                MonoAction(
                    text = stringResource(R.string.dpi_strategy_apply),
                    onClick = { apply(command) },
                )
                MonoAction(
                    text = stringResource(if (command.pinned) R.string.dpi_strategy_unpin else R.string.dpi_strategy_pin),
                    onClick = { if (command.pinned) store.unpinCommand(command.text) else store.pinCommand(command.text); refresh() },
                )
                MonoAction(
                    text = stringResource(R.string.dpi_strategy_delete),
                    onClick = { store.deleteCommand(command.text); refresh() },
                )
            }
        }
    }
    MonoAction(
        text = stringResource(R.string.dpi_strategy_clear_unpinned),
        onClick = { store.clearUnpinnedHistory(); refresh() },
    )
}
