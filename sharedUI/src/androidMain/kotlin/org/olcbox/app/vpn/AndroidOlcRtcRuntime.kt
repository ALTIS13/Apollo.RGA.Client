package org.olcbox.app.vpn

import android.content.Context
import org.olcbox.app.net.AndroidRuntimeLocationPolicy
import org.olcbox.app.net.RuntimeLocationPolicy

/** The optional Android-only binding. Ordinary builds have no implementation or native library. */
interface AndroidOlcRtcRuntime {
    fun setProtector(protect: (Long) -> Boolean)
    fun setLogWriter(writeLog: (String) -> Unit)
    fun setProvider(value: String)
    fun setRoom(value: String)
    fun setDeviceID(value: String)
    fun setKey(value: String)
    fun setSocksPort(value: Long)
    fun setSocksCredentials(username: String, password: String)
    fun setTransport(value: String)
    fun setDNS(value: String)
    fun setSocksListenHost(value: String)
    fun setVP8Options(fps: Long, batch: Long)
    fun setDirectRules(value: String)
    fun setUDP(enabled: Boolean)
    fun start()
    fun waitReady(timeoutMs: Long)
    fun stop(timeoutMs: Long)
    fun isRunning(): Boolean
    fun check(provider: String, transport: String, room: String, deviceId: String, key: String,
              socksPort: Long, timeoutMs: Long, fps: Long, batch: Long): Long
    fun ping(provider: String, transport: String, room: String, deviceId: String, key: String,
             socksPort: Long, timeoutMs: Long, url: String, fps: Long, batch: Long): Long
}

fun interface AndroidOlcRtcRuntimeFactory {
    fun create(): AndroidOlcRtcRuntime
}

object AndroidOlcRtcRuntimeProvider {
    private const val DEBUG_FACTORY = "org.olcbox.app.vpn.debug.DebugOlcRtcRuntimeFactory"

    fun create(context: Context): AndroidOlcRtcRuntime? =
        loadFactory(AndroidRuntimeLocationPolicy.forApp(context)) {
            Class.forName(DEBUG_FACTORY).getDeclaredConstructor().newInstance() as AndroidOlcRtcRuntimeFactory
        }?.let { factory -> runCatching { factory.create() }.getOrNull() }

    internal fun loadFactory(
        policy: RuntimeLocationPolicy,
        loader: () -> AndroidOlcRtcRuntimeFactory
    ): AndroidOlcRtcRuntimeFactory? =
        if (policy == RuntimeLocationPolicy.ExperimentalDebug) runCatching(loader).getOrNull() else null
}
