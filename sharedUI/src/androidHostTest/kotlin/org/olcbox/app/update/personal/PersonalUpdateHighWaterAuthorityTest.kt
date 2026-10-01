package org.olcbox.app.update.personal

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PersonalUpdateHighWaterAuthorityTest {
    private fun file() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "ota-authority-${System.nanoTime()}")
    private fun candidate(sequence: Long) = AndroidPersonalApkVerifierTest.Fixture().verified.let {
        it.copy(manifest = it.manifest.copy(sequence = sequence), payloadSha256 = "a".repeat(64))
    }
    private fun pair(sequence: Long) = PersonalUpdateHighWater(sequence, "a".repeat(64))

    @Test fun newerCommitFromAnotherStoreRetiresOldInstallerAuthority(): Unit = runBlocking {
        val path = file(); val store = PersonalUpdateHighWaterStore(path)
        store.commit(candidate(1)) { action -> action(); true }
        PersonalUpdateHighWaterStore(path).commit(candidate(2)) { action -> action(); true }
        var calls = 0
        assertFalse(store.authorizeIfCurrent(pair(1)) { ++calls; true })
        assertFalse(store.authorizeIfCurrent(PersonalUpdateHighWater(2, "b".repeat(64))) { ++calls; true })
        assertEquals(0, calls)
        assertTrue(store.authorizeIfCurrent(pair(2)) { ++calls; true })
        assertEquals(1, calls)
        assertFalse(store.authorizeIfCurrent(pair(2)) { false })
    }

    @Test fun absentOrCorruptStateCannotAuthorizeAnInstaller(): Unit = runBlocking {
        val path = file(); val store = PersonalUpdateHighWaterStore(path)
        var calls = 0
        assertFalse(store.authorizeIfCurrent(PersonalUpdateHighWater(0, null)) { ++calls; true })
        assertFalse(store.authorizeIfCurrent(pair(1)) { ++calls; true })
        path.writeText("broken")
        assertFalse(store.authorizeIfCurrent(pair(1)) { ++calls; true })
        assertEquals(0, calls)
        assertEquals("broken", path.readText())
    }

    @Test fun concurrentAdvanceWaitsForTheCurrentAuthorityHandoff(): Unit = runBlocking {
        val path = file(); val store = PersonalUpdateHighWaterStore(path)
        store.commit(candidate(1)) { action -> action(); true }
        val next = candidate(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val writerStarted = CountDownLatch(1); val writerFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val handoff = Thread {
            try {
                assertTrue(store.authorizeIfCurrent(pair(1)) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    true
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        val writer = Thread {
            try {
                writerStarted.countDown()
                runBlocking {
                    assertEquals(PersonalHighWaterCommit.STORED,
                        PersonalUpdateHighWaterStore(path).commit(next) { action -> action(); true })
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { writerFinished.countDown() }
        }
        try {
            handoff.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.start(); assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown(); handoff.join(5000); if (writer.isAlive) writer.join(5000)
        }
        failure.get()?.let { throw it }
        assertFalse(handoff.isAlive); assertFalse(writer.isAlive)
        assertEquals(PersonalHighWaterRead.Ready(pair(2)), store.read())
    }
}
