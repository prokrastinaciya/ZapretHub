package com.amurcanov.tgwsproxy.ui.dpi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore

@Composable
internal fun rememberDpiDocument(store: DpiSettingsStore): String {
    var document by remember(store) { mutableStateOf(store.exportJson()) }
    DisposableEffect(store) {
        val stop = store.observeDocument { document = it }
        document = store.exportJson()
        onDispose(stop)
    }
    return document
}
