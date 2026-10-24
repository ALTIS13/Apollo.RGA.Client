package org.olcbox.app.vpn.service

import java.net.URI
import kotlinx.coroutines.launch
import org.olcbox.app.vpn.AndroidGeneralRuntimeOptions

/** One actual Consumer entry, not cache/READY/network authority. Never persist this capability. */
internal class GeneralControlCapture internal constructor(
    internal val options: AndroidGeneralRuntimeOptions,
    internal val subscriptionUrl: String,
    private val live: () -> Boolean,
    private val commit: ((() -> Unit) -> Boolean),
    private val register: (GeneralControlOperation) -> Boolean,
    private val release: (GeneralControlOperation) -> Unit,
    private val elapsedMs: () -> Long,
) {
    private val lock = Any()
    private var retired = false
    private val operations = mutableSetOf<GeneralControlOperation>()
    internal val subscriptionHost: String? = canonicalHost(subscriptionUrl)
    fun current(): Boolean {
        if (synchronized(lock) { retired }) return false
        val valid = try { live() } catch (_: Exception) { false }
        if (!valid) retire()
        return valid && synchronized(lock) { !retired }
    }
    fun authorize(action: () -> Unit): Boolean = current() && commit {
        if (synchronized(lock) { retired }) throw IllegalStateException("GENERAL control retired")
        action()
    }
    internal fun open(url: String, subscription: Boolean, headers: Map<String, String> = emptyMap()): GeneralControlOperation? {
        val host = canonicalHost(url) ?: return null
        if (!current() || if (subscription) url != subscriptionUrl else host != "rga.apollot.ru") return null
        if (!subscription && url != "https://rga.apollot.ru/updates/public/v1/manifest.json" &&
            !Regex("https://rga\\.apollot\\.ru/updates/public/v1/[1-9][0-9]*/apollo-rga\\.apk").matches(url)) return null
        val operation = GeneralControlOperation({ GeneralControlHttpRequest(options, url, host, headers) }, elapsedMs,
            elapsedMs() + if (subscription) 8_000L else 120_000L, if (subscription) 2_097_152L else 268_435_456L,
            { current() }, { op -> synchronized(lock) { operations.remove(op) }; release(op) }, subscription)
        if (!register(operation)) return null
        val accepted = synchronized(lock) { if (retired) false else { operations.add(operation); true } }
        if (!accepted) operation.cancel()
        return operation
    }
    internal fun retire() {
        val captured = synchronized(lock) { retired = true; operations.toList() }
        captured.forEach { it.cancel() }
    }
    internal fun subscriptionProxy(): org.olcbox.app.data.repository.SubscriptionFetchProxy {
        if (!current() || subscriptionHost == null) return org.olcbox.app.data.repository.SubscriptionFetchProxy.blocked()
        return org.olcbox.app.data.repository.SubscriptionFetchProxy("127.0.0.1",options.socksPort.toInt(),
            options.socksUsername,options.socksPassword,
            org.olcbox.app.data.repository.SubscriptionFetchFence({ it == subscriptionUrl }, { current() }, { action -> authorize(action) }),
            object : org.olcbox.app.data.repository.SubscriptionOwnedFetch {
                override suspend fun fetch(exactUrl: String, hwid: String, userAgent: String): org.olcbox.app.data.repository.SubscriptionOwnedResponse {
                    val operation = open(exactUrl,true,mapOf("x-hwid" to hwid,"User-Agent" to userAgent,
                        "Accept" to "text/plain, text/markdown, application/octet-stream, */*")) ?: throw java.io.IOException("GENERAL control retired")
                    val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(
                        kotlinx.coroutines.Dispatchers.Unconfined, start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        try { kotlinx.coroutines.awaitCancellation() } finally { operation.cancel() }
                    }
                    try {
                        val value = operation.open()
                        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { value.body.readBytes().toString(Charsets.UTF_8) }
                        operation.close()
                        if (!current()) throw java.io.IOException("GENERAL control retired")
                        return org.olcbox.app.data.repository.SubscriptionOwnedResponse(value.status,value.headers,body)
                    } finally { watcher.cancel(); operation.cancel()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                            try { operation.close() } catch (_: Exception) { /* Consumer retains every failed actual Close. */ }
                        }
                    }
                }
            })
    }
    override fun toString() = "GeneralControlCapture([redacted])"
    companion object {
        internal fun canonicalHost(url: String): String? = try {
            val u = URI(url)
            u.host?.takeIf { u.scheme == "https" && u.rawUserInfo == null && u.rawFragment == null &&
                u.port in listOf(-1, 443) && it == it.lowercase() && it.length in 1..253 &&
                it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' } &&
                !it.startsWith('.') && !it.endsWith('.') && !it.contains("..") && u.toASCIIString() == url }
        } catch (_: Exception) { null }
    }
}
