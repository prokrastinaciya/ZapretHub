package com.amurcanov.tgwsproxy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiRemoteSession
import com.amurcanov.tgwsproxy.dpi.DpiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DpiSessionReconciliationTest {
    @Test fun freshModeIsVpnAndExplicitSocksFallbackIsNotRewritten() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val holder = DpiSessionHolder(dispatcher = Dispatchers.Unconfined, inspect = { _, _ -> null })
        assertEquals(DpiMode.VPN, holder.mode.value)
        holder.awaitRestoration(context)
        assertEquals(DpiMode.VPN, holder.mode.value)
        assertTrue(holder.switchMode(context, DpiMode.SOCKS5))
        holder.awaitRestoration(context)
        assertEquals(DpiMode.SOCKS5, holder.mode.value)
    }

    @Test fun hangingStatusHasBoundedFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val holder = DpiSessionHolder(
            dispatcher = Dispatchers.Unconfined,
            inspect = { _, _ -> delay(1_000); null },
            restorationTimeoutMs = 20,
        )
        holder.awaitRestoration(context)
        assertTrue(holder.state.value is DpiState.Failed)
        assertFalse(holder.switchMode(context, DpiMode.SOCKS5))
    }

    @Test fun unconfirmedRepairRemainsFailedUntilBothStopsSucceed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var confirm = false
        val holder = DpiSessionHolder(
            dispatcher = Dispatchers.Unconfined,
            inspect = { _, mode -> throw DpiRemoteSession.StatusUnavailableException(mode) },
            stopRemote = { _, _ -> confirm },
        )
        holder.awaitRestoration(context)
        holder.stop(context)
        assertTrue(holder.state.value is DpiState.Failed)
        assertFalse(holder.switchMode(context, DpiMode.SOCKS5))
        confirm = true
        holder.stop(context)
        assertEquals(DpiState.Off, holder.state.value)
    }

    @Test fun unavailableStatusBlocksModeSwitchUntilExplicitRepairStopsBothModes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stopped = mutableListOf<DpiMode>()
        val holder = DpiSessionHolder(
            dispatcher = Dispatchers.Unconfined,
            inspect = { _, mode -> throw DpiRemoteSession.StatusUnavailableException(mode) },
            stopRemote = { _, mode -> stopped.add(mode); true },
        )
        holder.awaitRestoration(context)
        assertTrue(holder.state.value is DpiState.Failed)
        assertFalse(holder.switchMode(context, DpiMode.SOCKS5))
        holder.stop(context)
        holder.awaitRestoration(context)
        assertEquals(DpiMode.entries.toSet(), stopped.toSet())
        assertEquals(DpiState.Off, holder.state.value)
        assertTrue(holder.switchMode(context, DpiMode.SOCKS5))
    }
}
