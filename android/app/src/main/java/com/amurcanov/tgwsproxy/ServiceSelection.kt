package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class ServiceSelection(val telegram: Boolean = true, val youtube: Boolean = false, val discord: Boolean = false) {
    val needsDpi get() = youtube || discord
}

internal class ServiceSelectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("service_selection", Context.MODE_PRIVATE)
    fun read(): ServiceSelection {
        val bits = prefs.getInt("selected", 0b110)
        return ServiceSelection(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
    }
    fun save(selection: ServiceSelection) {
        val bits = (if (selection.telegram) 1 else 0) or (if (selection.youtube) 2 else 0) or (if (selection.discord) 4 else 0)
        prefs.edit().putInt("selected", bits).apply()
    }
    val changes = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
