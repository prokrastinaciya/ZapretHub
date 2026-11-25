package com.amurcanov.tgwsproxy.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductHeaderTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun infoTabEndsWithTheUnofficialNotice() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent { TgWsProxyTheme { InfoTab(com.amurcanov.tgwsproxy.SettingsStore(context)) } }
        composeRule.onNodeWithText("Licenses:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Version ${com.amurcanov.tgwsproxy.BuildConfig.VERSION_NAME}").assertDoesNotExist()

        composeRule.onNodeWithText("Unofficial app, not affiliated with Telegram.")
            .assertExists()
            .performScrollTo()
    }

    @Test fun aboutListsTheDpiUpstream() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent { TgWsProxyTheme { InfoTab(com.amurcanov.tgwsproxy.SettingsStore(context)) } }
        // Three source links: this app, the Flowseal core and the RomanVHT DPI base.
        composeRule.onNodeWithText("amurcanov/tg-ws-proxy-android").assertHasClickAction()
        composeRule.onNodeWithText("tg-ws-proxy core").assertHasClickAction()
        composeRule.onNodeWithText("romanvht/ByeByeDPI").assertExists().assertHasClickAction()
    }

    @Test fun aboutCreditsTheAndroidUpstream() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent { TgWsProxyTheme { InfoTab(com.amurcanov.tgwsproxy.SettingsStore(context)) } }
        composeRule.onNodeWithText("Derigava/tg-ws-proxy-android").performScrollTo().assertHasClickAction()
    }

    @Test fun headerShowsProductAndSection() {
        composeRule.setContent { TgWsProxyTheme { BrandHeader(section = "Info") } }
        composeRule.onNodeWithText("Zapret Hub").assertExists()
        composeRule.onNodeWithText("INFO").assertExists()
        composeRule.onNodeWithText("TECH / INFO").assertDoesNotExist()
    }
}
