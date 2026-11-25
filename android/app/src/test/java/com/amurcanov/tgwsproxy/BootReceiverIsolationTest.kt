package com.amurcanov.tgwsproxy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiProxyService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootReceiverIsolationTest {
    @Test fun telegramFailureStillStartsEnabledDpiAndFinishesBroadcast() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        SettingsStore(app).apply {
            saveAutoStartOnBoot(true)
            saveDpiAutoStartOnBoot(true)
        }
        val starts = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName {
                if (service.component?.className == ProxyService::class.java.name)
                    throw IllegalStateException("foreground start denied")
                starts.add(service)
                return requireNotNull(service.component)
            }
        }
        // Install a real framework PendingResult: direct onReceive alone gives goAsync() null.
        val constructor = BroadcastReceiver.PendingResult::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, String::class.java, Bundle::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, IBinder::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        val pending = constructor.newInstance(0, null, null, 2, false, false, Binder(), 0, 0)
        val finished = shadowOf(pending).future
        val receiver = BootReceiver()
        BroadcastReceiver::class.java.getDeclaredMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, pending)
        ShadowLog.clear()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!finished.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("goAsync broadcast must finish", finished.isDone)
        assertTrue(ShadowLog.getLogsForTag("BootReceiver").any { it.throwable?.message == "foreground start denied" })
        assertEquals(DpiMode.SOCKS5, DpiSession.mode.value)
        assertTrue("DPI SOCKS5 start must be requested", starts.any { it.component?.className == DpiProxyService::class.java.name })
    }
}