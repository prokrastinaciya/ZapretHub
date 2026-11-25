package com.amurcanov.tgwsproxy.dpi

import org.json.JSONArray
import org.json.JSONObject

internal fun connectionOptions(snapshot: JSONObject): DpiConnectionOptions {
    val settings = snapshot.optJSONObject("settings") ?: JSONObject()
    fun text(key: String, default: String): String = settings.section(key)?.let {
        require(it is String) { "Expected text in $key" }
        it
    } ?: default
    val commandMode = (settings.section("byedpi_enable_cmd_settings") as? Boolean) ?: true

    val host: String
    val port: Int
    val args: List<String>
    if (commandMode) {
        val (tokens, endpoint) = commandTokens(settings, snapshot)
        val (cmdIp, cmdPort) = endpoint
        host = (cmdIp ?: text("byedpi_proxy_ip", "127.0.0.1")).removeSurrounding("[", "]")
        port = (cmdPort ?: (settings.section("byedpi_proxy_port") ?: "1080").toString())
            .toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Invalid SOCKS5 port")
        args = tokens
    } else {
        host = text("byedpi_proxy_ip", "127.0.0.1").removeSurrounding("[", "]")
        port = (settings.section("byedpi_proxy_port") ?: "1080").toString().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Invalid SOCKS5 port")
        args = strategyArgs(settings)
    }
    val loopback = when (host) {
        "127.0.0.1", "0.0.0.0" -> "127.0.0.1"
        "::1", "::" -> "::1"
        else -> throw IllegalArgumentException("SOCKS5 endpoint must be local")
    }
    val ipv6 = settings.section("ipv6_enable")?.let {
        require(it is Boolean) { "Invalid IPv6 setting" }
        it
    } ?: false
    val apps = snapshot.optJSONArray("apps")?.let { records ->
        (0 until records.length()).map { records.getString(it) }.distinct()
    }.orEmpty()
    val appList = when (text("applist_type", "disable")) {
        "disable" -> VpnAppListMode.ALL_APPS
        "blacklist" -> VpnAppListMode.BLACKLIST
        "whitelist" -> VpnAppListMode.WHITELIST
        else -> throw IllegalArgumentException("Invalid application filter")
    }
    val dns = text("dns_ip", "").trim()
    require(dns.isEmpty() || isNumericDnsAddress(dns)) { "DNS address must be numeric" }
    return DpiConnectionOptions(loopback, port, DpiVpnRouting(dns = dns, ipv6 = ipv6, appListMode = appList, apps = apps), args)
}

/**
 * Maps the v.1.8.2 UI settings to `ciadpi` strategy arguments, mirroring
 * upstream `ByeDpiProxyUIPreferences.uiargs` exactly (the endpoint `--ip`/
 * `--port` are handled by [DpiConfig.toArgv], not here).
 */
internal fun strategyArgs(settings: JSONObject): List<String> {
    fun text(key: String, default: String): String = settings.section(key)?.let {
        require(it is String) { "Expected text in $key" }
        it
    } ?: default
    fun int(key: String, default: Int): Int = settings.section(key)?.let {
        val value = it.toString().toIntOrNull() ?: throw IllegalArgumentException("Invalid number in $key")
        value
    } ?: default
    fun bool(key: String, default: Boolean): Boolean = settings.section(key)?.let {
        require(it is Boolean) { "Expected boolean in $key" }
        it
    } ?: default

    val args = mutableListOf<String>()
    int("byedpi_max_connections", 512).takeIf { it != 0 }?.let { args.add("-c$it") }
    int("byedpi_buffer_size", 16384).takeIf { it != 0 }?.let { args.add("-b$it") }

    val protocols = buildList {
        if (bool("byedpi_desync_https", true)) add("t")
        if (bool("byedpi_desync_http", true)) add("h")
    }
    val hostsMode = text("byedpi_hosts_mode", "disable")
    val hosts = when (hostsMode) {
        "disable" -> null
        "blacklist" -> text("byedpi_hosts_blacklist", "")
        "whitelist" -> text("byedpi_hosts_whitelist", "")
        else -> throw IllegalArgumentException("Invalid hosts mode")
    }
    if (!hosts.isNullOrBlank()) {
        val hostStr = ":${hosts.replace("\n", " ")}"
        when (hostsMode) {
            "blacklist" -> {
                args.add("-H$hostStr")
                args.add("-An")
                if (protocols.isNotEmpty()) args.add("-K${protocols.joinToString(",")}")
            }
            "whitelist" -> {
                if (protocols.isNotEmpty()) args.add("-K${protocols.joinToString(",")}")
                args.add("-H$hostStr")
            }
        }
    } else {
        if (protocols.isNotEmpty()) args.add("-K${protocols.joinToString(",")}")
    }

    int("byedpi_default_ttl", 0).takeIf { it != 0 }?.let { args.add("-g$it") }
    if (bool("byedpi_no_domain", false)) args.add("-N")

    val splitPosition = int("byedpi_split_position", 1)
    val desyncMethod = text("byedpi_desync_method", "oob")
    if (splitPosition != 0) {
        val posArg = splitPosition.toString() + if (bool("byedpi_split_at_host", false)) "+h" else ""
        val option = when (desyncMethod) {
            "split" -> "-s"
            "disorder" -> "-d"
            "oob" -> "-o"
            "disoob" -> "-q"
            "fake" -> "-f"
            "none" -> ""
            else -> throw IllegalArgumentException("Invalid desync method")
        }
        if (option.isNotEmpty()) args.add("$option$posArg")
    } else if (desyncMethod !in setOf("none", "split", "disorder", "oob", "disoob", "fake")) {
        throw IllegalArgumentException("Invalid desync method")
    }

    if (desyncMethod == "fake") {
        int("byedpi_fake_ttl", 8).takeIf { it != 0 }?.let { args.add("-t$it") }
        text("byedpi_fake_sni", "www.iana.org").takeIf { it.isNotEmpty() }?.let { args.add("-n$it") }
        int("byedpi_fake_offset", 0).takeIf { it != 0 }?.let { args.add("-O$it") }
    }
    if (desyncMethod == "oob" || desyncMethod == "disoob") {
        val oob = text("byedpi_oob_data", "a")
        require(oob.isNotEmpty()) { "OOB data must not be empty" }
        args.add("-e${oob[0].code.toByte()}")
    }

    val modHttpFlags = buildList {
        if (bool("byedpi_host_mixed_case", false)) add("h")
        if (bool("byedpi_domain_mixed_case", false)) add("d")
        if (bool("byedpi_host_remove_spaces", false)) add("r")
    }
    if (modHttpFlags.isNotEmpty()) args.add("-M${modHttpFlags.joinToString(",")}")

    if (bool("byedpi_tlsrec_enabled", false) && int("byedpi_tlsrec_position", 0) != 0) {
        val tlsRecArg = int("byedpi_tlsrec_position", 0).toString() + if (bool("byedpi_tlsrec_at_sni", false)) "+s" else ""
        args.add("-r$tlsRecArg")
    }

    if (bool("byedpi_tcp_fast_open", false)) args.add("-F")
    if (bool("byedpi_drop_sack", false)) args.add("-Y")

    args.add("-An")
    if (bool("byedpi_desync_udp", true)) {
        args.add("-Ku")
        int("byedpi_udp_fake_count", 1).takeIf { it != 0 }?.let { args.add("-a$it") }
        args.add("-An")
    }
    return args
}

internal fun isNumericDnsAddress(value: String): Boolean {
    if (':' in value) {
        // Restrict the parser to numeric IPv6 syntax; never send a hostname to DNS.
        if (!value.matches(Regex("[0-9a-fA-F:.]{2,45}"))) return false
        return try {
            java.net.InetAddress.getByName(value)
            true
        } catch (_: java.net.UnknownHostException) {
            false
        }
    }
    val octets = value.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.length in 1..3 && octet.all { it in '0'..'9' } && octet.toInt() <= 255
    }
}
