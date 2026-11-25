package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertThrows
import org.junit.Test

class DpiEndpointDefenseTest {
    @Test fun rejectsEndpointOverridesInBothModes() {
        for (mode in DpiMode.values()) {
            for (arg in listOf("--ip=0.0.0.0", "--port=42", "--po=42", "-i0.0.0.0", "-Xp42")) {
                assertThrows(IllegalArgumentException::class.java) {
                    DpiConfig(mode, args = listOf(arg)).toArgv()
                }
            }
            assertThrows(IllegalArgumentException::class.java) { DpiConfig(mode, host = "192.0.2.1").toArgv() }
            assertThrows(IllegalArgumentException::class.java) { DpiConfig(mode, port = 0).toArgv() }
        }
    }
}
