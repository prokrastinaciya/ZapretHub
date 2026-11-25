package com.amurcanov.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ServiceSelectionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("service_selection", Context.MODE_PRIVATE)

    @Before fun clear() { preferences.edit().clear().commit() }

    @Test fun freshDefaultsToDpiWithoutWritingPreferences() {
        assertEquals(ServiceSelection(false, true, true), ServiceSelectionStore(context).read())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun everySubsetIncludingNonePersistsWithoutAutomaticRewrite() {
        for (bits in 0..7) {
            val expected = ServiceSelection(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            ServiceSelectionStore(context).save(expected)
            val before = preferences.all.toMap()
            repeat(3) {
                val reopened = ServiceSelectionStore(context)
                assertEquals(expected, reopened.read())
                assertEquals(expected.youtube || expected.discord, reopened.read().needsDpi)
                assertEquals(before, preferences.all)
            }
            assertEquals(bits, preferences.getInt("selected", -1))
        }
    }
}
