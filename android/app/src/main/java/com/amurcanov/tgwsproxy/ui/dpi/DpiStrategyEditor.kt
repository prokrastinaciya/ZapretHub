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
import com.amurcanov.tgwsproxy.ui.BrandSegmented
import com.amurcanov.tgwsproxy.ui.BrandField
import com.amurcanov.tgwsproxy.ui.BrandMultilineField
import com.amurcanov.tgwsproxy.ui.SectionLabel
import com.amurcanov.tgwsproxy.ui.SwitchRow

private fun DpiSettingsStore.string(key: String, default: String) =
    (currentSettings()[key] as? String) ?: default

private fun DpiSettingsStore.bool(key: String, default: Boolean) =
    (currentSettings()[key] as? Boolean) ?: default

private fun DpiSettingsStore.int(key: String, default: Int) =
    currentSettings()[key]?.toString()?.toIntOrNull() ?: default

/**
 * Standard desync strategy editor (parity rows U-01..U-26). Each edit is
 * validated and persisted through [DpiSettingsStore.updateSettings] so an
 * invalid value never partially mutates the stored document.
 */
@Composable
internal fun DpiStrategyEditor() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val document = rememberDpiDocument(store)
    var saveError by remember(document) { mutableStateOf(false) }

    var method by remember(document) { mutableStateOf(store.string("byedpi_desync_method", "oob")) }
    var splitPosition by remember(document) { mutableStateOf(store.int("byedpi_split_position", 1).toString()) }
    var splitAtHost by remember(document) { mutableStateOf(store.bool("byedpi_split_at_host", false)) }
    var desyncHttp by remember(document) { mutableStateOf(store.bool("byedpi_desync_http", true)) }
    var desyncHttps by remember(document) { mutableStateOf(store.bool("byedpi_desync_https", true)) }
    var desyncUdp by remember(document) { mutableStateOf(store.bool("byedpi_desync_udp", true)) }
    var hostsMode by remember(document) { mutableStateOf(store.string("byedpi_hosts_mode", "disable")) }
    var hostsBlacklist by remember(document) { mutableStateOf(store.string("byedpi_hosts_blacklist", "")) }
    var hostsWhitelist by remember(document) { mutableStateOf(store.string("byedpi_hosts_whitelist", "")) }
    var defaultTtl by remember(document) { mutableStateOf(store.int("byedpi_default_ttl", 0).toString()) }
    var noDomain by remember(document) { mutableStateOf(store.bool("byedpi_no_domain", false)) }
    var tcpFastOpen by remember(document) { mutableStateOf(store.bool("byedpi_tcp_fast_open", false)) }
    var dropSack by remember(document) { mutableStateOf(store.bool("byedpi_drop_sack", false)) }
    var fakeTtl by remember(document) { mutableStateOf(store.int("byedpi_fake_ttl", 8).toString()) }
    var fakeSni by remember(document) { mutableStateOf(store.string("byedpi_fake_sni", "www.iana.org")) }
    var fakeOffset by remember(document) { mutableStateOf(store.int("byedpi_fake_offset", 0).toString()) }
    var oobData by remember(document) { mutableStateOf(store.string("byedpi_oob_data", "a")) }
    var hostMixedCase by remember(document) { mutableStateOf(store.bool("byedpi_host_mixed_case", false)) }
    var domainMixedCase by remember(document) { mutableStateOf(store.bool("byedpi_domain_mixed_case", false)) }
    var hostRemoveSpaces by remember(document) { mutableStateOf(store.bool("byedpi_host_remove_spaces", false)) }
    var tlsrecEnabled by remember(document) { mutableStateOf(store.bool("byedpi_tlsrec_enabled", false)) }
    var tlsrecPosition by remember(document) { mutableStateOf(store.int("byedpi_tlsrec_position", 0).toString()) }
    var tlsrecAtSni by remember(document) { mutableStateOf(store.bool("byedpi_tlsrec_at_sni", false)) }
    var udpFakeCount by remember(document) { mutableStateOf(store.int("byedpi_udp_fake_count", 1).toString()) }

    fun persist(key: String, value: Any?) {
        saveError = runCatching { store.updateSettings(mapOf(key to value)) }.isFailure
    }

    if (saveError) Text(stringResource(R.string.dpi_edit_unsaved))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier) {
        SectionLabel(stringResource(R.string.dpi_strategy_desync))
        BrandSegmented(
            options = listOf("none", "split", "disorder", "fake", "oob", "disoob").map { it to it },
            selected = method,
            onSelect = { method = it; persist("byedpi_desync_method", it) },
        )

        SectionLabel(stringResource(R.string.dpi_strategy_split))
        BrandField(
            value = splitPosition,
            onValueChange = { filtered ->
                val digits = filtered.filter(Char::isDigit)
                splitPosition = digits
                if (digits.toIntOrNull() == null) saveError = true else digits.toIntOrNull()?.let { persist("byedpi_split_position", it) }
            },
            placeholder = stringResource(R.string.dpi_strategy_split_position),
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_split_at_host),
            checked = splitAtHost,
            onCheckedChange = { splitAtHost = it; persist("byedpi_split_at_host", it) },
        )

        SectionLabel(stringResource(R.string.dpi_strategy_protocols))
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_desync_http),
            checked = desyncHttp,
            onCheckedChange = { desyncHttp = it; persist("byedpi_desync_http", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_desync_https),
            checked = desyncHttps,
            onCheckedChange = { desyncHttps = it; persist("byedpi_desync_https", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_desync_udp),
            checked = desyncUdp,
            onCheckedChange = { desyncUdp = it; persist("byedpi_desync_udp", it) },
        )

        SectionLabel(stringResource(R.string.dpi_strategy_hosts))
        BrandSegmented(
            options = listOf("disable", "blacklist", "whitelist").map { it to it },
            selected = hostsMode,
            onSelect = { hostsMode = it; persist("byedpi_hosts_mode", it) },
        )
        if (hostsMode == "blacklist") {
            BrandMultilineField(
                value = hostsBlacklist,
                onValueChange = { hostsBlacklist = it; persist("byedpi_hosts_blacklist", it) },
                placeholder = stringResource(R.string.dpi_strategy_hosts_blacklist),
            )
        } else if (hostsMode == "whitelist") {
            BrandMultilineField(
                value = hostsWhitelist,
                onValueChange = { hostsWhitelist = it; persist("byedpi_hosts_whitelist", it) },
                placeholder = stringResource(R.string.dpi_strategy_hosts_whitelist),
            )
        }

        SectionLabel(stringResource(R.string.dpi_strategy_other))
        BrandField(
            value = defaultTtl,
            onValueChange = { filtered ->
                val digits = filtered.filter(Char::isDigit)
                defaultTtl = digits
                if (digits.toIntOrNull() == null) saveError = true else digits.toIntOrNull()?.let { persist("byedpi_default_ttl", it) }
            },
            placeholder = stringResource(R.string.dpi_strategy_default_ttl),
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_no_domain),
            checked = noDomain,
            onCheckedChange = { noDomain = it; persist("byedpi_no_domain", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_tcp_fast_open),
            checked = tcpFastOpen,
            onCheckedChange = { tcpFastOpen = it; persist("byedpi_tcp_fast_open", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_drop_sack),
            checked = dropSack,
            onCheckedChange = { dropSack = it; persist("byedpi_drop_sack", it) },
        )

        if (method == "fake") {
            SectionLabel(stringResource(R.string.dpi_strategy_fake))
            BrandField(
                value = fakeTtl,
                onValueChange = { filtered ->
                    val digits = filtered.filter(Char::isDigit)
                    fakeTtl = digits
                    if (digits.toIntOrNull() == null) saveError = true else digits.toIntOrNull()?.let { persist("byedpi_fake_ttl", it) }
                },
                placeholder = stringResource(R.string.dpi_strategy_fake_ttl),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            BrandField(
                value = fakeSni,
                onValueChange = { fakeSni = it; persist("byedpi_fake_sni", it) },
                placeholder = stringResource(R.string.dpi_strategy_fake_sni),
            )
            BrandField(
                value = fakeOffset,
                onValueChange = { filtered ->
                    val digits = filtered.filter(Char::isDigit)
                    fakeOffset = digits
                    if (digits.toIntOrNull() == null) saveError = true else digits.toIntOrNull()?.let { persist("byedpi_fake_offset", it) }
                },
                placeholder = stringResource(R.string.dpi_strategy_fake_offset),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
        }
        if (method == "oob" || method == "disoob") {
            BrandField(
                value = oobData,
                onValueChange = { oobData = it; persist("byedpi_oob_data", it) },
                placeholder = stringResource(R.string.dpi_strategy_oob_data),
            )
        }

        SectionLabel(stringResource(R.string.dpi_strategy_mod_http))
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_host_mixed_case),
            checked = hostMixedCase,
            onCheckedChange = { hostMixedCase = it; persist("byedpi_host_mixed_case", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_domain_mixed_case),
            checked = domainMixedCase,
            onCheckedChange = { domainMixedCase = it; persist("byedpi_domain_mixed_case", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_host_remove_spaces),
            checked = hostRemoveSpaces,
            onCheckedChange = { hostRemoveSpaces = it; persist("byedpi_host_remove_spaces", it) },
        )

        SectionLabel(stringResource(R.string.dpi_strategy_tlsrec))
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_tlsrec_enabled),
            checked = tlsrecEnabled,
            onCheckedChange = { tlsrecEnabled = it; persist("byedpi_tlsrec_enabled", it) },
        )
        if (tlsrecEnabled) {
            BrandField(
                value = tlsrecPosition,
                onValueChange = { filtered ->
                    val digits = filtered.filter(Char::isDigit)
                    tlsrecPosition = digits
                    if (digits.toIntOrNull() == null) saveError = true else digits.toIntOrNull()?.let { persist("byedpi_tlsrec_position", it) }
                },
                placeholder = stringResource(R.string.dpi_strategy_tlsrec_position),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            SwitchRow(
                title = stringResource(R.string.dpi_strategy_tlsrec_at_sni),
                checked = tlsrecAtSni,
                onCheckedChange = { tlsrecAtSni = it; persist("byedpi_tlsrec_at_sni", it) },
            )
        }

        SectionLabel(stringResource(R.string.dpi_strategy_udp))
        BrandField(
            value = udpFakeCount,
            onValueChange = { filtered ->
                val digits = filtered.filter(Char::isDigit)
                udpFakeCount = digits
                if (digits.toIntOrNull() == null) saveError = true else digits.toIntOrNull()?.let { persist("byedpi_udp_fake_count", it) }
            },
            placeholder = stringResource(R.string.dpi_strategy_udp_fake_count),
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )
    }
}
