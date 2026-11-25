package com.amurcanov.tgwsproxy

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amurcanov.tgwsproxy.dpi.AndroidVpnSlotSampler
import com.amurcanov.tgwsproxy.dpi.DpiConfig
import com.amurcanov.tgwsproxy.dpi.DpiEngineResult
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiRemoteSession
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.dpi.DpiStrategyCandidate
import com.amurcanov.tgwsproxy.dpi.DpiStrategyTester
import com.amurcanov.tgwsproxy.dpi.DpiVpnEngine
import com.amurcanov.tgwsproxy.dpi.DpiVpnService
import com.amurcanov.tgwsproxy.dpi.HevNative
import com.amurcanov.tgwsproxy.dpi.NativeDpiEngine
import com.amurcanov.tgwsproxy.dpi.VpnAuthorizer
import io.github.romanvht.byedpi.core.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class DpiLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun newHolderRestoresSocks5AndAnIdleControllerCanStopIt() = withForeground {
        val previouslyIdle = DpiSessionHolder()
        withContext(Dispatchers.Main) { previouslyIdle.awaitRestoration(context) }
        assertEquals(DpiState.Off, previouslyIdle.state.value)
        val config = DpiConfig(DpiMode.SOCKS5, port = freePort())
        assertEquals(DpiEngineResult.Running, NativeDpiEngine(context).start(config))
        assertRestoresAndStops(config, previouslyIdle)
    }

    @Test
    fun strategyTestReusesNativeSocksWithoutChangingTheSelectedMode() = withForeground {
        val holder = DpiSessionHolder()
        val port = freePort()
        val scores = mutableListOf<Int>()
        withContext(Dispatchers.Main) {
            val tested = holder.runStrategyTest(context) {
                val engine = NativeDpiEngine(context, owner = android.os.Binder())
                val tester = DpiStrategyTester(engine, probe = { probePort, target ->
                    Socket("127.0.0.1", probePort).use { socket ->
                        socket.soTimeout = 1_000
                        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                        val response = socket.getInputStream()
                        response.read() == 5 && response.read() == 0 &&
                            (scores.isNotEmpty() || target != com.amurcanov.tgwsproxy.dpi.DpiProbeTarget.API)
                    }
                })
                tester.test(
                    listOf(DpiStrategyCandidate("-o1", listOf("-o1")), DpiStrategyCandidate("-s1", listOf("-s1"))),
                    port,
                ) {
                    assertNull(DpiRemoteSession.inspect(context, DpiMode.SOCKS5))
                    val stillListening = runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
                    }.isSuccess
                    assertFalse("The test proxy must exit between strategies", stillListening)
                    scores.add(it.reachable)
                }
            }
            assertEquals(true, tested)
            assertEquals(DpiMode.VPN, holder.mode.value)
            assertEquals(DpiState.Off, holder.state.value)
        }
        assertEquals(listOf(2, 3), scores)
    }

    @Suppress("DEPRECATION")
    @Test
    fun alwaysOnOptOutAndLegacySystemIntentLeaveManualVpnInUserControl() = withForeground {
        val vpnComponent = ComponentName(context, DpiVpnService::class.java)
        val service = context.packageManager.getServiceInfo(vpnComponent, PackageManager.GET_META_DATA)
        assertFalse(service.metaData.getBoolean(VpnService.SERVICE_META_DATA_SUPPORTS_ALWAYS_ON, true))

        // AOSP API 24–26 use this action without the in-app configuration.
        val systemStart = Intent(VpnService.SERVICE_INTERFACE).setComponent(vpnComponent)
        assertNotNull(context.startService(systemStart))
        assertNull(DpiRemoteSession.inspect(context, DpiMode.VPN))

        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        try {
            assertNull(VpnService.prepare(context))
            val config = DpiConfig(DpiMode.VPN, port = freePort())
            val engine = DpiVpnEngine(context, VpnAuthorizer(AndroidVpnSlotSampler(context)))
            assertEquals(DpiEngineResult.Running, engine.start(config))

            assertNotNull(context.startService(systemStart))
            assertRestoresAndStops(config)
        } finally {
            DpiRemoteSession.stopExisting(context, DpiMode.VPN)
            shell("appops set ${context.packageName} ACTIVATE_VPN default")
        }
    }

    @Test
    fun udp443FallbackReturnsPortUnreachableThroughTheNativeTunnel() {
        val peer = FileDescriptor()
        val tunnel = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, peer, tunnel)
        try {
            ParcelFileDescriptor.dup(tunnel).use { fd ->
                HevNative.setBlockUdp443(true)
                val listener = thread(name = "test-tunnel", isDaemon = true) {
                    TProxyService.startTunnel(
                        "tunnel:\n  mtu: 1500\nsocks5:\n  address: 127.0.0.1\n  port: 1080\n  udp: udp\n",
                        fd.fd,
                    )
                }
                try {
                    val deadline = SystemClock.elapsedRealtime() + 3_000
                    while (HevNative.isReady() == 0 && SystemClock.elapsedRealtime() < deadline && listener.isAlive) {
                        Thread.sleep(10)
                    }
                    assertEquals("Tunnel must be ready", 1, HevNative.isReady())

                    val packet = ByteBuffer.allocate(29)
                        .put(0x45).put(0).putShort(29).putInt(0)
                        .put(64).put(17).putShort(0)
                        .putInt(0x0a0a0a0a).putInt(0xc6336401.toInt())
                        .putShort(10_000).putShort(443).putShort(9).putShort(0).put(0)
                        .array()
                    assertEquals(packet.size, Os.write(peer, packet, 0, packet.size))
                    val poll = StructPollfd().apply { this.fd = peer; events = OsConstants.POLLIN.toShort() }
                    assertEquals("Expected ICMP rejection from TUN", 1, Os.poll(arrayOf(poll), 2_000))

                    val reply = ByteArray(128)
                    assertEquals(56, Os.read(peer, reply, 0, reply.size))
                    val header = ByteBuffer.wrap(reply)
                    assertEquals(1, reply[9].toInt()) // IPv4 protocol ICMP
                    assertEquals(0xc6336401.toInt(), header.getInt(12))
                    assertEquals(0x0a0a0a0a, header.getInt(16))
                    assertEquals(3, reply[20].toInt()) // Destination unreachable
                    assertEquals(3, reply[21].toInt()) // Port unreachable
                    assertEquals(443, header.getShort(50).toInt())
                } finally {
                    TProxyService.stopTunnel()
                    listener.join(3_000)
                    HevNative.setBlockUdp443(false)
                }
                assertFalse("Tunnel must stop", listener.isAlive)
            }
        } finally {
            Os.close(tunnel)
            Os.close(peer)
        }
    }

    private suspend fun assertRestoresAndStops(
        config: DpiConfig,
        stopFrom: DpiSessionHolder = DpiSessionHolder(),
    ) = withContext(Dispatchers.Main) {
        val restored = DpiSessionHolder()
        restored.awaitRestoration(context)
        assertEquals(DpiState.Running(config.mode), restored.state.value)
        assertEquals(config.mode, restored.mode.value)
        assertEquals("127.0.0.1:${config.port}", restored.endpoint.value)

        stopFrom.stop(context)
        stopFrom.awaitRestoration(context)
        assertEquals(DpiState.Off, stopFrom.state.value)
        withTimeout(8_000) { restored.state.first { it is DpiState.Failed } }
        assertNull(DpiRemoteSession.inspect(context, config.mode))
    }

    private fun withForeground(test: suspend () -> Unit) = runBlocking {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            test()
        } finally {
            try {
                DpiRemoteSession.stopExisting(context, DpiMode.VPN)
                DpiRemoteSession.stopExisting(context, DpiMode.SOCKS5)
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
