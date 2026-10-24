package org.olcbox.app.vpn.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.system.OsConstants
import android.util.Log
import java.net.InetSocketAddress
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Personal QA only. This validates an independent UID's live HTTPS exit receipt; the caller must
 * separately correlate the exact socket with this generation's TUN/OlcRTC flow before promoting UI.
 */
internal object PersonalTunProbeClient {
    data class Prepared(
        val nonce: String,
        val helperUid: Int,
        val remoteIpv4: String,
        val localPort: Int,
    )

    data class Receipt(
        val nonce: String,
        val helperUid: Int,
        val httpStatus: Int,
        val egressMatched: Boolean,
        val localIpv4: String,
        val remoteIpv4: String,
        val localPort: Int,
    )

    sealed interface Result {
        data class CompanionReceipt(val value: Receipt) : Result
        data class Rejected(val reason: Reason, val report: Receipt? = null) : Result
    }

    enum class Reason {
        UNSUPPORTED, HELPER_MISSING, SIGNATURE, NOT_INDEPENDENT_UID, PERMISSION,
        BIND, PROTOCOL, ARM_REJECTED, NETWORK, OWNER_UNAVAILABLE, RECEIPT_REJECTED,
        LIVE_FLOW_REJECTED, TIMEOUT,
    }

    /**
     * Arm the native exact-flow observer in [onPrepared]. [onLiveReceipt] runs after the live
     * socket-owner check but before CANCEL/unbind, so it can compare native flow evidence and
     * current bridge/network/session generation. Neither callback may promote UI on its own.
     */
    suspend fun probe(
        context: Context,
        expectedIpv4: String,
        onPrepared: suspend (Prepared) -> Boolean,
        onLiveReceipt: suspend (Receipt) -> Boolean,
    ): Result = withContext(Dispatchers.Main.immediate) {
        diagnostic(context, Stage.BEGIN)
        if (Build.VERSION.SDK_INT < 29 || context.packageName != MAIN_PACKAGE) {
            return@withContext Result.Rejected(Reason.UNSUPPORTED)
        }
        // The caller selects DNS metadata on its captured physical network. The independent
        // helper must not resolve through the VPN's FakeDNS or substitute a different target.
        if (!PersonalTunProof.isPublicProbeIpv4(expectedIpv4)) {
            return@withContext Result.Rejected(Reason.PROTOCOL)
        }
        val pm = context.packageManager
        val helperUid = try {
            pm.getPackageUid(HELPER_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext Result.Rejected(Reason.HELPER_MISSING)
        }
        if (helperUid <= 0 || helperUid == Process.myUid()) {
            return@withContext Result.Rejected(Reason.NOT_INDEPENDENT_UID)
        }
        if (!sameCurrentSigners(pm, MAIN_PACKAGE, HELPER_PACKAGE)) {
            return@withContext Result.Rejected(Reason.SIGNATURE)
        }
        if (pm.checkPermission(Manifest.permission.INTERNET, HELPER_PACKAGE) !=
                PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.Rejected(Reason.PERMISSION)
        }

        val nonce = randomNonce()
        diagnostic(context, Stage.SECURITY_READY)
        val inbox = Channel<Incoming>(capacity = 2)
        val binder = CompletableDeferred<IBinder>()
        var bindingInvalidated = false
        var liveBinder: IBinder? = null
        var probeJob: Job? = null
        fun invalidateBinding(reason: String) {
            bindingInvalidated = true
            liveBinder = null
            binder.completeExceptionally(IllegalStateException(reason))
            inbox.close()
            // Inbox closure alone cannot stop a callback after RESULT was consumed.
            probeJob?.cancel(BindingLostCancellation())
        }
        fun bindingLive(): Boolean = !bindingInvalidated && liveBinder?.isBinderAlive == true
        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            inbox.trySend(Incoming(message.what, message.sendingUid, Bundle(message.data)))
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                diagnostic(context, Stage.BINDER_CONNECTED)
                if (!bindingInvalidated && name.packageName == HELPER_PACKAGE && name.className == SERVICE_CLASS) {
                    liveBinder = service
                    binder.complete(service)
                } else {
                    invalidateBinding("unexpected_component")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                diagnostic(context, Stage.BINDER_DISCONNECTED)
                invalidateBinding("disconnected")
            }

            override fun onNullBinding(name: ComponentName) {
                diagnostic(context, Stage.NULL_BINDING)
                invalidateBinding("null_binding")
            }

            override fun onBindingDied(name: ComponentName) {
                diagnostic(context, Stage.BINDING_DIED)
                invalidateBinding("binding_died")
            }
        }
        var bound = false
        var helperMessenger: Messenger? = null
        try {
            withTimeout(TOTAL_TIMEOUT_MS) {
                probeJob = coroutineContext[Job]
                val intent = Intent(ACTION).setComponent(ComponentName(HELPER_PACKAGE, SERVICE_CLASS))
                diagnostic(context, Stage.BIND_REQUEST)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                diagnostic(context, if (bound) Stage.BIND_ACCEPTED else Stage.BIND_REJECTED)
                if (!bound) return@withTimeout Result.Rejected(Reason.BIND)
                helperMessenger = Messenger(binder.await())
                diagnostic(context, Stage.BINDER_READY)
                if (!bindingLive()) return@withTimeout Result.Rejected(Reason.BIND)
                diagnostic(context, Stage.PREPARE_SEND)
                helperMessenger!!.send(Message.obtain(null, PREPARE).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(KEY_NONCE, nonce)
                        putString(KEY_REMOTE_IPV4, expectedIpv4)
                    }
                })

                val first = inbox.receiveCatching().getOrNull()
                    ?: return@withTimeout Result.Rejected(Reason.BIND)
                diagnostic(context, if (first.what == PREPARED) Stage.PREPARED_RECEIVED else Stage.PREPARE_UNEXPECTED_REPLY)
                if (first.sendingUid != helperUid) {
                    return@withTimeout Result.Rejected(Reason.PROTOCOL)
                }
                if (first.what == RESULT) return@withTimeout Result.Rejected(Reason.NETWORK)
                if (first.what != PREPARED || first.data.getString(KEY_NONCE) != nonce ||
                    first.data.getInt(KEY_UID, -1) != helperUid
                ) return@withTimeout Result.Rejected(Reason.PROTOCOL)
                val preparedTuple = PersonalTunProof.Prepared(
                    first.data.getString(KEY_REMOTE_IPV4).orEmpty(),
                    first.data.getInt(KEY_LOCAL_PORT, 0),
                )
                if (preparedTuple.remoteIpv4 != expectedIpv4 ||
                    !PersonalTunProof.isPublicProbeIpv4(preparedTuple.remoteIpv4) ||
                    preparedTuple.localPort !in 1..65535
                ) return@withTimeout Result.Rejected(Reason.PROTOCOL)
                val prepared = Prepared(
                    nonce, helperUid, preparedTuple.remoteIpv4, preparedTuple.localPort
                )
                diagnostic(context, Stage.ON_PREPARED_BEGIN)
                val armed = onPrepared(prepared)
                diagnostic(context, if (armed) Stage.ON_PREPARED_ACCEPTED else Stage.ON_PREPARED_REJECTED)
                if (!armed) return@withTimeout Result.Rejected(Reason.ARM_REJECTED)
                coroutineContext.ensureActive()
                if (!bindingLive()) return@withTimeout Result.Rejected(Reason.BIND)

                diagnostic(context, Stage.GO_SEND)
                helperMessenger!!.send(Message.obtain(null, GO).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply { putString(KEY_NONCE, nonce) }
                })
                val second = inbox.receiveCatching().getOrNull()
                    ?: return@withTimeout Result.Rejected(Reason.BIND)
                diagnostic(context, if (second.what == RESULT) Stage.RESULT_RECEIVED else Stage.RESULT_UNEXPECTED_REPLY)
                if (second.what != RESULT || second.sendingUid != helperUid ||
                    second.data.getInt(KEY_UID, -1) != helperUid
                ) return@withTimeout Result.Rejected(Reason.PROTOCOL)

                val reply = PersonalTunProof.Reply(
                    sendingUid = second.sendingUid,
                    claimedUid = second.data.getInt(KEY_UID, -1),
                    nonce = second.data.getString(KEY_NONCE).orEmpty(),
                    httpStatus = second.data.getInt(KEY_HTTP_STATUS, 0),
                    egressMatched = second.data.getBoolean(KEY_EGRESS_MATCHED, false),
                    localIpv4 = second.data.getString(KEY_LOCAL_IPV4).orEmpty(),
                    remoteIpv4 = second.data.getString(KEY_REMOTE_IPV4).orEmpty(),
                    localPort = second.data.getInt(KEY_LOCAL_PORT, 0),
                    failure = second.data.getString(KEY_FAILURE),
                )
                val report = Receipt(
                    reply.nonce, helperUid, reply.httpStatus, reply.egressMatched,
                    reply.localIpv4, reply.remoteIpv4, reply.localPort
                )
                if (!PersonalTunProof.isIpv4(reply.localIpv4) ||
                    reply.remoteIpv4 != expectedIpv4 ||
                    !PersonalTunProof.isPublicProbeIpv4(reply.remoteIpv4) || reply.localPort !in 1..65535
                ) return@withTimeout Result.Rejected(Reason.RECEIPT_REJECTED, report)
                suspend fun socketOwnerUid(): Int? = withContext(Dispatchers.IO) {
                    // Android's owner lookup performs network diagnostics; keep both calls off
                    // Main without changing StrictMode or weakening the independent UID proof.
                    try {
                        val connectivity = context.getSystemService(ConnectivityManager::class.java)
                        connectivity?.getConnectionOwnerUid(
                            OsConstants.IPPROTO_TCP,
                            InetSocketAddress(numericIpv4(reply.localIpv4), reply.localPort),
                            InetSocketAddress(numericIpv4(reply.remoteIpv4), REMOTE_PORT),
                        )
                    } catch (_: SecurityException) {
                        null
                    }
                }
                diagnostic(context, Stage.OWNER_CHECK_BEGIN)
                val ownerUid = socketOwnerUid()
                    ?: return@withTimeout Result.Rejected(Reason.OWNER_UNAVAILABLE, report)
                coroutineContext.ensureActive()
                if (!bindingLive()) return@withTimeout Result.Rejected(Reason.BIND, report)
                if (!PersonalTunProof.accepts(helperUid, nonce, preparedTuple, ownerUid, reply)) {
                    diagnostic(context, Stage.RECEIPT_REJECTED)
                    return@withTimeout Result.Rejected(Reason.RECEIPT_REJECTED, report)
                }
                diagnostic(context, Stage.RECEIPT_VALIDATED)
                val liveFlowMatches = try {
                    diagnostic(context, Stage.LIVE_CALLBACK_BEGIN)
                    onLiveReceipt(report)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    diagnostic(context, Stage.LIVE_CALLBACK_EXCEPTION)
                    false
                }
                diagnostic(context, if (liveFlowMatches) Stage.LIVE_CALLBACK_ACCEPTED else Stage.LIVE_CALLBACK_REJECTED)
                coroutineContext.ensureActive()
                if (!bindingLive()) return@withTimeout Result.Rejected(Reason.BIND, report)
                if (!liveFlowMatches) {
                    return@withTimeout Result.Rejected(Reason.LIVE_FLOW_REJECTED, report)
                }
                val finalOwnerUid = socketOwnerUid()
                    ?: return@withTimeout Result.Rejected(Reason.OWNER_UNAVAILABLE, report)
                coroutineContext.ensureActive()
                if (!bindingLive()) return@withTimeout Result.Rejected(Reason.BIND, report)
                if (!PersonalTunProof.accepts(helperUid, nonce, preparedTuple, finalOwnerUid, reply)) {
                    diagnostic(context, Stage.FINAL_OWNER_REJECTED)
                    return@withTimeout Result.Rejected(Reason.RECEIPT_REJECTED, report)
                }
                if (!bindingLive()) return@withTimeout Result.Rejected(Reason.BIND, report)
                diagnostic(context, Stage.COMPANION_RECEIPT)
                Result.CompanionReceipt(report)
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is BindingLostCancellation) {
                diagnostic(context, Stage.BINDING_LOST)
                Result.Rejected(Reason.BIND)
            } else if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                diagnostic(context, Stage.TIMEOUT)
                Result.Rejected(Reason.TIMEOUT)
            } else {
                diagnostic(context, Stage.CANCELLED)
                throw cancelled
            }
        } catch (error: Exception) {
            diagnostic(context, Stage.NETWORK_EXCEPTION)
            if (context.packageName == MAIN_PACKAGE) {
                android.util.Log.d("RgaPersonalProofClient", "NETWORK_EXCEPTION_CLASS=${error.javaClass.simpleName}")
            }
            Result.Rejected(Reason.NETWORK)
        } finally {
            withContext(NonCancellable) {
                diagnostic(context, Stage.CLEANUP)
                probeJob = null
                bindingInvalidated = true
                liveBinder = null
                try {
                    helperMessenger?.send(Message.obtain(null, CANCEL).apply {
                        replyTo = replyMessenger
                        data = Bundle().apply { putString(KEY_NONCE, nonce) }
                    })
                } catch (_: Exception) { /* Binder may already have died. */ }
                if (bound) {
                    try { context.unbindService(connection) } catch (_: Exception) { /* Already gone. */ }
                }
                inbox.close()
                diagnostic(context, Stage.CLEANUP_COMPLETE)
            }
        }
    }

    private fun diagnostic(context: Context, stage: Stage) {
        if (context.packageName == MAIN_PACKAGE) Log.d("RgaPersonalProofClient", stage.name)
    }

    private enum class Stage {
        BEGIN, SECURITY_READY, BIND_REQUEST, BIND_ACCEPTED, BIND_REJECTED,
        BINDER_CONNECTED, BINDER_READY, BINDER_DISCONNECTED, NULL_BINDING, BINDING_DIED,
        PREPARE_SEND, PREPARED_RECEIVED, PREPARE_UNEXPECTED_REPLY,
        ON_PREPARED_BEGIN, ON_PREPARED_ACCEPTED, ON_PREPARED_REJECTED,
        GO_SEND, RESULT_RECEIVED, RESULT_UNEXPECTED_REPLY, OWNER_CHECK_BEGIN,
        RECEIPT_REJECTED, RECEIPT_VALIDATED, LIVE_CALLBACK_BEGIN, LIVE_CALLBACK_EXCEPTION,
        LIVE_CALLBACK_ACCEPTED, LIVE_CALLBACK_REJECTED, FINAL_OWNER_REJECTED, COMPANION_RECEIPT,
        BINDING_LOST, TIMEOUT, CANCELLED, NETWORK_EXCEPTION, CLEANUP, CLEANUP_COMPLETE
    }

    private fun numericIpv4(value: String): InetAddress = InetAddress.getByAddress(
        value.split('.').map { it.toInt().toByte() }.toByteArray()
    )

    private fun randomNonce(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val digits = "0123456789abcdef"
        return buildString(32) {
            for (byte in bytes) {
                val number = byte.toInt() and 0xff
                append(digits[number ushr 4])
                append(digits[number and 15])
            }
        }
    }

    private fun sameCurrentSigners(
        pm: PackageManager, mainPackage: String, helperPackage: String,
    ): Boolean = try {
        if (pm.checkSignatures(mainPackage, helperPackage) != PackageManager.SIGNATURE_MATCH) {
            false
        } else {
            fun signers(name: String): Set<String> {
                val signingInfo = pm.getPackageInfo(
                name, PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo ?: return emptySet()
                return signingInfo.apkContentsSigners.map { signature ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                }.toSet()
            }
            val main = signers(mainPackage)
            main.isNotEmpty() && main == signers(helperPackage)
        }
    } catch (_: Exception) {
        false
    }

    private data class Incoming(val what: Int, val sendingUid: Int, val data: Bundle)
    private class BindingLostCancellation : CancellationException("personal_probe_binding_lost")

    private const val MAIN_PACKAGE = "tech.gatealtas.rga.personal"
    private const val HELPER_PACKAGE = "tech.gatealtas.rga.personal.test"
    private const val SERVICE_CLASS = "org.olcbox.app.PersonalTunProbeService"
    private const val ACTION = "tech.gatealtas.rga.personal.action.TUN_PROBE"
    private const val PERMISSION = "tech.gatealtas.rga.personal.permission.PERSONAL_TUN_PROBE"
    private const val PREPARE = 1
    private const val PREPARED = 2
    private const val GO = 3
    private const val RESULT = 4
    private const val CANCEL = 5
    private const val REMOTE_PORT = 443
    private const val TOTAL_TIMEOUT_MS = 20_000L
    private const val KEY_NONCE = "nonce"
    private const val KEY_UID = "uid"
    private const val KEY_REMOTE_IPV4 = "remoteIpv4"
    private const val KEY_LOCAL_IPV4 = "localIpv4"
    private const val KEY_LOCAL_PORT = "localPort"
    private const val KEY_HTTP_STATUS = "httpStatus"
    private const val KEY_EGRESS_MATCHED = "egressMatched"
    private const val KEY_FAILURE = "failure"
}
