package com.amurcanov.tgwsproxy.dpi

import com.amurcanov.tgwsproxy.ServiceSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

internal data class DpiStrategyCandidate(val command: String, val args: List<String>)

internal data class DpiStrategyResult(
    val candidate: DpiStrategyCandidate,
    val reached: Set<DpiProbeTarget>,
    val startFailed: Boolean = false,
    val targets: Set<DpiProbeTarget> = DpiProbeTarget.entries.toSet(),
    val elapsedByTarget: Map<DpiProbeTarget, Long> = emptyMap(),
) {
    val reachable: Int get() = reached.size
    val slowestMillis: Long? get() = elapsedByTarget.values.maxOrNull()
}

internal enum class DpiProbeTarget(
    val host: String,
    path: String = "/",
    private val extraStatus: Set<Int> = emptySet(),
    mirrorHosts: List<String> = emptyList(),
    val bodyBytes: Int = 1,
) {
    WEB("www.youtube.com"),
    // A plain GET to the API root can return 404 or 405 when the endpoint is reachable.
    API("youtubei.googleapis.com", extraStatus = setOf(
        HttpURLConnection.HTTP_NOT_FOUND,
        HttpURLConnection.HTTP_BAD_METHOD,
    ), bodyBytes = 0),
    IMAGES("i.ytimg.com", "/vi/M7lc1UVf-VE/hqdefault.jpg", bodyBytes = 8 * 1024),
    VIDEO_EDGE("googlevideo.com", "/generate_204", mirrorHosts = listOf(
        "rr1---sn-4axm-n8vs.googlevideo.com",
        "rr1---sn-gvnuxaxjvh-o8ge.googlevideo.com",
        "rr4---sn-q4flrnsl.googlevideo.com",
    ), bodyBytes = 0),
    DISCORD_WEB("discord.com"),
    DISCORD_API("discord.com", "/api/v10/gateway"),
    DISCORD_GATEWAY("gateway.discord.gg", extraStatus = setOf(HttpURLConnection.HTTP_NOT_FOUND)),
    DISCORD_CDN("cdn.discordapp.com", "/embed/avatars/0.png", bodyBytes = 512);

    val urls = mirrorHosts.ifEmpty { listOf(host) }.map { "https://$it$path" }
    fun accepts(status: Int): Boolean = status in 200..299 || status in extraStatus
}

internal fun dpiTargetsFor(selection: ServiceSelection): Set<DpiProbeTarget> = buildSet {
    if (selection.youtube) addAll(setOf(DpiProbeTarget.WEB, DpiProbeTarget.API, DpiProbeTarget.IMAGES, DpiProbeTarget.VIDEO_EDGE))
    if (selection.discord) addAll(setOf(DpiProbeTarget.DISCORD_WEB, DpiProbeTarget.DISCORD_API, DpiProbeTarget.DISCORD_GATEWAY, DpiProbeTarget.DISCORD_CDN))
}

internal fun rankDpiStrategies(results: List<DpiStrategyResult>): List<DpiStrategyResult> =
    results.filter { it.reachable > 0 }.sortedWith(
        compareByDescending<DpiStrategyResult> { it.reachable }
            .thenByDescending { DpiProbeTarget.VIDEO_EDGE in it.reached }
            .thenByDescending { DpiProbeTarget.DISCORD_CDN in it.reached }
            .thenByDescending { DpiProbeTarget.API in it.reached }
            .thenBy { it.slowestMillis ?: Long.MAX_VALUE },
    )

internal class DpiStrategyTester(
    private val engine: DpiEngine,
    private val probe: suspend (Int, DpiProbeTarget) -> Boolean = ::checkDpiTarget,
) {
    suspend fun test(
        candidates: List<DpiStrategyCandidate>,
        port: Int,
        targets: Set<DpiProbeTarget> = setOf(DpiProbeTarget.WEB, DpiProbeTarget.API, DpiProbeTarget.IMAGES),
        onResult: suspend (DpiStrategyResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(targets.isNotEmpty())
        val testing = coroutineContext.job
        engine.onExit = { testing.cancel() }
        var complete = 0
        try {
            for (candidate in candidates) {
                ensureActive()
                val result = try {
                    val config = DpiConfig(DpiMode.SOCKS5, port = port, args = candidate.args)
                    if (engine.start(config) is DpiEngineResult.Running) {
                        val reachable = coroutineScope {
                            targets.map { target ->
                                async {
                                    val start = System.nanoTime()
                                    if (probe(port, target)) target to (System.nanoTime() - start) / 1_000_000 else null
                                }
                            }.awaitAll().filterNotNull().toMap()
                        }
                        DpiStrategyResult(candidate, reachable.keys, targets = targets, elapsedByTarget = reachable)
                    } else {
                        DpiStrategyResult(candidate, emptySet(), startFailed = true, targets = targets)
                    }
                } finally {
                    withContext(NonCancellable) { engine.stop() }
                }
                withContext(NonCancellable) { onResult(result) }
                if (result.reached.containsAll(targets) && ++complete >= 3) break
            }
        } finally {
            engine.onExit = null
        }
    }
}

private fun checkDpiTarget(port: Int, target: DpiProbeTarget): Boolean {
    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
    return target.urls.any { url -> checkDpiUrl(url, proxy, target) }
}

private fun checkDpiUrl(url: String, proxy: Proxy, target: DpiProbeTarget): Boolean {
    val connection = URL(url).openConnection(proxy) as HttpURLConnection
    return try {
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Connection", "close")
        val status = connection.responseCode
        if (!target.accepts(status)) return false
        if (target.bodyBytes == 0 || status !in 200..299) return true

        // Measure some payload transfer, not just the response headers.
        val buffer = ByteArray(target.bodyBytes)
        connection.inputStream.use { input ->
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) return false
                total += read
            }
        }
        true
    } catch (_: IOException) {
        false
    } finally {
        connection.disconnect()
    }
}
