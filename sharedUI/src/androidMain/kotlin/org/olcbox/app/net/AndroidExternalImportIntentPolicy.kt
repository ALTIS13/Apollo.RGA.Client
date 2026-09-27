package org.olcbox.app.net

import java.net.URI

/** Accepts only our HTTPS import envelope; the Activity still requires user confirmation. */
object AndroidExternalImportIntentPolicy {
    private const val IMPORT_HOST = "rga.apollot.ru"
    private const val IMPORT_PATH = "/import"
    private const val SUBSCRIPTION_HOST = "pan.gate-altas.tech"

    /** An explicit Intent can be forged, so matching this envelope is not proof of its sender. */
    fun allowedData(action: String?, data: String?): String? {
        val payload = OwnedHttpsImportIntentParser.payloadOf(action, data, IMPORT_HOST, IMPORT_PATH)
            ?: return null
        // Keep the one-tap path on our subscription origin. Manual HTTPS import
        // remains provider-agnostic and is intentionally unaffected.
        val target = URI(payload)
        return payload.takeIf { target.rawAuthority == SUBSCRIPTION_HOST && target.host == SUBSCRIPTION_HOST }
    }
}
