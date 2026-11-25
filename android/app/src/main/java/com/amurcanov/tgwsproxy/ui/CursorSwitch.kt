package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R

enum class CursorVisualState { Off, Connecting, On }

@Composable
fun CursorSwitch(
    state: CursorVisualState,
    description: String,
    stateText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = enabled && state == CursorVisualState.Off
    val fill = if (accent) Brand.fill else Brand.raised
    val ink = when {
        !enabled -> Brand.muted
        accent -> Brand.onFill
        else -> Brand.ink
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = fill,
        contentColor = ink,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .semantics {
                contentDescription = description
                stateDescription = stateText
                role = Role.Button
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandIcon(R.drawable.ic_power, tint = ink, size = 22.dp)
            Text(description, style = MaterialTheme.typography.titleMedium)
        }
    }
}
