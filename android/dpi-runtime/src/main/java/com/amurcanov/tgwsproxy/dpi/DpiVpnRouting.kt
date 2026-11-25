package com.amurcanov.tgwsproxy.dpi

/**
 * How the VPN tunnels apps. [ALL_APPS] routes everything (minus the always
 * self-excluded host package) through the tunnel; [BLACKLIST] additionally
 * excludes the listed apps; [WHITELIST] routes only the listed apps.
 */
enum class VpnAppListMode { ALL_APPS, BLACKLIST, WHITELIST }

/**
 * Immutable routing configuration for the VPN tunnel. `dns` and `ipv6` set the
 * tunnel's DNS server and IPv6 route; `appListMode`/`apps` apply the per-app
 * routing. The host package (both the Telegram engine and the DPI engine, which
 * share one applicationId) is ALWAYS excluded from the tunnel — the VPN must
 * never capture its own engines' outgoing connections.
 */
data class DpiVpnRouting(
    val dns: String = "",
    val ipv6: Boolean = true,
    val appListMode: VpnAppListMode = VpnAppListMode.ALL_APPS,
    val apps: List<String> = emptyList(),
)

/** Android's VPN builder only permits one kind of application list per tunnel. */
internal fun applyVpnApplicationRules(
    routing: DpiVpnRouting,
    hostPackage: String,
    addAllowed: (String) -> Boolean,
    addDisallowed: (String) -> Unit,
) {
    val selected = routing.apps.distinct().filterNot { it == hostPackage }
    when (routing.appListMode) {
        VpnAppListMode.ALL_APPS -> addDisallowed(hostPackage)
        VpnAppListMode.BLACKLIST -> {
            addDisallowed(hostPackage)
            selected.forEach(addDisallowed)
        }
        VpnAppListMode.WHITELIST -> {
            val installed = selected.count(addAllowed)
            require(installed > 0) { "VPN allowlist has no installed applications" }
        }
    }
}
