package com.amurcanov.tgwsproxy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProxyStartStopRaceTest {
    @Test fun notificationStopInvalidatesOnlyEarlierStart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = org.robolectric.Robolectric.buildService(ProxyService::class.java).create().get()
        val notification = ProxyService::class.java.getDeclaredMethod("notification", String::class.java)
            .apply { isAccessible = true }.invoke(service, "running") as android.app.Notification
        val stopAction = notification.actions.last().actionIntent
        val oldStart = async(start = CoroutineStart.UNDISPATCHED) { ProxyController.startFromSavedSettings(context) }
        assertFalse(oldStart.isCompleted)
        assertTrue("notification stop must target a receiver", shadowOf(stopAction).isBroadcast)
        stopAction.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val shadow = shadowOf(context as Application)
        val newStart = async(start = CoroutineStart.UNDISPATCHED) { ProxyController.startFromSavedSettings(context) }
        assertFalse(oldStart.await())
        assertTrue(newStart.await())
        val actions = mutableListOf<String?>()
        while (true) actions.add((shadow.nextStartedService ?: break).action)
        assertEquals(listOf(ProxyService.ACTION_STOP, ProxyService.ACTION_START), actions)
    }

    @Test fun stopInvalidatesStartStillReadingSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val start = async(start = CoroutineStart.UNDISPATCHED) { ProxyController.startFromSavedSettings(context) }
        assertFalse("start must be suspended before stop", start.isCompleted)
        ProxyController.stop(context)
        assertFalse(start.await())
        val shadow = shadowOf(context as Application)
        val actions = mutableListOf<String?>()
        while (true) actions.add((shadow.nextStartedService ?: break).action)
        assertEquals(listOf(ProxyService.ACTION_STOP), actions)
    }
}
