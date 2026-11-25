package com.amurcanov.tgwsproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.AppReleaseInfo
import com.amurcanov.tgwsproxy.AppUpdateRepository
import com.amurcanov.tgwsproxy.BuildConfig
import com.amurcanov.tgwsproxy.LogEntry
import com.amurcanov.tgwsproxy.LogManager
import com.amurcanov.tgwsproxy.ProxyJournal
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.UPDATE_DIALOG_ACTION_POSTPONED
import com.amurcanov.tgwsproxy.UPDATE_DIALOG_ACTION_RELEASE_OPENED
import com.amurcanov.tgwsproxy.isNewerVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RepositoryUrl = "https://github.com/prokrastinaciya/ZapretHub"
private const val ReleasesUrl = "$RepositoryUrl/releases"
private const val AndroidPortUrl = "https://github.com/Derigava/tg-ws-proxy-android"
private const val IssuesUrl = "$RepositoryUrl/issues"
private const val AndroidBaseUrl = "https://github.com/amurcanov/tg-ws-proxy-android"
private const val CoreUrl = "https://github.com/Flowseal/tg-ws-proxy"
private const val DpiUpstreamUrl = "https://github.com/romanvht/ByeByeDPI"
private const val ProxyReferenceUrl = "https://github.com/Flowseal/tg-ws-proxy/issues/389"

@Composable
fun InfoTab(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showHelpDialog by remember { mutableStateOf(false) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var pendingManualRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val savedPort by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    val savedPoolSize by settingsStore.poolSize.collectAsStateWithLifecycle(initialValue = 4)
    val savedCfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val savedCfDisableTls by settingsStore.cfDisableTls.collectAsStateWithLifecycle(initialValue = false)
    val savedCustomCfDomainEnabled by settingsStore.customCfDomainEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedCustomCfDomain by settingsStore.customCfDomain.collectAsStateWithLifecycle(initialValue = "")
    val savedCfWorkerDomain by settingsStore.cfWorkerDomain.collectAsStateWithLifecycle(initialValue = "")
    val updateLatestVersion by settingsStore.updateLatestVersion.collectAsStateWithLifecycle(initialValue = "")
    val updateLastError by settingsStore.updateLastError.collectAsStateWithLifecycle(initialValue = "")
    val currentLogs by LogManager.logs.collectAsStateWithLifecycle()
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val updateStatusSubtitle = when {
        !BuildConfig.APP_UPDATES_ENABLED -> stringResource(R.string.android_fork_repo_subtitle)
        isCheckingUpdates -> stringResource(R.string.update_checking)
        updateLastError.isNotBlank() -> stringResource(R.string.update_last_check_failed)
        updateLatestVersion.isNotBlank() && isNewerVersion(currentVersion, updateLatestVersion) ->
            stringResource(R.string.update_available_on_github, updateLatestVersion)
        updateLatestVersion.isNotBlank() -> stringResource(R.string.update_latest_version, updateLatestVersion)
        else -> stringResource(R.string.update_check_manually)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        BrandHeader(section = stringResource(R.string.nav_info))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.hero_description),
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.muted
        )
        Spacer(Modifier.height(16.dp))
        KeyValueTable(
            rows = listOf(
                stringResource(R.string.row_version) to BuildConfig.VERSION_NAME,
                stringResource(R.string.row_core) to "Flowseal ${BuildConfig.CORE_VERSION}",
            )
        )

        SectionLabel(stringResource(R.string.actions))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ActionRow(
                title = stringResource(if (BuildConfig.APP_UPDATES_ENABLED) R.string.check_updates else R.string.open_releases),
                subtitle = updateStatusSubtitle,
                icon = R.drawable.ic_dt_deployment,
                onClick = {
                    if (!BuildConfig.APP_UPDATES_ENABLED) {
                        openUrlInBrowser(context, ReleasesUrl)
                        return@ActionRow
                    }
                    if (isCheckingUpdates) return@ActionRow
                    isCheckingUpdates = true
                    scope.launch {
                        val checkedAt = System.currentTimeMillis()
                        val release = AppUpdateRepository.check(context, force = true)
                        isCheckingUpdates = false

                        if (release == null) {
                            val message = if (updateLatestVersion.isNotBlank()) {
                                context.getString(R.string.update_check_failed_known, updateLatestVersion)
                            } else {
                                context.getString(R.string.update_check_failed)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        if (release.versionCode > BuildConfig.VERSION_CODE) {
                            settingsStore.saveUpdateDialogShown(release.versionTag, checkedAt)
                            pendingManualRelease = release
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.update_already_latest, release.versionTag),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
            ActionRow(
                title = stringResource(R.string.help),
                subtitle = stringResource(R.string.help_subtitle),
                icon = R.drawable.ic_dt_documentation,
                onClick = { showHelpDialog = true }
            )
            ActionRow(
                title = stringResource(R.string.build_report),
                subtitle = stringResource(R.string.report_subtitle),
                icon = R.drawable.ic_dt_diagnostics,
                onClick = {
                    scope.launch {
                        // Built on demand: the journal can be a megabyte, too much for every recomposition.
                        val journal = withContext(Dispatchers.IO) { ProxyJournal.recentProblems(context) }
                        val reportText = buildSupportReport(
                            port = savedPort,
                            poolSize = savedPoolSize,
                            cfEnabled = savedCfEnabled,
                            cfDisableTls = savedCfDisableTls,
                            customCfDomainEnabled = savedCustomCfDomainEnabled,
                            customCfDomain = savedCustomCfDomain,
                            cfWorkerDomain = savedCfWorkerDomain,
                            logs = currentLogs,
                            journal = journal,
                            context = context
                        )
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Zapret Hub", reportText))
                        Toast.makeText(context, context.getString(R.string.report_copied), Toast.LENGTH_SHORT).show()
                    }
                }
            )
            ActionRow(
                title = stringResource(R.string.share_log),
                subtitle = stringResource(R.string.share_log_subtitle),
                icon = R.drawable.ic_dt_monitor,
                onClick = {
                    scope.launch {
                        val shared = withContext(Dispatchers.IO) { runCatching { ProxyJournal.share(context) }.getOrDefault(false) }
                        if (!shared) Toast.makeText(context, context.getString(R.string.share_log_empty), Toast.LENGTH_SHORT).show()
                    }
                }
            )
            ActionRow(
                title = stringResource(R.string.raise_issue),
                subtitle = stringResource(R.string.open_github_issue),
                icon = R.drawable.ic_dt_debug,
                onClick = { openUrlInBrowser(context, IssuesUrl) }
            )
        }

        SectionLabel(stringResource(R.string.about_project))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ActionRow(
                title = stringResource(R.string.android_fork_repo),
                subtitle = stringResource(R.string.android_fork_repo_subtitle),
                icon = R.drawable.ic_dt_version_control,
                onClick = { openUrlInBrowser(context, RepositoryUrl) }
            )
            ActionRow(
                title = stringResource(R.string.android_port_upstream),
                subtitle = "Derigava/tg-ws-proxy-android",
                icon = R.drawable.ic_dt_integration,
                onClick = { openUrlInBrowser(context, AndroidPortUrl) }
            )
            ActionRow(
                title = stringResource(R.string.original_tg_ws_proxy),
                subtitle = stringResource(R.string.original_tg_ws_proxy_subtitle),
                icon = R.drawable.ic_dt_server,
                onClick = { openUrlInBrowser(context, CoreUrl) }
            )
            ActionRow(
                title = stringResource(R.string.android_author),
                subtitle = stringResource(R.string.android_author_subtitle),
                icon = R.drawable.ic_dt_code,
                onClick = { openUrlInBrowser(context, AndroidBaseUrl) }
            )
            ActionRow(
                title = stringResource(R.string.dpi_upstream),
                subtitle = stringResource(R.string.dpi_upstream_subtitle),
                icon = R.drawable.ic_dt_integration,
                onClick = { openUrlInBrowser(context, DpiUpstreamUrl) }
            )
            ActionRow(
                title = stringResource(R.string.useful_material),
                subtitle = stringResource(R.string.useful_material_subtitle),
                icon = R.drawable.ic_dt_network,
                onClick = { openUrlInBrowser(context, ProxyReferenceUrl) }
            )
        }

        Spacer(Modifier.height(16.dp))
        MonoLabel(stringResource(R.string.unofficial_notice))
    }

    pendingManualRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            onPostpone = {
                pendingManualRelease = null
                Toast.makeText(context, context.getString(R.string.update_postponed_24h), Toast.LENGTH_SHORT).show()
                scope.launch {
                    val now = System.currentTimeMillis()
                    settingsStore.saveUpdatePostpone(
                        version = release.versionTag,
                        until = now + 24L * 60L * 60L * 1000L
                    )
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_POSTPONED,
                        actedAt = now
                    )
                }
            },
            onOpenRelease = {
                if (openUrlInBrowser(context, release.releaseUrl)) {
                    pendingManualRelease = null
                    scope.launch {
                        settingsStore.saveUpdateDialogAction(
                            version = release.versionTag,
                            action = UPDATE_DIALOG_ACTION_RELEASE_OPENED,
                            actedAt = System.currentTimeMillis()
                        )
                    }
                } else {
                    Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showHelpDialog) {
        BrandDialog(
            onDismiss = { showHelpDialog = false },
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
        ) {
            Text(
                stringResource(R.string.help),
                style = MaterialTheme.typography.headlineSmall,
                color = Brand.ink
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                HelpSection(stringResource(R.string.help_auto_dc_title), stringResource(R.string.help_auto_dc_text))
                HelpSection("CloudFlare CDN", stringResource(R.string.help_cloudflare_text))
                HelpSection(stringResource(R.string.ws_pool), stringResource(R.string.help_ws_pool_text))
                HelpSection(stringResource(R.string.secret_key), stringResource(R.string.help_secret_key_text))
                HelpSection(stringResource(R.string.experimental_mode), stringResource(R.string.help_experimental_text))
                HelpSection(stringResource(R.string.autostart), stringResource(R.string.help_autostart_text))
                HelpSection(stringResource(R.string.help_slow_connect_title), stringResource(R.string.help_slow_connect_text))
            }
            Spacer(Modifier.height(16.dp))
            BrandButton(text = stringResource(R.string.understood), onClick = { showHelpDialog = false })
        }
    }
}

@Composable
private fun HelpSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoLabel("> ${title.uppercase()}", color = Brand.accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.muted)
    }
}

private fun buildSupportReport(
    port: String,
    poolSize: Int,
    cfEnabled: Boolean,
    cfDisableTls: Boolean,
    customCfDomainEnabled: Boolean,
    customCfDomain: String,
    cfWorkerDomain: String,
    logs: List<LogEntry>,
    journal: List<ProxyJournal.Problem>?,
    context: Context
): String {
    val androidVersion = Build.VERSION.RELEASE ?: "?"
    val sdkInt = Build.VERSION.SDK_INT
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" }
    val supportedAbis = Build.SUPPORTED_ABIS.joinToString().ifBlank { "unknown" }
    val supported32Abis = Build.SUPPORTED_32_BIT_ABIS.joinToString().ifBlank { "none" }
    val supported64Abis = Build.SUPPORTED_64_BIT_ABIS.joinToString().ifBlank { "none" }
    val manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" }
    val brand = Build.BRAND.orEmpty().ifBlank { "unknown" }
    val model = Build.MODEL.orEmpty().ifBlank { "unknown" }
    val device = Build.DEVICE.orEmpty().ifBlank { "unknown" }
    val product = Build.PRODUCT.orEmpty().ifBlank { "unknown" }
    val hardware = Build.HARDWARE.orEmpty().ifBlank { "unknown" }
    val board = Build.BOARD.orEmpty().ifBlank { "unknown" }
    val romDisplay = Build.DISPLAY.orEmpty().ifBlank { "unknown" }
    val buildId = Build.ID.orEmpty().ifBlank { "unknown" }
    val buildFingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "unknown" }
    val buildType = Build.TYPE.orEmpty().ifBlank { "unknown" }
    val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MANUFACTURER.orEmpty().ifBlank { "unknown" }
    } else {
        "n/a"
    }
    val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.orEmpty().ifBlank { "unknown" }
    } else {
        "n/a"
    }
    val mode = if (cfEnabled) "Cloudflare" else context.getString(R.string.report_mode_direct)
    val cfDomainLine = if (cfEnabled && customCfDomainEnabled && customCfDomain.isNotBlank()) {
        "\n${context.getString(R.string.report_cf_domain, customCfDomain.trim())}"
    } else {
        ""
    }
    // Count only: the report goes to a public issue, and a Worker URL spends the user's quota.
    val workerCount = cfWorkerDomain.split(',', ';', ' ').count { it.isNotBlank() }
    val cfWorkerLine = if (workerCount > 0) "\n${context.getString(R.string.report_cf_worker, workerCount)}" else ""

    val recentErrors = logs
        .asReversed()
        .filter { it.priority >= 5 }
        .take(10)

    val errorsBlock = if (journal != null) {
        // The journal also covers hours when the app was closed; the in-memory view does not.
        if (journal.isEmpty()) context.getString(R.string.none_lower)
        else journal.joinToString("\n") { row ->
            "- ${row.lastSeen} [${row.level}] ${row.message}${if (row.count > 1) " (x${row.count})" else ""}"
        }
    } else if (recentErrors.isEmpty()) {
        context.getString(R.string.none_lower)
    } else {
        recentErrors.joinToString("\n") { entry ->
            val level = when (entry.priority) {
                6 -> "ERROR"
                5 -> "WARN"
                else -> "INFO"
            }
            "- [$level] ${entry.message}${if (entry.count > 1) " (x${entry.count})" else ""}"
        }
    }

    return buildString {
        appendLine(context.getString(R.string.report_app_version, BuildConfig.VERSION_NAME))
        appendLine(context.getString(R.string.report_android, androidVersion, sdkInt))
        appendLine(context.getString(R.string.report_device, manufacturer, brand, model))
        appendLine(context.getString(R.string.report_device_code, device))
        appendLine(context.getString(R.string.report_product, product))
        appendLine("ABI: $primaryAbi")
        appendLine(context.getString(R.string.report_all_abi, supportedAbis))
        appendLine("32-bit ABI: $supported32Abis")
        appendLine("64-bit ABI: $supported64Abis")
        appendLine("SoC: $socManufacturer / $socModel")
        appendLine("Hardware: $hardware")
        appendLine("Board: $board")
        appendLine("ROM: $romDisplay")
        appendLine("Build ID: $buildId")
        appendLine("Build type: $buildType")
        appendLine("Fingerprint: $buildFingerprint")
        appendLine(context.getString(R.string.report_settings))
        appendLine(context.getString(R.string.report_mode, mode))
        if (cfDisableTls) appendLine(context.getString(R.string.report_cf_disable_tls))
        appendLine(context.getString(R.string.report_ws_pool, poolSize))
        append(context.getString(R.string.report_port, port.trim().ifBlank { "1443" }))
        append(cfDomainLine)
        append(cfWorkerLine)
        appendLine()
        appendLine()
        appendLine(context.getString(if (journal != null) R.string.report_journal_errors else R.string.report_recent_errors))
        append(errorsBlock)
    }.trim()
}
