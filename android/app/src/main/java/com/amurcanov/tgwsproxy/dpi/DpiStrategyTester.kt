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
) {
    val reachable: Int get() = reached.size
}

internal enum class DpiProbeTarget(
    val host: String,
    path: String = "/",
    private val extraStatus: Set<Int> = emptySet(),
) {
    WEB("www.youtube.com"),
    // A plain GET to the API root can return 404 or 405 when the endpoint is reachable.
    API("youtubei.googleapis.com", extraStatus = setOf(
        HttpURLConnection.HTTP_NOT_FOUND,
        HttpURLConnection.HTTP_BAD_METHOD,
    )),
    IMAGES("i.ytimg.com", "/vi/M7lc1UVf-VE/hqdefault.jpg"),
    DISCORD_WEB("discord.com"),
    DISCORD_API("discord.com", "/api/v10/gateway");

    val url = "https://$host$path"
    fun accepts(status: Int): Boolean = status in 200..299 || status in extraStatus
}

internal fun dpiTargetsFor(selection: ServiceSelection): Set<DpiProbeTarget> = buildSet {
    if (selection.youtube) addAll(setOf(DpiProbeTarget.WEB, DpiProbeTarget.API, DpiProbeTarget.IMAGES))
    if (selection.discord) addAll(setOf(DpiProbeTarget.DISCORD_WEB, DpiProbeTarget.DISCORD_API))
}

internal fun rankDpiStrategies(results: List<DpiStrategyResult>): List<DpiStrategyResult> =
    results.filter { it.reachable > 0 }.sortedWith(
        compareByDescending<DpiStrategyResult> { it.reachable }
            .thenByDescending { DpiProbeTarget.API in it.reached },
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
        try {
            for (candidate in candidates) {
                ensureActive()
                val result = try {
                    val config = DpiConfig(DpiMode.SOCKS5, port = port, args = candidate.args)
                    if (engine.start(config) is DpiEngineResult.Running) {
                        val reachable = coroutineScope {
                            targets.map { target -> async { target.takeIf { probe(port, target) } } }
                                .awaitAll().filterNotNull().toSet()
                        }
                        DpiStrategyResult(candidate, reachable, targets = targets)
                    } else {
                        DpiStrategyResult(candidate, emptySet(), startFailed = true, targets = targets)
                    }
                } finally {
                    withContext(NonCancellable) { engine.stop() }
                }
                withContext(NonCancellable) { onResult(result) }
                if (result.reached.containsAll(targets)) break
            }
        } finally {
            engine.onExit = null
        }
    }
}

private fun checkDpiTarget(port: Int, target: DpiProbeTarget): Boolean {
    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
    val connection = URL(target.url).openConnection(proxy) as HttpURLConnection
    return try {
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Connection", "close")
        val status = connection.responseCode
        target.accepts(status) && (
            target == DpiProbeTarget.API || status !in 200..299 ||
                connection.inputStream.use { it.read() != -1 }
            )
    } catch (_: IOException) {
        false
    } finally {
        connection.disconnect()
    }
}
