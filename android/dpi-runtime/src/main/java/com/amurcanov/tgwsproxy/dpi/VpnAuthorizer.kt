package com.amurcanov.tgwsproxy.dpi

/**
 * Production authorization entrypoint. It runs the pure [VpnStartPolicy.decide]
 * over a fresh slot sample and, when that decision is [VpnStartDecision.Start],
 * re-samples and re-decides immediately before the caller establishes — so a
 * slot that changed (a third-party/unknown VPN appeared, or a *different* VPN
 * with different network handles appeared), a prepare grant that was revoked,
 * an observation that became unavailable, or a lockdown that became active
 * between decision and establish is refused conservatively.
 *
 * Capture the network handles when presenting the replacement offer, and
 * keep the proof for that attempt across the Android consent dialog.
 */
class VpnAuthorizer(private val sampler: VpnSlotSampler) {

    fun authorize(replacement: VpnReplacement?): VpnStartDecision {
        val first = sampler.sample(replacement)
        val firstDecision = VpnStartPolicy.decide(first, replacement)
        if (firstDecision !is VpnStartDecision.Start) return firstDecision

        val second = sampler.sample(replacement)
        val secondDecision = VpnStartPolicy.decide(second, replacement)
        if (secondDecision is VpnStartDecision.Start) return VpnStartDecision.Start
        return VpnStartDecision.Refuse(recheckReason(first, second))
    }

    /**
     * Mints a deliberate, single-attempt replacement proof bound to the exact
     * VPN network handles observed right now. It fails if nothing to replace is
     * observed or if the observation is unavailable/identity-less, so a proof is
     * only ever produced from a confident, handle-bearing VPN observation.
     */
    fun confirmReplacement(): VpnReplacement {
        val sample = sampler.sample(null)
        require(sample.slot == VpnSlotState.THIRD_PARTY || sample.slot == VpnSlotState.UNKNOWN) {
            "No foreign VPN observed to replace (slot=${sample.slot})"
        }
        require(sample.vpnNetworks.isNotEmpty()) {
            "Observed VPN carries no network identity"
        }
        return VpnReplacement(confirmedSlot = sample.slot, confirmedNetworks = sample.vpnNetworks)
    }

    /** Maps a recheck refusal to the most specific conservative reason. */
    private fun recheckReason(first: VpnStartRequest, second: VpnStartRequest): VpnRefuseReason = when {
        second.lockdown -> VpnRefuseReason.LOCKDOWN
        second.slot == VpnSlotState.UNAVAILABLE -> VpnRefuseReason.OBSERVATION_UNAVAILABLE
        first.slot != second.slot -> VpnRefuseReason.SLOT_CHANGED
        first.vpnNetworks != second.vpnNetworks -> VpnRefuseReason.SLOT_CHANGED
        first.prepared && !second.prepared -> VpnRefuseReason.CONSENT_LOST
        else -> VpnRefuseReason.CONSENT_NOT_GRANTED
    }
}
