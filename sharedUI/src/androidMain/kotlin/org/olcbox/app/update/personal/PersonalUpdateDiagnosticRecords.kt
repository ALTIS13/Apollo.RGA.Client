package org.olcbox.app.update.personal

import android.os.SystemClock
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

const val PERSONAL_UPDATE_DIAGNOSTIC_TAG = "RgaPersonalUpdate"
const val PERSONAL_UPDATE_DIAGNOSTIC_READ_ACTION = "tech.gatealtas.rga.personal.DEBUG_READ_UPDATE_DIAGNOSTIC"

internal enum class PersonalUpdateDiagnosticKind { CHECK, DOWNLOAD, INSTALL }
internal enum class PersonalUpdateDiagnosticBranch {
    COMPLETED, RETIRED, CONTROLLER_REFUSAL, HTTP_IO, UNKNOWN_TIMEOUT, COROUTINE_CANCEL, UNKNOWN,
}
internal enum class PersonalUpdateDiagnosticFence {
    NONE, SNAPSHOT_MISSING, PHASE_NOT_WORKING, STATUS_NOT_CONNECTED, MODE_UNSUPPORTED,
    STOPPING, OWNER_CHANGED, TUN_OR_BRIDGE_CHANGED, SUBSCRIPTION_CHANGED, PHYSICAL_CHANGED,
    PHASE_EPOCH_CHANGED, NATIVE_NOT_RUNNING, UNKNOWN_TRANSITION,
}
internal data class PersonalUpdateTerminalDiagnostic(val operation: PersonalUpdateDiagnosticKind,
    val branch: PersonalUpdateDiagnosticBranch, val retired: Boolean,
    val fence: PersonalUpdateDiagnosticFence, val eventElapsedRealtimeMs: Long,
    val elapsedMs: Long, val bodyBytesRead: Long)
internal data class PersonalUpdateProofDiagnostic(val committed: Boolean, val eventElapsedRealtimeMs: Long)

/** Diagnostic storage only: no route evaluator, sink, timer, file or identity is retained. */
class PersonalUpdateDiagnosticOwner internal constructor(private val clock: () -> Long) {
    private data class OperationSlot(val operation: PersonalUpdateDiagnosticOperation, val record: PersonalUpdateTerminalDiagnostic?)
    private data class ProofSlot(val operation: PersonalUpdateDiagnosticProofOperation, val record: PersonalUpdateProofDiagnostic?)
    private val operationSlot = AtomicReference<OperationSlot?>()
    private val proofSlot = AtomicReference<ProofSlot?>()
    internal fun time(): Long? = runCatching { clock().takeIf { it >= 0 } }.getOrNull()
    internal fun begin(kind: PersonalUpdateDiagnosticKind): PersonalUpdateDiagnosticOperation {
        val operation = PersonalUpdateDiagnosticOperation(this, kind, time())
        if (PersonalUpdateDiagnosticRecords.owns(this)) operationSlot.set(OperationSlot(operation, null))
        return operation
    }
    internal fun current(operation: PersonalUpdateDiagnosticOperation) =
        PersonalUpdateDiagnosticRecords.owns(this) && operationSlot.get()?.operation === operation
    internal fun finish(operation: PersonalUpdateDiagnosticOperation, record: PersonalUpdateTerminalDiagnostic?) {
        val previous = operationSlot.get()
        if (PersonalUpdateDiagnosticRecords.owns(this) && previous?.operation === operation)
            operationSlot.compareAndSet(previous, OperationSlot(operation, record))
    }
    internal fun terminal() = if (PersonalUpdateDiagnosticRecords.owns(this)) operationSlot.get()?.record else null
    internal fun snapshot(): Triple<String, PersonalUpdateTerminalDiagnostic?, PersonalUpdateProofDiagnostic?> {
        val slot = operationSlot.get()
        val state = when {
            slot == null -> "NO_RECORD"
            slot.record != null -> "TERMINAL"
            slot.operation.finished -> "INCOMPLETE"
            else -> "PENDING"
        }
        return Triple(state, slot?.record, proofSlot.get()?.record)
    }
    internal fun beginProof(): PersonalUpdateDiagnosticProofOperation {
        val operation = PersonalUpdateDiagnosticProofOperation(this)
        if (PersonalUpdateDiagnosticRecords.owns(this)) proofSlot.updateAndGet { ProofSlot(operation, it?.record) }
        return operation
    }
    internal fun finishProof(operation: PersonalUpdateDiagnosticProofOperation, record: PersonalUpdateProofDiagnostic?) {
        val previous = proofSlot.get()
        if (PersonalUpdateDiagnosticRecords.owns(this) && previous?.operation === operation)
            proofSlot.compareAndSet(previous, ProofSlot(operation, record))
    }
    internal fun proof() = if (PersonalUpdateDiagnosticRecords.owns(this)) proofSlot.get()?.record else null
}
internal class PersonalUpdateDiagnosticProofOperation(private val owner: PersonalUpdateDiagnosticOwner) {
    private val closed = AtomicBoolean()
    fun committed(value: Boolean, eventMs: Long?) {
        if (closed.compareAndSet(false, true)) owner.finishProof(this,
            eventMs?.takeIf { it >= 0 }?.let { PersonalUpdateProofDiagnostic(value, it) })
    }
}
internal class PersonalUpdateDiagnosticOperation(private val owner: PersonalUpdateDiagnosticOwner,
    private val kind: PersonalUpdateDiagnosticKind, private val startMs: Long?) {
    private val closed = AtomicBoolean()
    private val count = AtomicLong()
    private val failure = AtomicReference(PersonalUpdateDiagnosticFence.NONE)
    private val httpFailure = AtomicReference(PersonalUpdateDiagnosticBranch.UNKNOWN)
    val finished: Boolean get() = closed.get()
    val fenceFailure: PersonalUpdateDiagnosticFence get() = failure.get()
    val errorBranch: PersonalUpdateDiagnosticBranch get() = httpFailure.get()
    fun bytesRead(value: Int) {
        if (value > 0 && !closed.get() && owner.current(this)) count.updateAndGet {
            if (it > Long.MAX_VALUE - value) Long.MAX_VALUE else it + value
        }
    }
    fun failedFence(reason: PersonalUpdateDiagnosticFence) {
        if (!closed.get() && owner.current(this)) failure.compareAndSet(PersonalUpdateDiagnosticFence.NONE, reason)
    }
    fun httpFailed(error: Exception) {
        if (!closed.get() && owner.current(this)) httpFailure.set(when (error) {
            is SocketTimeoutException -> PersonalUpdateDiagnosticBranch.UNKNOWN_TIMEOUT
            is IOException -> PersonalUpdateDiagnosticBranch.HTTP_IO
            else -> PersonalUpdateDiagnosticBranch.UNKNOWN
        }) // No exception/message/cause is retained; call-vs-read timeout is not inferred.
    }
    fun terminal(branch: PersonalUpdateDiagnosticBranch, retired: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        val event = owner.time()
        val record = if (startMs != null && event != null && event >= startMs) PersonalUpdateTerminalDiagnostic(
            kind, branch, retired,
            if (retired && failure.get() == PersonalUpdateDiagnosticFence.NONE) PersonalUpdateDiagnosticFence.UNKNOWN_TRANSITION else failure.get(),
            event, event - startMs, count.get()) else null
        owner.finish(this, record)
    }
}
object PersonalUpdateDiagnosticRecords {
    private val owner = AtomicReference<PersonalUpdateDiagnosticOwner?>()
    internal fun newOwner(clock: () -> Long = SystemClock::elapsedRealtime) =
        PersonalUpdateDiagnosticOwner(clock).also(owner::set)
    internal fun owns(value: PersonalUpdateDiagnosticOwner) = owner.get() === value
    internal fun proofOwner(): PersonalUpdateDiagnosticOwner? = owner.get()
    // Fixed platform clock, never an injected callback under a Service structural/tunnel lock.
    internal fun eventTime(): Long? = runCatching { SystemClock.elapsedRealtime() }.getOrNull()
    fun readOrdered(packageName: String, action: String?, ordered: Boolean, enabled: Boolean): String? {
        if (!enabled || !ordered || packageName != "tech.gatealtas.rga.personal" || action != PERSONAL_UPDATE_DIAGNOSTIC_READ_ACTION) return null
        val captured = owner.get() ?: return "RGA_UPDATE_V1 state=NO_RECORD"
        val (state, terminal, proof) = captured.snapshot()
        if (!owns(captured)) return "RGA_UPDATE_V1 state=NO_RECORD"
        return buildString {
            append("RGA_UPDATE_V1 state=").append(state)
            if (terminal != null) {
                append(" operation=").append(terminal.operation.name).append(" branch=").append(terminal.branch.name)
                append(" retired=").append(if (terminal.retired) 1 else 0).append(" fence=").append(terminal.fence.name)
                append(" eventMs=").append(terminal.eventElapsedRealtimeMs).append(" elapsedMs=").append(terminal.elapsedMs)
                append(" bodyBytesRead=").append(terminal.bodyBytesRead)
            }
            if (proof != null) append(" committed=").append(if (proof.committed) 1 else 0).append(" proofEventMs=").append(proof.eventElapsedRealtimeMs)
        }
    }
}
