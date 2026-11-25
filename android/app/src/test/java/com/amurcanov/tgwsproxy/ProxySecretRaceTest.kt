package com.amurcanov.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProxySecretRaceTest {
    @Test fun concurrentFirstStartsUsePersistedSecret() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.saveSecretKey("")
        (1..24).map { async(Dispatchers.IO) { ProxyController.startFromSavedSettings(context) } }.awaitAll()
        val saved = store.secretKey.first()
        val shadow = shadowOf(context as android.app.Application)
        val secrets = mutableListOf<String?>()
        while (true) {
            val intent = shadow.nextStartedService ?: break
            if (intent.action == ProxyService.ACTION_START) secrets.add(intent.getStringExtra(ProxyService.EXTRA_SECRET_KEY))
        }
        assertEquals(24, secrets.size)
        assertEquals(setOf(saved), secrets.toSet())
    }
}
