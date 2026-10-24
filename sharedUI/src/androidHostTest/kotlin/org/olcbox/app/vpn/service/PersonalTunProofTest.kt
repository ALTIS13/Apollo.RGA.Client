package org.olcbox.app.vpn.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalTunProofTest {
    private val prepared = PersonalTunProof.Prepared("1.1.1.1", 43127)
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val helperUid = 10342

    private fun validReply() = PersonalTunProof.Reply(
        sendingUid = helperUid,
        claimedUid = helperUid,
        nonce = nonce,
        httpStatus = 200,
        egressMatched = true,
        localIpv4 = "10.0.88.88",
        remoteIpv4 = prepared.remoteIpv4,
        localPort = prepared.localPort,
        failure = null,
    )

    private fun accepts(reply: PersonalTunProof.Reply = validReply(), ownerUid: Int = helperUid) =
        PersonalTunProof.accepts(helperUid, nonce, prepared, ownerUid, reply)

    @Test fun acceptsOnlyMatchingLiveHelperReceipt() {
        assertTrue(accepts())
    }

    @Test fun rejectsWrongMessageClaimOrSocketOwnerUid() {
        assertFalse(accepts(validReply().copy(sendingUid = helperUid + 1)))
        assertFalse(accepts(validReply().copy(claimedUid = helperUid + 1)))
        assertFalse(accepts(ownerUid = helperUid + 1))
        assertFalse(PersonalTunProof.accepts(0, nonce, prepared, helperUid, validReply()))
    }

    @Test fun rejectsReplayAndUnsuccessfulNetworkResponse() {
        assertFalse(accepts(validReply().copy(nonce = "fedcba9876543210fedcba9876543210")))
        assertFalse(accepts(validReply().copy(httpStatus = 302)))
        assertFalse(accepts(validReply().copy(egressMatched = false)))
        assertFalse(accepts(validReply().copy(failure = "tls")))
        assertFalse(PersonalTunProof.accepts(helperUid, "not-a-nonce", prepared, helperUid, validReply()))
    }

    @Test fun rejectsPreparedTupleDriftOrMalformedAddresses() {
        assertFalse(accepts(validReply().copy(remoteIpv4 = "1.1.1.2")))
        assertFalse(accepts(validReply().copy(localPort = prepared.localPort + 1)))
        assertFalse(accepts(validReply().copy(localIpv4 = "0.0.0.0")))
        assertFalse(accepts(validReply().copy(localIpv4 = "not-an-ip")))
        assertFalse(PersonalTunProof.accepts(helperUid, nonce,
            prepared.copy(remoteIpv4 = "host.invalid"), helperUid, validReply()))
        assertFalse(PersonalTunProof.accepts(helperUid, nonce,
            prepared.copy(localPort = 0), helperUid, validReply()))
    }

    @Test fun preparedRemoteMustBePublicForTheOwnedHealthEndpoint() {
        assertTrue(accepts())
        assertTrue(PersonalTunProof.accepts(helperUid, nonce,
            prepared.copy(remoteIpv4 = "8.8.8.8"), helperUid,
            validReply().copy(remoteIpv4 = "8.8.8.8")))
        listOf(
            "0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.254",
            "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.254",
            "192.168.1.1", "224.0.0.1", "239.255.255.255", "255.255.255.255",
            "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.17",
        ).forEach { remote ->
            assertFalse(remote, PersonalTunProof.accepts(helperUid, nonce,
                prepared.copy(remoteIpv4 = remote), helperUid,
                validReply().copy(remoteIpv4 = remote)))
        }
    }
}
