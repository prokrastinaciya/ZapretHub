package com.amurcanov.tgwsproxy.dpi

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun decodeDpiDocument(json: String): Pair<JSONObject, JSONArray?> {
    require(json.length <= MAX_CHARS) { "ByeByeDPI document exceeds the size limit" }
    var successful: JSONArray? = null
    val validated = try {
        val source = JSONObject(json)
        require(source.get("app") == UPSTREAM_APP)
        if (source.has("tgwsBackupVersion")) {
            require(source.get("tgwsBackupVersion") == 1) { "Unsupported backup version" }
            successful = source.getJSONArray("successfulStrategies").also { require(it.isStringArray()) }
        } else {
            require(!source.has("successfulStrategies")) { "Missing backup format version" }
        }
        val version = source.get("version") as? String ?: error("Missing backup version")
        require(version.isNotBlank())
        document().put("version", version).apply {
            source.section("settings")?.let {
                val values = it as? JSONObject ?: invalid()
                val filtered = JSONObject()
                for (key in values.keys()) {
                    if (key !in SETTINGS_KEYS) continue
                    val value = values.get(key)
                    require(value is String || value is Number || value is Boolean ||
                        (value is JSONArray && value.isStringArray()))
                    filtered.put(key, value)
                }
                put("settings", filtered)
            }
            source.section("apps")?.let {
                require(it is JSONArray && it.isStringArray())
                put("apps", it)
            }
            source.section("history")?.let {
                put("history", records(it, setOf("text"), setOf("pinned"), setOf("name")))
            }
            source.section("domainLists")?.let {
                put("domainLists", records(it, setOf("id", "name"), DOMAIN_FLAGS, stringArray = "domains"))
            }
            require(length() > 2) { "No settings sections" }
        }
    } catch (exception: JSONException) {
        throw IllegalArgumentException("Invalid ByeByeDPI document", exception)
    }
    return validated to successful
}

internal fun JSONObject.section(name: String): Any? = if (isNull(name)) null else get(name)

private fun records(
    section: Any,
    requiredStrings: Set<String>,
    booleans: Set<String>,
    optionalStrings: Set<String> = emptySet(),
    stringArray: String? = null,
): JSONArray {
    val input = section as? JSONArray ?: invalid()
    return JSONArray().apply {
        for (index in 0 until input.length()) {
            val record = input.getJSONObject(index)
            require(requiredStrings.all { record.get(it) is String })
            require(booleans.all { !record.has(it) || record.get(it) is Boolean })
            require(optionalStrings.all { record.isNull(it) || record.get(it) is String })
            if (stringArray != null) require(record.getJSONArray(stringArray).isStringArray())
            val allowed = requiredStrings + booleans + optionalStrings + listOfNotNull(stringArray)
            val clean = JSONObject()
            for (key in record.keys()) if (key in allowed) clean.put(key, record.get(key))
            put(clean)
        }
    }
}

internal fun JSONArray.isStringArray() = (0 until length()).all { get(it) is String }

internal fun document() = JSONObject().put("app", UPSTREAM_APP).put("version", "1.8.2")

private fun invalid(): Nothing = throw IllegalArgumentException("Invalid ByeByeDPI section")

internal const val UPSTREAM_APP = "io.github.romanvht.byedpi"
internal const val MAX_CHARS = 2 * 1024 * 1024
internal const val DEFAULT_CMD_ARGS = "-o1 -a1 -r-5+se"
internal const val SUCCESSFUL_STRATEGIES = "successful_strategies"
internal const val MAX_HISTORY = 40
internal val DOMAIN_FLAGS = setOf("isActive", "isBuiltIn", "isModified", "isDeleted")
internal val SETTINGS_KEYS = setOf(
    "language", "app_theme", "dns_ip", "private_dns", "ipv6_enable", "applist_type", "autostart", "auto_connect",
    "byedpi_mode", "byedpi_proxy_ip", "byedpi_proxy_port", "byedpi_enable_cmd_settings", "byedpi_cmd_args",
    "byedpi_max_connections", "byedpi_buffer_size", "byedpi_no_domain", "byedpi_tcp_fast_open", "byedpi_default_ttl",
    "byedpi_hosts_mode", "byedpi_hosts_blacklist", "byedpi_hosts_whitelist", "byedpi_desync_method",
    "byedpi_split_position", "byedpi_split_at_host", "byedpi_drop_sack", "byedpi_fake_ttl", "byedpi_fake_offset",
    "byedpi_fake_sni", "byedpi_oob_data", "byedpi_desync_http", "byedpi_desync_https", "byedpi_desync_udp",
    "byedpi_host_mixed_case", "byedpi_domain_mixed_case", "byedpi_host_remove_spaces", "byedpi_tlsrec_enabled",
    "byedpi_tlsrec_position", "byedpi_tlsrec_at_sni", "byedpi_udp_fake_count", "byedpi_proxytest_delay",
    "byedpi_proxytest_requests", "byedpi_proxytest_limit", "byedpi_proxytest_timeout", "byedpi_proxytest_sni",
    "byedpi_proxytest_usercommands", "byedpi_proxytest_commands",
)
