package com.amurcanov.tgwsproxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Optimized LogManager: uses a Channel + batching approach to avoid
 * creating a new list on every single log line — reduces GC pressure
 * and eliminates UI jank caused by high-frequency log updates.
 *
 * Key optimizations:
 * - Channel-based buffering: log lines are queued, not applied immediately
 * - Batch processing: up to 20 lines applied per tick (every 150ms)
 * - Array-backed list with cap of 50: avoids growing/shrinking allocations
 * - Duplicate merging: last-entry count increment done in-place conceptually
 */
object LogManager {
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    private var job: Job? = null
    private var logcatProcess: Process? = null
    private val nextKey = AtomicLong(0)

    // Buffered channel — absorbs bursts of log lines without blocking the reader
    private val logChannel = Channel<LogEntry>(capacity = BUFFERED)

    fun startListening() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO).launch {
            // Start logcat reader coroutine
            val readerJob = launch(Dispatchers.IO) {
                try {
                    val pid = android.os.Process.myPid()
                    val process = ProcessBuilder("logcat", "-v", "tag", "--pid", pid.toString())
                        .redirectErrorStream(true)
                        .start()

                    logcatProcess = process

                    process.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = try { reader.readLine() } catch (e: Exception) { null } ?: break
                            val entry = parseLine(line) ?: continue
                            logChannel.trySend(entry)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    logcatProcess?.destroy()
                    logcatProcess = null
                }
            }

            // Batch consumer: collects queued entries and applies in batches
            launch {
                val pendingBatch = mutableListOf<LogEntry>()
                while (isActive) {
                    // Drain the channel (non-blocking)
                    var received = logChannel.tryReceive()
                    while (received.isSuccess) {
                        pendingBatch.add(received.getOrThrow())
                        if (pendingBatch.size >= 20) break // cap batch size
                        received = logChannel.tryReceive()
                    }

                    if (pendingBatch.isNotEmpty()) {
                        // Apply batch to state — single list mutation
                        logs.value = applyBatch(logs.value, pendingBatch)
                        pendingBatch.clear()
                    }

                    // Throttle updates — 150ms between UI refreshes
                    delay(150)
                }
            }

            readerJob.join()
        }
    }

    /**
     * Efficiently applies a batch of new entries to the current log list.
     * Merges consecutive duplicates and caps at 50 entries.
     */
    private fun applyBatch(current: List<LogEntry>, batch: List<LogEntry>): List<LogEntry> {
        val result = ArrayDeque(current)
        for (entry in batch) {
            var merged = false
            val searchDepth = minOf(result.size, 10)
            for (i in result.indices.reversed().take(searchDepth)) {
                if (result[i].message == entry.message) {
                    val existing = result.removeAt(i)
                    result.addLast(existing.copy(count = existing.count + 1))
                    merged = true
                    break
                }
            }
            if (!merged) {
                result.addLast(entry)
            }
        }
        while (result.size > 50) {
            result.removeFirst()
        }
        return result.toList()
    }

    fun stopListening() {
        job?.cancel()
        job = null
        logcatProcess?.destroy()
        logcatProcess = null
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    private fun parseLine(raw: String): LogEntry? {
        var message: String
        val isError: Boolean
        val priority: Int

        when {
            raw.contains("[ERROR]") -> {
                message = raw.substringAfter("[ERROR]").trim()
                isError = true
                priority = 6 // Log.ERROR
            }
            raw.contains("[WARN]") -> {
                message = raw.substringAfter("[WARN]").trim()
                isError = false // WARN is not ERROR, but distinctive
                priority = 5 // Log.WARN
            }
            raw.contains("[DEBUG]") -> {
                message = raw.substringAfter("[DEBUG]").trim()
                isError = false
                priority = 3 // Log.DEBUG
            }
            raw.contains("TgWsProxy") -> {
                // Info doesn't have a prefix, so we strip basically everything up to the actual message
                var msg = raw.substringAfter("TgWsProxy:").trim()
                if (msg.startsWith("[ERROR]") || msg.startsWith("[WARN]") || msg.startsWith("[DEBUG]")) {
                     return null // Handled above, but just in case
                }

                // Strip dynamic metrics like ↑3.3KB ↓1.1KB 0.3с so that lines can collapse
                if (msg.contains("↑")) {
                    msg = msg.substringBefore("↑").trim()
                }
                if (msg.contains("↓")) {
                    msg = msg.substringBefore("↓").trim()
                }

                message = msg
                isError = false
                priority = 4 // Log.INFO
            }
            else -> return null
        }

        // Remove emojis and stickers
        val emojiRegex = Regex("[\\x{1F300}-\\x{1F5FF}\\x{1F900}-\\x{1F9FF}\\x{1F600}-\\x{1F64F}\\x{1F680}-\\x{1F6FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{1F191}-\\x{1F251}\\x{1F004}\\x{1F0CF}\\x{1F170}-\\x{1F171}\\x{1F17E}-\\x{1F17F}\\x{1F18E}\\x{3030}\\x{2B50}\\x{2B55}\\x{2934}-\\x{2935}\\x{2B05}-\\x{2B07}\\x{2B1B}-\\x{2B1C}\\x{3297}\\x{3299}\\x{303D}\\x{00A9}\\x{00AE}\\x{2122}\\x{23F3}\\x{24C2}\\x{23E9}-\\x{23EF}\\x{25B6}\\x{23F8}-\\x{23FA}⚠✅❌⚡🔥🔄🔗]")
        message = message.replace(emojiRegex, "").trim()
        // The core's startup banner frames itself with rulers; they say nothing in a log view.
        if (message.isEmpty() || message.all { it == '=' || it == '-' }) return null

        val isEssential = listOf(
            "pool", "key:", "started", "address:", "error", "failed", "blocked",
            "Пул", "Ключ:", "запущен", "Адрес:", "ошибка", "провалены", "заблокирован"
        ).any { marker -> message.contains(marker, ignoreCase = true) }

        return LogEntry(
            key = "log_${nextKey.getAndIncrement()}",
            message = message,
            count = 1,
            isError = isError,
            priority = priority,
            isEssential = isEssential
        )
    }}
