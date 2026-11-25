package com.amurcanov.tgwsproxy.dpi

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.nio.ByteBuffer
import java.util.zip.ZipFile

internal const val MAX_WINDOWS_BACKUP_BYTES = 8 * 1024 * 1024
private const val MAX_LIST_BYTES = 512 * 1024
private val windowsDomainLists = listOf("list-general-user", "list-exclude-user")

internal fun readWindowsDomainLists(file: File): Map<String, List<String>> = ZipFile(file).use { zip ->
    val manifest = zip.getEntry("zaprethub.json") ?: error("Missing Zapret Hub manifest")
    val metadata = JSONObject(zip.getInputStream(manifest).use { it.readBackupText(MAX_LIST_BYTES) })
    require(metadata.optString("app") == "Zapret Hub")
    windowsDomainLists.mapNotNull { name ->
        val entry = zip.getEntry("lists/$name.txt") ?: return@mapNotNull null
        val domains = zip.getInputStream(entry).use { input ->
            input.readBackupText(MAX_LIST_BYTES).lineSequence()
                .map { it.substringBefore('#').trim().trimEnd('.') }
                .mapNotNull(::normalizeDomain)
                .distinct()
                .toList()
        }
        name to domains
    }.filter { (_, domains) -> domains.isNotEmpty() }.toMap()
}

private fun normalizeDomain(value: String): String? {
    if ('.' !in value || value.equals("domain.example.abc", ignoreCase = true)) return null
    val ascii = try {
        IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase()
    } catch (_: IllegalArgumentException) {
        return null
    }
    return ascii.takeIf { it.length in 1..253 && it.substringAfterLast('.').any(Char::isLetter) }
}

internal fun InputStream.copyBackupTo(output: OutputStream, limit: Int) {
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        total += count
        require(total <= limit) { "Backup exceeds the size limit" }
        output.write(buffer, 0, count)
    }
}

private fun InputStream.readBackupText(limit: Int): String {
    val bytes = ByteArrayOutputStream()
    copyBackupTo(bytes, limit)
    return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
}
