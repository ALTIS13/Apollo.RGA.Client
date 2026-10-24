package org.olcbox.app.net

import org.junit.Assert.*
import org.junit.Test

class AndroidGeneralBootstrapSelectionTest {
    @Test fun originalExpiryIsNotRenewedAndReceiptOwnsThe24HourBound() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        val selection = GeneralBootstrapSelection.fromVerifiedReceipt(metadata, key, GeneralTestFixture.now, GeneralTestFixture.now)!!
        assertSame(metadata, selection.metadata)
        assertTrue(selection.matchesKeyAndIsUsableAt(key, GeneralTestFixture.now + 300_001))
        assertEquals(GeneralTestFixture.end, selection.metadata.expiresAtEpochMs)
        assertFalse(selection.metadata.isUsableAt(GeneralTestFixture.now + 300_001))
        assertTrue(selection.matchesKeyAndIsUsableAt(key, GeneralTestFixture.now + 86_399_999))
        assertFalse(selection.matchesKeyAndIsUsableAt(key, GeneralTestFixture.now + 86_400_000))
        assertFalse(selection.matchesKeyAndIsUsableAt(key, GeneralTestFixture.now - 1))
        assertFalse(selection.matchesKeyAndIsUsableAt(GeneralTestFixture.signer(), GeneralTestFixture.now))
        assertFalse(selection.matchesKeyAndIsUsableAt(object : PersonalJoinSigningKey by key {
            override val installationId = "different_installation_01"
        }, GeneralTestFixture.now))
        assertEquals("GeneralBootstrapSelection([redacted])", selection.toString())
    }

    @Test fun cannotRestoreAReceiptWhichNeverHadFreshMetadata() {
        val key = GeneralTestFixture.signer()
        for (expiry in listOf(GeneralTestFixture.now, GeneralTestFixture.now + 330_001)) {
            val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata(expiry).toByteArray())!!
            assertNull(GeneralBootstrapSelection.fromVerifiedReceipt(metadata, key, GeneralTestFixture.now, GeneralTestFixture.now))
        }
    }
}
