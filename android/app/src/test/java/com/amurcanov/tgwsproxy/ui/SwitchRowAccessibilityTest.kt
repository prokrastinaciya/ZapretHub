package com.amurcanov.tgwsproxy.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SwitchRowAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun populatedFieldKeepsItsAccessibleLabel() {
        rule.setContent { TgWsProxyTheme { BrandField("2345", {}, placeholder = "Port") } }
        rule.onNodeWithContentDescription("Port").assertExists()
    }

    @Test fun titleAndSwitchAreOneActionableNode() {
        var changed = false
        rule.setContent { TgWsProxyTheme { SwitchRow("Enable feature", false, { changed = it }) } }
        val node = rule.onNodeWithText("Enable feature")
        node.assertHasClickAction()
        assertEquals(Role.Switch, node.fetchSemanticsNode().config[SemanticsProperties.Role])
        node.performClick()
        assertEquals(true, changed)
    }
}
