package org.olcbox.app.vpn

import java.util.concurrent.atomic.AtomicBoolean
import mobile.ManagedJoinSigner
import mobile.LogWriter
import mobile.Mobile
import mobile.Runtime
import mobile.SocketProtector
import org.olcbox.app.net.AndroidManagedJoinSigner
import org.olcbox.app.net.GeneralBootstrapV1
import org.olcbox.app.net.GeneralBootstrapSelection
import org.olcbox.app.net.PersonalJoinSigningKey
import org.olcbox.app.log.LogScrubber

/** Opt-in source only. Not registered with the default/personal provider. */
internal class AndroidGeneralOlcRtcRuntimeFactory : AndroidGeneralSelectionRuntimeHandleFactory {
    override fun create(metadata: GeneralBootstrapV1, installation: PersonalJoinSigningKey): AndroidGeneralRuntimeHandle =
        GeneralHandle(metadata, installation) // No Mobile/Runtime call or key callback here.
    override fun create(selection: GeneralBootstrapSelection, installation: PersonalJoinSigningKey): AndroidGeneralRuntimeHandle =
        GeneralHandle(selection.metadata, installation, selection)
}

private class GeneralHandle(
    private val metadata: GeneralBootstrapV1,
    private val installation: PersonalJoinSigningKey,
    private val selection: GeneralBootstrapSelection? = null
) : AndroidGeneralRuntimeHandle() {
    private val initializeOnce = AtomicBoolean()
    private val startOnce = AtomicBoolean()
    @Volatile private var jniAttempted = false
    @Volatile private var runtime: Runtime? = null
    @Volatile private var signer: ManagedJoinSigner? = null
    @Volatile private var stopReturned = false

    override fun initialize() = guarded {
        check(initializeOnce.compareAndSet(false, true)) { GENERAL_RUNTIME_DENIED }
        val roomUrl = metadata.roomUrl ?: throw IllegalStateException(GENERAL_RUNTIME_DENIED)
        check(metadata.usesManagedLease && metadata.hasValidProviderLocator) { GENERAL_RUNTIME_DENIED }
        try {
            android.util.Log.d("RgaGeneralRuntime", when (metadata.provider) {
                "salutejazz" -> "profile=SALUTEJAZZ_DATA"
                "vkcalls" -> "profile=VKCALLS_VP8"
                "mtslink" -> "profile=MTSLINK_VP9_TCP"
                "telemost" -> "profile=TELEMOST_VP8"
                else -> "profile=DENIED"
            })
        } catch (_: Exception) { /* Telemetry cannot veto native startup. */ }
        // Key/freshness validation precedes JNI allocation; reconnect uses this SAME signer.
        val proof = if (selection == null) AndroidManagedJoinSigner(metadata, installation)
            else AndroidManagedJoinSigner(selection, installation)
        val callback = object : ManagedJoinSigner {
            override fun signManagedJoin(): String = guarded { proof.signManagedJoin() }
        }
        signer = callback
        jniAttempted = true
        val owned = Mobile.new_()
        runtime = owned // Retain immediately, including every later setter exception.
        owned.setDebug(false)
        owned.setLoadDiagnostics(android.util.Log.isLoggable("RgaGeneralLoad", android.util.Log.DEBUG))
        owned.setProvider(metadata.provider)
        owned.setTransport(metadata.transport)
        configureGeneralNativeRoom(metadata, { owned.setRoom(it) }, { owned.setChannel(it) })
        owned.setKey(metadata.roomKey)
        owned.setDeviceID(metadata.installationId)
        owned.setManagedJoinSigner(callback)
    }

    override fun setProtector(protect: (Long) -> Boolean) = guarded {
        native().setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean = try { protect(fd) } catch (_: Throwable) { false }
        })
    }
    override fun setLogWriter(writeLog: (String) -> Unit) = guarded {
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                // Gomobile logger is global; never forward its raw message to app output.
                try { writeLog(LogScrubber.default.scrub(msg)) } catch (_: Throwable) { }
            }
        })
    }
    override fun setSocksPort(value: Long) = guarded {
        check(!startOnce.get()) { GENERAL_RUNTIME_DENIED }
        validateCapturedSocksPort(value) // Never change the pre-JNI listener capture.
        native().setSocksPort(value)
    }
    override fun setSocksListenHost(value: String) = guarded {
        check(!startOnce.get() && value == "127.0.0.1") { GENERAL_RUNTIME_DENIED }
        native().setSocksListenHost(value)
    }
    override fun setSocksCredentials(username: String, password: String) = guarded { native().setSocksCredentials(username, password) }
    override fun setDNS(value: String) = guarded { native().setDNS(value) }
    override fun setVP8Options(fps: Long, batch: Long) = guarded { native().setVP8Options(fps, batch) }
    override fun setDirectRules(value: String) = guarded { native().setDirectRules(value) }
    override fun setUDP(enabled: Boolean) = guarded { native().setUDP(enabled) }
    override fun start() = guarded {
        check(startOnce.compareAndSet(false, true) && capturedSocksPort in 1..65535 && signer != null) { GENERAL_RUNTIME_DENIED }
        native().start()
    }
    override fun waitReady(timeoutMs: Long) = guarded {
        check(startOnce.get() && timeoutMs in 1..60_000) { GENERAL_RUNTIME_DENIED }
        native().waitReady(timeoutMs)
    }
    override fun stop(timeoutMs: Long) = guarded {
        check(timeoutMs in 1..60_000) { GENERAL_RUNTIME_DENIED }
        if (!jniAttempted) return@guarded // Known no-JNI path after inert/freshness failure.
        native().stop(timeoutMs)
        stopReturned = true
    }
    override fun isRunning(): Boolean = guarded { if (!jniAttempted) false else native().isRunning }

    override fun transportHealth(): GeneralTransportHealth33 {
        if (!jniAttempted) return GeneralTransportHealth33.UNKNOWN
        val captured = runtime ?: return GeneralTransportHealth33.UNKNOWN
        // Live stream observation only; lifecycle state/readiness is not health.
        return try {
            GeneralTransportHealth33.fromNative(captured.javaClass.getMethod("transportHealth").invoke(captured) as? String)
        } catch (_: Throwable) { GeneralTransportHealth33.UNKNOWN }
    }

    override fun readLoadDiagnostics(): String {
        if (!jniAttempted || stopReturned) return ""
        return try { runtime?.readLoadDiagnostics().orEmpty() } catch (_: Throwable) { "" }
    }

    override fun listenerReleased(): Boolean {
        if (!jniAttempted) return true // No native allocation or listener was attempted.
        if (!stopReturned || runtime == null || capturedSocksPort !in 1..65535) return false
        // Called ONLY after owned Stop returned, Kotlin startup joined and native idle.
        // Exact IPv4 loopback/port, never wildcard; this is not a Go-goroutine join proof.
        return generalListenerAddressReleased(capturedSocksPort)
    }

    private fun native(): Runtime = runtime ?: throw IllegalStateException(GENERAL_RUNTIME_DENIED)
    private inline fun <T> guarded(call: () -> T): T = try { call() }
        catch (error: Throwable) { throw GeneralNativeFailure.from(error) }
    override fun toString() = "AndroidGeneralOlcRtcRuntime([redacted])"
}
