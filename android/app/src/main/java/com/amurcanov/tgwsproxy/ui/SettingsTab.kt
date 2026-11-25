package com.amurcanov.tgwsproxy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.ProxyService
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyTestDialog
import kotlinx.coroutines.launch

@Composable
fun SettingsTab(settingsStore: SettingsStore) {
    val scope = rememberCoroutineScope()
    val port80 by settingsStore.cfDisableTls.collectAsStateWithLifecycle(initialValue = false)
    val tgBoot by settingsStore.autoStartOnBoot.collectAsStateWithLifecycle(initialValue = false)
    val dpiBoot by settingsStore.dpiAutoStartOnBoot.collectAsStateWithLifecycle(initialValue = false)
    val running by ProxyService.isRunning.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var repair by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = advanced) { advanced = false }
    if (advanced) {
        AdvancedSettingsTab(settingsStore) { advanced = false }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            BrandHeader(section = stringResource(R.string.settings))
            Spacer(Modifier.height(8.dp))
            SettingsBasicsContent(port80, tgBoot, dpiBoot, !running,
                { value -> scope.launch { settingsStore.saveCfDisableTls(value) } },
                { value -> scope.launch { settingsStore.saveAutoStartOnBoot(value) } },
                { value -> scope.launch { settingsStore.saveDpiAutoStartOnBoot(value) } },
                { repair = true }, { picker = true }, { advanced = true })
        }
    }
    if (repair) DpiStrategyTestDialog(onDismiss = { repair = false })
    if (picker) StrategyPicker(onDismiss = { picker = false })
}

@Composable
internal fun SettingsBasicsContent(
    port80: Boolean, tgBoot: Boolean, dpiBoot: Boolean, portEnabled: Boolean,
    onPort: (Boolean) -> Unit, onTgBoot: (Boolean) -> Unit, onDpiBoot: (Boolean) -> Unit,
    onRepair: () -> Unit, onPicker: () -> Unit, onAdvanced: () -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_v3_basic))
    BrandOutlineButton(stringResource(R.string.settings_v3_pick), onRepair)
    Spacer(Modifier.height(8.dp))
    BrandOutlineButton(stringResource(R.string.settings_v3_change), onPicker)
    Spacer(Modifier.height(8.dp))
    SwitchRow(stringResource(R.string.settings_v3_port), port80, onPort, enabled = portEnabled)
    Text(stringResource(R.string.settings_v3_port_hint), style = MaterialTheme.typography.bodySmall, color = Brand.muted)
    SwitchRow(stringResource(R.string.settings_v3_boot_tg), tgBoot, onTgBoot)
    SwitchRow(stringResource(R.string.settings_v3_boot_dpi), dpiBoot, onDpiBoot)
    Spacer(Modifier.height(16.dp))
    BrandOutlineButton(stringResource(R.string.settings_v3_advanced), onAdvanced)
}

@Composable
private fun StrategyPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val commands = remember { (store.successfulStrategies() + store.history().map { it.text }).distinct() }
    BrandDialog(onDismiss = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_v3_change), style = MaterialTheme.typography.titleLarge, color = Brand.ink)
        if (commands.isEmpty()) Text(stringResource(R.string.settings_v3_picker_empty), color = Brand.muted)
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            commands.forEach { command ->
                BrandOutlineButton(command, onClick = { DpiSession.applyStrategy(context, command); onDismiss() })
            }
        }
        BrandOutlineButton(stringResource(android.R.string.cancel), onDismiss)
    }
}
