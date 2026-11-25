package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Timestamped journal written by the Python side (android_proxy.set_log_file).
 * It outlives the activity and logcat's ring buffer, so a report can cover
 * background drops from hours ago. Secrets and Worker domains are already
 * redacted when a line is written.
 */
object ProxyJournal {
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L
    private val LINE = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (\w+) (.*)$""")

    fun file(context: Context) = File(context.filesDir, "logs/proxy.log")

    /** Oldest first: rotated backups, then the live file. */
    fun files(context: Context): List<File> {
        val live = file(context)
        return listOf(File(live.path + ".2"), File(live.path + ".1"), live).filter { it.isFile }
    }

    data class Problem(val lastSeen: String, val level: String, val message: String, val count: Int)

    /**
     * Warnings, errors and service events from the last 24 hours, newest last.
     * Repeats collapse into one row with a count; null when there is no journal yet.
     */
    fun recentProblems(context: Context, now: Long = System.currentTimeMillis(), limit: Int = 30): List<Problem>? {
        val sources = files(context)
        if (sources.isEmpty()) return null
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val rows = LinkedHashMap<String, Problem>()
        for (source in sources) source.forEachLine(Charsets.UTF_8) { line ->
            val match = LINE.matchEntire(line) ?: return@forEachLine
            val (stamp, level, message) = match.destructured
            val important = level == "WARNING" || level == "ERROR" || message.startsWith("[service]")
            if (!important) return@forEachLine
            val time = runCatching { format.parse(stamp)?.time }.getOrNull() ?: return@forEachLine
            if (now - time > WINDOW_MS) return@forEachLine
            val key = "$level|$message"
            val previous = rows.remove(key)  // Re-insert so the order follows the latest occurrence.
            rows[key] = Problem(stamp.substring(5, 16), level, message, (previous?.count ?: 0) + 1)
        }
        return rows.values.toList().takeLast(limit)
    }

    /** Shares the whole journal as one text file, for issues that need hours of history. */
    fun share(context: Context): Boolean {
        val sources = files(context)
        if (sources.isEmpty()) return false
        val folder = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(folder, "tgwsproxy-log.txt")
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}, core ${BuildConfig.CORE_VERSION}")
            sources.forEach { source -> source.bufferedReader(Charsets.UTF_8).use { it.copyTo(writer) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_log)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
