package com.amurcanov.tgwsproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val UPDATE_CHECK_NEVER = -1
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
private const val RELEASE_API = "https://api.github.com/repos/Derigava/tg-ws-proxy-android/releases/latest"
private const val RELEASE_PREFIX = "https://github.com/Derigava/tg-ws-proxy-android/releases/"
private val RELEASE_TAG = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

fun updateIntervalLabel(context: Context, hours: Int): String = when (hours) {
    7 -> context.getString(R.string.interval_7h)
    24 -> context.getString(R.string.interval_24h)
    48 -> context.getString(R.string.interval_48h)
    UPDATE_CHECK_NEVER -> context.getString(R.string.interval_never)
    else -> context.getString(R.string.interval_hours, hours)
}

fun updateIntervalLabel(hours: Int): String = when (hours) {
    7 -> "7 h"
    24 -> "24 h"
    48 -> "48 h"
    UPDATE_CHECK_NEVER -> "Never"
    else -> "$hours h"
}

data class AppReleaseInfo(
    val versionTag: String,
    val versionCode: Long
) {
    init {
        require(RELEASE_TAG.matches(versionTag) && versionCode > 0)
    }

    val releaseUrl: String get() = RELEASE_PREFIX + "tag/" + versionTag
}

internal fun AppReleaseInfo.cacheJson(): String = JSONObject()
    .put("versionTag", versionTag).put("versionCode", versionCode).toString()

internal fun cachedReleaseInfo(json: String): AppReleaseInfo? = runCatching {
    if (json.isBlank()) return@runCatching null
    val value = JSONObject(json)
    AppReleaseInfo(value.getString("versionTag"), value.getLong("versionCode"))
}.getOrNull()

/** Serializes UI/background checks and reuses a fresh persisted result. */
object AppUpdateRepository {
    private val checkLock = Mutex()

    suspend fun check(context: Context, force: Boolean = false): AppReleaseInfo? {
        if (!BuildConfig.APP_UPDATES_ENABLED) return null
        return checkLock.withLock {
            val store = SettingsStore(context.applicationContext)
            val hours = store.updateCheckIntervalHours.first()
            if (!force && hours == UPDATE_CHECK_NEVER) return@withLock null
            val interval = updateIntervalHoursToMillis(hours)
                ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)!!
            val checkedAt = System.currentTimeMillis()
            val lastCheckAt = store.updateLastCheckAt.first()
            if (!force && lastCheckAt > 0 && checkedAt - lastCheckAt < interval) {
                return@withLock store.updateLatestRelease.first()
            }
            val release = fetchLatestReleaseInfo()
            store.saveUpdateCheckResult(checkedAt, release,
                if (release == null) context.getString(R.string.update_check_failed_short) else "")
            release
        }
    }
}

const val UPDATE_DIALOG_ACTION_POSTPONED = "postponed"
const val UPDATE_DIALOG_ACTION_RELEASE_OPENED = "release_opened"

/** Only published stable releases with complete APK metadata are update candidates. */
private suspend fun fetchLatestReleaseInfo(): AppReleaseInfo? = withContext(Dispatchers.IO) {
    if (!BuildConfig.APP_UPDATES_ENABLED) return@withContext null
    try {
        parseRelease(JSONObject(readUpdateText(RELEASE_API))) { url ->
            readUpdateText(url, releaseAsset = true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("TgWsProxy", "Update check unavailable: ${e.javaClass.simpleName}")
        null
    }
}

internal fun parseRelease(release: JSONObject, readMetadata: (String) -> String): AppReleaseInfo? {
    if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null
    val tag = release.getString("tag_name")
    require(RELEASE_TAG.matches(tag)) { "Invalid release tag" }
    val name = tag.removePrefix("v")
    val apkName = "tgwsproxy-$name-arm64.apk"
    val assets = release.getJSONArray("assets")
    val available = (0 until assets.length()).map { assets.getJSONObject(it).getString("name") }
    if (listOf("update.json", "SHA256SUMS", apkName).any { required -> available.count { it == required } != 1 }) {
        return null
    }
    val metadata = JSONObject(readMetadata(RELEASE_PREFIX + "download/$tag/update.json"))
    require(metadata.getString("versionName") == name && metadata.getString("apk") == apkName) {
        "Update metadata does not describe this release"
    }
    require(Regex("[0-9a-fA-F]{64}").matches(metadata.getString("sha256"))) { "Invalid APK checksum" }
    return AppReleaseInfo(tag, metadata.getLong("versionCode"))
}

fun isNewerVersion(local: String, remote: String): Boolean {
    fun parts(value: String) = Regex("\\d+(?:\\.\\d+)*").find(value)?.value
        ?.split('.')?.mapNotNull { it.toLongOrNull() }.orEmpty()
    val localParts = parts(local)
    val remoteParts = parts(remote)
    if (remoteParts.isEmpty()) return false
    for (i in 0 until maxOf(localParts.size, remoteParts.size)) {
        val comparison = remoteParts.getOrElse(i) { 0L }.compareTo(localParts.getOrElse(i) { 0L })
        if (comparison != 0) return comparison > 0
    }
    return false
}

private fun openUpdateConnection(url: String): HttpURLConnection {
    require(URL(url).protocol == "https") { "HTTPS required for updates" }
    return (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        useCaches = false
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "DerigavaTGWS/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/octet-stream")
        setRequestProperty("Cache-Control", "no-cache")
    }
}

private fun readUpdateText(url: String, releaseAsset: Boolean = false): String {
    val connection = openUpdateConnection(url)
    try {
        val status = connection.responseCode
        if (releaseAsset && status in setOf(301, 302, 303, 307, 308)) {
            val redirect = URL(connection.getHeaderField("Location") ?: throw IOException("Missing update redirect"))
            require(redirect.protocol == "https" && redirect.host == "release-assets.githubusercontent.com" &&
                redirect.userInfo == null && redirect.port in setOf(-1, 443)) { "Unexpected update redirect" }
            return readUpdateText(redirect.toExternalForm())
        }
        if (status !in 200..299) throw IOException("Update server HTTP $status")
        connection.inputStream.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                require(output.size() + size <= 1024 * 1024) { "Update metadata too large" }
                output.write(buffer, 0, size)
            }
            return output.toByteArray().toString(Charsets.UTF_8)
        }
    } finally { connection.disconnect() }
}
