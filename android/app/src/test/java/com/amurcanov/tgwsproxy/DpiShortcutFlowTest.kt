package com.amurcanov.tgwsproxy

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DpiShortcutFlowTest {
    @Test fun interactiveStartRequestIsConsumedOnlyOnce() {
        val token = DpiSession.beginInteractiveStart()
        val intent = Intent().putExtra(DpiShortcutActivity.EXTRA_INTERACTIVE_START, token)
        assertEquals(token, consumeDpiShortcutStart(intent))
        assertNull(consumeDpiShortcutStart(intent))
        assertNull(consumeDpiShortcutStart(Intent().putExtra(DpiShortcutActivity.EXTRA_INTERACTIVE_START, "forged")))
    }
}
