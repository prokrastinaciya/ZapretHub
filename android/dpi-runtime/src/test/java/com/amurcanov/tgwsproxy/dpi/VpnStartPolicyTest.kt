package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-table test for [VpnStartPolicy]. The table pins the conservative
 * contract: a cached OS prepare grant authorizes a free/ours start but NEVER
 * authorizes replacing a foreign VPN — that requires a deliberate per-attempt
 * [VpnReplacement] bound to the exact observed network handles AND a valid OS
 * grant. Unavailable observation never authorizes anything; a proof bound to a
 * different slot or different handles is ignored (the freshly observed slot wins).
 */
class VpnStartPolicyTest {

    private fun req(slot: VpnSlotState, prepared: Boolean, networks: Set<Long> = emptySet(), lockdown: Boolean = false) =
        VpnStartRequest(slot, prepared = prepared, lockdown = lockdown, vpnNetworks = networks)

    @Test
    fun freeSlotPreparedStarts() {
        assertEquals(VpnStartDecision.Start, VpnStartPolicy.decide(req(VpnSlotState.FREE, true)))
    }

    @Test
    fun freeSlotNotPreparedRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED),
            VpnStartPolicy.decide(req(VpnSlotState.FREE, false)),
        )
    }

    @Test
    fun unavailableObservationRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.OBSERVATION_UNAVAILABLE),
            VpnStartPolicy.decide(req(VpnSlotState.UNAVAILABLE, true)),
        )
    }

    @Test
    fun thirdPartyWithoutReplacementOffers() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            VpnStartPolicy.decide(req(VpnSlotState.THIRD_PARTY, true, setOf(1L))),
        )
    }

    @Test
    fun thirdPartyWithMatchingReplacementStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(
                req(VpnSlotState.THIRD_PARTY, true, setOf(1L)),
                VpnReplacement(VpnSlotState.THIRD_PARTY, setOf(1L)),
            ),
        )
    }

    @Test
    fun thirdPartyCachedPrepareGrantCannotReplace() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            VpnStartPolicy.decide(req(VpnSlotState.THIRD_PARTY, true, setOf(1L))),
        )
    }

    @Test
    fun thirdPartyReplacementWithDifferentHandlesIsRejected() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            VpnStartPolicy.decide(
                req(VpnSlotState.THIRD_PARTY, true, setOf(2L)),
                VpnReplacement(VpnSlotState.THIRD_PARTY, setOf(1L)),
            ),
        )
    }

    @Test
    fun unknownWithoutReplacementWarnsConservatively() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(req(VpnSlotState.UNKNOWN, true, setOf(1L))),
        )
    }

    @Test
    fun unknownCachedGrantWithoutReplacementOffers() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(req(VpnSlotState.UNKNOWN, true, setOf(1L))),
        )
    }

    @Test
    fun unknownWithMatchingReplacementStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(
                req(VpnSlotState.UNKNOWN, true, setOf(1L)),
                VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L)),
            ),
        )
    }

    @Test
    fun unknownReplacementWithDifferentHandlesIsRejected() {
        // Same slot state (both UNKNOWN) but a different VPN: the proof is
        // bound to handles {1}, the fresh observation is {2} -> rejected.
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            VpnStartPolicy.decide(
                req(VpnSlotState.UNKNOWN, true, setOf(2L)),
                VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L)),
            ),
        )
    }

    @Test
    fun unknownReplacementWithEmptyNetworksIsRejected() {
        // Empty/identity-less observation must never authorize replacement.
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            VpnStartPolicy.decide(
                req(VpnSlotState.UNKNOWN, true, emptySet()),
                VpnReplacement(VpnSlotState.UNKNOWN, emptySet()),
            ),
        )
    }

    @Test
    fun replacementBoundToWrongSlotIsRejected() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            VpnStartPolicy.decide(
                req(VpnSlotState.UNKNOWN, true, setOf(1L)),
                VpnReplacement(VpnSlotState.THIRD_PARTY, setOf(1L)),
            ),
        )
    }

    @Test
    fun lockdownRefusesRegardless() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(req(VpnSlotState.FREE, true, lockdown = true)),
        )
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(
                req(VpnSlotState.UNKNOWN, true, setOf(1L), lockdown = true),
                VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L)),
            ),
        )
    }

    @Test
    fun decisionIsPureAndRecheckable() {
        val request = req(VpnSlotState.UNKNOWN, true, setOf(1L))
        val proof = VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L))
        assertEquals(VpnStartPolicy.decide(request, proof), VpnStartPolicy.decide(request, proof))
        assertTrue(VpnStartPolicy.decide(request) is VpnStartDecision.Offer)
    }
}
