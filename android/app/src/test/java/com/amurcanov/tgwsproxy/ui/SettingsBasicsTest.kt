package com.amurcanov.tgwsproxy.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsBasicsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsHasOnlyOneSectionTitle() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent { TgWsProxyTheme { SettingsTab(com.amurcanov.tgwsproxy.SettingsStore(context)) } }
        compose.onAllNodesWithText(context.getString(com.amurcanov.tgwsproxy.R.string.settings), ignoreCase = true).assertCountEquals(1)
    }

    @Test fun portAndBootTogglesInvokeOnlyTheirOwnCallback() {
        var port = false
        var tg = false
        var dpi = false
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SettingsBasicsContent(false, false, false, true, { port = it }, { tg = it }, { dpi = it }, {}, {}, {})
            }
        }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(com.amurcanov.tgwsproxy.R.string.settings_v3_port)).performClick()
        compose.waitForIdle()
        assertEquals(true, port)
        assertEquals(false, tg)
        assertEquals(false, dpi)
        compose.onNodeWithText(context.getString(com.amurcanov.tgwsproxy.R.string.settings_v3_boot_dpi)).performClick()
        compose.waitForIdle()
        assertEquals(true, dpi)
        assertEquals(false, tg)
    }

    @Test fun basicActionsOpenOnlyTheirDestination() {
        var destination = ""
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SettingsBasicsContent(false, false, false, true, {}, {}, {},
                    { destination = "repair" }, { destination = "picker" }, { destination = "advanced" })
            }
        }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(com.amurcanov.tgwsproxy.R.string.settings_v3_pick)).performClick()
        compose.waitForIdle()
        assertEquals("repair", destination)
        compose.onNodeWithText(context.getString(com.amurcanov.tgwsproxy.R.string.settings_v3_change)).performClick()
        compose.waitForIdle()
        assertEquals("picker", destination)
        compose.onNodeWithText(context.getString(com.amurcanov.tgwsproxy.R.string.settings_v3_advanced)).performClick()
        compose.waitForIdle()
        assertEquals("advanced", destination)
    }
}
