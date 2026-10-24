package org.olcbox.app.vpn.debug

import android.util.Log
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import org.olcbox.app.App
import org.olcbox.app.vpn.AndroidOlcRtcRuntime
import org.olcbox.app.vpn.AndroidOlcRtcRuntimeFactory
import org.olcbox.app.vpn.PersonalManagedUdpStartGate

/** Packaged only in debug builds, together with the optional gomobile AAR. */
class DebugOlcRtcRuntimeFactory : AndroidOlcRtcRuntimeFactory {
    override fun create(): AndroidOlcRtcRuntime = DebugOlcRtcRuntime()
}

private class DebugOlcRtcRuntime : AndroidOlcRtcRuntime {
    // The generated no-argument Runtime constructor does not initialize Go state.
    private val runtime = Mobile.new_()
    private val managedUdpStartGate = PersonalManagedUdpStartGate(
        isPersonalPackage = { App.appContext.packageName == "tech.gatealtas.rga.personal" },
        setNativeClaims = runtime::setClaimsJSON,
        setNativeContract = runtime::setManagedUDPContract,
        startNative = runtime::start
    )
    // ai-generated: dedicated default-off personal sampler, independent of general verbose.
    private val loadSampler = PersonalLoadDiagnosticsSampler()

    private fun loadDiagnosticsEnabled(): Boolean =
        App.appContext.packageName == "tech.gatealtas.rga.personal" &&
            Log.isLoggable("RgaPersonalLoad", Log.DEBUG)

    override fun setProtector(protect: (Long) -> Boolean) {
        runtime.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean = protect.invoke(fd)
        })
    }

    override fun setLogWriter(writeLog: (String) -> Unit) {
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) = writeLog.invoke(msg)
        })
    }

    override fun setProvider(value: String) = runtime.setProvider(value)
    override fun setRoom(value: String) = runtime.setRoom(value)
    override fun setDeviceID(value: String) = runtime.setDeviceID(value)
    override fun setKey(value: String) = runtime.setKey(value)
    override fun setClaimsJSON(value: String) = managedUdpStartGate.setClaimsJSON(value)
    override fun setSocksPort(value: Long) = runtime.setSocksPort(value)
    override fun setSocksCredentials(username: String, password: String) =
        runtime.setSocksCredentials(username, password)
    override fun setTransport(value: String) = runtime.setTransport(value)
    override fun setDNS(value: String) = runtime.setDNS(value)
    override fun setSocksListenHost(value: String) = runtime.setSocksListenHost(value)
    override fun setVP8Options(fps: Long, batch: Long) = runtime.setVP8Options(fps, batch)
    override fun setDirectRules(value: String) = runtime.setDirectRules(value)
    override fun setUDP(enabled: Boolean) = runtime.setUDP(enabled)
    override fun start() {
        // ai-generated: collector attaches before native Start; failure retires it immediately.
        loadSampler.stop()
        val enabled = loadDiagnosticsEnabled()
        runtime.setLoadDiagnostics(enabled)
        try {
            managedUdpStartGate.start()
            loadSampler.start(enabled, ::loadDiagnosticsEnabled, runtime::readLoadDiagnostics) {
                Log.d("RgaPersonalLoad", it)
            }
        } catch (failure: Exception) {
            loadSampler.stop()
            runtime.setLoadDiagnostics(false)
            throw failure
        }
    }
    override fun waitReady(timeoutMs: Long) = runtime.waitReady(timeoutMs)
    override fun stop(timeoutMs: Long) {
        // ai-generated: no logging/read is awaited before stopping native transport.
        loadSampler.stop()
        runtime.setLoadDiagnostics(false)
        runtime.stop(timeoutMs)
    }
    override fun isRunning(): Boolean = runtime.isRunning()
    override fun check(provider: String, transport: String, room: String, deviceId: String, key: String,
                       socksPort: Long, timeoutMs: Long, fps: Long, batch: Long): Long =
        runtime.check(provider, transport, room, deviceId, key, socksPort, timeoutMs, fps, batch)
    override fun ping(provider: String, transport: String, room: String, deviceId: String, key: String,
                      socksPort: Long, timeoutMs: Long, url: String, fps: Long, batch: Long): Long =
        runtime.ping(provider, transport, room, deviceId, key, socksPort, timeoutMs, url, fps, batch)
}
