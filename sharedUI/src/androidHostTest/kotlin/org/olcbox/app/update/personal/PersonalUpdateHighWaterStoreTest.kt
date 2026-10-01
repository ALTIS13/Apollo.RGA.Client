package org.olcbox.app.update.personal

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PersonalUpdateHighWaterStoreTest {
    private fun file() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "ota-water-${System.nanoTime()}")
    private fun candidate(sequence: Long = 1, hash: String = "a".repeat(64)) =
        AndroidPersonalApkVerifierTest.Fixture().verified.let { it.copy(manifest = it.manifest.copy(sequence = sequence), payloadSha256 = hash) }
    @Test fun absentStartsAtZeroAndCommittedPairSurvivesReopen(): Unit = runBlocking {
        val file = file(); val store = PersonalUpdateHighWaterStore(file)
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0,null)), store.read())
        assertEquals(PersonalHighWaterCommit.STORED, store.commit(candidate()) { action -> action(); true })
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(1,"a".repeat(64))), PersonalUpdateHighWaterStore(file).read())
        assertEquals(PersonalHighWaterCommit.UNCHANGED, store.commit(candidate()) { action -> action(); true })
        assertEquals(PersonalHighWaterCommit.STORED, store.commit(candidate(2)) { action -> action(); true })
    }
    @Test fun rollbackAndSequenceConflictPreserveLastDurablePair(): Unit = runBlocking {
        val file = file(); val store = PersonalUpdateHighWaterStore(file)
        store.commit(candidate(2)) { action -> action(); true }; val bytes = file.readBytes()
        assertEquals(PersonalHighWaterCommit.ROLLBACK, store.commit(candidate()) { action -> action(); true })
        assertEquals(PersonalHighWaterCommit.CONFLICT, store.commit(candidate(2,"b".repeat(64))) { action -> action(); true })
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun corruptionNeverResetsAndMalformedInputCannotPersist(): Unit = runBlocking {
        val file = file().apply { writeText("broken state") }; val store = PersonalUpdateHighWaterStore(file)
        assertEquals(PersonalHighWaterRead.Corrupt, store.read())
        assertEquals(PersonalHighWaterCommit.CORRUPT, store.commit(candidate()) { action -> action(); true })
        assertEquals("broken state", file.readText())
        val clean = file(); val other = PersonalUpdateHighWaterStore(clean)
        assertEquals(PersonalHighWaterCommit.CORRUPT, other.commit(candidate(0)) { action -> action(); true })
        assertEquals(PersonalHighWaterCommit.CORRUPT, other.commit(candidate(hash = "BAD")) { action -> action(); true })
        assertFalse(clean.exists())
    }
    @Test fun fenceDenialAtDurableBoundaryPreservesAtomicFile(): Unit = runBlocking {
        val file = file(); val store = PersonalUpdateHighWaterStore(file)
        store.commit(candidate()) { action -> action(); true }; val bytes = file.readBytes(); var calls = 0
        assertEquals(PersonalHighWaterCommit.DENIED, store.commit(candidate(2)) { ++calls; false })
        assertEquals(1, calls)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(1,"a".repeat(64))), store.read())
    }
    @Test fun cancellationAtDurableBoundaryPropagatesAndPreservesPair(): Unit = runBlocking {
        val file = file(); val store = PersonalUpdateHighWaterStore(file)
        store.commit(candidate()) { action -> action(); true }; val bytes = file.readBytes()
        val job = Job()
        try { runBlocking(job) { store.commit(candidate(2)) { action -> job.cancel(); action(); true } }; fail("cancel must propagate") }
        catch (_: CancellationException) { }
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun atomicFileBackupRecoveryKeepsPairAndOrphanNewFileFailsClosed(): Unit = runBlocking {
        val file = file(); val store = PersonalUpdateHighWaterStore(file)
        store.commit(candidate()) { action -> action(); true }
        val original = file.readBytes()
        file.renameTo(File(file.path + ".bak")); file.writeText("partial newer state")
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(1,"a".repeat(64))), store.read())
        assertArrayEquals(original, file.readBytes())
        val orphan = file(); File(orphan.path + ".new").writeText("partial")
        assertEquals(PersonalHighWaterRead.Corrupt, PersonalUpdateHighWaterStore(orphan).read())
    }
    @Test fun atomicFileRenameFailureCannotReportStored(): Unit = runBlocking {
        val file = file(); val store = PersonalUpdateHighWaterStore(file)
        assertEquals(PersonalHighWaterCommit.STORED, store.commit(candidate()) { action -> action(); true })
        val held = File(file.path + ".held")
        val result = store.commit(candidate(2)) { action ->
            assertTrue(file.renameTo(held))
            assertTrue(file.mkdir())
            File(file, "block-rename").writeText("fixture")
            action(); true
        }
        assertEquals(PersonalHighWaterCommit.IO_FAILURE, result)
        assertEquals(PersonalHighWaterRead.IoFailure, store.read())
    }
    @Test fun fenceCannotClaimCommitWithoutRunningAction(): Unit = runBlocking {
        val file = file(); val store = PersonalUpdateHighWaterStore(file)
        assertEquals(PersonalHighWaterCommit.DENIED, store.commit(candidate()) { true })
        assertFalse(file.exists())
    }
}
