package org.olcbox.app.net

import java.net.URI

/** Only the short UUID from the already imported literal Remnawave link may reach Bridge. */
internal class RemnawavePersonalBearer private constructor(private val shortUuid: String) {
    fun shortUuidForBridgeRequest(): String = shortUuid

    override fun toString(): String = "RemnawavePersonalBearer([redacted])"

    companion object {
        private const val HOST = "pan.gate-altas.tech"
        private val TOKEN = Regex("[A-Za-z0-9_-]{8,128}")

        fun parse(importedUrl: String): RemnawavePersonalBearer? {
            return try {
                val url = URI(importedUrl)
                if (url.scheme != "https" || url.isOpaque || url.rawAuthority != HOST ||
                    url.host != HOST || url.rawUserInfo != null || url.port != -1 ||
                    url.rawQuery != null || url.rawFragment != null) return null
                val rawPath = url.rawPath ?: return null
                if (!rawPath.startsWith('/') || rawPath.length < 2) return null
                val token = rawPath.substring(1)
                if (!TOKEN.matches(token)) return null
                RemnawavePersonalBearer(token)
            } catch (_: Exception) {
                null
            }
        }
    }
}
