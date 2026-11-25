package com.amurcanov.tgwsproxy.ui.dpi

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.ProxyService
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.ServiceSelectionStore
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.dpi.DpiStrategyTestService
import com.amurcanov.tgwsproxy.dpi.dpiTargetsFor
import com.amurcanov.tgwsproxy.dpi.rankDpiStrategies
import com.amurcanov.tgwsproxy.ui.Brand
import com.amurcanov.tgwsproxy.ui.BrandButton
import com.amurcanov.tgwsproxy.ui.BrandDialog
import com.amurcanov.tgwsproxy.ui.BrandLink
import com.amurcanov.tgwsproxy.ui.BrandOutlineButton
import com.amurcanov.tgwsproxy.ui.MonoLabel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

@Composable
internal fun DpiStrategyTestDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val settings = remember(context) { SettingsStore(context) }
    val selectionStore = remember(context) { ServiceSelectionStore(context) }
    val selection by selectionStore.changes.collectAsStateWithLifecycle(initialValue = selectionStore.read())
    val test by DpiStrategyTestService.state.collectAsStateWithLifecycle()
    val dpiState by DpiSession.state.collectAsStateWithLifecycle()
    val cfEnabled by remember(settings) { settings.cfproxyEnabled.map<Boolean, Boolean?> { it } }.collectAsStateWithLifecycle(initialValue = null)
    val worker by remember(settings) { settings.cfWorkerDomain.map<String, String?> { it } }.collectAsStateWithLifecycle(initialValue = null)
    val port80 by remember(settings) { settings.cfDisableTls.map<Boolean, Boolean?> { it } }.collectAsStateWithLifecycle(initialValue = null)
    val proxyRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val proxyStarting by ProxyService.isStarting.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by remember(selection) { mutableIntStateOf(if (selection.needsDpi) 1 else 0) }
    var started by remember(selection) { mutableStateOf(test.running) }
    var error by remember { mutableStateOf(false) }
    var portApplied by remember(selection) { mutableStateOf(false) }
    var dpiAppliedVerified by remember(selection) { mutableStateOf(false) }
    val targets = dpiTargetsFor(selection)
    val results = if (started) test.results.filter { it.targets == targets } else emptyList()
    val verified = verifiedDpiResult(selection, results)
    val configured = cfEnabled == true || !worker.isNullOrBlank()
    val settingsLoaded = cfEnabled != null && worker != null && port80 != null
    LaunchedEffect(step, settingsLoaded, configured, selection) {
        if (step == 0 && settingsLoaded) step = if (selection.telegram && configured) 2 else 3
    }
    val canOfferPort = settingsLoaded && showTelegramFallback(selection, configured, port80 == true, proxyRunning || proxyStarting)

    fun apply(command: String) {
        try {
            store.applyStrategy(command)
            dpiAppliedVerified = verified?.candidate?.command == command
            if (dpiAppliedVerified) {
                if (selection.telegram) step = 0 else onDismiss()
            } else {
                step = if (selection.telegram) 0 else 3
            }
        } catch (_: Exception) { error = true }
    }
    fun next() { step = if (selection.telegram) 0 else 3 }

    BrandDialog(onDismiss = onDismiss, modifier = Modifier.fillMaxWidth(0.92f)) {
        Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.repair_wizard_title), style = MaterialTheme.typography.titleLarge, color = Brand.ink)
            when (step) {
                0 -> Unit
                1 -> {
                    MonoLabel(stringResource(R.string.repair_step_strategy))
                    Text(stringResource(R.string.repair_probe_description), color = Brand.muted)
                    if (selection.telegram) MonoLabel(stringResource(R.string.repair_mixed_notice))
                    if (dpiState != DpiState.Off && !test.running) MonoLabel(stringResource(R.string.dpi_strategy_test_busy))
                    if (dpiState is DpiState.Failed && !test.running) BrandOutlineButton(text = stringResource(R.string.dpi_action_stop), onClick = { DpiSession.stop(context) })
                    if (started && test.total > 0) MonoLabel(stringResource(R.string.dpi_strategy_test_progress, test.results.size, test.total))
                    if (started && test.message != 0) MonoLabel(stringResource(test.message))
                    if (error) MonoLabel(stringResource(R.string.dpi_strategy_test_failed))
                    if (test.running) {
                        BrandOutlineButton(text = stringResource(R.string.dpi_strategy_test_stop), onClick = { DpiStrategyTestService.stop(context) })
                    } else {
                        BrandButton(text = stringResource(R.string.dpi_strategy_test_start), enabled = dpiState == DpiState.Off, onClick = { error = false; started = true; DpiStrategyTestService.start(context) })
                        if (verified != null) MonoLabel(stringResource(R.string.repair_dpi_verified))
                        rankDpiStrategies(results).take(5).forEach { result ->
                            MonoLabel(stringResource(R.string.dpi_strategy_test_score, result.reachable, result.targets.size))
                            val missing = result.targets.filter { it !in result.reached }
                            if (missing.isNotEmpty()) MonoLabel(stringResource(R.string.dpi_strategy_test_unreachable, missing.joinToString(", ") { it.host }))
                            Text(result.candidate.command, style = MaterialTheme.typography.bodySmall, color = Brand.ink)
                            BrandLink(text = stringResource(R.string.dpi_strategy_apply), onClick = { apply(result.candidate.command) })
                        }
                        if (verified == null) BrandLink(text = stringResource(R.string.repair_continue), onClick = { next() })
                    }
                }
                2 -> {
                    MonoLabel(stringResource(R.string.repair_step_telegram))
                    Text(stringResource(R.string.repair_telegram_explain), color = Brand.muted)
                    if (!configured) MonoLabel(stringResource(R.string.repair_no_cf))
                    if (proxyRunning || proxyStarting) MonoLabel(stringResource(R.string.repair_stop_proxy))
                    if (port80 == true || portApplied) MonoLabel(stringResource(R.string.repair_port_enabled))
                    if (error) MonoLabel(stringResource(R.string.dpi_strategy_test_failed))
                    if (canOfferPort && !portApplied) BrandButton(text = stringResource(R.string.repair_enable_port), onClick = {
                        scope.launch {
                            try { settings.saveCfDisableTls(true); portApplied = true; error = false } catch (_: Exception) { error = true }
                        }
                    })
                    if (!selection.needsDpi || dpiAppliedVerified) {
                        BrandLink(text = stringResource(R.string.repair_telegram_works), onClick = onDismiss)
                    }
                    BrandLink(text = stringResource(R.string.repair_continue), onClick = { step = 3 })
                }
                else -> {
                    MonoLabel(stringResource(R.string.repair_step_share))
                    Text(stringResource(R.string.repair_share_explain), color = Brand.muted)
                    if (error) MonoLabel(stringResource(R.string.dpi_strategy_test_failed))
                    BrandButton(text = stringResource(R.string.repair_share), onClick = {
                        // Strict allowlist: never include commands, addresses, domains, logs or secrets.
                        val report = buildString {
                            appendLine("Repair diagnostics (redacted)")
                            appendLine("Telegram selected: ${selection.telegram}")
                            appendLine("YouTube selected: ${selection.youtube}")
                            appendLine("Discord selected: ${selection.discord}")
                            appendLine("Strategies tested: ${if (started) results.size else 0}")
                            appendLine("Selected HTTPS targets: ${targets.size}")
                            appendLine("Best targets reached: ${results.maxOfOrNull { it.reachable } ?: 0}")
                            appendLine("Telegram CF/Worker configured: $configured")
                            appendLine("Telegram port 80 enabled: $port80")
                        }
                        try {
                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.repair_share)))
                        } catch (_: Exception) { error = true }
                    })
                }
            }
            BrandLink(text = stringResource(R.string.dpi_strategy_test_close), onClick = onDismiss)
        }
    }
}
