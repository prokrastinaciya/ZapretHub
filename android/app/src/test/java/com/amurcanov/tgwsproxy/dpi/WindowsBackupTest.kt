package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WindowsBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun importsOnlyWindowsDomainLists() {
        val backup = archive(
            "zaprethub.json" to """{"app":"Zapret Hub"}""",
            "lists/list-general-user.txt" to "\uFEFF# Header\r\nYouTube.com.\r\nyoutube.com\r\n192.0.2.1\r\ndomain.example.abc",
            "lists/list-exclude-user.txt" to "пример.рф",
            "lists/ipset-all-user.txt" to "test.example",
            "strategies/custom (test).bat" to "winws.exe",
        )

        assertEquals(
            mapOf("list-general-user" to listOf("youtube.com"), "list-exclude-user" to listOf("xn--e1afmkfd.xn--p1ai")),
            readWindowsDomainLists(backup),
        )
    }

    @Test
    fun refusesNonZapretHubArchiveAndOversizedList() {
        val other = archive("zaprethub.json" to """{"app":"other"}""")
        assertThrows(IllegalArgumentException::class.java) { readWindowsDomainLists(other) }

        val large = archive(
            "zaprethub.json" to """{"app":"Zapret Hub"}""",
            "lists/list-general-user.txt" to "example.net\n".repeat(50_000),
        )
        assertThrows(IllegalArgumentException::class.java) { readWindowsDomainLists(large) }
    }

    private fun archive(vararg entries: Pair<String, String>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, contents) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
