package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** The language chosen in Settings. The service and the tile need it too, not only the activity. */
fun Context.withAppLanguage(): Context {
    val language = getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("app_language", "ru") ?: "ru"
    val config = Configuration(resources.configuration)
    config.setLocale(Locale(language))
    return createConfigurationContext(config)
}
