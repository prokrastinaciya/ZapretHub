package com.amurcanov.tgwsproxy.dpi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class DpiConnectionOptions(
    val host: String,
    val port: Int,
    val routing: DpiVpnRouting,
    val args: List<String> = emptyList(),
)

internal data class DpiCommand(val text: String, val pinned: Boolean = false, val name: String? = null)

internal data class DpiDiagnosticsSnapshot(
    val host: String, val port: Int, val commandMode: Boolean,
    val ipv6: Boolean, val appFilter: VpnAppListMode,
)

internal data class DpiDomainList(
    val id: String,
    val name: String,
    val domains: List<String>,
    val isActive: Boolean = true,
    val isBuiltIn: Boolean = false,
)

/** Stores a validated v.1.8.2 exchange document, independently of TG settings. */
internal class DpiSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("dpi_document", Context.MODE_PRIVATE)

    fun exportJson(): String = preferences.getString("backup", null)
        ?: document().put("settings", JSONObject()).toString()

    fun observeDocument(onChange: (String) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "backup") onChange(exportJson())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun exportPortableBackup(): String = JSONObject(exportJson())
        .put("tgwsBackupVersion", 1)
        .put("successfulStrategies", JSONArray(successfulStrategies()))
        .toString()

    fun importJson(json: String) {
        val (validated, successful) = decodeDpiDocument(json)
        connectionOptions(validated)
        val edit = preferences.edit().putString("backup", validated.toString())
        successful?.let { edit.putString(SUCCESSFUL_STRATEGIES, it.toString()) }
        check(edit.commit()) { "Could not save ByeByeDPI document" }
    }

    fun connectionOptions(): DpiConnectionOptions = connectionOptions(JSONObject(exportJson()))

    fun diagnosticsSnapshot(): DpiDiagnosticsSnapshot {
        val document = JSONObject(exportJson())
        val options = connectionOptions(document)
        val mode = document.optJSONObject("settings")?.section("byedpi_enable_cmd_settings") as? Boolean ?: true
        return DpiDiagnosticsSnapshot(options.host, options.port, mode, options.routing.ipv6, options.routing.appListMode)
    }

    /** Raw settings map for the editor; values are String/Number/Boolean. */
    fun currentSettings(): Map<String, Any?> {
        val settings = JSONObject(exportJson()).optJSONObject("settings") ?: return emptyMap()
        return settings.keys().asSequence().associateWith { settings.get(it) }
    }

    /**
     * Merges validated strategy/settings values into the stored document and
     * re-validates the whole document before committing. A value of `null`
     * removes the key. Unknown keys and invalid values are rejected without
     * changing the stored document.
     */
    fun updateSettings(fields: Map<String, Any?>) {
        val current = JSONObject(exportJson())
        val settings = current.optJSONObject("settings") ?: JSONObject().also { current.put("settings", it) }
        for ((key, value) in fields) {
            require(key in SETTINGS_KEYS) { "Unknown setting: $key" }
            if (value == null) {
                settings.remove(key)
            } else {
                require(value is String || value is Number || value is Boolean ||
                    (value is JSONArray && value.isStringArray())) { "Invalid value for $key" }
                settings.put(key, value)
            }
        }
        connectionOptions(current)
        check(preferences.edit().putString("backup", current.toString()).commit()) { "Could not save ByeByeDPI document" }
    }

    /** Strategy history (parity C-03..C-05), stored in the document's `history` section. */
    fun history(): List<DpiCommand> {
        val document = JSONObject(exportJson())
        val history = document.optJSONArray("history") ?: return emptyList()
        return (0 until history.length()).map { index ->
            val record = history.getJSONObject(index)
            DpiCommand(record.getString("text"), record.optBoolean("pinned"), record.optString("name").ifBlank { null })
        }
    }

    fun pinnedHistory(): List<DpiCommand> = history().filter { it.pinned }

    fun addCommand(command: String) {
        if (command.isBlank()) return
        mutateHistory { current ->
            if (current.any { it.text == command }) current
            else {
                val next = (listOf(DpiCommand(command)) + current).toMutableList()
                while (next.size > MAX_HISTORY) {
                    val lastUnpinned = next.indexOfLast { !it.pinned }
                    if (lastUnpinned >= 0) next.removeAt(lastUnpinned) else next.removeAt(next.size - 1)
                }
                next
            }
        }
    }

    fun pinCommand(command: String) = mutateHistory { current -> current.map { if (it.text == command) it.copy(pinned = true) else it } }

    fun unpinCommand(command: String) = mutateHistory { current -> current.map { if (it.text == command) it.copy(pinned = false) else it } }

    fun deleteCommand(command: String) = mutateHistory { current -> current.filterNot { it.text == command } }

    fun renameCommand(command: String, name: String) = mutateHistory { current -> current.map { if (it.text == command) it.copy(name = name) else it } }

    fun clearUnpinnedHistory() = mutateHistory { current -> current.filter { it.pinned } }

    fun successfulStrategies(): List<String> {
        val saved = JSONArray(preferences.getString(SUCCESSFUL_STRATEGIES, "[]") ?: "[]")
        return (0 until saved.length()).map { saved.getString(it) }
    }

    fun saveSuccessfulStrategy(result: DpiStrategyResult) {
        if (result.startFailed || result.targets.isEmpty() || !result.reached.containsAll(result.targets)) return
        val saved = successfulStrategies()
        val command = result.candidate.command
        if (command in saved) return
        val next = JSONArray(listOf(command) + saved)
        check(preferences.edit().putString(SUCCESSFUL_STRATEGIES, next.toString()).commit()) {
            "Could not save successful ByeByeDPI strategy"
        }
    }

    fun strategyCandidates(builtIn: Sequence<String>): List<DpiStrategyCandidate> {
        val snapshot = JSONObject(exportJson())
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        val current = if (settings.section("byedpi_enable_cmd_settings") == false) {
            strategyArgs(settings).joinToString(" ") { token ->
                if (token.any { it.isWhitespace() || it == '"' || it == '\'' }) {
                    "\"" + token.replace("\"", "\\\"") + "\""
                } else token
            }
        } else {
            settings.section("byedpi_cmd_args") as? String ?: DEFAULT_CMD_ARGS
        }
        val saved = pinnedHistory().asSequence().map { it.text } + successfulStrategies().asSequence()
        return (sequenceOf(current) + saved + builtIn)
            .map { it.trim().replace("{sni}", "google.com") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
            .mapNotNull { command ->
                val args = commandTokens(JSONObject().put("byedpi_cmd_args", command), snapshot).first
                args.takeIf { it.isNotEmpty() }?.let { DpiStrategyCandidate(command, it) }
            }
            .distinctBy { it.args }
            .toList()
    }

    /** Applies a strategy command: enables command mode, sets the args, records history. */
    fun applyStrategy(command: String) {
        if (command.isBlank()) return
        updateSettings(mapOf("byedpi_enable_cmd_settings" to true, "byedpi_cmd_args" to command))
        addCommand(command)
    }

    /** Resets to a default document (parity X-03), dropping all settings, history and apps. */
    fun resetSettings() {
        val reset = preferences.edit()
            .putString("backup", document().put("settings", JSONObject()).toString())
            .remove(SUCCESSFUL_STRATEGIES)
        check(reset.commit()) {
            "Could not reset ByeByeDPI settings"
        }
    }

    /** Domain lists (parity T-06), stored in the document's `domainLists` section. */
    fun domainLists(): List<DpiDomainList> {
        val lists = JSONObject(exportJson()).optJSONArray("domainLists") ?: return emptyList()
        return (0 until lists.length()).map { index ->
            val record = lists.getJSONObject(index)
            DpiDomainList(
                record.getString("id"),
                record.getString("name"),
                (0 until record.getJSONArray("domains").length()).map { record.getJSONArray("domains").getString(it) },
                record.optBoolean("isActive", true),
                record.optBoolean("isBuiltIn", false),
            )
        }
    }

    fun addDomainList(name: String, domains: List<String>) {
        val id = name.lowercase().replace(" ", "_")
        mutateDomainLists { current ->
            if (current.any { it.id == id }) current
            else current + DpiDomainList(id, name, domains.filter { it.isNotBlank() }, isActive = true)
        }
    }

    fun mergeWindowsDomainLists(lists: Map<String, List<String>>) {
        mutateDomainLists { current ->
            val imported = lists.map { (name, domains) ->
                val existing = current.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: current.firstOrNull { it.id == "windows/$name" }
                existing?.copy(domains = (existing.domains + domains).distinct())
                    ?: DpiDomainList("windows/$name", name, domains)
            }
            current.filterNot { list -> imported.any { it.id == list.id } } + imported
        }
    }

    fun updateDomainList(id: String, name: String, domains: List<String>) =
        mutateDomainLists { current -> current.map { if (it.id == id) it.copy(name = name, domains = domains.filter { it.isNotBlank() }) else it } }

    fun deleteDomainList(id: String) =
        mutateDomainLists { current -> current.filterNot { it.id == id } }

    /** Sets the VPN application filter (parity S-07/S-08): type + selected package names. */
    fun setAppList(type: String, apps: List<String>) {
        require(type in setOf("disable", "blacklist", "whitelist")) { "Invalid application filter" }
        val document = JSONObject(exportJson())
        val settings = document.optJSONObject("settings") ?: JSONObject().also { document.put("settings", it) }
        settings.put("applist_type", type)
        val array = org.json.JSONArray()
        apps.distinct().filter { it.isNotBlank() }.forEach { array.put(it) }
        document.put("apps", array)
        connectionOptions(document)
        check(preferences.edit().putString("backup", document.toString()).commit()) { "Could not save ByeByeDPI app selection" }
    }

    private fun mutateDomainLists(transform: (List<DpiDomainList>) -> List<DpiDomainList>) {
        val document = JSONObject(exportJson())
        val next = transform(domainLists())
        val array = org.json.JSONArray()
        next.forEach { list ->
            val domains = org.json.JSONArray()
            list.domains.forEach { domains.put(it) }
            array.put(org.json.JSONObject()
                .put("id", list.id)
                .put("name", list.name)
                .put("domains", domains)
                .put("isActive", list.isActive)
                .put("isBuiltIn", list.isBuiltIn))
        }
        document.put("domainLists", array)
        check(preferences.edit().putString("backup", document.toString()).commit()) { "Could not save ByeByeDPI domain lists" }
    }

    private fun mutateHistory(transform: (List<DpiCommand>) -> List<DpiCommand>) {
        val document = JSONObject(exportJson())
        val next = transform(historyFrom(document))
        val array = org.json.JSONArray()
        next.forEach { command ->
            array.put(org.json.JSONObject()
                .put("text", command.text)
                .put("pinned", command.pinned)
                .put("name", command.name ?: org.json.JSONObject.NULL))
        }
        document.put("history", array)
        check(preferences.edit().putString("backup", document.toString()).commit()) { "Could not save ByeByeDPI history" }
    }

    private fun historyFrom(document: JSONObject): List<DpiCommand> {
        val history = document.optJSONArray("history") ?: return emptyList()
        return (0 until history.length()).map { index ->
            val record = history.getJSONObject(index)
            DpiCommand(record.getString("text"), record.optBoolean("pinned"), record.optString("name").ifBlank { null })
        }
    }


}
