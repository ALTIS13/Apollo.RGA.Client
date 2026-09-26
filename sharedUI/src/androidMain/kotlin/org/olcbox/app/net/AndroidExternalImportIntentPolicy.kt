package org.olcbox.app.net

import java.net.URI
import java.net.URISyntaxException

/** External intents stay inert in release until an owned App Link and confirmation UI ship. */
object AndroidExternalImportIntentPolicy {
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val DEBUG_SCHEME = "apollorga"
    private const val MAX_DATA_CHARS = 16_384

    /** Returns a debug-only test link, never a release import request. */
    fun allowedData(action: String?, data: String?, debugBuild: Boolean): String? {
        if (!debugBuild || action != ACTION_VIEW || data == null || data.length > MAX_DATA_CHARS) return null
        val uri = try {
            URI(data)
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host ?: return null
        if (uri.isOpaque || uri.scheme != DEBUG_SCHEME ||
            (host != "add" && host != "import") || uri.rawAuthority != host ||
            uri.rawUserInfo != null || uri.port != -1
        ) return null
        return data
    }
}
