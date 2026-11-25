package com.amurcanov.tgwsproxy

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConfigurationTest {
    private fun config(disableSecure: Boolean = false) = ProxyConfiguration(
        host = "127.0.0.1", port = 1443, dcIps = "2:149.154.167.220",
        secret = "0123456789abcdef0123456789abcdef", cfEnabled = true,
        disableSecure = disableSecure
    )

    @Test fun defaultKeepsCfTlsEnabled() {
        assertFalse(JSONObject(config().toJson()).getBoolean("disable_secure"))
    }

    @Test fun optInForwardsCleartextToPython() {
        assertTrue(JSONObject(config(disableSecure = true).toJson()).getBoolean("disable_secure"))
    }
}
