package org.olcbox.app.update.personal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.olcbox.app.vpn.service.GeneralControlCapture

/** Public profile + actual retained GENERAL entry, not personal attestation or a network grant. */
class GeneralUpdateFetchTicket internal constructor(internal val capture: GeneralControlCapture,
    internal val profile: OwnServerUpdateProfile.PublicOwnServer, internal val apkUrl: String? = null) {
    fun current() = capture.current()
    fun allowsUrl(url: String) = url == profile.manifestUrl || url == apkUrl
    fun authorize(url: String, action: () -> Unit): Boolean = allowsUrl(url) && capture.authorize(action)
    internal fun withVerifiedApk(success: PersonalUpdateVerification.Success): PersonalUpdateFetchRoute =
        if (current() && profile.acceptsManifest(success.manifest)) PersonalUpdateFetchRoute.GeneralSelected(
            GeneralUpdateFetchTicket(capture, profile, "https://rga.apollot.ru${success.manifest.apkPath}"))
        else PersonalUpdateFetchRoute.Blocked(PersonalUpdateRouteBlock.NOT_READY)
    internal suspend fun open(url: String): PersonalUpdateHttpResponse {
        if (!allowsUrl(url) || !current()) throw RetiredPersonalUpdateRoute()
        val operation = capture.open(url, subscription = false) ?: throw RetiredPersonalUpdateRoute()
        val watcher = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined, start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { operation.cancel() }
        }
        try {
            val value = operation.open()
            if (!current()) throw RetiredPersonalUpdateRoute()
            return PersonalUpdateHttpResponse(value.status, value.body) { watcher.cancel(); operation.close() }
        } catch (failure: Throwable) { watcher.cancel(); operation.cancel()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try { operation.close() } catch (_: Exception) { /* Exact operation stays Consumer-owned on unknown Close. */ }
            }
            throw failure
        }
    }
    override fun toString() = "GeneralUpdateFetchTicket([redacted])"
}
