package org.olcbox.app.update.personal

import android.net.Network

const val PERSONAL_UPDATE_MANIFEST_URL = "https://rga.apollot.ru/updates/personal/v1/manifest.json"
enum class PersonalUpdateRouteBlock { DISABLED, NOT_READY }
class PersonalUpdateSocksEndpoint internal constructor(internal val host: String, internal val port: Int,
    internal val username: String?, internal val password: String?) {
    override fun toString() = "PersonalUpdateSocksEndpoint(redacted)"
}
sealed interface PersonalUpdateFetchRoute {
    class Blocked(val reason: PersonalUpdateRouteBlock) : PersonalUpdateFetchRoute
    class Selected(val ticket: PersonalUpdateFetchTicket) : PersonalUpdateFetchRoute
    class GeneralSelected internal constructor(val ticket: GeneralUpdateFetchTicket) : PersonalUpdateFetchRoute
    class Ordinary internal constructor(internal val network: Network, internal val guard: () -> Boolean,
        internal val fence: (action: () -> Unit) -> Boolean, internal val apkUrl: String? = null,
        internal val profile: OwnServerUpdateProfile = OwnServerUpdateProfile.Personal) : PersonalUpdateFetchRoute
}
class PersonalUpdateFetchTicket internal constructor(internal val endpoint: PersonalUpdateSocksEndpoint,
    private val guard: () -> Boolean, private val fence: (String, () -> Unit) -> Boolean,
    private val apkUrl: String? = null,
    private val observedGuard: ((PersonalUpdateDiagnosticOperation) -> Boolean)? = null,
    private val observedFence: ((String, () -> Unit, PersonalUpdateDiagnosticOperation) -> Boolean)? = null,
    private val observation: PersonalUpdateDiagnosticOperation? = null) {
    fun current(): Boolean {
        val op = observation
        val decision = if (op != null && observedGuard != null) observedGuard.invoke(op) else guard()
        if (!decision) op?.failedFence(PersonalUpdateDiagnosticFence.UNKNOWN_TRANSITION)
        return decision
    }
    internal fun diagnosticView(operation: PersonalUpdateDiagnosticOperation) =
        PersonalUpdateFetchTicket(endpoint, guard, fence, apkUrl, observedGuard, observedFence, operation)
    fun allowsUrl(exactUrl: String): Boolean = exactUrl == PERSONAL_UPDATE_MANIFEST_URL || exactUrl == apkUrl
    fun withVerifiedApk(success: PersonalUpdateVerification.Success): PersonalUpdateFetchRoute {
        val m = success.manifest
        if (!current() || m.versionCode <= 0 || m.apkPath != "/updates/personal/v1/${m.versionCode}/apollo-rga-personal.apk") {
            return PersonalUpdateFetchRoute.Blocked(PersonalUpdateRouteBlock.NOT_READY)
        }
        return PersonalUpdateFetchRoute.Selected(PersonalUpdateFetchTicket(endpoint, guard, fence, "https://rga.apollot.ru${m.apkPath}",
            observedGuard, observedFence, observation))
    }
    fun authorize(exactUrl: String, action: () -> Unit): Boolean =
        allowsUrl(exactUrl) && authorizeFence(exactUrl) { if (!current()) throw RetiredPersonalUpdateRoute(); action() }
    private fun authorizeFence(url: String, action: () -> Unit): Boolean {
        val op = observation
        return if (op != null && observedFence != null) observedFence.invoke(url, action, op) else fence(url, action)
    }
}

internal class RetiredPersonalUpdateRoute : IllegalStateException()
internal object PersonalUpdateSocksHandshake {
    fun negotiate(input: java.io.InputStream, output: java.io.OutputStream, endpoint: PersonalUpdateSocksEndpoint) {
        val username = endpoint.username.orEmpty().toByteArray(Charsets.UTF_8)
        val password = endpoint.password.orEmpty().toByteArray(Charsets.UTF_8)
        val auth = username.isNotEmpty()
        if (username.size > 255 || auth && password.size > 255) throw java.io.IOException("Update transport refused")
        fun byte(): Int = input.read().takeIf { it >= 0 } ?: throw java.io.IOException("Update transport truncated")
        fun expect(value: Int) { if (byte() != value) throw java.io.IOException("Update transport refused") }
        output.write(byteArrayOf(5, 1, if (auth) 2 else 0)); output.flush()
        expect(5); expect(if (auth) 2 else 0)
        if (auth) {
            output.write(byteArrayOf(1, username.size.toByte())); output.write(username)
            output.write(password.size); output.write(password); output.flush()
            expect(1); expect(0)
        }
        val host = "rga.apollot.ru".toByteArray(Charsets.US_ASCII)
        output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte())); output.write(host)
        output.write(byteArrayOf(1, -69)); output.flush() // HTTPS 443, remote SOCKS DNS.
        expect(5); expect(0); expect(0)
        val addressBytes = when (byte()) { 1 -> 4; 4 -> 16; 3 -> byte().takeIf { it > 0 }
            ?: throw java.io.IOException("Update transport refused")
            else -> throw java.io.IOException("Update transport refused") }
        repeat(addressBytes + 2) { byte() }
    }
}
internal fun PersonalUpdateFetchRoute.forProfile(profile: OwnServerUpdateProfile): Boolean = when (this) {
    is PersonalUpdateFetchRoute.Blocked -> false
    is PersonalUpdateFetchRoute.Selected -> profile === OwnServerUpdateProfile.Personal // Public Olc remains DENY.
    is PersonalUpdateFetchRoute.GeneralSelected -> profile === ticket.profile
    is PersonalUpdateFetchRoute.Ordinary -> this.profile === profile
}
internal fun PersonalUpdateFetchRoute.current(): Boolean = when (this) {
    is PersonalUpdateFetchRoute.Blocked -> false
    is PersonalUpdateFetchRoute.Selected -> ticket.current()
    is PersonalUpdateFetchRoute.GeneralSelected -> ticket.current()
    is PersonalUpdateFetchRoute.Ordinary -> guard()
}
internal fun PersonalUpdateFetchRoute.authorize(exactUrl: String, action: () -> Unit): Boolean = when (this) {
    is PersonalUpdateFetchRoute.Blocked -> false
    is PersonalUpdateFetchRoute.Selected -> ticket.authorize(exactUrl, action)
    is PersonalUpdateFetchRoute.GeneralSelected -> ticket.authorize(exactUrl, action)
    is PersonalUpdateFetchRoute.Ordinary -> (exactUrl == profile.manifestUrl || exactUrl == apkUrl) &&
        fence { if (!guard()) throw RetiredPersonalUpdateRoute(); action() }
}
internal fun PersonalUpdateFetchRoute.withVerifiedApk(success: PersonalUpdateVerification.Success): PersonalUpdateFetchRoute = when (this) {
    is PersonalUpdateFetchRoute.Selected -> ticket.withVerifiedApk(success)
    is PersonalUpdateFetchRoute.GeneralSelected -> ticket.withVerifiedApk(success)
    is PersonalUpdateFetchRoute.Ordinary -> if (current() && success.manifest.versionCode > 0 &&
        success.manifest.apkPath == profile.apkPath(success.manifest.versionCode) &&
        (profile !is OwnServerUpdateProfile.PublicOwnServer || profile.acceptsManifest(success.manifest)))
        PersonalUpdateFetchRoute.Ordinary(network, guard, fence, "https://rga.apollot.ru${success.manifest.apkPath}", profile)
        else PersonalUpdateFetchRoute.Blocked(PersonalUpdateRouteBlock.NOT_READY)
    is PersonalUpdateFetchRoute.Blocked -> this
}
