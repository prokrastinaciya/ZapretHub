package com.amurcanov.tgwsproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelegramAndUpdateTest {
    @Test fun linkUsesActualConfigurationAndCompleteSecret() {
        val connection = ActiveProxyConnection("127.0.0.1", 23456, "ee0123456789abcdef0123456789abcdef74656c656772616d2e6f7267")
        val uri = TelegramConnector.proxyUri(connection)
        assertEquals("tg", uri.scheme)
        assertEquals("proxy", uri.authority)
        assertEquals("127.0.0.1", uri.getQueryParameter("server"))
        assertEquals("23456", uri.getQueryParameter("port"))
        assertEquals(connection.secret, uri.getQueryParameter("secret"))
        assertFalse(uri.toString().startsWith("https:"))
    }

    @Test fun wildcardBindingUsesDeviceLoopback() {
        assertEquals("127.0.0.1", TelegramConnector.proxyUri(ActiveProxyConnection("0.0.0.0", 1443, "dd00")).getQueryParameter("server"))
        assertEquals("::1", TelegramConnector.proxyUri(ActiveProxyConnection("::", 1443, "dd00")).getQueryParameter("server"))
        assertEquals("2001:db8::1", TelegramConnector.proxyUri(ActiveProxyConnection("2001:db8::1", 1443, "dd00")).getQueryParameter("server"))
    }

    private val releaseJson = """
        {
            "tag_name": "v1.1.0", "draft": false, "prerelease": false,
            "html_url": "https://example.invalid/fake",
            "assets": [
                {"name":"update.json", "browser_download_url":"https://example.invalid/update.json"},
                {"name":"SHA256SUMS"}, {"name":"tgwsproxy-1.1.0-arm64.apk"}
            ]
        }
    """.trimIndent()
    private val metadataJson = """
        {"versionCode":1050,"versionName":"1.1.0","apk":"tgwsproxy-1.1.0-arm64.apk","sha256":"${"a".repeat(64)}"}
    """.trimIndent()

    @Test fun usesOnlyCanonicalMetadataAndReleaseUrls() {
        val result = parseRelease(JSONObject(releaseJson)) { url ->
            assertEquals("https://github.com/Derigava/tg-ws-proxy-android/releases/download/v1.1.0/update.json", url)
            metadataJson
        }
        assertEquals(AppReleaseInfo("v1.1.0", 1050), result)
        assertEquals("https://github.com/Derigava/tg-ws-proxy-android/releases/tag/v1.1.0", result?.releaseUrl)
    }

    @Test fun excludesUnpublishedAndIncompleteReleasesBeforeMetadataRequest() {
        for (field in listOf("draft", "prerelease")) {
            assertNull(parseRelease(JSONObject(releaseJson).put(field, true)) { error("Unexpected metadata request") })
        }
        val noChecksum = JSONObject(releaseJson.replace("SHA256SUMS", "notes.txt"))
        assertNull(parseRelease(noChecksum) { error("Unexpected metadata request") })
    }

    @Test fun rejectsPathInTagBeforeMetadataRequest() {
        val badTag = JSONObject(releaseJson).put("tag_name", "v1.1.0/../../fake")
        assertTrue(runCatching { parseRelease(badTag) { error("Unexpected metadata request") } }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun rejectsInconsistentMetadata() {
        val release = JSONObject(releaseJson)
        val wrongVersion = metadataJson.replace("1.1.0", "1.0.41")
        assertTrue(runCatching { parseRelease(release) { wrongVersion } }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun requestedPermissionsExcludeDirectInstallationAndBatteryExemption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        assertFalse("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in permissions)
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE" in permissions)
    }

    @Test fun sensitiveAppStateIsOutsideAndroidBackups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun bootServiceAndLauncherShortcutArePrivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val boot = pm.getReceiverInfo(ComponentName(context, BootReceiver::class.java), 0)
        @Suppress("DEPRECATION")
        val service = pm.getServiceInfo(ComponentName(context, ProxyService::class.java), 0)
        @Suppress("DEPRECATION")
        val shortcut = pm.getActivityInfo(ComponentName(context, LauncherShortcutActivity::class.java), 0)
        assertFalse(boot.exported)
        assertFalse(service.exported)
        assertFalse(shortcut.exported)
        assertNull(pm.resolveContentProvider("${context.packageName}.updates", 0))
        assertNotNull(pm.resolveContentProvider("${context.packageName}.logs", 0))
    }

    @Test fun releaseVersionComparisonHandlesMissingSegments() {
        assertTrue(isNewerVersion("v1.2.9", "1.3.0"))
        assertFalse(isNewerVersion("1.3", "v1.3.0"))
        assertFalse(isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(isNewerVersion("1.0", "unknown"))
    }
}
