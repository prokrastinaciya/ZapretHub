package com.amurcanov.tgwsproxy

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Density
import com.amurcanov.tgwsproxy.ui.TgWsProxyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
class NavigationBarTest {
    @get:Rule val rule = createComposeRule()
    @Test fun dpadMovesBetweenTabsAndActivatesSelection() {
        var selected = -1
        val items = listOf("Proxy", "Settings", "Logs", "Info").map { NavItem(it, R.drawable.ic_dt_network) }
        rule.setContent {
            val keyboard = object : InputModeManager {
                override val inputMode = InputMode.Keyboard
                override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
            }
            CompositionLocalProvider(LocalInputModeManager provides keyboard) {
                TgWsProxyTheme { ProxyNavigationBar(items, 0, -1, 0f, { selected = it }) }
            }
        }
        rule.onNodeWithText("Proxy").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("Proxy").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Settings").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, selected)
    }

    @Test fun tabsExpandForLargeTextAndRemainClickable() {
        var selected = -1
        val items = listOf("Proxy", "Settings", "Logs", "Info").map { NavItem(it, R.drawable.ic_dt_network) }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TgWsProxyTheme { ProxyNavigationBar(items, 0, -1, 0f, { selected = it }) }
            }
        }
        val node = rule.onNodeWithText("Settings").assertHasClickAction()
        assertTrue(node.fetchSemanticsNode().size.height / rule.density.density > 64f)
        node.performClick()
        assertEquals(1, selected)
    }
}
