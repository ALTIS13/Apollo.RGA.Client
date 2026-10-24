package org.olcbox.app.update.personal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class PersonalUpdateDiagnosticRecordsTest {
    @Test fun terminalIsOnceWithActualPositiveReadCountAndMonotonicEvent() {
        // Break caught: lost positive reads, wall-clock duration, or a second terminal overwrite.
        val clock = AtomicLong(100)
        val owner = PersonalUpdateDiagnosticRecords.newOwner(clock::get)
        val operation = owner.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        operation.bytesRead(7); operation.bytesRead(0); operation.bytesRead(-1)
        clock.set(125); operation.terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        clock.set(150); operation.terminal(PersonalUpdateDiagnosticBranch.HTTP_IO, false)
        val record = owner.terminal()
        assertNotNull("actual terminal record was not frozen", record)
        assertEquals(7L, record!!.bodyBytesRead)
        assertEquals(125L, record.eventElapsedRealtimeMs)
        assertEquals(25L, record.elapsedMs)
        assertEquals(PersonalUpdateDiagnosticBranch.RETIRED, record.branch)
        assertTrue(record.retired)
    }

    @Test fun lateOperationCannotOverwriteSuccessorOnTheSameOwner() {
        // Break caught: late old body/fence/catch writes pollute the current operation.
        val clock = AtomicLong(100)
        val owner = PersonalUpdateDiagnosticRecords.newOwner(clock::get)
        val old = owner.begin(PersonalUpdateDiagnosticKind.CHECK)
        clock.set(110); val fresh = owner.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        fresh.bytesRead(3); clock.set(140)
        fresh.terminal(PersonalUpdateDiagnosticBranch.CONTROLLER_REFUSAL, false)
        old.bytesRead(99); old.failedFence(PersonalUpdateDiagnosticFence.PHYSICAL_CHANGED)
        clock.set(170); old.terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        val record = owner.terminal()
        assertNotNull("fresh operation lost terminal ownership", record)
        assertEquals(PersonalUpdateDiagnosticKind.DOWNLOAD, record!!.operation)
        assertEquals(3L, record.bodyBytesRead)
        assertEquals(30L, record.elapsedMs)
        assertEquals(PersonalUpdateDiagnosticFence.NONE, record.fence)
    }

    @Test fun replacedOwnerCannotPublishTerminalOrProofIntoNewReadIndex() {
        // Break caught: old Controller/proof completion replaces a fresh process read owner.
        val oldOwner = PersonalUpdateDiagnosticRecords.newOwner { 100 }
        val old = oldOwner.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
        val freshOwner = PersonalUpdateDiagnosticRecords.newOwner { 200 }
        val fresh = freshOwner.begin(PersonalUpdateDiagnosticKind.CHECK)
        fresh.terminal(PersonalUpdateDiagnosticBranch.CONTROLLER_REFUSAL, false)
        freshOwner.beginProof().committed(false, 201)
        old.terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        oldOwner.beginProof().committed(true, 202)
        assertNotNull("current owner terminal missing", freshOwner.terminal())
        assertEquals(PersonalUpdateDiagnosticKind.CHECK, freshOwner.terminal()!!.operation)
        assertEquals(PersonalUpdateProofDiagnostic(false, 201), freshOwner.proof())
        assertFalse(PersonalUpdateDiagnosticRecords.readOrdered("tech.gatealtas.rga.personal",
            PERSONAL_UPDATE_DIAGNOSTIC_READ_ACTION, true, true)!!.contains("operation=DOWNLOAD"))
    }

    @Test fun blockedExternalConsumerCannotBlockRecordingOrOwnerReplacement() {
        // Break caught: exposing a reader holds a lifecycle/record lock through consumer work.
        val owner = PersonalUpdateDiagnosticRecords.newOwner { 100 }
        owner.begin(PersonalUpdateDiagnosticKind.CHECK).terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val observed = AtomicReference<PersonalUpdateTerminalDiagnostic?>()
        val consumer = thread {
            observed.set(owner.terminal())
            entered.countDown(); release.await()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotNull("reader did not expose immutable terminal", observed.get())
            val next = owner.begin(PersonalUpdateDiagnosticKind.DOWNLOAD)
            next.bytesRead(5); next.terminal(PersonalUpdateDiagnosticBranch.HTTP_IO, false)
            assertEquals(5L, owner.terminal()!!.bodyBytesRead)
        } finally { release.countDown(); consumer.join(2_000) }
    }

    @Test fun lateProofAttemptCannotReplaceNewerProofOnSameOwner() {
        // Storage ownership only: no fabricated Service receipt/publication is invoked.
        val owner = PersonalUpdateDiagnosticRecords.newOwner { 100 }
        val old = owner.beginProof(); val fresh = owner.beginProof()
        fresh.committed(false, 130); old.committed(true, 140)
        assertEquals("late old proof completion replaced current proof record",
            PersonalUpdateProofDiagnostic(false, 130), owner.proof())
    }

    @Test fun failedDiagnosticClockLeavesNoFalseCompleteRecord() {
        val owner = PersonalUpdateDiagnosticRecords.newOwner { throw IllegalStateException("private clock failure") }
        owner.begin(PersonalUpdateDiagnosticKind.CHECK).terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        assertNull(owner.terminal())
    }
}
