package com.amurcanov.tgwsproxy.dpi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VpnStopCoordinatorTest {
    @Test fun hungNativeStopTriggersTerminationOffCallerThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val killed = CountDownLatch(1)
        val caller = Thread.currentThread()
        VpnStopCoordinator.begin(100, {
            assertNotEquals(caller, Thread.currentThread())
            entered.countDown()
            release.await()
        }, { killed.countDown() })
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(killed.await(2, TimeUnit.SECONDS))
        } finally { release.countDown() }
    }
}
