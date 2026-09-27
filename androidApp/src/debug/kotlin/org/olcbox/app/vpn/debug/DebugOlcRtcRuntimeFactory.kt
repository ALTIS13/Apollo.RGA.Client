package org.olcbox.app.vpn.debug

import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import org.olcbox.app.vpn.AndroidOlcRtcRuntime
import org.olcbox.app.vpn.AndroidOlcRtcRuntimeFactory

/** Packaged only in debug builds, together with the optional gomobile AAR. */
class DebugOlcRtcRuntimeFactory : AndroidOlcRtcRuntimeFactory {
    override fun create(): AndroidOlcRtcRuntime = DebugOlcRtcRuntime()
}

private class DebugOlcRtcRuntime : AndroidOlcRtcRuntime {
    // The generated no-argument Runtime constructor does not initialize Go state.
    private val runtime = Mobile.new_()

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
    override fun setSocksPort(value: Long) = runtime.setSocksPort(value)
    override fun setSocksCredentials(username: String, password: String) =
        runtime.setSocksCredentials(username, password)
    override fun setTransport(value: String) = runtime.setTransport(value)
    override fun setDNS(value: String) = runtime.setDNS(value)
    override fun setSocksListenHost(value: String) = runtime.setSocksListenHost(value)
    override fun setVP8Options(fps: Long, batch: Long) = runtime.setVP8Options(fps, batch)
    override fun setDirectRules(value: String) = runtime.setDirectRules(value)
    override fun setUDP(enabled: Boolean) = runtime.setUDP(enabled)
    override fun start() = runtime.start()
    override fun waitReady(timeoutMs: Long) = runtime.waitReady(timeoutMs)
    override fun stop(timeoutMs: Long) = runtime.stop(timeoutMs)
    override fun isRunning(): Boolean = runtime.isRunning()
    override fun check(provider: String, transport: String, room: String, deviceId: String, key: String,
                       socksPort: Long, timeoutMs: Long, fps: Long, batch: Long): Long =
        runtime.check(provider, transport, room, deviceId, key, socksPort, timeoutMs, fps, batch)
    override fun ping(provider: String, transport: String, room: String, deviceId: String, key: String,
                      socksPort: Long, timeoutMs: Long, url: String, fps: Long, batch: Long): Long =
        runtime.ping(provider, transport, room, deviceId, key, socksPort, timeoutMs, url, fps, batch)
}
