package com.amurcanov.tgwsproxy.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.consumeDpiShortcutStart
import com.amurcanov.tgwsproxy.ProxyController
import com.amurcanov.tgwsproxy.ProxyService
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.ServiceSelectionStore
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.TelegramConnector
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.dpi.VpnPrepareContract
import com.amurcanov.tgwsproxy.dpi.VpnRefuseReason
import androidx.compose.runtime.saveable.rememberSaveable
import com.amurcanov.tgwsproxy.dpi.VpnSlotState
import com.amurcanov.tgwsproxy.dpi.VpnReplacement
import com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyPickerDialog
import com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyTestDialog
import com.amurcanov.tgwsproxy.dpi.VpnStartDecision
import kotlinx.coroutines.launch

@Composable
fun ConnectionTab(
    settingsStore: SettingsStore,
    showAccessWarning: Boolean = false,
    onShowAccess: () -> Unit = {},
) {
    val context = LocalContext.current
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val isVerifiedRunning by ProxyService.isVerifiedRunning.collectAsStateWithLifecycle()
    val activeConnection by ProxyService.activeConnection.collectAsStateWithLifecycle()

    val isReady by settingsStore.isReady.collectAsStateWithLifecycle(initialValue = false)

    // Settings
    val savedPort by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    val savedCfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val savedPoolSize by settingsStore.poolSize.collectAsStateWithLifecycle(initialValue = 4)
    val savedSecretKey by settingsStore.secretKey.collectAsStateWithLifecycle(initialValue = "LOADING")

    val scope = rememberCoroutineScope()

    // ByeByeDPI module: the controller is the only source of the running verdict.
    val dpiState by DpiSession.state.collectAsStateWithLifecycle()
    val dpiMode by DpiSession.mode.collectAsStateWithLifecycle()

    if (!isReady || savedSecretKey == "LOADING") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = Brand.accent,
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
        return
    }

    val isStarting by ProxyService.isStarting.collectAsStateWithLifecycle()
    val proxyUrl = activeConnection?.let { TelegramConnector.proxyUri(it).toString() }.orEmpty()
    val dpiIsActive = dpiState is DpiState.Running || dpiState is DpiState.Starting || dpiState is DpiState.Stopping
    val selectionStore = remember(context) { ServiceSelectionStore(context) }
    val selection by selectionStore.changes.collectAsStateWithLifecycle(initialValue = selectionStore.read())
    var foreignVpnVisible by remember { mutableStateOf(false) }
    LaunchedEffect(selection.needsDpi, dpiState, dpiMode) {
        while (selection.needsDpi && dpiMode == DpiMode.VPN) {
            // Passive network observation only: VpnService.prepare can claim the slot.
            foreignVpnVisible = if (dpiState is DpiState.Running && dpiMode == DpiMode.VPN) false else runCatching {
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                @Suppress("DEPRECATION")
                manager?.allNetworks?.any { network -> manager.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true } == true
            }.getOrDefault(false)
            kotlinx.coroutines.delay(2000)
        }
        foreignVpnVisible = false
    }
    val anyActive = isRunning || isStarting || dpiIsActive
    val anySelected = selection.telegram || selection.needsDpi
    val allRunning = anySelected && (!selection.telegram || isVerifiedRunning) && (!selection.needsDpi || dpiState is DpiState.Running) && (!isRunning || selection.telegram) && (!dpiIsActive || selection.needsDpi)
    val visual = when {
        isStarting || (isRunning && !isVerifiedRunning) || dpiState is DpiState.Starting || dpiState is DpiState.Stopping -> CursorVisualState.Connecting
        allRunning -> CursorVisualState.On
        else -> CursorVisualState.Off
    }
    val statusText = when {
        visual == CursorVisualState.Connecting -> stringResource(R.string.status_connecting)
        visual == CursorVisualState.On && selection.needsDpi && dpiMode == DpiMode.SOCKS5 -> stringResource(R.string.home_socks_running)
        visual == CursorVisualState.On -> stringResource(R.string.status_connected)
        anyActive || dpiState is DpiState.Failed -> stringResource(R.string.home_partial)
        !anySelected -> stringResource(R.string.home_select)
        else -> stringResource(R.string.status_disconnected)
    }

    // Immutable replacement proof minted before the offer dialog is shown; reused
    // on confirm, never re-minted against a freshly sampled VPN.
    var pendingOffer by remember { mutableStateOf<Triple<Boolean, VpnReplacement, String>?>(null) }
    var pendingConsentAttempt by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConsentSlot by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConsentNetworks by rememberSaveable { mutableStateOf<LongArray?>(null) }
    var showStrategyPicker by remember { mutableStateOf(false) }
    var showStrategyTest by rememberSaveable { mutableStateOf(false) }

    // Strings resolved once in composition (stringResource is @Composable), then
    // read by the non-composable command lambdas below.
    val msgConsentLost = stringResource(R.string.dpi_failed, "consent lost")
    val msgLockdown = stringResource(R.string.dpi_failed, "always-on VPN or lockdown")
    val msgSlotChanged = stringResource(R.string.dpi_failed, "VPN slot changed")
    val msgUnavailable = stringResource(R.string.dpi_failed, "VPN slot unavailable")
    val msgOfferUncertain = stringResource(R.string.vpn_offer_body_uncertain)
    val msgConsentDenied = stringResource(R.string.vpn_consent_denied)

    val showDpiError: (String) -> Unit = { message ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // Assigned below once the command lambdas exist; the consent callback
    // re-enters startVpn only after the launcher is available.
    lateinit var prepareConsentLauncher: androidx.activity.result.ActivityResultLauncher<Unit>

    val handleRefusal: (VpnRefuseReason, VpnReplacement?, Boolean, String) -> Unit = { reason, proof, canPrompt, attempt ->
        when (reason) {
            VpnRefuseReason.CONSENT_NOT_GRANTED -> if (canPrompt) {
                pendingConsentSlot = proof?.confirmedSlot?.name
                pendingConsentNetworks = proof?.confirmedNetworks?.toLongArray()
                pendingConsentAttempt = attempt
                prepareConsentLauncher.launch(Unit)
            } else showDpiError(msgConsentLost)
            VpnRefuseReason.CONSENT_LOST -> showDpiError(msgConsentLost)
            VpnRefuseReason.LOCKDOWN -> showDpiError(msgLockdown)
            VpnRefuseReason.SLOT_CHANGED -> showDpiError(msgSlotChanged)
            VpnRefuseReason.OBSERVATION_UNAVAILABLE -> showDpiError(msgUnavailable)
        }
    }

    val startVpn: (VpnReplacement?, Boolean, String) -> Unit = start@{ replacement, canPrompt, attempt ->
        if (!DpiSession.isInteractiveStartCurrent(attempt)) return@start
        val authorizer = DpiSession.vpnAuthorizer(context)
        when (val decision = authorizer.authorize(replacement)) {
            is VpnStartDecision.Start -> DpiSession.start(context, replacement)
            is VpnStartDecision.Offer -> {
                // Capture the proof bound to the *currently observed* VPN before
                // showing the offer; a proof is never minted after confirmation.
                val proof = runCatching { authorizer.confirmReplacement() }.getOrNull()
                if (proof == null) {
                    showDpiError(msgOfferUncertain)
                } else {
                    pendingOffer = Triple(decision.ownerUncertain, proof, attempt)
                }
            }
            is VpnStartDecision.Refuse -> handleRefusal(decision.reason, replacement, canPrompt, attempt)
        }
    }

    val startDpi: () -> Unit = {
        if (dpiMode == DpiMode.VPN) startVpn(null, true, DpiSession.beginInteractiveStart()) else DpiSession.start(context)
    }

    prepareConsentLauncher = rememberLauncherForActivityResult(VpnPrepareContract()) { granted ->
        val attempt = pendingConsentAttempt
        val proof = pendingConsentSlot?.let { slot ->
            VpnReplacement(VpnSlotState.valueOf(slot), pendingConsentNetworks?.toSet() ?: emptySet())
        }
        pendingConsentSlot = null
        pendingConsentNetworks = null
        pendingConsentAttempt = null
        if (DpiSession.isInteractiveStartCurrent(attempt)) {
            if (granted) startVpn(proof, false, checkNotNull(attempt)) else {
                DpiSession.cancelInteractiveStart()
                showDpiError(msgConsentDenied)
            }
        }
    }

    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        val attempt = activity?.intent?.let(::consumeDpiShortcutStart)
        if (attempt != null) startVpn(null, true, attempt)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        BrandHeader(section = stringResource(R.string.nav_proxy))
        Spacer(Modifier.height(12.dp))
        ScreenTitle(stringResource(R.string.home_dpi_title))
        DualModuleControls(
            selection = selection,
            onSelectionChange = { selectionStore.save(it) },
            visualState = visual,
            status = statusText,
            powerDescription = stringResource(
                when {
                    anyActive || pendingConsentAttempt != null || pendingOffer != null -> R.string.home_stop
                    selection.telegram && !selection.needsDpi -> R.string.action_start_proxy
                    else -> R.string.home_start
                }
            ),
            powerEnabled = anyActive || anySelected || pendingConsentAttempt != null || pendingOffer != null,
            onPower = {
                val plan = homePowerPlan(selection, isRunning || isStarting, dpiIsActive, pendingConsentAttempt != null || pendingOffer != null)
                if (plan.stopDpi) DpiSession.stop(context)
                if (plan.stopTelegram) ProxyController.stop(context)
                if (plan.stopDpi) pendingOffer = null
                if (plan.startTelegram) scope.launch { ProxyController.startFromSavedSettings(context = context, showInvalidPortToast = true) }
                if (plan.startDpi) startDpi()
            },
            showVpnWarning = selection.needsDpi && dpiMode == DpiMode.VPN && foreignVpnVisible,
            socksMode = dpiMode == DpiMode.SOCKS5,
        )

        if (selection.needsDpi || dpiIsActive) {
            SectionLabel(stringResource(R.string.home_strategy_section))
            ActionRow(
                title = stringResource(R.string.dpi_change_strategy),
                subtitle = stringResource(R.string.home_pick_strategy_hint),
                icon = R.drawable.ic_dt_settings,
                onClick = { showStrategyPicker = true },
            )
            Spacer(Modifier.height(6.dp))
            ActionRow(
                title = stringResource(R.string.home_repair),
                subtitle = stringResource(R.string.home_test_strategy_hint),
                icon = R.drawable.ic_dt_diagnostics,
                onClick = { showStrategyTest = true },
            )
        }

        if (showAccessWarning) {
            AccessWarningRow(onClick = onShowAccess, modifier = Modifier.padding(bottom = 10.dp))
        }

        if (selection.telegram || activeConnection != null) {
            SectionLabel(stringResource(R.string.proxy_subtitle))
            activeConnection?.let { connection ->
                BrandButton(
                    text = stringResource(R.string.apply_in_telegram),
                    onClick = { TelegramConnector.open(context, connection) },
                    trailing = "→",
                )
                BrandLink(
                    text = stringResource(R.string.choose_other_client),
                    onClick = { TelegramConnector.open(context, connection, chooseAgain = true) },
                )
            }
            KeyValueTable(
                rows = listOf(
                    stringResource(R.string.row_mode) to if (savedCfEnabled) "CF" else stringResource(R.string.direct_mode),
                    stringResource(R.string.row_pool) to "$savedPoolSize",
                    stringResource(R.string.row_port) to (activeConnection?.port?.toString() ?: savedPort),
                )
            )
            Spacer(Modifier.height(10.dp))
            ProxyLinkRow(proxyUrl = proxyUrl, context = context)
        }
    }

    // VPN replacement offer: the proof was captured before this dialog, so the
    // confirm action only re-validates and reuses it.
    pendingOffer?.let { (_, proof, attempt) ->
        BrandDialog(
            onDismiss = { pendingOffer = null; DpiSession.cancelInteractiveStart() },
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Text(
                stringResource(R.string.home_offer_title),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.ink
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.home_offer_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.muted
            )
            Spacer(Modifier.height(16.dp))
            BrandButton(
                text = stringResource(R.string.home_offer_replace),
                onClick = {
                    pendingOffer = null
                    startVpn(proof, true, attempt)
                }
            )
            Spacer(Modifier.height(8.dp))
            BrandOutlineButton(
                text = stringResource(R.string.home_offer_socks),
                onClick = {
                    pendingOffer = null
                    if (DpiSession.isInteractiveStartCurrent(attempt) && DpiSession.switchMode(context, DpiMode.SOCKS5)) DpiSession.start(context)
                }
            )
            Spacer(Modifier.height(8.dp))
            BrandLink(
                text = stringResource(R.string.vpn_offer_cancel),
                onClick = { pendingOffer = null; DpiSession.cancelInteractiveStart() }
            )
        }
    }

    if (showStrategyTest) DpiStrategyTestDialog(onDismiss = { showStrategyTest = false })

    if (showStrategyPicker) {
        DpiStrategyPickerDialog(onDismiss = { showStrategyPicker = false })
    }
}

@Composable
private fun ProxyLinkRow(proxyUrl: String, context: Context) {
    val copiedText = stringResource(R.string.copied)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, Brand.line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
        ) {
            Text(
                text = proxyUrl.ifEmpty { stringResource(R.string.proxy_link_hint) },
                style = MonoLabelStyle,
                color = Brand.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
            )
            if (proxyUrl.isNotEmpty()) {
                MonoAction(
                    text = stringResource(R.string.copy),
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Proxy", proxyUrl))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
