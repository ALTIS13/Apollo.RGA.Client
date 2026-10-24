package org.olcbox.app.net

import java.net.URL

/** Build-owned endpoints only; no subscription, room or user value is an origin. */
internal class PersonalBootstrapEndpoint private constructor(
    private val origin: String,
    val usbCertificatePin: String?
) {
    fun urlFor(path: String): URL? = if (path in PATHS) URL(origin + path) else null

    companion object {
        private val PATHS = setOf("/v1/personal/link/begin", "/v1/personal/bootstrap")
        internal fun usb(pin: String) = PersonalBootstrapEndpoint("https://127.0.0.1:18443", pin)
        internal fun remote() = PersonalBootstrapEndpoint("https://rga.apollot.ru", null)
    }
}

internal object PersonalBootstrapEndpointPolicy {
    fun select(packageName: String, experimentalDebug: Boolean, remoteOrigin: String, usbPin: String): PersonalBootstrapEndpoint? {
        if (packageName != "tech.gatealtas.rga.personal" || !experimentalDebug) return null
        if (remoteOrigin.isNotEmpty()) {
            if (remoteOrigin != "https://rga.apollot.ru" || usbPin.isNotEmpty()) return null
            return PersonalBootstrapEndpoint.remote()
        }
        return if (Regex("[0-9a-f]{64}").matches(usbPin)) PersonalBootstrapEndpoint.usb(usbPin) else null
    }
}
