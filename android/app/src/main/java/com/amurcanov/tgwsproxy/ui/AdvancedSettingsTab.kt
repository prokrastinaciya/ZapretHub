package com.amurcanov.tgwsproxy.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.ProxyService
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.ui.dpi.DpiAppSelector
import com.amurcanov.tgwsproxy.ui.dpi.DpiBackupActions
import com.amurcanov.tgwsproxy.ui.dpi.DpiCommandEditor
import com.amurcanov.tgwsproxy.ui.dpi.DpiDiagnostics
import com.amurcanov.tgwsproxy.ui.dpi.DpiDomainLists
import com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyEditor
import com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun generateRandomSecret(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSettingsTab(settingsStore: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    var showDpiSettings by rememberSaveable { mutableStateOf(false) }
    val dpiMode by DpiSession.mode.collectAsStateWithLifecycle()
    val dpiState by DpiSession.state.collectAsStateWithLifecycle()
    val dpiBoot by settingsStore.dpiAutoStartOnBoot.collectAsStateWithLifecycle(initialValue = false)

    val isReady by settingsStore.isReady.collectAsStateWithLifecycle(initialValue = false)
    val isExperimental by settingsStore.isExperimentalMode.collectAsStateWithLifecycle(initialValue = false)

    val savedIsDcAuto by settingsStore.isDcAuto.collectAsStateWithLifecycle(initialValue = true)
    val savedDc1 by settingsStore.dc1.collectAsStateWithLifecycle(initialValue = "")
    val savedDc2 by settingsStore.dc2.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_DIRECT_DC2_IP)
    val savedDc3 by settingsStore.dc3.collectAsStateWithLifecycle(initialValue = "")
    val savedDc4 by settingsStore.dc4.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_DIRECT_DC4_IP)
    val savedDc5 by settingsStore.dc5.collectAsStateWithLifecycle(initialValue = "")
    val savedDc203 by settingsStore.dc203.collectAsStateWithLifecycle(initialValue = "")
    val savedDc1m by settingsStore.dc1m.collectAsStateWithLifecycle(initialValue = "")
    val savedDc2m by settingsStore.dc2m.collectAsStateWithLifecycle(initialValue = "")
    val savedDc3m by settingsStore.dc3m.collectAsStateWithLifecycle(initialValue = "")
    val savedDc4m by settingsStore.dc4m.collectAsStateWithLifecycle(initialValue = "")
    val savedDc5m by settingsStore.dc5m.collectAsStateWithLifecycle(initialValue = "")
    val savedDc203m by settingsStore.dc203m.collectAsStateWithLifecycle(initialValue = "")
    val savedPort by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    val savedBindIp by settingsStore.bindIp.collectAsStateWithLifecycle(initialValue = "127.0.0.1")
    val savedPoolSize by settingsStore.poolSize.collectAsStateWithLifecycle(initialValue = 4)
    val savedCfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val savedCfDisableTls by settingsStore.cfDisableTls.collectAsStateWithLifecycle(initialValue = false)
    val savedCustomDomainEnabled by settingsStore.customCfDomainEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedCustomDomain by settingsStore.customCfDomain.collectAsStateWithLifecycle(initialValue = "")
    val savedWorkerDomain by settingsStore.cfWorkerDomain.collectAsStateWithLifecycle(initialValue = "")
    val autoStartOnBoot by settingsStore.autoStartOnBoot.collectAsStateWithLifecycle(initialValue = false)
    val savedSecretKey by settingsStore.secretKey.collectAsStateWithLifecycle(initialValue = "LOADING")
    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeModes.DARK)

    if (!isReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = Brand.accent,
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
        return
    }

    var isDcAuto by rememberSaveable(savedIsDcAuto) { mutableStateOf(savedIsDcAuto) }
    var experimentalMode by rememberSaveable(isExperimental) { mutableStateOf(isExperimental) }
    var dc1Text by rememberSaveable(savedDc1) { mutableStateOf(savedDc1) }
    var dc2Text by rememberSaveable(savedDc2) { mutableStateOf(savedDc2) }
    var dc3Text by rememberSaveable(savedDc3) { mutableStateOf(savedDc3) }
    var dc4Text by rememberSaveable(savedDc4) { mutableStateOf(savedDc4) }
    var dc5Text by rememberSaveable(savedDc5) { mutableStateOf(savedDc5) }
    var dc203Text by rememberSaveable(savedDc203) { mutableStateOf(savedDc203) }
    var dc1mText by rememberSaveable(savedDc1m) { mutableStateOf(savedDc1m) }
    var dc2mText by rememberSaveable(savedDc2m) { mutableStateOf(savedDc2m) }
    var dc3mText by rememberSaveable(savedDc3m) { mutableStateOf(savedDc3m) }
    var dc4mText by rememberSaveable(savedDc4m) { mutableStateOf(savedDc4m) }
    var dc5mText by rememberSaveable(savedDc5m) { mutableStateOf(savedDc5m) }
    var dc203mText by rememberSaveable(savedDc203m) { mutableStateOf(savedDc203m) }

    var portText by rememberSaveable(savedPort) { mutableStateOf(savedPort) }
    var bindIpText by rememberSaveable(savedBindIp) { mutableStateOf(savedBindIp) }
    var selectedPoolSize by rememberSaveable(savedPoolSize) { mutableIntStateOf(savedPoolSize) }
    var cfEnabled by rememberSaveable(savedCfEnabled) { mutableStateOf(savedCfEnabled) }
    var cfDisableTls by rememberSaveable(savedCfDisableTls) { mutableStateOf(savedCfDisableTls) }
    var customCfDomainEnabled by rememberSaveable(savedCustomDomainEnabled) { mutableStateOf(savedCustomDomainEnabled) }
    var customCfDomain by rememberSaveable(savedCustomDomain) { mutableStateOf(savedCustomDomain) }
    var cfWorkerDomain by rememberSaveable(savedWorkerDomain) { mutableStateOf(savedWorkerDomain) }
    var secretKeyText by remember(savedSecretKey) { mutableStateOf(if (savedSecretKey == "LOADING") "" else savedSecretKey) }

    LaunchedEffect(savedSecretKey) {
        if (savedSecretKey == "") {
            val generated = generateRandomSecret()
            secretKeyText = generated
            settingsStore.saveSecretKey(generated)
        } else if (savedSecretKey != "LOADING") {
            secretKeyText = savedSecretKey
        }
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            settingsStore.saveAll(
                isDcAuto, dc1Text, dc2Text, dc3Text, dc4Text, dc5Text, dc203Text,
                dc1mText, dc2mText, dc3mText, dc4mText, dc5mText, dc203mText,
                experimentalMode, bindIpText, portText, selectedPoolSize,
                cfEnabled, cfDisableTls, customCfDomainEnabled, customCfDomain, cfWorkerDomain, secretKeyText
            )
        }
    }

    var showIpSetupDialog by rememberSaveable { mutableStateOf(false) }

    if (showIpSetupDialog) {
        IpSetupDialog(
            isExperimental = experimentalMode,
            onExperimentalChange = { experimentalMode = it; scheduleSave() },
            dc1Text = dc1Text, onDc1Change = { dc1Text = it; scheduleSave() },
            dc2Text = dc2Text, onDc2Change = { dc2Text = it; scheduleSave() },
            dc3Text = dc3Text, onDc3Change = { dc3Text = it; scheduleSave() },
            dc4Text = dc4Text, onDc4Change = { dc4Text = it; scheduleSave() },
            dc5Text = dc5Text, onDc5Change = { dc5Text = it; scheduleSave() },
            dc203Text = dc203Text, onDc203Change = { dc203Text = it; scheduleSave() },
            dc1mText = dc1mText, onDc1mChange = { dc1mText = it; scheduleSave() },
            dc2mText = dc2mText, onDc2mChange = { dc2mText = it; scheduleSave() },
            dc3mText = dc3mText, onDc3mChange = { dc3mText = it; scheduleSave() },
            dc4mText = dc4mText, onDc4mChange = { dc4mText = it; scheduleSave() },
            dc5mText = dc5mText, onDc5mChange = { dc5mText = it; scheduleSave() },
            dc203mText = dc203mText, onDc203mChange = { dc203mText = it; scheduleSave() },
            onDismiss = { showIpSetupDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        BrandHeader(section = stringResource(R.string.settings))
        Spacer(Modifier.height(8.dp))
        ScreenTitle(stringResource(R.string.settings_v3_advanced))
        BrandOutlineButton(stringResource(R.string.settings_v3_back), onBack)
        Spacer(Modifier.height(8.dp))
        BrandSegmented(
            options = listOf(false to stringResource(R.string.dpi_logs_source_tg), true to stringResource(R.string.dpi_section)),
            selected = showDpiSettings,
            onSelect = { showDpiSettings = it },
        )

        if (showDpiSettings) {
            SectionLabel(stringResource(R.string.dpi_section))
            BrandSegmented(
                options = listOf(
                    DpiMode.VPN to stringResource(R.string.dpi_mode_vpn),
                    DpiMode.SOCKS5 to stringResource(R.string.dpi_mode_socks5),
                ),
                selected = dpiMode,
                enabled = dpiState is DpiState.Off || dpiState is DpiState.Failed,
                onSelect = { DpiSession.switchMode(context, it) },
            )
            SwitchRow(
                title = stringResource(R.string.dpi_boot_socks5),
                checked = dpiBoot,
                onCheckedChange = { enabled -> scope.launch { settingsStore.saveDpiAutoStartOnBoot(enabled) } },
            )
            Spacer(Modifier.height(12.dp))
            DpiAppSelector()
            Spacer(Modifier.height(12.dp))
            DpiCommandEditor()
            Spacer(Modifier.height(12.dp))
            DpiStrategyList()
            Spacer(Modifier.height(12.dp))
            DpiStrategyEditor()
            Spacer(Modifier.height(12.dp))
            DpiDomainLists()
            Spacer(Modifier.height(12.dp))
            DpiDiagnostics()
            Spacer(Modifier.height(12.dp))
            DpiBackupActions()
        } else {
        SectionLabel(stringResource(R.string.ip_and_port))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BrandField(
                value = bindIpText,
                onValueChange = { newValue ->
                    bindIpText = newValue.filter { it.isDigit() || it == '.' }
                    scheduleSave()
                },
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.weight(1f)
            )
            BrandField(
                value = portText,
                onValueChange = {
                    val filtered = it.filter { char -> char.isDigit() }
                    if (filtered.length <= 5) {
                        portText = filtered
                        scheduleSave()
                    }
                },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.width(92.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        BrandOutlineButton(
            text = stringResource(if (cfEnabled) R.string.auto_cf_enabled else R.string.configure_dc_addresses),
            onClick = { showIpSetupDialog = true },
            enabled = !cfEnabled && !isRunning
        )

        SectionLabel(stringResource(R.string.ws_pool))
        BrandSegmented(
            options = listOf(2, 4, 6).map { it to "$it" },
            selected = selectedPoolSize,
            enabled = !isRunning,
            onSelect = {
                selectedPoolSize = it
                scheduleSave()
            }
        )

        SectionLabel(stringResource(R.string.secret_key))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Brand.surface)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = secretKeyText,
                style = MonoValueStyle.copy(fontSize = 13.sp),
                color = Brand.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            MonoAction(
                text = stringResource(R.string.secret_regenerate),
                enabled = !isRunning,
                onClick = {
                    val newKey = generateRandomSecret()
                    secretKeyText = newKey
                    scope.launch { settingsStore.saveSecretKey(newKey) }
                    scheduleSave()
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        SwitchRow(
            title = stringResource(R.string.settings_v3_cloudflare),
            checked = cfEnabled,
            enabled = !isRunning,
            onCheckedChange = {
                cfEnabled = it
                isDcAuto = it
                scheduleSave()
            }
        )
        SwitchRow(
            title = stringResource(R.string.cf_disable_tls),
            checked = cfDisableTls,
            enabled = !isRunning && (cfEnabled || cfWorkerDomain.isNotBlank()),
            onCheckedChange = { cfDisableTls = it; scheduleSave() }
        )
        Text(
            text = stringResource(R.string.cf_disable_tls_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Brand.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        SectionLabel(stringResource(R.string.own_cloudflare))
        DomainInput(
            label = stringResource(R.string.cf_proxy_domains),
            value = customCfDomain,
            placeholder = "example.com",
            hint = stringResource(if (cfEnabled) R.string.cf_proxy_domains_hint else R.string.cf_proxy_domains_needs_cdn),
            enabled = cfEnabled && !isRunning,
            onValueChange = {
                customCfDomain = it
                customCfDomainEnabled = it.isNotBlank()
                scheduleSave()
            }
        )
        Spacer(Modifier.height(12.dp))
        DomainInput(
            label = stringResource(R.string.cf_worker_domains),
            value = cfWorkerDomain,
            placeholder = "name.account.workers.dev",
            hint = stringResource(R.string.cf_worker_domains_hint),
            enabled = !isRunning,
            onValueChange = {
                cfWorkerDomain = it
                scheduleSave()
            }
        )

        Spacer(Modifier.height(16.dp))
        SwitchRow(
            title = stringResource(R.string.autostart),
            checked = autoStartOnBoot,
            onCheckedChange = { enabled ->
                scope.launch { settingsStore.saveAutoStartOnBoot(enabled) }
            }
        )

        }

        SectionLabel(stringResource(R.string.access_section))
        AccessList(rows = rememberAccessRows(), onOpen = rememberAccessOpener())

        SectionLabel(stringResource(R.string.theme))
        BrandSegmented(
            options = listOf(
                ThemeModes.DARK to stringResource(R.string.theme_dark),
                ThemeModes.LIGHT to stringResource(R.string.theme_light),
                ThemeModes.SYSTEM to stringResource(R.string.theme_system),
            ),
            selected = themeMode,
            onSelect = { mode -> scope.launch { settingsStore.saveThemeMode(mode) } }
        )

        SectionLabel(stringResource(R.string.language))
        LanguageSelector()
    }
}

@Composable
private fun LanguageSelector() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE) }
    val current = remember(context) { prefs.getString("app_language", "ru") ?: "ru" }
    BrandSegmented(
        options = listOf("ru" to "Русский", "en" to "English"),
        selected = current,
        onSelect = { language ->
            if (language != current) {
                prefs.edit().putString("app_language", language).apply()
                (context as? Activity)?.recreate()
            }
        }
    )
}
