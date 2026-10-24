package org.olcbox.app.net

import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.vpn.OlcChannelRole
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

class GeneralBootstrapCandidatesTest {
    @Test fun vkUsesTheSameBoundSerialReserveSlotWithoutChangingSubscriberOrCountry() {
        val vkJson = reserveJson.replace("salutejazz", "vkcalls").replace("datachannel", "vp8channel")
            .replace("abc123:abcd1234", "https://vk.ru/call/join/fixture-opaque_123")
        val primary = selection(primaryJson)
        val vk = selection(vkJson)
        val candidates = generalBootstrapCandidates(primary, vk, key, GeneralTestFixture.now)
        assertEquals(listOf(OlcChannelRole.Primary, OlcChannelRole.Reserve), candidates.map { it.channel })
        assertSame(vk, candidates.last().selection)
        assertEquals(OlcChannelRole.Reserve, generalBootstrapCandidates(null, vk, key, GeneralTestFixture.now).single().channel)
        assertEquals(1, generalBootstrapCandidates(primary, selection(vkJson.replace("\"country\":\"NL\"", "\"country\":\"SG\"")), key, GeneralTestFixture.now).size)
        assertTrue(generalBootstrapCandidates(vk, null, key, GeneralTestFixture.now).isEmpty())
    }
    @Test fun retiredPrimaryIsRestoredWhenReserveBecomesIneligibleBeforeAcquiringAnOwner(): Unit = runBlocking {
        val candidates = generalBootstrapCandidates(selection(primaryJson), selection(reserveJson), key, GeneralTestFixture.now)
        val events = mutableListOf<String>()
        val handled = runGeneralBootstrapCandidates(candidates, { true }, { _, _ -> true },
            { candidate, _ ->
                events += candidate.channel.name
                if (candidate.channel == OlcChannelRole.Primary) GeneralCandidateOutcome.Retired else GeneralCandidateOutcome.Skipped
            }, { events += "restore-or-close"; true })
        assertTrue(handled)
        assertEquals(listOf("Primary", "Reserve", "restore-or-close"), events)
    }
    @Test fun handledDenialUnknownOwnerAndCancellationNeverAdvanceOrRestoreAnotherConnection(): Unit = runBlocking {
        val candidates = generalBootstrapCandidates(selection(primaryJson), selection(reserveJson), key, GeneralTestFixture.now)
        var attempts = 0; var restores = 0
        assertTrue(runGeneralBootstrapCandidates(candidates, { true }, { _, _ -> true },
            { _, _ -> attempts++; GeneralCandidateOutcome.Handled }, { restores++; true }))
        assertEquals(1, attempts); assertEquals(0, restores)
        attempts = 0
        try {
            runGeneralBootstrapCandidates(candidates, { true }, { _, _ -> true },
                { _, _ -> attempts++; throw CancellationException("controlled") }, { restores++; true })
            fail("Cancellation must propagate to the existing service cleanup owner")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts); assertEquals(0, restores)
    }
    @Test fun staleServiceFenceAfterRetirementFinishesCustodyWithoutStartingReserve(): Unit = runBlocking {
        val candidates = generalBootstrapCandidates(selection(primaryJson), selection(reserveJson), key, GeneralTestFixture.now)
        var current = true; var starts = 0; var finishes = 0
        assertTrue(runGeneralBootstrapCandidates(candidates, { current }, { _, _ -> true },
            { _, _ -> starts++; current = false; GeneralCandidateOutcome.Retired }, { finishes++; true }))
        assertEquals(1, starts); assertEquals(1, finishes)
    }
    private val key = GeneralTestFixture.signer()
    private val primaryJson = managedMetadata()
    private val reserveJson = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"salutejazz\"")
        .replace("\"transport\":\"vp8channel\"", "\"transport\":\"datachannel\"")
        .replace("https://telemost.yandex.ru/j/12345678901234", "abc123:abcd1234")
        .replace(GeneralTestFixture.room, "room_reserve_0001")
        .replace(GeneralTestFixture.key, "c".repeat(64))
    private fun selection(json: String) = GeneralBootstrapSelection.fromVerifiedReceipt(
        GeneralBootstrapV1.fromPrivateBytes(json.toByteArray())!!, key, GeneralTestFixture.now, GeneralTestFixture.now)!!

    @Test fun fullImmutableProviderTuplesAreSelectedInOrderWithoutRewritingRoomOrKey() {
        val primary = selection(primaryJson); val reserve = selection(reserveJson)
        val candidates = generalBootstrapCandidates(primary, reserve, key, GeneralTestFixture.now)
        assertEquals(listOf(OlcChannelRole.Primary, OlcChannelRole.Reserve), candidates.map { it.channel })
        assertSame(primary, candidates[0].selection); assertSame(reserve, candidates[1].selection)
        assertEquals("telemost", candidates[0].selection.metadata.provider)
        assertEquals("salutejazz", candidates[1].selection.metadata.provider)
        assertNotEquals(candidates[0].selection.metadata.roomId, candidates[1].selection.metadata.roomId)
    }
    @Test fun crossSubscriptionStalePolicyOrReusedAuthorityCannotBecomeAReserve() {
        val primary = selection(primaryJson)
        for (changed in listOf(
            reserveJson.replace("\"userId\":801", "\"userId\":802"),
            reserveJson.replace("rga-main", "other-panel"),
            reserveJson.replace("\"country\":\"NL\"", "\"country\":\"SG\""),
            reserveJson.replace("\"policyRevision\":7", "\"policyRevision\":8"),
            reserveJson.replace("room_reserve_0001", GeneralTestFixture.room),
            reserveJson.replace("c".repeat(64), GeneralTestFixture.key)
        )) assertEquals(1, generalBootstrapCandidates(primary, selection(changed), key, GeneralTestFixture.now).size)
        assertTrue(generalBootstrapCandidates(primary, selection(reserveJson), GeneralTestFixture.signer(), GeneralTestFixture.now).isEmpty())
        assertTrue(generalBootstrapCandidates(primary, selection(reserveJson), key, GeneralTestFixture.now + 86_400_000).isEmpty())
    }
    @Test fun reserveCanBeDiscoveredAloneButLegacyOrSwappedProviderReceiptsCannotBeAdopted() {
        val reserve = selection(reserveJson)
        assertEquals(OlcChannelRole.Reserve, generalBootstrapCandidates(null, reserve, key, GeneralTestFixture.now).single().channel)
        assertTrue(generalBootstrapCandidates(reserve, selection(primaryJson), key, GeneralTestFixture.now).isEmpty())
        assertTrue(generalBootstrapCandidates(selection(GeneralTestFixture.metadata()), null, key, GeneralTestFixture.now).isEmpty())
    }
}
