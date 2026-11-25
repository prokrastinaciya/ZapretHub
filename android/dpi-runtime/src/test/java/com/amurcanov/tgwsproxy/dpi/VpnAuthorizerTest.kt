package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Integration-seam tests for [VpnAuthorizer]. A scripted [VpnSlotSampler]
 * supplies the snapshots so the decide-then-recheck sequence, the
 * handle-scoped replacement path, and the conservative refusals are exercised
 * without Android. The production [AndroidVpnSlotSampler] is the real
 * (device-only, fail-closed) seam.
 */
class VpnAuthorizerTest {

    /** Returns each scripted sample in turn; the last one repeats. */
    private class ScriptedSampler(private val samples: List<VpnStartRequest>) : VpnSlotSampler {
        private var index = 0
        override fun sample(replacement: VpnReplacement?): VpnStartRequest = samples[minOf(index++, samples.size - 1)]
    }

    private fun authorizer(vararg samples: VpnStartRequest) = VpnAuthorizer(ScriptedSampler(samples.toList()))

    private fun req(slot: VpnSlotState, prepared: Boolean, networks: Set<Long> = emptySet(), lockdown: Boolean = false) =
        VpnStartRequest(slot, prepared = prepared, lockdown = lockdown, vpnNetworks = networks)

    private fun freePrepared() = req(VpnSlotState.FREE, true)
    private fun freeUnprepared() = req(VpnSlotState.FREE, false)
    private fun unavailable() = req(VpnSlotState.UNAVAILABLE, true)
    private fun unknown(handles: Set<Long>) = req(VpnSlotState.UNKNOWN, true, handles)
    private fun thirdParty(handles: Set<Long>) = req(VpnSlotState.THIRD_PARTY, true, handles)

    @Test
    fun disappearingNetworkFailsClosedWithoutCallingPrepare() {
        val observation = classifyVpnNetworks(listOf(1L, 2L)) { id ->
            if (id == 1L) id to false else null
        }
        assertEquals(VpnSlotState.UNAVAILABLE to emptySet<Long>(), observation)
        val request = VpnStartRequest(observation.first, prepared = false, vpnNetworks = observation.second)
        assertEquals(request, request.checkConsent(null) { error("prepare must not be called") })
    }

    @Test
    fun startSurvivesRecheckWhenSlotStable() {
        assertEquals(VpnStartDecision.Start, authorizer(freePrepared(), freePrepared()).authorize(null))
    }

    @Test
    fun changedSlotDuringRecheckRefusesConservatively() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            authorizer(freePrepared(), unknown(setOf(1L))).authorize(null),
        )
    }

    @Test
    fun unavailableObservationFailsClosed() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.OBSERVATION_UNAVAILABLE),
            authorizer(unavailable()).authorize(null),
        )
    }

    @Test
    fun unavailableDuringRecheckRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.OBSERVATION_UNAVAILABLE),
            authorizer(freePrepared(), unavailable()).authorize(null),
        )
    }

    @Test
    fun thirdPartyWithoutReplacementOffers() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            authorizer(thirdParty(setOf(1L))).authorize(null),
        )
    }

    @Test
    fun cachedPrepareGrantCannotReplaceThirdParty() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            authorizer(thirdParty(setOf(1L))).authorize(null),
        )
    }

    @Test
    fun thirdPartyWithMatchingReplacementStarts() {
        val proof = VpnReplacement(VpnSlotState.THIRD_PARTY, setOf(1L))
        assertEquals(
            VpnStartDecision.Start,
            authorizer(thirdParty(setOf(1L)), thirdParty(setOf(1L))).authorize(proof),
        )
    }

    @Test
    fun sameStateDifferentHandlesRejectedOnRecheck() {
        // A -> B, both THIRD_PARTY state but different VPN handles: rejected.
        val proof = VpnReplacement(VpnSlotState.THIRD_PARTY, setOf(1L))
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            authorizer(thirdParty(setOf(1L)), thirdParty(setOf(2L))).authorize(proof),
        )
    }

    @Test
    fun unknownWithMatchingReplacementStarts() {
        val proof = VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L))
        assertEquals(
            VpnStartDecision.Start,
            authorizer(unknown(setOf(1L)), unknown(setOf(1L))).authorize(proof),
        )
    }

    @Test
    fun unknownSameStateDifferentHandlesRejectedOnRecheck() {
        val proof = VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L))
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            authorizer(unknown(setOf(1L)), unknown(setOf(2L))).authorize(proof),
        )
    }

    @Test
    fun confirmedReplacementRequestsConsentAndCanResumeWithSameProof() {
        val proof = VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L))
        val unprepared = unknown(setOf(1L)).copy(prepared = false)
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED),
            authorizer(unprepared).authorize(proof),
        )
        assertEquals(VpnStartDecision.Start, authorizer(unknown(setOf(1L))).authorize(proof))
        assertEquals(VpnStartDecision.Start, authorizer(freePrepared()).authorize(proof))
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            authorizer(unknown(setOf(2L))).authorize(proof),
        )
    }

    @Test
    fun androidPrepareIsDeferredUntilTheObservedForeignVpnWasConfirmed() {
        val observed = unknown(setOf(1L)).copy(prepared = false)
        val different = VpnReplacement(VpnSlotState.UNKNOWN, setOf(2L))
        val matching = VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L))
        assertEquals(observed, observed.checkConsent(null) { error("must not claim slot") })
        assertEquals(observed, observed.checkConsent(different) { error("must not claim slot") })
        assertEquals(observed.copy(prepared = true), observed.checkConsent(matching) { true })
    }

    @Test
    fun revokedOrDeniedPrepareRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED),
            authorizer(freeUnprepared()).authorize(null),
        )
    }

    @Test
    fun lockdownRefuses() {
        val locked = req(VpnSlotState.FREE, true, lockdown = true)
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            authorizer(locked).authorize(null),
        )
    }

    @Test
    fun consentLostDuringRecheckRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST),
            authorizer(freePrepared(), freeUnprepared()).authorize(null),
        )
    }

    @Test
    fun confirmReplacementBindsToObservedSlotAndHandles() {
        assertEquals(
            VpnReplacement(VpnSlotState.UNKNOWN, setOf(1L, 2L)),
            authorizer(unknown(setOf(1L, 2L))).confirmReplacement(),
        )
    }

    @Test
    fun confirmReplacementRefusesWhenNothingToReplace() {
        assertThrows(IllegalArgumentException::class.java) { authorizer(freePrepared()).confirmReplacement() }
    }

    @Test
    fun confirmReplacementRefusesWhenUnavailable() {
        assertThrows(IllegalArgumentException::class.java) { authorizer(unavailable()).confirmReplacement() }
    }

    @Test
    fun confirmReplacementRefusesWhenIdentityLess() {
        assertThrows(IllegalArgumentException::class.java) { authorizer(req(VpnSlotState.UNKNOWN, true, emptySet())).confirmReplacement() }
    }
}
