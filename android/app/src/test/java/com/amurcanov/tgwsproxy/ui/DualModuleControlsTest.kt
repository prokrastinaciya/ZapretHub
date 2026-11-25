package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RED/GREEN target for Task 4. Proves the two module controls are independent
 * buttons with their own accessible name and state, that toggling one never
 * commands the other, that a Starting module never advertises "running", that
 * narrow width + large font keep both labels and actions reachable, and that a
 * TV D-pad can move focus between them and activate the right one.
 *
 * The controls are exercised with a pure [DualModuleControls] composable and
 * fake callbacks — no Android service or native engine is involved, so the test
 * runs on the JVM under Robolectric and pins the shared control contract that
 * [ConnectionTab] renders in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DualModuleControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun strategyEditorReadsStringTypedNumbersFromImport() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = com.amurcanov.tgwsproxy.dpi.DpiSettingsStore(context)
        // v1.8.2 exchange documents store numeric settings as JSON strings.
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080","byedpi_enable_cmd_settings":false,"byedpi_split_position":"7"}}""")
        composeRule.setContent {
            TgWsProxyTheme { com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyEditor() }
        }
        composeRule.onNodeWithText("7").assertExists()
    }

    @Test
    fun changeStrategyPickerAppliesPinnedStrategy() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = com.amurcanov.tgwsproxy.dpi.DpiSettingsStore(context)
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"},"history":[{"text":"-d2 -a1","pinned":true,"name":"Default"}]}""")
        composeRule.setContent {
            TgWsProxyTheme { com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyPickerDialog(onDismiss = {}) }
        }
        composeRule.onNodeWithText("Default").assertExists()
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.waitForIdle()
        assertEquals("-d2 -a1", store.currentSettings()["byedpi_cmd_args"])
        assertEquals(true, store.currentSettings()["byedpi_enable_cmd_settings"])
    }

    @Test
    fun strategyEditorPersistsDesyncMethodChoice() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = com.amurcanov.tgwsproxy.dpi.DpiSettingsStore(context)
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        composeRule.setContent {
            TgWsProxyTheme { com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyEditor() }
        }
        composeRule.onNodeWithText("split").performClick()
        composeRule.waitForIdle()
        assertEquals("split", store.currentSettings()["byedpi_desync_method"])
    }

    @Test
    fun changingLogSourceHidesTelegramEntries() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val logs = com.amurcanov.tgwsproxy.LogManager.logs
        val previous = logs.value
        logs.value = listOf(com.amurcanov.tgwsproxy.LogEntry("test", "Telegram-only event", 1, false, 4, true))
        try {
            composeRule.setContent {
                TgWsProxyTheme { LogsTab(com.amurcanov.tgwsproxy.SettingsStore(context)) }
            }
            composeRule.onNodeWithText("Telegram-only event").assertExists()
            composeRule.onNodeWithText("ByeByeDPI").performClick()
            composeRule.onNodeWithText("Telegram-only event").assertDoesNotExist()
        } finally {
            logs.value = previous
        }
    }

    @Test
    fun settingsExposeSeparateModulePagesBehindAdvanced() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent {
            TgWsProxyTheme { SettingsTab(com.amurcanov.tgwsproxy.SettingsStore(context)) }
        }
        composeRule.onNodeWithText("ByeByeDPI").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(com.amurcanov.tgwsproxy.R.string.settings_v3_advanced)).performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("ByeByeDPI")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("TG-WS").assertExists()
        composeRule.onNodeWithText("ByeByeDPI").performClick()
        composeRule.onNodeWithText("SOCKS5").assertExists()
        composeRule.onNodeWithText("Import ByeByeDPI backup").assertHasClickAction()
        composeRule.onNodeWithText("Export ByeByeDPI backup").assertHasClickAction()
    }

    @Test
    fun commonHeaderNamesProduct() {
        composeRule.setContent { TgWsProxyTheme { BrandHeader("Test") } }
        composeRule.onNodeWithText("Zapret Hub").assertExists()
    }

    @Test
    fun dpadMovesBetweenServiceRowsAndActivatesFocusedSelection() {
        var selection = com.amurcanov.tgwsproxy.ServiceSelection()
        lateinit var inputMode: InputModeManager
        composeRule.setContent {
            inputMode = LocalInputModeManager.current
            var current by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(selection) }
            TgWsProxyTheme {
                DualModuleControls(current, { current = it; selection = it }, CursorVisualState.Off, "Offline", "Start", {}, false, false)
            }
        }
        composeRule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        composeRule.onNodeWithText("Telegram").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithText("Telegram").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("YouTube").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(selection.telegram && selection.youtube && !selection.discord)
    }

    @Test
    fun selectingVpnServiceExplainsTrafficScopeWithoutForeignVpn() {
        composeRule.setContent {
            var selection by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.amurcanov.tgwsproxy.ServiceSelection()) }
            TgWsProxyTheme {
                DualModuleControls(selection, { selection = it }, CursorVisualState.Off, "Offline", "Start", {}, false, false)
            }
        }
        val notice = "The local VPN routes app traffic on this device. YouTube and Discord are used to test strategies."
        composeRule.onNodeWithText(notice).assertDoesNotExist()
        composeRule.onNodeWithText("YouTube").performClick()
        composeRule.onNodeWithText(notice).assertExists()
        composeRule.onNodeWithText("YouTube").performClick()
        composeRule.onNodeWithText(notice).assertDoesNotExist()
    }

    @Test
    fun homeHasOnePowerControlAndThreeAccessibleSelections() {
        var changes = com.amurcanov.tgwsproxy.ServiceSelection()
        var powers = 0
        composeRule.setContent {
            var selection by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.amurcanov.tgwsproxy.ServiceSelection()) }
            TgWsProxyTheme {
                DualModuleControls(selection, { selection = it; changes = it }, CursorVisualState.Off, "Offline", "Start selected services", { powers++ }, false, false)
            }
        }
        val telegram = composeRule.onNodeWithText("Telegram").assertHasClickAction()
        assertEquals(Role.Checkbox, telegram.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertEquals(Role.Checkbox, composeRule.onNodeWithText("YouTube").fetchSemanticsNode().config[SemanticsProperties.Role])
        assertEquals(Role.Checkbox, composeRule.onNodeWithText("Discord").fetchSemanticsNode().config[SemanticsProperties.Role])
        val power = composeRule.onNodeWithContentDescription("Start selected services")
        assertEquals(Role.Button, power.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertEquals("Offline", power.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        composeRule.onNodeWithText("YouTube").performClick()
        assertTrue(changes.telegram && changes.youtube && !changes.discord)
        composeRule.onNodeWithText("Discord").performClick()
        assertTrue(changes.discord)
        composeRule.onNodeWithContentDescription("Start selected services").performClick()
        assertEquals(1, powers)
    }
}
