package com.amurcanov.tgwsproxy.dpi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService

/**
 * Samples the current VPN slot into a [VpnStartRequest]. This is the seam the
 * authorization layer depends on, so it can be unit-tested with a scripted
 * sampler while [AndroidVpnSlotSampler] provides the real (best-effort) OS view.
 */
fun interface VpnSlotSampler {
    /** Builds a fresh request snapshot (slot, prepared, lockdown, vpnNetworks). */
    fun sample(replacement: VpnReplacement?): VpnStartRequest
}

/**
 * Production sampler. It is fail-closed: when observation is unavailable (no
 * ConnectivityManager, a null or *empty* network list — empty is indeterminate,
 * not confidently free — or a capabilities read throws) it reports
 * [VpnSlotState.UNAVAILABLE], never [VpnSlotState.FREE], and carries no network
 * handles, so a start or replacement can never be authorized on a failed sample.
 *
 * It inspects every visible network (not just the default active network) for
 * [NetworkCapabilities.TRANSPORT_VPN], and records the `Network.getNetworkHandle()`
 * of each observed VPN network so a replacement proof can be bound to the exact
 * observed VPN and a later, different VPN is rejected.
 *
 * Honest limits: Android exposes no public API to name the owner of an active
 * VPN, so any visible foreign VPN is [VpnSlotState.UNKNOWN] (never
 * [VpnSlotState.THIRD_PARTY]) but carries its network handles; [VpnSlotState.OURS]
 * is not produced (the controller already rejects a start while our VPN is
 * Running); always-on/lockdown is reported absent because it cannot be read.
 * `getAllNetworks()` is a one-shot snapshot — the residual gap between it and
 * `establish()` is the documented check/establish window.
 */
class AndroidVpnSlotSampler(private val context: Context) : VpnSlotSampler {
    override fun sample(replacement: VpnReplacement?): VpnStartRequest {
        val (slot, networks) = observeNetworks()
        val observed = VpnStartRequest(slot, prepared = false, vpnNetworks = networks)
        return observed.checkConsent(replacement) {
            runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun observeNetworks(): Pair<VpnSlotState, Set<Long>> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return VpnSlotState.UNAVAILABLE to emptySet()
        return try {
            val networks = manager.allNetworks
                ?: return VpnSlotState.UNAVAILABLE to emptySet()
            if (networks.isEmpty()) return VpnSlotState.UNAVAILABLE to emptySet() // empty is indeterminate

            return classifyVpnNetworks(networks.toList()) { network ->
                manager.getNetworkCapabilities(network)?.let { caps ->
                    network.networkHandle to caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }
        } catch (e: Exception) {
            VpnSlotState.UNAVAILABLE to emptySet() // fail-closed on observation failure
        }
    }
}

internal fun <T> classifyVpnNetworks(
    networks: List<T>,
    capabilities: (T) -> Pair<Long, Boolean>?,
): Pair<VpnSlotState, Set<Long>> {
    if (networks.isEmpty()) return VpnSlotState.UNAVAILABLE to emptySet()
    val handles = mutableSetOf<Long>()
    for (network in networks) {
        val (handle, vpn) = capabilities(network) ?: return VpnSlotState.UNAVAILABLE to emptySet()
        if (vpn) handles.add(handle)
    }
    return if (handles.isEmpty()) VpnSlotState.FREE to emptySet() else VpnSlotState.UNKNOWN to handles
}

/** Android can claim the slot during prepare() if this package was pre-consented. */
internal fun VpnStartRequest.checkConsent(
    replacement: VpnReplacement?,
    prepare: () -> Boolean,
): VpnStartRequest {
    val decision = VpnStartPolicy.decide(this, replacement)
    return if (decision == VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED)) {
        copy(prepared = prepare())
    } else this
}
