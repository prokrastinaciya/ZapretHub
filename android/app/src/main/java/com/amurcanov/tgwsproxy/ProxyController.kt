package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first

object ProxyController {
    private val commandLock = Any()
    private var stopGeneration = 0L

    /** Same as Flowseal's desktop default (`ProxyConfig.dc_redirects`). */
    const val AUTO_DC_IPS =
        "2:${SettingsStore.DEFAULT_DIRECT_DC2_IP},4:${SettingsStore.DEFAULT_DIRECT_DC4_IP}"

    suspend fun startFromSavedSettings(
        context: Context,
        showInvalidPortToast: Boolean = false
    ): Boolean {
        val generation = synchronized(commandLock) { stopGeneration }
        val settingsStore = SettingsStore(context)
        settingsStore.migrateLegacyDefaults()
        val portText = settingsStore.port.first()
        val port = portText.toIntOrNull()
        if (port == null) {
            if (showInvalidPortToast) {
                Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
            }
            ProxyTileService.requestSync(context)
            return false
        }

        val bindIp = settingsStore.bindIp.first()
        val isExperimental = settingsStore.isExperimentalMode.first()
        val isDcAuto = settingsStore.isDcAuto.first()
        val poolSize = settingsStore.poolSize.first()
        val cfEnabled = settingsStore.cfproxyEnabled.first()
        val cfDisableTls = settingsStore.cfDisableTls.first()
        val customCfDomainEnabled = settingsStore.customCfDomainEnabled.first()
        val customCfDomain = settingsStore.customCfDomain.first().trim()
        val cfWorkerDomain = settingsStore.cfWorkerDomain.first().trim()
        val secretKey = settingsStore.ensureSecretKey()

        // "Auto" keeps Flowseal's own default route: direct WebSocket to DC2
        // and DC4 first, Cloudflare only as fallback. An empty list would make
        // the shared CF domains the sole route, so their outage drops everything.
        val parsedIps = if (isDcAuto) AUTO_DC_IPS else buildList {
            appendDc(1, settingsStore.dc1.first())
            appendDc(2, settingsStore.dc2.first())
            appendDc(3, settingsStore.dc3.first())
            appendDc(4, settingsStore.dc4.first())

            if (isExperimental) {
                appendDc(5, settingsStore.dc5.first())
                appendDc(203, settingsStore.dc203.first())
                appendDc(-1, settingsStore.dc1m.first())
                appendDc(-2, settingsStore.dc2m.first())
                appendDc(-3, settingsStore.dc3m.first())
                appendDc(-4, settingsStore.dc4m.first())
                appendDc(-5, settingsStore.dc5m.first())
                appendDc(-203, settingsStore.dc203m.first())
            }
        }.joinToString(",")

        synchronized(commandLock) {
            if (generation != stopGeneration) return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_START
                    putExtra(ProxyService.EXTRA_BIND_IP, bindIp)
                    putExtra(ProxyService.EXTRA_PORT, port)
                    putExtra(ProxyService.EXTRA_IPS, parsedIps)
                    putExtra(ProxyService.EXTRA_POOL_SIZE, poolSize)
                    putExtra(ProxyService.EXTRA_CFPROXY_ENABLED, cfEnabled)
                    putExtra(ProxyService.EXTRA_CF_DISABLE_TLS, cfDisableTls)
                    putExtra(ProxyService.EXTRA_CFPROXY_PRIORITY, true)
                    putExtra(
                        ProxyService.EXTRA_CFPROXY_DOMAIN,
                        if (customCfDomainEnabled && cfEnabled) customCfDomain else ""
                    )
                    putExtra(ProxyService.EXTRA_CF_WORKER_DOMAIN, cfWorkerDomain)
                    putExtra(ProxyService.EXTRA_SECRET_KEY, secretKey)
                }
            )
        }
        ProxyTileService.requestSync(context)
        return true
    }

    fun stop(context: Context) {
        synchronized(commandLock) {
            stopGeneration++
            context.startService(
                Intent(context, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_STOP
                }
            )
        }
        ProxyTileService.requestSync(context)
    }

    private fun MutableList<String>.appendDc(dc: Int, value: String) {
        val ip = value.trim()
        if (ip.isNotBlank()) {
            add("$dc:$ip")
        }
    }

}
