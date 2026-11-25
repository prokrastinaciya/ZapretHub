package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnApplicationRulesTest {
    @Test fun whitelistDoesNotMixWithDisallowedApplications() {
        val allowed = mutableListOf<String>()
        val disallowed = mutableListOf<String>()
        applyVpnApplicationRules(
            DpiVpnRouting(appListMode = VpnAppListMode.WHITELIST, apps = listOf("org.host", "org.client")),
            "org.host", { allowed += it; true }, { disallowed += it },
        )
        assertEquals(listOf("org.client"), allowed)
        assertEquals(emptyList<String>(), disallowed)
    }

    @Test fun whitelistWithNoInstalledMatchNeverBecomesAllApps() {
        assertThrows(IllegalArgumentException::class.java) {
            applyVpnApplicationRules(
                DpiVpnRouting(appListMode = VpnAppListMode.WHITELIST, apps = listOf("org.absent")),
                "org.host", { false }, { error("must not add a disallowed package") },
            )
        }
    }

    @Test fun blacklistAlwaysExcludesBothEnginesHostPackage() {
        val disallowed = mutableListOf<String>()
        applyVpnApplicationRules(
            DpiVpnRouting(appListMode = VpnAppListMode.BLACKLIST, apps = listOf("org.host", "org.excluded")),
            "org.host", { error("must not add an allowed package") }, { disallowed += it },
        )
        assertEquals(listOf("org.host", "org.excluded"), disallowed)
    }
}
