package org.olcbox.app.update.personal

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalUpdateFetchTicketTest {
    @Test fun servicePublishesDistinctManifestOnlyAuthorityWithoutBroadeningSubscription() = fixture { service ->
        val ticket = OlcboxVpnState.personalUpdateTicket!!
        assertTrue(ticket.current())
        assertTrue(ticket.allowsUrl(PERSONAL_UPDATE_MANIFEST_URL))
        for (url in listOf("$PERSONAL_UPDATE_MANIFEST_URL?x=1", "$PERSONAL_UPDATE_MANIFEST_URL#x",
            "https://rga.apollot.ru/updates/personal/v1/3/apollo-rga-personal.apk")) assertFalse(ticket.allowsUrl(url))
        val subscription = OlcboxVpnState.channelProxy!!.fence!!
        assertFalse(subscription.allowsUrl(PERSONAL_UPDATE_MANIFEST_URL))
        assertTrue(subscription.allowsUrl("https://panel.invalid/sub/selected"))
        assertNotNull(service)
    }
    @Test fun proofEpochRenewalSurvivesButReplacementGenerationRetiresBorrowedAuthority() = fixture { service ->
        val ticket = OlcboxVpnState.personalUpdateTicket!!
        (field(service, "personalProofEpoch") as AtomicLong).incrementAndGet()
        assertTrue(ticket.current())
        set(service, "generation", 8L)
        assertFalse(ticket.current())
        var writes = 0
        assertFalse(ticket.authorize(PERSONAL_UPDATE_MANIFEST_URL) { writes++ })
        assertEquals(0, writes)
    }
    @Test fun checkingAndStopRetireBorrowedTicketAndClearPublication() = fixture { service ->
        val ticket = OlcboxVpnState.personalUpdateTicket!!
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        assertFalse(ticket.current())
        service.javaClass.getDeclaredMethod("setStatus", VpnStatus::class.java).apply { isAccessible = true }
            .invoke(service, VpnStatus.Stopping)
        assertNull(OlcboxVpnState.personalUpdateTicket)
    }
    @Test fun missingSnapshotCannotPublishUpdateAuthority() = fixture { service ->
        set(service, "personalRefreshSnapshot", null)
        publish(service)
        assertNull(OlcboxVpnState.personalUpdateTicket)
    }
    @Test fun replacementDescriptorRetiresUpdateAuthority() = retired { set(it, "vpnInterface", null) }
    @Test fun replacementNetworkRetiresUpdateAuthority() = retired { set(it, "currentNetwork", null) }
    @Test fun replacementTransportEpochRetiresUpdateAuthority() = retired {
        (field(it, "personalTransportEpoch") as AtomicLong).incrementAndGet()
    }
    @Test fun replacementBridgeEpochRetiresUpdateAuthority() = retired {
        (field(it, "personalBridgeEpoch") as AtomicLong).incrementAndGet()
    }
    @Test fun stoppedBridgeRetiresUpdateAuthority() = retired { set(it, "tun2socksStarted", false) }
    @Test fun selectedStorageDriftRetiresUpdateAuthority() = retired { set(it, "connectedStorageId", "replacement") }
    @Test fun selectedContextDriftRetiresUpdateAuthority() = retired {
        set(it, "connectedPersonalSubscriptionUrl", "https://panel.invalid/sub/replacement")
    }
    @Test fun observedServiceGuardPreservesFirstFalseOrderAndAuthorityRejection() = fixture { service ->
        // Break caught: observer runs a second policy pass or changes generation-before-bridge ordering.
        val canonical = OlcboxVpnState.personalUpdateTicket!!
        val owner = PersonalUpdateDiagnosticRecords.newOwner { 100 }
        val operation = owner.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        val observed = canonical.diagnosticView(operation)
        set(service, "generation", 8L); set(service, "tun2socksStarted", false)
        assertFalse(canonical.current()); assertFalse(observed.current())
        var acted = false
        assertFalse(observed.authorize(PERSONAL_UPDATE_MANIFEST_URL) { acted = true })
        assertFalse(acted)
        operation.terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        assertNotNull("Service original false fence was not annotated", owner.terminal())
        assertEquals(PersonalUpdateDiagnosticFence.OWNER_CHANGED, owner.terminal()!!.fence)
    }

    @Test fun observedOuterPhaseFailureDoesNotRunLaterNativePredicate() = fixture { service ->
        val operation = PersonalUpdateDiagnosticRecords.newOwner { 100 }.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        val observed = OlcboxVpnState.personalUpdateTicket!!.diagnosticView(operation)
        val runtime = java.lang.reflect.Proxy.newProxyInstance(org.olcbox.app.vpn.AndroidOlcRtcRuntime::class.java.classLoader,
            arrayOf(org.olcbox.app.vpn.AndroidOlcRtcRuntime::class.java)) { _, method, _ ->
                if (method.name == "isRunning") throw AssertionError("later native predicate ran after false phase") else null
            } as org.olcbox.app.vpn.AndroidOlcRtcRuntime
        set(service, "olcrtc\$delegate", lazy { runtime })
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        assertFalse(observed.current())
        assertEquals(PersonalUpdateDiagnosticFence.PHASE_NOT_WORKING, operation.fenceFailure)
    }

    @Test fun rebindUsesCanonicalProvidersNotPreviousOperationClosures() = fixture { service ->
        // Break caught: rebinding a diagnostic view retains the prior observer closure.
        val owner = PersonalUpdateDiagnosticRecords.newOwner { 100 }
        val old = owner.begin(PersonalUpdateDiagnosticKind.CHECK)
        val oldView = OlcboxVpnState.personalUpdateTicket!!.diagnosticView(old)
        val fresh = owner.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        val freshView = oldView.diagnosticView(fresh)
        set(service, "currentNetwork", null)
        assertFalse(freshView.current())
        assertEquals(PersonalUpdateDiagnosticFence.NONE, old.fenceFailure)
        assertEquals(PersonalUpdateDiagnosticFence.PHYSICAL_CHANGED, fresh.fenceFailure)
    }

    @Test fun observedAttemptAndAcceptedObjectRenewalStillPermitStableWorking() = fixture { service ->
        val operation = PersonalUpdateDiagnosticRecords.newOwner { 100 }.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        val observed = OlcboxVpnState.personalUpdateTicket!!.diagnosticView(operation)
        (field(service, "personalProofEpoch") as AtomicLong).incrementAndGet()
        val snapshot = field(service, "personalRefreshSnapshot")!!
        val fields = listOf("pfd", "network", "physical", "bridgeThread", "location", "ordinary", "bootstrap",
            "storageId", "url", "phaseEpoch", "stamp").map { snapshot.javaClass.getDeclaredField(it) }
        val copy = snapshot.javaClass.declaredConstructors.single { it.parameterCount == 11 }.apply { isAccessible = true }
            .newInstance(*fields.map { it.isAccessible = true; it.get(snapshot)!! }.toTypedArray())
        set(service, "personalRefreshSnapshot", copy)
        assertTrue(observed.current())
        assertTrue(observed.authorize(PERSONAL_UPDATE_MANIFEST_URL) {})
        assertEquals(PersonalUpdateDiagnosticFence.NONE, operation.fenceFailure)
    }

    private fun retired(mutate: (Any) -> Unit) = fixture { service ->
        val ticket = OlcboxVpnState.personalUpdateTicket!!; assertTrue(ticket.current()); mutate(service)
        assertFalse(ticket.current()); var acted = false
        assertFalse(ticket.authorize(PERSONAL_UPDATE_MANIFEST_URL) { acted = true }); assertFalse(acted)
    }
    private fun fixture(test: (Any) -> Unit) {
        // Reuse the accepted full Service fixture without running or editing its tests.
        val type = Class.forName("org.olcbox.app.vpn.service.PersonalSubscriptionRefreshFenceTest\$Fixture")
        val fixture = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val service = type.getDeclaredMethod("getService").apply { isAccessible = true }.invoke(fixture)
        try { publish(service); test(service) }
        finally { type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(fixture) }
    }
    private fun publish(service: Any) = service.javaClass.getDeclaredMethod("publishPersonalUpdateTicket", SubscriptionFetchProxy::class.java)
        .apply { isAccessible = true }.invoke(service, SubscriptionFetchProxy("127.0.0.1", 1080))
    private fun field(service: Any, name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun set(service: Any, name: String, value: Any?) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
}
