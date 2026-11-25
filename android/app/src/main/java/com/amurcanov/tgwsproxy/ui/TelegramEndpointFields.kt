package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R

@Composable
internal fun IpSetupDialog(
    isExperimental: Boolean,
    onExperimentalChange: (Boolean) -> Unit,
    dc1Text: String, onDc1Change: (String) -> Unit,
    dc2Text: String, onDc2Change: (String) -> Unit,
    dc3Text: String, onDc3Change: (String) -> Unit,
    dc4Text: String, onDc4Change: (String) -> Unit,
    dc5Text: String, onDc5Change: (String) -> Unit,
    dc203Text: String, onDc203Change: (String) -> Unit,
    dc1mText: String, onDc1mChange: (String) -> Unit,
    dc2mText: String, onDc2mChange: (String) -> Unit,
    dc3mText: String, onDc3mChange: (String) -> Unit,
    dc4mText: String, onDc4mChange: (String) -> Unit,
    dc5mText: String, onDc5mChange: (String) -> Unit,
    dc203mText: String, onDc203mChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BrandDialog(
        onDismiss = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .heightIn(max = 600.dp)
    ) {
        Text(
            stringResource(R.string.dc_addresses),
            style = MaterialTheme.typography.titleLarge,
            color = Brand.ink
        )
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isExperimental) {
                DcInput("DC1", dc1Text, onDc1Change)
                DcInput("DC2", dc2Text, onDc2Change)
                DcInput("DC3", dc3Text, onDc3Change)
                DcInput("DC4", dc4Text, onDc4Change)
                DcInput("DC5", dc5Text, onDc5Change)
                DcInput("DC203", dc203Text, onDc203Change)
                SectionLabel(stringResource(R.string.media_dcs))
                DcInput("DC1m", dc1mText, onDc1mChange)
                DcInput("DC2m", dc2mText, onDc2mChange)
                DcInput("DC3m", dc3mText, onDc3mChange)
                DcInput("DC4m", dc4mText, onDc4mChange)
                DcInput("DC5m", dc5mText, onDc5mChange)
                DcInput("DC203m", dc203mText, onDc203mChange)
            } else {
                DcInput("DC2", dc2Text, onDc2Change)
                DcInput("DC4", dc4Text, onDc4Change)
            }
        }

        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = stringResource(R.string.experimental_mode),
            checked = isExperimental,
            onCheckedChange = onExperimentalChange
        )
        Spacer(Modifier.height(8.dp))
        BrandButton(text = stringResource(R.string.done), onClick = onDismiss)
    }
}

/** Comma-separated host names, as the core's coerce_domain_list reads them. */
internal fun invalidDomains(value: String): List<String> = value
    .split(',', ';', ' ')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .filterNot { DOMAIN_PATTERN.matches(it) }

private val DOMAIN_PATTERN =
    Regex("""^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$""")

@Composable
internal fun DomainInput(
    label: String,
    value: String,
    placeholder: String,
    hint: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val invalid = remember(value) { invalidDomains(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoLabel(label.uppercase())
        BrandField(
            value = value,
            onValueChange = { newValue ->
                onValueChange(newValue.filter { it.isLetterOrDigit() || it in ".-, " })
            },
            keyboardType = KeyboardType.Uri,
            enabled = enabled,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth()
        )
        if (invalid.isNotEmpty()) {
            MonoLabel("[!] " + stringResource(R.string.invalid_domain, invalid.first()), color = Brand.alert)
        } else {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Brand.muted)
        }
    }
}

@Composable
private fun DcInput(label: String, value: String, update: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoLabel(label.uppercase())
        BrandField(
            value = value,
            onValueChange = { newValue -> update(newValue.filter { it.isDigit() || it == '.' }) },
            keyboardType = KeyboardType.Number,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
