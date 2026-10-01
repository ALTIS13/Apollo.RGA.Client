package org.olcbox.app.update.personal

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface PersonalHighWaterRead {
    data class Ready(val value: PersonalUpdateHighWater) : PersonalHighWaterRead
    data object Corrupt : PersonalHighWaterRead
    data object IoFailure : PersonalHighWaterRead
}
enum class PersonalHighWaterCommit { STORED, UNCHANGED, DENIED, ROLLBACK, CONFLICT, CORRUPT, IO_FAILURE }
class PersonalUpdateHighWaterStore(private val file: File) {
    private val atomicFile = AtomicFile(file)
    fun read(): PersonalHighWaterRead = synchronized(lock) { readLocked() }

    /** Retain the durable sequence authority through the final installer handoff.
     * Lock order is store -> current-session fence -> verifier. Callers must not
     * enter this method while already holding the session/verifier monitor, and
     * must check their captured cancellation inside [action]. No APK hashing or
     * network work belongs here. A fresh/uncommitted record never authorizes. */
    fun authorizeIfCurrent(expected: PersonalUpdateHighWater, action: () -> Boolean): Boolean = synchronized(lock) {
        if (expected.sequence <= 0 || !hash.matches(expected.payloadSha256.orEmpty())) return@synchronized false
        if (readLocked() != PersonalHighWaterRead.Ready(expected)) return@synchronized false
        action()
    }

    /** The fence must run [action] synchronously while holding current-session authority.
     * It returns false without running action if authorization was revoked. */
    suspend fun commit(verified: PersonalUpdateVerification.Success,
        commitFence: (action: () -> Unit) -> Boolean): PersonalHighWaterCommit {
        val cancellation = currentCoroutineContext()
        return synchronized(lock) {
            val next = PersonalUpdateHighWater(verified.manifest.sequence, verified.payloadSha256)
            if (next.sequence <= 0 || !hash.matches(next.payloadSha256.orEmpty())) return@synchronized PersonalHighWaterCommit.CORRUPT
            val current = when (val read = readLocked()) {
                is PersonalHighWaterRead.Ready -> read.value
                PersonalHighWaterRead.Corrupt -> return@synchronized PersonalHighWaterCommit.CORRUPT
                PersonalHighWaterRead.IoFailure -> return@synchronized PersonalHighWaterCommit.IO_FAILURE
            }
            if (next.sequence < current.sequence) return@synchronized PersonalHighWaterCommit.ROLLBACK
            if (next.sequence == current.sequence && next.payloadSha256 != current.payloadSha256) return@synchronized PersonalHighWaterCommit.CONFLICT
            if (next == current) return@synchronized PersonalHighWaterCommit.UNCHANGED
            var stream: FileOutputStream? = null
            var performed = false
            try {
                val allowed = commitFence {
                    check(!performed) { "Invalid update commit fence" }
                    cancellation.ensureActive()
                    stream = atomicFile.startWrite()
                    stream!!.write("APOLLO-RGA-PERSONAL-HIGH-WATER/1\n${next.sequence}\n${next.payloadSha256}\n".toByteArray(Charsets.US_ASCII))
                    stream!!.fd.sync()
                    cancellation.ensureActive()
                    atomicFile.finishWrite(stream)
                    stream = null
                    // finishWrite is void and may only log a failed rename internally.
                    if (readLocked() != PersonalHighWaterRead.Ready(next)) throw IOException("Update high-water unavailable")
                    performed = true
                }
                if (allowed && performed) PersonalHighWaterCommit.STORED else PersonalHighWaterCommit.DENIED
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PersonalHighWaterCommit.IO_FAILURE }
            finally { stream?.let { try { atomicFile.failWrite(it) } catch (_: Exception) { } } }
        }
    }
    private fun readLocked(): PersonalHighWaterRead = try {
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) {
            if (File(file.path + ".new").exists()) PersonalHighWaterRead.Corrupt
            else PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null))
        } else atomicFile.openRead().use { input ->
            val bytes = ByteArray(160); var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; count += n
            }
            if (count == 0 || input.read() != -1) return@use PersonalHighWaterRead.Corrupt
            val text = String(bytes, 0, count, Charsets.US_ASCII)
            val match = record.matchEntire(text) ?: return@use PersonalHighWaterRead.Corrupt
            val sequence = match.groupValues[1].toLongOrNull() ?: return@use PersonalHighWaterRead.Corrupt
            PersonalHighWaterRead.Ready(PersonalUpdateHighWater(sequence, match.groupValues[2]))
        }
    } catch (_: Exception) { PersonalHighWaterRead.IoFailure }
    companion object {
        // Includes reopen/multiple store instances: AtomicFile itself provides no thread exclusion.
        private val lock = Any()
        private val hash = Regex("[0-9a-f]{64}")
        private val record = Regex("APOLLO-RGA-PERSONAL-HIGH-WATER/1\n([1-9][0-9]*)\n([0-9a-f]{64})\n")
    }
}
