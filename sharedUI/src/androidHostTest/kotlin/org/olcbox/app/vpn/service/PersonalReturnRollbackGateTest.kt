package org.olcbox.app.vpn.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.vpn.UpstreamTransport

class PersonalReturnRollbackGateTest {
    private val expected = PhysicalNetworkSnapshot(
        generation = 9, networkId = 4, revision = 12,
        transport = UpstreamTransport.Wifi,
        usable = true, validated = true, captive = false,
        dnsServers = listOf("192.0.2.53"),
    )

    @Test fun unchangedUsableSnapshotAndAdmissionPermitRollback() {
        assertTrue(personalReturnRollbackSnapshotReady(expected, expected, true, true))
    }

    @Test fun changedRevisionOrNetworkNeverReusesOldReturnSnapshot() {
        assertFalse(personalReturnRollbackSnapshotReady(expected,
            expected.copy(revision = 13), true, true))
        assertFalse(personalReturnRollbackSnapshotReady(expected,
            expected.copy(networkId = 5), true, true))
        assertFalse(personalReturnRollbackSnapshotReady(expected, expected, false, true))
    }

    @Test fun captiveLostOrDeniedPathCannotStartPersonalNativeJoin() {
        assertFalse(personalReturnRollbackSnapshotReady(expected,
            expected.copy(captive = true), true, true))
        assertFalse(personalReturnRollbackSnapshotReady(expected,
            expected.copy(usable = false), true, true))
        assertFalse(personalReturnRollbackSnapshotReady(expected, null, true, true))
        assertFalse(personalReturnRollbackSnapshotReady(expected, expected, true, false))
    }
}
