package com.amurcanov.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.dpi.DpiProbeTarget
import com.amurcanov.tgwsproxy.dpi.DpiStrategyCandidate
import com.amurcanov.tgwsproxy.dpi.DpiStrategyResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DpiSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = DpiSettingsStore(context)
    // Fixture matches the AppSettings/Command/DomainList DTOs at v.1.8.2.
    private val fixture = """{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080","ipv6_enable":true},"apps":["org.example.client"],"history":[{"text":"-s1","pinned":true}],"domainLists":[{"id":"test","name":"Example","domains":["example.org"]}]}"""

    @Test fun fullPassesPersistWithoutApplyingOrDuplicatingStrategies() {
        store.importJson(fixture)
        val before = store.exportJson()
        val first = DpiStrategyCandidate("-d1", listOf("-d1"))
        val second = DpiStrategyCandidate("-s2", listOf("-s2"))
        val full = DpiProbeTarget.entries.toSet()

        store.saveSuccessfulStrategy(DpiStrategyResult(first, setOf(DpiProbeTarget.WEB)))
        assertEquals(emptyList<String>(), store.successfulStrategies())
        store.saveSuccessfulStrategy(DpiStrategyResult(first, full))
        store.saveSuccessfulStrategy(DpiStrategyResult(second, full))
        store.saveSuccessfulStrategy(DpiStrategyResult(first, full))

        val reopened = DpiSettingsStore(context)
        assertEquals(listOf("-s2", "-d1"), reopened.successfulStrategies())
        assertEquals(before, reopened.exportJson())
        assertTrue(reopened.strategyCandidates(emptySequence()).any { it.command == "-s2" })

        reopened.resetSettings()
        assertEquals(emptyList<String>(), DpiSettingsStore(context).successfulStrategies())
    }

    @Test fun portableBackupRestoresSuccessfulStrategiesAndRejectsMalformedResultsAtomically() {
        store.resetSettings()
        store.importJson(fixture)
        store.saveSuccessfulStrategy(DpiStrategyResult(DpiStrategyCandidate("-d1", listOf("-d1")), DpiProbeTarget.entries.toSet()))
        val backup = store.exportPortableBackup()
        store.resetSettings()
        store.importJson(backup)
        assertEquals(listOf("-d1"), store.successfulStrategies())
        assertEquals("1080", store.currentSettings()["byedpi_proxy_port"])
        val before = store.exportPortableBackup()
        val invalid = JSONObject(backup).put("successfulStrategies", org.json.JSONArray().put(false)).toString()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(invalid) }
        assertEquals(before, store.exportPortableBackup())
    }

    @Test fun diagnosticsSnapshotUsesOneDocumentAndOmitsArguments() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_enable_cmd_settings":true,"byedpi_cmd_args":"-H{list:private} --port 2345"},"domainLists":[{"id":"1","name":"private","domains":["secret.example"]}]}""")
        val first = store.diagnosticsSnapshot()
        assertTrue(first.commandMode)
        assertEquals(2345, first.port)
        assertFalse(first.toString().contains("secret.example"))
        assertFalse(first.toString().contains("-H"))
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_enable_cmd_settings":false,"byedpi_proxy_port":"3456"}}""")
        val second = store.diagnosticsSnapshot()
        assertFalse(second.commandMode)
        assertEquals(3456, second.port)
    }

    @Test fun observersSeeImportAndResetFromOtherStoreInstances() {
        store.resetSettings()
        val seen = mutableListOf<String>()
        val stop = store.observeDocument { seen.add(it) }
        val other = DpiSettingsStore(context)
        other.importJson(fixture)
        other.resetSettings()
        stop()
        other.importJson(fixture)
        assertEquals(2, seen.size)
        assertTrue(JSONObject(seen[0]).has("apps"))
        assertFalse(JSONObject(seen[1]).has("apps"))
    }

    @Test fun pinnedFormatRoundTripsAllSectionsWithoutTgKeys() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"secret_key\":\"not-for-export\""))
        val output = JSONObject(store.exportJson())
        val input = JSONObject(fixture)
        for (section in listOf("settings", "apps", "history", "domainLists")) {
            assertEquals(input.get(section).toString(), output.get(section).toString())
        }
        assertFalse(output.toString().contains("not-for-export"))
    }

    @Test fun strategyParametersMapToEngineArguments() {
        val strategy = """{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080","byedpi_enable_cmd_settings":false,"byedpi_max_connections":"100","byedpi_buffer_size":"4096","byedpi_desync_http":true,"byedpi_desync_https":true,"byedpi_desync_udp":true,"byedpi_hosts_mode":"blacklist","byedpi_hosts_blacklist":"example.com\ngoogle.com","byedpi_default_ttl":"64","byedpi_no_domain":true,"byedpi_desync_method":"oob","byedpi_split_position":"2","byedpi_split_at_host":true,"byedpi_fake_ttl":"8","byedpi_fake_sni":"www.iana.org","byedpi_fake_offset":"4","byedpi_oob_data":"a","byedpi_host_mixed_case":true,"byedpi_host_remove_spaces":true,"byedpi_tlsrec_enabled":true,"byedpi_tlsrec_position":"3","byedpi_tlsrec_at_sni":true,"byedpi_tcp_fast_open":true,"byedpi_drop_sack":true,"byedpi_udp_fake_count":"2"}}"""
        store.importJson(strategy)
        assertEquals(
            listOf(
                "-c100", "-b4096", "-H:example.com google.com", "-An", "-Kt,h",
                "-g64", "-N", "-o2+h", "-e97", "-Mh,r", "-r3+s", "-F", "-Y",
                "-An", "-Ku", "-a2", "-An",
            ),
            store.connectionOptions().args,
        )
    }

    @Test fun defaultIsCommandModeWithUpstreamDefaults() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        // Upstream defaults byedpi_enable_cmd_settings=true with DEFAULT_CMD_ARGS "-o1 -a1 -r-5+se".
        assertEquals(listOf("-o1", "-a1", "-r-5+se"), store.connectionOptions().args)
    }

    @Test fun uiModeUsesStandardEditorArgs() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080","byedpi_enable_cmd_settings":false}}""")
        assertEquals(
            listOf("-c512", "-b16384", "-Kt,h", "-o1", "-e97", "-An", "-Ku", "-a1", "-An"),
            store.connectionOptions().args,
        )
    }

    @Test fun commandModeEndpointOverridesDefault() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_enable_cmd_settings":true,"byedpi_cmd_args":"--ip 127.0.0.1 --port 2345 -o2"}}""")
        val options = store.connectionOptions()
        assertEquals(2345, options.port)
        assertEquals("127.0.0.1", options.host)
        assertEquals(listOf("-o2"), options.args)
    }

    @Test fun groupedFlagsSurviveEndpointExtraction() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_cmd_args":"-DNp2345 -FXi127.0.0.1 -o2"}}""")
        val options = store.connectionOptions()
        assertEquals(2345, options.port)
        assertEquals(listOf("-DN", "-FX", "-o2"), options.args)
    }

    @Test fun commandListPlaceholderExpandsDomains() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_enable_cmd_settings":true,"byedpi_cmd_args":"-H{list:blocked}"},"domainLists":[{"id":"1","name":"blocked","domains":["a.com"]}]}""")
        assertEquals(listOf("-Ha.com"), store.connectionOptions().args)
    }

    @Test fun invalidStrategyValueDoesNotOverwriteBackup() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"byedpi_enable_cmd_settings\":false"))
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.importJson(before.replace("\"byedpi_enable_cmd_settings\":false", "\"byedpi_enable_cmd_settings\":false,\"byedpi_desync_method\":\"bogus\""))
        }
        assertEquals(before, store.exportJson())
    }

    @Test fun importsEndpointAndPerAppRoutingWithoutActivatingVpn() {
        store.importJson(fixture.replace("\"1080\"", "\"23456\"").replace("\"ipv6_enable\":true", "\"ipv6_enable\":false,\"applist_type\":\"whitelist\""))
        val options = store.connectionOptions()
        assertEquals(23456, options.port)
        assertEquals("127.0.0.1", options.host)
        assertFalse(options.routing.ipv6)
        assertEquals(com.amurcanov.tgwsproxy.dpi.VpnAppListMode.WHITELIST, options.routing.appListMode)
        assertEquals(listOf("org.example.client"), options.routing.apps)
    }

    @Test fun dnsAcceptsOnlyNumericAddressesOrSystemDefault() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"dns_ip\":\"2001:db8::53\""))
        assertEquals("2001:db8::53", store.connectionOptions().routing.dns)
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.importJson(before.replace("2001:db8::53", "dns.example.org"))
        }
        assertEquals(before, store.exportJson())
        store.importJson(before.replace("2001:db8::53", ""))
        assertEquals("", store.connectionOptions().routing.dns)
    }

    @Test fun invalidImportedEndpointDoesNotOverwriteBackup() {
        store.importJson(fixture)
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.importJson(fixture.replace("\"1080\"", "\"99999\""))
        }
        assertEquals(before, store.exportJson())
    }

    @Test fun emptyStoreCanExportAnImportableBackup() {
        context.getSharedPreferences("dpi_document", Context.MODE_PRIVATE).edit().clear().commit()
        val initial = store.exportJson()
        store.importJson(initial)
        assertEquals(initial, store.exportJson())
    }

    @Test fun malformedLaterSectionDoesNotReplacePreviousSnapshot() {
        store.importJson(fixture)
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.importJson(fixture.replace("\"example.org\"", "false"))
        }
        assertEquals(before, DpiSettingsStore(context).exportJson())
    }

    @Test fun editingStrategyFieldsPersistsAndRevalidates() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"byedpi_enable_cmd_settings\":false"))
        store.updateSettings(mapOf("byedpi_desync_method" to "split", "byedpi_split_position" to "4"))
        val options = store.connectionOptions()
        assertTrue(options.args.contains("-s4"))
        assertFalse(options.args.contains("-o1"))
        val document = JSONObject(store.exportJson()).getJSONObject("settings")
        assertEquals("split", document.getString("byedpi_desync_method"))
    }

    @Test fun editingInvalidStrategyDoesNotOverwriteBackup() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"byedpi_enable_cmd_settings\":false"))
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.updateSettings(mapOf("byedpi_desync_method" to "bogus"))
        }
        assertEquals(before, store.exportJson())
    }

    @Test fun editingUnknownSettingIsRejected() {
        store.importJson(fixture)
        assertThrows(IllegalArgumentException::class.java) {
            store.updateSettings(mapOf("secret_key" to "must-not-leak"))
        }
    }

    @Test fun commandHistoryDedupsPinsAndCaps() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        store.addCommand("-o1")
        store.addCommand("-o2")
        store.addCommand("-o1") // duplicate: no second entry
        assertEquals(listOf("-o2", "-o1"), store.history().map { it.text })
        store.pinCommand("-o2")
        assertEquals(listOf("-o2"), store.pinnedHistory().map { it.text })
        store.unpinCommand("-o2")
        assertTrue(store.pinnedHistory().isEmpty())
    }

    @Test fun clearUnpinnedKeepsPinnedCommands() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        store.addCommand("-o1")
        store.addCommand("-o2")
        store.pinCommand("-o2")
        store.clearUnpinnedHistory()
        assertEquals(listOf("-o2"), store.history().map { it.text })
    }

    @Test fun deleteAndRenameCommands() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        store.addCommand("-o1")
        store.renameCommand("-o1", "default")
        assertEquals("default", store.history().first().name)
        store.deleteCommand("-o1")
        assertTrue(store.history().isEmpty())
    }

    @Test fun applyStrategySwitchesToCommandMode() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080","byedpi_enable_cmd_settings":false}}""")
        store.applyStrategy("-d3 -a2")
        val options = store.connectionOptions()
        assertEquals(listOf("-d3", "-a2"), options.args)
        assertEquals("-d3 -a2", store.currentSettings()["byedpi_cmd_args"])
        assertEquals(listOf("-d3 -a2"), store.history().map { it.text }.take(1))
    }

    @Test fun resetSettingsClearsToDefaultDocument() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"byedpi_desync_method\":\"split\""))
        store.addCommand("-o1")
        store.resetSettings()
        val document = JSONObject(store.exportJson())
        assertEquals("io.github.romanvht.byedpi", document.getString("app"))
        assertFalse(document.has("history"))
        assertFalse(document.has("apps"))
        assertEquals(listOf("-o1", "-a1", "-r-5+se"), store.connectionOptions().args)
    }

    @Test fun domainListsAddUpdateDelete() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        store.addDomainList("Blocked", listOf("a.com", "b.org"))
        val list = store.domainLists().single()
        assertEquals("blocked", list.id)
        assertEquals(listOf("a.com", "b.org"), list.domains)
        store.updateDomainList("blocked", "Blocked sites", listOf("c.net"))
        assertEquals(listOf("c.net"), store.domainLists().single().domains)
        store.deleteDomainList("blocked")
        assertTrue(store.domainLists().isEmpty())
    }

    @Test fun appSelectionPersistsWhitelist() {
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        store.setAppList("whitelist", listOf("org.a", "org.b", "org.a"))
        val options = store.connectionOptions()
        assertEquals(com.amurcanov.tgwsproxy.dpi.VpnAppListMode.WHITELIST, options.routing.appListMode)
        assertEquals(listOf("org.a", "org.b"), options.routing.apps)
    }

    @Test fun invalidAppListTypeDoesNotOverwriteBackup() {
        store.importJson(fixture)
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.setAppList("bogus", listOf("org.a"))
        }
        assertEquals(before, store.exportJson())
    }
}
