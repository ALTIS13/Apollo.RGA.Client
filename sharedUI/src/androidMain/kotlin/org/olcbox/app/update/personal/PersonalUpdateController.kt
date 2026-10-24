package org.olcbox.app.update.personal

import java.io.Closeable
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface PersonalUpdateState {
    data object Idle : PersonalUpdateState
    data object Checking : PersonalUpdateState
    data class Available(val versionCode: Long, val versionName: String) : PersonalUpdateState
    data object Downloading : PersonalUpdateState
    data class Ready(val versionCode: Long, val versionName: String) : PersonalUpdateState
    data object SessionChanged : PersonalUpdateState
    data object Unavailable : PersonalUpdateState
    data class Rejected(val reason: PersonalUpdateControllerRefusal) : PersonalUpdateState
    data object HandedOff : PersonalUpdateState
}
enum class PersonalUpdateControllerRefusal { MANIFEST, NOT_NEWER, HTTP, SIZE, BASELINE, HIGH_WATER, ARTIFACT }
interface PersonalUpdateLastCheckStore { fun read(): Long?; fun write(epochSeconds: Long): Boolean }
class AndroidPersonalUpdateLastCheckStore(context: Context,
    profile: OwnServerUpdateProfile = OwnServerUpdateProfile.Personal) : PersonalUpdateLastCheckStore {
    private val preferences = context.applicationContext.getSharedPreferences(profile.checkPreferences, Context.MODE_PRIVATE)
    override fun read(): Long? = if (preferences.contains("last")) preferences.getLong("last", -1).takeIf { it >= 0 } else null
    override fun write(epochSeconds: Long): Boolean = epochSeconds >= 0 && preferences.edit().putLong("last", epochSeconds).commit()
}
interface PersonalUpdateHttp { suspend fun open(route: PersonalUpdateFetchRoute, exactUrl: String): PersonalUpdateHttpResponse }
class AndroidPersonalUpdateHttp(private val profile: OwnServerUpdateProfile = OwnServerUpdateProfile.Personal) : PersonalUpdateHttp {
    internal fun clientFor(route: PersonalUpdateFetchRoute): OkHttpClient {
        if (!route.forProfile(profile) || !route.current()) throw RetiredPersonalUpdateRoute()
        // No environment proxy, cookies, HTTP credentials, redirect or cross-session socket pool.
        val builder = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        when (route) {
            is PersonalUpdateFetchRoute.GeneralSelected -> throw IOException("GENERAL uses its owned transport")
            is PersonalUpdateFetchRoute.Selected -> {
                builder.socketFactory(PersonalUpdateSocksSocketFactory(route.ticket.endpoint))
                builder.dns(Dns { hostname ->
                    if (hostname != "rga.apollot.ru") throw IOException("Update DNS refused")
                    // Placeholder only. The private socket's SOCKS CONNECT resolves the exact host remotely.
                    listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 1)))
                })
            }
            is PersonalUpdateFetchRoute.Ordinary -> {
                builder.socketFactory(route.network.socketFactory)
                builder.dns(Dns { hostname -> route.network.getAllByName(hostname).toList() })
            }
            is PersonalUpdateFetchRoute.Blocked -> throw RetiredPersonalUpdateRoute()
        }
        // TLS factory, trust manager, SNI and hostname verifier remain OkHttp/platform defaults.
        return builder.build()
    }
    internal suspend fun wrapResponse(response: Response, client: OkHttpClient, call: Call, max: Long): PersonalUpdateHttpResponse {
        if (response.headers.byteCount() > 16384 || response.body.contentLength() > max ||
            response.header("Content-Encoding")?.let { it != "identity" } == true) {
            call.cancel(); response.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            throw IOException("Update response refused")
        }
        // Unconfined cancellation closes a blocking read promptly, not after its coroutine completes.
        val watcher = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                call.cancel(); response.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
        return PersonalUpdateHttpResponse(response.code, response.body.byteStream()) {
            watcher.cancel(); response.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    override suspend fun open(route: PersonalUpdateFetchRoute, exactUrl: String): PersonalUpdateHttpResponse {
        currentCoroutineContext().ensureActive()
        if (!route.current()) throw RetiredPersonalUpdateRoute()
        if (!route.forProfile(profile) || !profile.allowsUrl(exactUrl)) throw IOException("Update URL refused")
        if (route is PersonalUpdateFetchRoute.GeneralSelected) return route.ticket.open(exactUrl)
        if (route is PersonalUpdateFetchRoute.Selected && !route.ticket.allowsUrl(exactUrl)) throw IOException("Update URL refused")
        if (route is PersonalUpdateFetchRoute.Ordinary && exactUrl != profile.manifestUrl && exactUrl != route.apkUrl) throw IOException("Update URL refused")
        val client = clientFor(route)
        val call = client.newCall(Request.Builder().url(exactUrl).get().header("Accept-Encoding", "identity").build())
        var response: Response? = null
        try {
            response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Update HTTP unavailable"))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
                        else response.close()
                    }
                })
            }
            currentCoroutineContext().ensureActive()
            if (!route.current()) throw RetiredPersonalUpdateRoute()
            val accepted = response ?: throw IOException("Update response unavailable")
            val max = if (exactUrl == profile.manifestUrl) 8192L else 268435456L
            val wrapped = wrapResponse(accepted, client, call, max)
            response = null
            return wrapped
        } catch (failure: Throwable) {
            call.cancel(); response?.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            throw failure
        }
    }
    companion object { private val APK_URL = Regex("https://rga\\.apollot\\.ru/updates/personal/v1/[1-9][0-9]*/apollo-rga-personal\\.apk") }
}

private class PersonalUpdateSocksSocketFactory(private val endpoint: PersonalUpdateSocksEndpoint) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(destination: SocketAddress, timeout: Int) {
            // Use this Socket's native FD; no wrapper around a separate FD or global Authenticator.
            try {
                if (endpoint.host !in setOf("127.0.0.1", "::1", "localhost") || endpoint.port !in 1..65535) throw IOException("Update proxy refused")
                super.connect(InetSocketAddress(endpoint.host, endpoint.port), timeout)
                soTimeout = 20000
                PersonalUpdateSocksHandshake.negotiate(getInputStream(), getOutputStream(), endpoint)
            } catch (failure: Throwable) { close(); throw failure }
        }
    }
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port), 15000) }
    override fun createSocket(address: InetAddress, port: Int): Socket = createSocket(address.hostAddress!!, port)
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port), 15000) }
    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(address.hostAddress!!, port, local, localPort)
}
class PersonalUpdateHttpResponse(val status: Int, val body: InputStream,
    private val closeAction: () -> Unit = {}) : Closeable {
    override fun close() { try { body.close() } finally { closeAction() } }
}
class PersonalUpdateController(pinnedSpki: ByteArray?, private val baselineProvider: () -> PersonalBaselineRead,
    private val highWaterStore: PersonalUpdateHighWaterStore, private val apkVerifier: AndroidPersonalApkVerifier,
    private val installer: AndroidPersonalUpdateInstaller, private val routeSupplier: () -> PersonalUpdateFetchRoute,
    private val lastCheck: PersonalUpdateLastCheckStore, private val now: () -> Long,
    http: PersonalUpdateHttp? = null,
    private val diagnostics: PersonalUpdateDiagnosticOwner? = null,
    private val profile: OwnServerUpdateProfile = OwnServerUpdateProfile.Personal) {
    private val http = http ?: AndroidPersonalUpdateHttp(profile)
    private val pin = pinnedSpki?.copyOf()
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<PersonalUpdateState>(PersonalUpdateState.Idle)
    val state = mutableState.asStateFlow()
    private data class Candidate(val envelope: ByteArray, val success: PersonalUpdateVerification.Success,
        val baseline: PersonalUpdateBaseline, val route: PersonalUpdateFetchRoute, val apk: VerifiedPersonalApk? = null)
    private var candidate: Candidate? = null
    private var diagnosticOperation: PersonalUpdateDiagnosticOperation? = null // Controller mutex-owned; never a sink.

    private suspend fun <T> diagnosticOperation(kind: PersonalUpdateDiagnosticKind, action: suspend () -> T): T {
        val op = diagnostics?.begin(kind)
        diagnosticOperation = op
        try {
            val result = action()
            op?.terminal(PersonalUpdateDiagnosticBranch.COMPLETED, false)
            return result
        } finally { diagnosticOperation = null }
    }
    private fun observed(route: PersonalUpdateFetchRoute): PersonalUpdateFetchRoute =
        if (route is PersonalUpdateFetchRoute.Selected && diagnosticOperation != null)
            PersonalUpdateFetchRoute.Selected(route.ticket.diagnosticView(diagnosticOperation!!)) else route
    private suspend fun openHttp(route: PersonalUpdateFetchRoute, url: String): PersonalUpdateHttpResponse =
        try { http.open(route, url) } catch (failure: Exception) {
            diagnosticOperation?.httpFailed(failure)
            throw failure // Exact original exception behavior/type is retained.
        }

    suspend fun manualCheck() = withContext(Dispatchers.IO) { mutex.withLock {
        diagnosticOperation(PersonalUpdateDiagnosticKind.CHECK) { checkLocked() }
    } }
    /** One foreground event, never a timer or constructor-triggered network request. */
    suspend fun foregroundCheck(foreground: Boolean) = withContext(Dispatchers.IO) {
        if (!foreground || pin == null) return@withContext
        mutex.withLock {
            val time = now(); val previous = lastCheck.read()
            if (time < 0 || previous != null && (time < previous || time - previous < 21600)) return@withLock
            // Durable reservation precedes I/O; failures must not become a foreground retry loop.
            currentCoroutineContext().ensureActive()
            if (!lastCheck.write(time)) { mutableState.value = PersonalUpdateState.Unavailable; return@withLock }
            diagnosticOperation(PersonalUpdateDiagnosticKind.CHECK) { checkLocked() }
        }
    }
    private suspend fun checkLocked() {
        if (pin == null) { candidate = null; mutableState.value = PersonalUpdateState.Unavailable; return }
        candidate = null // A newly accepted high-water may never leave an older install offer alive.
        val route = observed(routeSupplier())
        if (!route.forProfile(profile) || route is PersonalUpdateFetchRoute.Blocked) { mutableState.value = PersonalUpdateState.Unavailable; return }
        mutableState.value = PersonalUpdateState.Checking
        guarded(route) {
            requireCurrent(route)
            val baseline = baseline(route)
            val envelope = openHttp(route, profile.manifestUrl).use { response ->
                requireCurrent(route)
                if (response.status != 200) reject(PersonalUpdateControllerRefusal.HTTP)
                val output = ByteArrayOutputStream()
                readBody(route, response.body, 8192) { buffer, count -> output.write(buffer, 0, count) }
                output.toByteArray()
            }
            requireCurrent(route)
            val verified = verify(route, envelope, baseline)
            if (baseline(route) != baseline) reject(PersonalUpdateControllerRefusal.BASELINE)
            val cancellation = currentCoroutineContext()
            requireCurrent(route)
            val commit = highWaterStore.commit(verified) { action ->
                route.authorize(profile.manifestUrl) { cancellation.ensureActive(); action() }
            }
            requireCurrent(route)
            if (commit == PersonalHighWaterCommit.DENIED) throw RetiredPersonalUpdateRoute()
            if (commit != PersonalHighWaterCommit.STORED && commit != PersonalHighWaterCommit.UNCHANGED) reject(PersonalUpdateControllerRefusal.HIGH_WATER)
            val apkRoute = route.withVerifiedApk(verified)
            requireCurrent(apkRoute)
            publishCandidate(Candidate(envelope, verified, baseline, apkRoute), ready = false)
        }
    }
    suspend fun download() = withContext(Dispatchers.IO) { mutex.withLock {
        val available = candidate ?: return@withLock
        if (available.apk != null) return@withLock
        diagnosticOperation(PersonalUpdateDiagnosticKind.DOWNLOAD) {
        val route = observed(available.route)
        mutableState.value = PersonalUpdateState.Downloading
        guarded(route) {
            requireCurrent(route)
            val baseline = baseline(route)
            if (baseline != available.baseline) reject(PersonalUpdateControllerRefusal.BASELINE)
            val verified = verify(route, available.envelope, baseline)
            val file = apkVerifier.newDownloadFile()
            try {
                requireCurrent(route)
                val url = "https://rga.apollot.ru${verified.manifest.apkPath}"
                openHttp(route, url).use { response ->
                    requireCurrent(route)
                    if (response.status != 200) reject(PersonalUpdateControllerRefusal.HTTP)
                    FileOutputStream(file).use { output ->
                        val size = readBody(route, response.body, verified.manifest.apkBytes) { buffer, count -> output.write(buffer, 0, count) }
                        if (size != verified.manifest.apkBytes) reject(PersonalUpdateControllerRefusal.SIZE)
                        requireCurrent(route); output.fd.sync(); requireCurrent(route)
                    }
                }
                requireCurrent(route)
                val result = apkVerifier.verifyApk(file, verified, baseline)
                requireCurrent(route)
                if (result !is PersonalApkVerification.Verified) reject(PersonalUpdateControllerRefusal.ARTIFACT)
                // Slow archive/hash reads may have raced a newer acceptance in another controller.
                verify(route, available.envelope, baseline)
                requireCurrent(route)
                publishCandidate(available.copy(route = route, apk = result.apk), ready = true)
            } finally { file.delete() } // Only this verifier-created temporary download; frozen tokens are never unlinked here.
        }
        }
    } }
    suspend fun install(ownerAction: Boolean): PersonalInstallResult = withContext(Dispatchers.IO) { mutex.withLock {
        if (!ownerAction) return@withLock PersonalInstallResult.Refused(PersonalInstallRefusal.OWNER_ACTION_REQUIRED)
        val ready = candidate
        if (ready?.apk == null) return@withLock PersonalInstallResult.Refused(PersonalInstallRefusal.ARTIFACT_REJECTED)
        diagnosticOperation(PersonalUpdateDiagnosticKind.INSTALL) {
        val route = observed(ready.route)
        var result: PersonalInstallResult = PersonalInstallResult.Refused(PersonalInstallRefusal.ARTIFACT_REJECTED)
        guarded(route) {
            requireCurrent(route)
            val baseline = baseline(route)
            if (baseline != ready.baseline) reject(PersonalUpdateControllerRefusal.BASELINE)
            val success = verify(route, ready.envelope, baseline)
            if (apkVerifier.revalidate(ready.apk) !is PersonalApkVerification.Verified) reject(PersonalUpdateControllerRefusal.ARTIFACT)
            requireCurrent(route)
            val cancellation = currentCoroutineContext()
            val expected = PersonalUpdateHighWater(success.manifest.sequence, success.payloadSha256)
            result = installer.install(ready.apk, true) { action ->
                highWaterStore.authorizeIfCurrent(expected) {
                    route.authorize("https://rga.apollot.ru${success.manifest.apkPath}") {
                        cancellation.ensureActive(); action()
                    }
                }
            }
            if (result is PersonalInstallResult.HandedOff) {
                // Android may read the frozen file after this method returns; never delete it.
                candidate = null; mutableState.value = PersonalUpdateState.HandedOff
            } else {
                requireCurrent(route)
                if (result is PersonalInstallResult.Refused) reject(PersonalUpdateControllerRefusal.ARTIFACT)
            }
        }
        result
        }
    } }
    private suspend fun baseline(route: PersonalUpdateFetchRoute): PersonalUpdateBaseline {
        val result = baselineProvider(); requireCurrent(route)
        return (result as? PersonalBaselineRead.Ready)?.baseline ?: reject(PersonalUpdateControllerRefusal.BASELINE)
    }
    private suspend fun publishCandidate(value: Candidate, ready: Boolean) {
        val cancellation = currentCoroutineContext()
        val m = value.success.manifest
        val url = if (ready) "https://rga.apollot.ru${m.apkPath}" else profile.manifestUrl
        val allowed = highWaterStore.authorizeIfCurrent(PersonalUpdateHighWater(m.sequence, value.success.payloadSha256)) {
            value.route.authorize(url) {
                cancellation.ensureActive()
                candidate = value
                mutableState.value = if (ready) PersonalUpdateState.Ready(m.versionCode, m.versionName)
                    else PersonalUpdateState.Available(m.versionCode, m.versionName)
            }
        }
        if (!allowed) {
            requireCurrent(value.route)
            reject(PersonalUpdateControllerRefusal.HIGH_WATER)
        }
    }
    private suspend fun verify(route: PersonalUpdateFetchRoute, envelope: ByteArray, baseline: PersonalUpdateBaseline): PersonalUpdateVerification.Success {
        requireCurrent(route)
        val highWater = (highWaterStore.read() as? PersonalHighWaterRead.Ready)?.value ?: reject(PersonalUpdateControllerRefusal.HIGH_WATER)
        requireCurrent(route)
        val result = PersonalUpdateManifestVerifier.verify(envelope, pin ?: reject(PersonalUpdateControllerRefusal.MANIFEST), baseline, highWater, now(), profile)
        requireCurrent(route)
        return when (result) {
            is PersonalUpdateVerification.Success -> result
            is PersonalUpdateVerification.Failure -> reject(
                if (result.reason == PersonalUpdateFailure.DOWNGRADE) PersonalUpdateControllerRefusal.NOT_NEWER
                else PersonalUpdateControllerRefusal.MANIFEST)
        }
    }
    private suspend fun readBody(route: PersonalUpdateFetchRoute, input: InputStream, max: Long,
        consume: (ByteArray, Int) -> Unit): Long {
        if (max !in 1..268435456) reject(PersonalUpdateControllerRefusal.SIZE)
        val buffer = ByteArray(8192); var total = 0L
        while (true) {
            requireCurrent(route)
            val count = try { input.read(buffer) } catch (failure: Exception) {
                diagnosticOperation?.httpFailed(failure); throw failure
            }
            if (count > 0) diagnosticOperation?.bytesRead(count)
            requireCurrent(route)
            if (count < 0) break
            total += count
            if (total > max) reject(PersonalUpdateControllerRefusal.SIZE)
            consume(buffer, count)
        }
        return total
    }
    private suspend fun requireCurrent(route: PersonalUpdateFetchRoute) {
        currentCoroutineContext().ensureActive()
        if (!route.forProfile(profile) || !route.current()) throw RetiredPersonalUpdateRoute()
    }
    private suspend fun guarded(route: PersonalUpdateFetchRoute, action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) {
            candidate = null; mutableState.value = PersonalUpdateState.Idle
            diagnosticOperation?.terminal(PersonalUpdateDiagnosticBranch.COROUTINE_CANCEL, false)
            throw cancelled
        }
        catch (_: RetiredPersonalUpdateRoute) {
            candidate = null; mutableState.value = PersonalUpdateState.SessionChanged
            diagnosticOperation?.terminal(PersonalUpdateDiagnosticBranch.RETIRED, true)
        }
        catch (rejected: ControllerRejected) {
            diagnostic(rejected.reason)
            candidate = null; mutableState.value = PersonalUpdateState.Rejected(rejected.reason)
            diagnosticOperation?.terminal(PersonalUpdateDiagnosticBranch.CONTROLLER_REFUSAL, false)
        }
        catch (_: Exception) {
            candidate = null
            if (!currentCoroutineContext().isActive) {
                mutableState.value = PersonalUpdateState.Idle
                diagnosticOperation?.terminal(PersonalUpdateDiagnosticBranch.COROUTINE_CANCEL, false)
                currentCoroutineContext().ensureActive()
            }
            val current = route.current() // The original one evaluation chooses both state and diagnostic outcome.
            mutableState.value = if (!current) PersonalUpdateState.SessionChanged
                else PersonalUpdateState.Rejected(PersonalUpdateControllerRefusal.HTTP).also {
                    diagnostic(PersonalUpdateControllerRefusal.HTTP)
                }
            diagnosticOperation?.terminal(diagnosticOperation?.errorBranch ?: PersonalUpdateDiagnosticBranch.UNKNOWN, !current)
        }
    }
    private fun diagnostic(reason: PersonalUpdateControllerRefusal) {
        // Opt-in fixed codes only; diagnostic failures must not alter rejection behavior.
        runCatching {
            if (Log.isLoggable("RgaPersonalUpdate", Log.DEBUG)) Log.d("RgaPersonalUpdate", when (reason) {
                PersonalUpdateControllerRefusal.MANIFEST -> "CONTROLLER_REFUSED_MANIFEST"
                PersonalUpdateControllerRefusal.NOT_NEWER -> "CONTROLLER_REFUSED_NOT_NEWER"
                PersonalUpdateControllerRefusal.HTTP -> "CONTROLLER_REFUSED_HTTP"
                PersonalUpdateControllerRefusal.SIZE -> "CONTROLLER_REFUSED_SIZE"
                PersonalUpdateControllerRefusal.BASELINE -> "CONTROLLER_REFUSED_BASELINE"
                PersonalUpdateControllerRefusal.HIGH_WATER -> "CONTROLLER_REFUSED_HIGH_WATER"
                PersonalUpdateControllerRefusal.ARTIFACT -> "CONTROLLER_REFUSED_ARTIFACT"
            })
        }
    }
    private class ControllerRejected(val reason: PersonalUpdateControllerRefusal) : IllegalStateException()
    private fun reject(reason: PersonalUpdateControllerRefusal): Nothing = throw ControllerRejected(reason)
}
