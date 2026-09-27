package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessPolicySnapshotTest {
    private fun payload(
        subject: String = "installation_subject_1234",
        status: String = "ACTIVE",
        used: Long = 100,
        allowed: Boolean = false,
        refreshAfter: Long = 2_000
    ) = """{
        "schemaVersion":1,
        "opaqueSubject":"$subject",
        "revision":3,
        "serverTimeEpochMs":1000,
        "expiresAtEpochMs":100000,
        "refreshAfterEpochMs":$refreshAfter,
        "status":"$status",
        "totalLimitBytes":1000,
        "confirmedUsedBytes":$used,
        "remainingBytes":800,
        "olcrtcEnabled":true,
        "ordinaryAllowed":true,
        "alternateAllowed":$allowed,
        "reason":"ENFORCEMENT_UNAVAILABLE"
    }""".trimIndent()

    @Test fun validSnapshotIsPresentableButCannotOpenReleaseOlcrtc() {
        val snapshot = assertNotNull(AccessPolicySnapshotV1.decode(payload()))
        assertEquals("installation_subject_1234", snapshot.opaqueSubject)
        assertEquals(800L, snapshot.remainingBytes)
        assertTrue(snapshot.isFreshAt(1_500))
        assertFalse(snapshot.mayShowAlternate(RuntimeLocationPolicy.OrdinaryOnly, 1_500))
    }

    @Test fun malformedOrUnknownRequiredValuesAreRejectedWithoutReturningPayload() {
        assertNull(AccessPolicySnapshotV1.decode(payload().replace("\"revision\":3,", "")))
        assertNull(AccessPolicySnapshotV1.decode(payload(status = "MYSTERY")))
        assertNull(AccessPolicySnapshotV1.decode(payload(used = -1)))
        assertNull(AccessPolicySnapshotV1.decode(payload(subject = "https://panel.example.invalid/sub/secret")))
        assertNull(AccessPolicySnapshotV1.decode(payload().replace("\"reason\":\"ENFORCEMENT_UNAVAILABLE\"", "\"reason\":\"UNKNOWN_REASON\"")))
    }

    @Test fun staleOrExpiredDataNeverShowsAlternate() {
        val stale = assertNotNull(AccessPolicySnapshotV1.decode(
            payload(allowed = true).replace("ENFORCEMENT_UNAVAILABLE", "NONE")
        ))
        assertFalse(stale.isFreshAt(2_000))
        assertFalse(stale.mayShowAlternate(RuntimeLocationPolicy.ExperimentalDebug, 2_000))
        assertFalse(stale.mayShowAlternate(RuntimeLocationPolicy.ExperimentalDebug, 100_000))
    }

    @Test fun finiteQuotaInconsistencyCannotPresentAlternate() {
        val allowed = payload(allowed = true).replace("ENFORCEMENT_UNAVAILABLE", "NONE")
        assertNull(AccessPolicySnapshotV1.decode(allowed.replace("\"confirmedUsedBytes\":100", "\"confirmedUsedBytes\":1200")))
        assertNull(AccessPolicySnapshotV1.decode(allowed.replace("\"remainingBytes\":800", "\"remainingBytes\":1200")))
        assertNull(AccessPolicySnapshotV1.decode(allowed.replace("\"remainingBytes\":800", "\"remainingBytes\":0")))
    }

    @Test fun evenServerAllowedSnapshotCannotOverrideOrdinaryOnlyRuntimeGate() {
        val serverAllowed = assertNotNull(AccessPolicySnapshotV1.decode(
            payload(allowed = true).replace("ENFORCEMENT_UNAVAILABLE", "NONE")
        ))
        assertFalse(serverAllowed.mayShowAlternate(RuntimeLocationPolicy.OrdinaryOnly, 1_500))
        assertFalse(RuntimeLocationPolicy.OrdinaryOnly.permits(LocationKind.Olcrtc))
        assertTrue(serverAllowed.mayShowAlternate(RuntimeLocationPolicy.ExperimentalDebug, 1_500))
    }
}
