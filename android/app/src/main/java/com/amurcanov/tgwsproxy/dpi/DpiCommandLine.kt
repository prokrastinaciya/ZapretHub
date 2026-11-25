package com.amurcanov.tgwsproxy.dpi

import org.json.JSONArray
import org.json.JSONObject

/**
 * Command-line mode (upstream `ByeDpiProxyCmdPreferences`). Mirrors upstream
 * `parseCmdToArguments`: expand `{list:name}` placeholders from the domain
 * lists, shell-split, drop leading non-flag tokens, filter help/version, then
 * let the caller splice `--ip`/`--port` for the endpoint. Returns the tokens
 * minus the ip/port tokens, plus the ip/port extracted from the command.
 */
internal fun commandTokens(settings: JSONObject, snapshot: JSONObject): Pair<List<String>, Pair<String?, String?>> {
    val raw = settings.section("byedpi_cmd_args") as? String ?: DEFAULT_CMD_ARGS
    val expanded = Regex("""\{list:([^}]+)\}""").replace(raw) { match ->
        val name = match.groupValues[1].trim()
        snapshot.optJSONArray("domainLists")?.let { lists ->
            for (index in 0 until lists.length()) {
                val list = lists.getJSONObject(index)
                if (list.optString("name").equals(name, ignoreCase = true)) {
                    return@replace list.optJSONArray("domains")?.let { domains ->
                        (0 until domains.length()).joinToString(" ") { domains.getString(it) }
                    } ?: ""
                }
            }
        }
        ""
    }
    val tokens = shellSplit(expanded)
        .dropWhile { !it.startsWith("-") }
        .filter { it !in setOf("--help", "--version", "-h", "-v") }
    val (ip, port) = tokens.extractIpAndPort()
    return tokens.minusIpPort() to (ip to port)
}

private fun shellSplit(string: CharSequence): List<String> {
    val tokens = mutableListOf<String>()
    var quoteChar = ' '
    var escaping = false
    var quoting = false
    var lastCloseQuoteIndex = Int.MIN_VALUE
    var current = StringBuilder()
    for (i in string.indices) {
        val c = string[i]
        if (escaping) {
            current.append(c)
            escaping = false
        } else if (c == '\\' && quoting) {
            if (i + 1 < string.length && string[i + 1] == quoteChar) escaping = true else current.append(c)
        } else if (quoting && c == quoteChar) {
            quoting = false
            lastCloseQuoteIndex = i
        } else if (!quoting && (c == '\'' || c == '"')) {
            quoting = true
            quoteChar = c
        } else if (!quoting && Character.isWhitespace(c)) {
            if (current.isNotEmpty() || lastCloseQuoteIndex == i - 1) {
                tokens.add(current.toString())
                current = StringBuilder()
            }
        } else {
            current.append(c)
        }
    }
    if (current.isNotEmpty() || lastCloseQuoteIndex == string.length - 1) tokens.add(current.toString())
    return tokens
}

private val NO_VALUE_FLAGS = setOf(
    "--daemon", "--no-domain", "--no-ipv6", "--no-udp", "--help", "--version",
    "--transparent", "--tfo", "--md5sig", "--wait-send", "--drop-sack",
)

private fun List<String>.extractIpAndPort(): Pair<String?, String?> {
    var host: String? = null
    var port: String? = null
    var index = 0
    while (index < size) {
        val arg = this[index++]
        if (arg == "--") break
        if (arg.startsWith("--")) {
            val key = arg.substringBefore('=')
            if (NO_VALUE_FLAGS.any { it.startsWith(key) }) continue
            val value = if ('=' in arg) arg.substringAfter('=') else getOrNull(index++)
            when (key) {
                "--ip" -> host = value
                "--po", "--por", "--port" -> port = value
            }
        } else if (arg.startsWith("-")) {
            for (position in 1 until arg.length) {
                val key = arg[position]
                if (key in "DNXUhvEFSZY") continue
                val value = arg.substring(position + 1).ifEmpty { getOrNull(index++) }
                when (key) {
                    'i' -> host = value
                    'p' -> port = value
                }
                break
            }
        }
    }
    return host to port
}

/** Drops the `-i`/`-p`/`--ip`/`--port` tokens so the endpoint is never duplicated. */
private fun List<String>.minusIpPort(): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < size) {
        val arg = this[index]
        if (arg == "--") { result.add(arg); index++; continue }
        var consumedValue = false
        if (arg.startsWith("--")) {
            val key = arg.substringBefore('=')
            if (key == "--ip" || key == "--po" || key == "--por" || key == "--port") {
                if ('=' !in arg) index++
                consumedValue = true
            }
        } else if (arg.startsWith("-") && !arg.startsWith("--")) {
            for (position in 1 until arg.length) {
                val key = arg[position]
                if (key in "DNXUhvEFSZY") continue
                if (key == 'i' || key == 'p') {
                    if (position > 1) result.add(arg.substring(0, position))
                    if (arg.substring(position + 1).isEmpty()) index++
                    consumedValue = true
                }
                break
            }
        }
        if (!consumedValue) result.add(arg)
        index++
    }
    return result
}
