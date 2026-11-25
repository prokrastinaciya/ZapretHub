package com.amurcanov.tgwsproxy.dpi

/**
 * What the OS VPN slot looks like in one sample.
 *
 * [UNKNOWN] means a VPN was observed (with concrete network handles in
 * [VpnStartRequest.vpnNetworks]) but its owner cannot be named; [UNAVAILABLE]
 * means observation itself failed (no manager, null/empty network list, or a
 * read threw) and is therefore a *distinct*, fail-closed state that can never
 * be mistaken for a confidently-free slot.
 */
enum class VpnSlotState { FREE, OURS, THIRD_PARTY, UNKNOWN, UNAVAILABLE }

/** Conservative refusal reasons. */
enum class VpnRefuseReason {
    /** The system VPN permission has not been obtained (prepare() still returns an intent). */
    CONSENT_NOT_GRANTED,

    /** Authorization was present at decision time but was lost before establish. */
    CONSENT_LOST,

    /** Always-on VPN or lockdown is active; we must not capture the slot. */
    LOCKDOWN,

    /** The VPN slot changed between decision and establish (a different/foreign VPN appeared). */
    SLOT_CHANGED,

    /** The VPN slot could not be observed, so a start cannot be authorized safely. */
    OBSERVATION_UNAVAILABLE,
}

/**
 * Immutable, single-attempt proof that the user deliberately confirmed replacing
 * the *specific* VPN observed at confirmation time. It is bound to the exact
 * [Network] handles ([confirmedNetworks]) of that VPN, so a later, different VPN
 * is rejected even when both fall under the same owner-unknown slot state.
 *
 * This is NOT a cached OS grant and NOT an unscoped Boolean: a cached
 * `VpnService.prepare() == null` is ambient permission to use the VPN at all,
 * never authorization to replace a specific active VPN.
 */
data class VpnReplacement(
    val confirmedSlot: VpnSlotState,
    val confirmedNetworks: Set<Long>,
)

/**
 * A single, immutable snapshot the VPN policy decides on.
 *
 * [prepared] is the ambient OS grant (`VpnService.prepare()` returned null);
 * [vpnNetworks] are the `Network.getNetworkHandle()` values of every observed
 * VPN network (empty for [VpnSlotState.FREE] and [VpnSlotState.UNAVAILABLE]).
 */
data class VpnStartRequest(
    val slot: VpnSlotState,
    val prepared: Boolean,
    val lockdown: Boolean = false,
    val vpnNetworks: Set<Long> = emptySet(),
)

/** Outcome of [VpnStartPolicy.decide]. */
sealed interface VpnStartDecision {
    /** Establish the VPN now. */
    data object Start : VpnStartDecision

    /**
     * Present the user a choice: switch to SOCKS5, replace the current VPN
     * with deliberate confirmation, or cancel. [ownerUncertain] is true when the
     * active VPN's owner could not be verified, so the UI must warn rather
     * than assume it is a third-party VPN.
     */
    data class Offer(val ownerUncertain: Boolean) : VpnStartDecision

    /** Do not start; [reason] says why. */
    data class Refuse(val reason: VpnRefuseReason) : VpnStartDecision
}

/**
 * Pure decision over whether a VPN start is authorized. It never performs I/O
 * and never mutates state, so the caller can re-sample the slot immediately
 * before establishing and call [decide] again — a changed answer means the
 * start is aborted before touching the system VPN slot.
 */
object VpnStartPolicy {
    /** A foreign slot requires matching network handles before requesting OS consent. */
    fun decide(request: VpnStartRequest, replacement: VpnReplacement? = null): VpnStartDecision {
        if (request.lockdown) return VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN)

        when (request.slot) {
            VpnSlotState.UNAVAILABLE ->
                return VpnStartDecision.Refuse(VpnRefuseReason.OBSERVATION_UNAVAILABLE)

            VpnSlotState.UNKNOWN, VpnSlotState.THIRD_PARTY -> {
                if (replacement == null) {
                    return VpnStartDecision.Offer(ownerUncertain = request.slot == VpnSlotState.UNKNOWN)
                }
                if (!replacementMatches(request, replacement)) {
                    return VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED)
                }
            }

            VpnSlotState.FREE, VpnSlotState.OURS -> Unit
        }

        return if (request.prepared) VpnStartDecision.Start
        else VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED)
    }

    private fun replacementMatches(request: VpnStartRequest, replacement: VpnReplacement?): Boolean =
        replacement != null &&
            replacement.confirmedSlot == request.slot &&
            request.vpnNetworks.isNotEmpty() &&
            replacement.confirmedNetworks == request.vpnNetworks
}
