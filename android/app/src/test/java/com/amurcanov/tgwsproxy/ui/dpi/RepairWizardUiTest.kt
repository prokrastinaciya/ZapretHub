package com.amurcanov.tgwsproxy.ui.dpi

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.ServiceSelection
import com.amurcanov.tgwsproxy.ServiceSelectionStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepairWizardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun youtubeRendersStrategyStepAndCanContinueToRedactedShare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceSelectionStore(context).save(ServiceSelection(telegram = false, youtube = true))
        compose.setContent { DpiStrategyTestDialog {} }
        compose.onNodeWithText(context.getString(R.string.repair_step_strategy)).assertExists()
        compose.onNodeWithText(context.getString(R.string.repair_continue)).performClick()
        compose.onNodeWithText(context.getString(R.string.repair_step_share)).assertExists()
        compose.onNodeWithText(context.getString(R.string.repair_share)).assertExists()
    }
}
