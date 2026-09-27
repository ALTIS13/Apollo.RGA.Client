package org.olcbox.app.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import java.net.ServerSocket

internal class OlcRtcConnectionChecker(context: Context) {
    // The checker's own engine, so a probe never touches the service's.
    private val runtime by lazy { AndroidOlcRtcRuntimeProvider.create(context.applicationContext) }

    suspend fun check(locationConfig: LocationConfig, deviceId: String): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null
            val runtime = runtime ?: return@withContext null

            repeat(CONNECTION_CHECK_ATTEMPTS) {
                val socksPort = allocateLocalPort()

                val result: Long? = runCatching {
                    runtime.check(
                        config.bypassProvider,
                        config.transport,
                        config.id,
                        deviceId,
                        config.key,
                        socksPort.toLong(),
                        CONNECTION_CHECK_TIMEOUT_MS,
                        config.vp8Fps.toLong(),
                        config.vp8Batch.toLong()
                    )
                }.getOrNull()

                if (result != null && result > 0L) {
                    return@withContext result
                }
            }

            null
        }
    }

    suspend fun ping(locationConfig: LocationConfig, deviceId: String): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null
            val runtime = runtime ?: return@withContext null

            repeat(HTTP_PING_ATTEMPTS) {
                val socksPort = allocateLocalPort()

                val result: Long? = runCatching {
                    runtime.ping(
                        config.bypassProvider,
                        config.transport,
                        config.id,
                        deviceId,
                        config.key,
                        socksPort.toLong(),
                        HTTP_PING_TIMEOUT_MS,
                        HTTP_PING_URL,
                        config.vp8Fps.toLong(),
                        config.vp8Batch.toLong()
                    )
                }.onFailure {
                    // Native errors can embed a config or URL in their message/stack.
                    // Keep the failure category without giving logcat the raw throwable.
                    Log.e("OlcRtcConnectionChecker", "HTTP ping failed (${it.javaClass.simpleName})")
                }.getOrNull()

                if (result != null && result >= 0L) {
                    return@withContext result
                }
            }

            null
        }
    }

    private fun allocateLocalPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private companion object {
        const val CONNECTION_CHECK_ATTEMPTS = 2
        const val CONNECTION_CHECK_TIMEOUT_MS = 8_000L

        const val HTTP_PING_ATTEMPTS = 1
        const val HTTP_PING_TIMEOUT_MS = 8_000L
        const val HTTP_PING_URL = "https://www.google.com/generate_204"
    }
}
