package org.olcbox.app.update.personal

import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual sharedUI handler used by the thin debug receiver, not Android DUMP enforcement. */
class PersonalUpdateDiagnosticReadTest {
    @Test fun exactEnabledOrderedReadExposesOnlyFrozenFiniteRecord() {
        // Break caught: terminal evidence lost at read, or arbitrary caller data reaches result-data.
        val owner = PersonalUpdateDiagnosticRecords.newOwner { 42 }
        val operation = owner.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        operation.bytesRead(9); operation.failedFence(PersonalUpdateDiagnosticFence.PHYSICAL_CHANGED)
        operation.terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        val data = read()
        assertNotNull("enabled ordered read omitted terminal evidence", data)
        assertTrue(data!!.contains("state=TERMINAL"))
        assertTrue(data.contains("bodyBytesRead=9"))
        assertTrue(data.contains("eventMs=42"))
        assertTrue(data.contains("fence=PHYSICAL_CHANGED"))
        assertTrue(data.length <= 512)
        assertTrue(data.matches(Regex("[A-Za-z0-9_= \n]+")))
    }

    @Test fun disabledWrongActionWrongPackageAndUnorderedReadsAreInert() {
        assertNull(read(enabled = false))
        assertNull(read(action = "private caller payload"))
        assertNull(read(packageName = "tech.gatealtas.rga.debug"))
        assertNull(read(ordered = false))
    }

    @Test fun proofSlotDoesNotReplaceTerminalAndReadDoesNotConsumeEvidence() {
        // This unit test exercises storage only; it is not a positive Service full-TUN proof.
        val owner = PersonalUpdateDiagnosticRecords.newOwner { 70 }
        owner.begin(PersonalUpdateDiagnosticKind.CHECK).terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        owner.beginProof().committed(false, 71)
        val first = read()
        assertNotNull("post-Stop one-shot read lacks stored records", first)
        assertTrue(first!!.contains("state=TERMINAL"))
        assertTrue(first.contains("committed=0"))
        assertTrue(first.contains("proofEventMs=71"))
        assertEquals(first, read())
    }

    private fun read(packageName: String = "tech.gatealtas.rga.personal",
        action: String = PERSONAL_UPDATE_DIAGNOSTIC_READ_ACTION, ordered: Boolean = true,
        enabled: Boolean = true) = PersonalUpdateDiagnosticRecords.readOrdered(packageName, action, ordered, enabled)
}
