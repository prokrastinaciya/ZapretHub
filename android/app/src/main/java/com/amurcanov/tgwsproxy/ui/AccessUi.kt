package com.amurcanov.tgwsproxy.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SystemAccess

data class AccessRowState(val item: SystemAccess.Item, val granted: Boolean?)

/** Current state of every row. Rereads on resume: the answers come from system screens. */
@Composable
fun rememberAccessRows(): List<AccessRowState> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(revision) {
        SystemAccess.items(context).map { AccessRowState(it, SystemAccess.isGranted(context, it)) }
    }
}

fun List<AccessRowState>.hasCriticalGap(): Boolean =
    any { it.granted == false && it.item in SystemAccess.critical }

@Composable
fun rememberAccessOpener(): (SystemAccess.Item) -> Unit {
    val context = LocalContext.current
    val notificationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // After repeated refusals Android stops showing the prompt: only its settings screen is left.
        val activity = context as? Activity
        if (!granted && activity != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            SystemAccess.open(context, SystemAccess.Item.Notifications)
        }
    }
    return remember(context) {
        { item ->
            if (item == SystemAccess.Item.Notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SystemAccess.open(context, item)
            }
        }
    }
}

@Composable
fun AccessList(rows: List<AccessRowState>, onOpen: (SystemAccess.Item) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row -> AccessRow(row, onClick = { onOpen(row.item) }) }
    }
}

@Composable
private fun AccessRow(row: AccessRowState, onClick: () -> Unit) {
    val (title, subtitle) = when (row.item) {
        SystemAccess.Item.Notifications -> R.string.access_notifications to R.string.access_notifications_hint
        SystemAccess.Item.Battery -> R.string.access_battery to R.string.access_battery_hint
        SystemAccess.Item.Background -> R.string.access_background to R.string.access_background_hint
        SystemAccess.Item.OemAutostart -> R.string.access_autostart to R.string.access_autostart_hint
    }
    // Colour never works alone: each state has its own glyph.
    val (glyph, glyphColor) = when (row.granted) {
        true -> "[OK]" to Brand.accent
        // Update installs are asked for at update time, so a missing grant is not a risk.
        false -> if (row.item in SystemAccess.critical) "[!] " to Brand.alert else "//  " to Brand.muted
        null -> "//  " to Brand.muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Brand.surface)
            .clickable(enabled = row.granted != true, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonoLabel(glyph, color = glyphColor)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = Brand.ink)
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = Brand.muted)
        }
        if (row.granted != true) {
            Spacer(Modifier.width(8.dp))
            Text("→", style = MonoValueStyle, color = Brand.muted)
        }
    }
}

/** Shown once on first launch, and again from the warning on the proxy screen. */
@Composable
fun AccessSetupDialog(rows: List<AccessRowState>, onOpen: (SystemAccess.Item) -> Unit, onDone: () -> Unit) {
    val allDone = !rows.hasCriticalGap()
    BrandDialog(
        onDismiss = onDone,
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        MonoLabel("> ${stringResource(R.string.access_section).uppercase()}", color = Brand.accent)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.titleLarge, color = Brand.ink)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.access_intro), style = MaterialTheme.typography.bodyMedium, color = Brand.muted)
        Spacer(Modifier.height(16.dp))
        AccessList(rows = rows, onOpen = onOpen)
        Spacer(Modifier.height(16.dp))
        if (allDone) {
            BrandButton(text = stringResource(R.string.done), onClick = onDone)
        } else {
            BrandOutlineButton(text = stringResource(R.string.later), onClick = onDone)
        }
    }
}

/** Compact reminder on the proxy screen while the system can still stop the proxy. */
@Composable
fun AccessWarningRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(Brand.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonoLabel(stringResource(R.string.access_warning), color = Brand.alert, modifier = Modifier.weight(1f))
        Text("→", style = MonoValueStyle, color = Brand.alert)
    }
}
