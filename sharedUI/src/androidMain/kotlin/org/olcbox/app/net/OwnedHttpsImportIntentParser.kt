package org.olcbox.app.net

import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Parses an inert, owned HTTPS import envelope. Caller still owes explicit user confirmation. */
internal object OwnedHttpsImportIntentParser {
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val MAX_URI_CHARS = 16_384
    private const val MAX_FRAGMENT_CHARS = 8_192

    fun payloadOf(action: String?, data: String?, expectedHost: String, expectedPath: String): String? {
        if (action != ACTION_VIEW || data == null || data.length > MAX_URI_CHARS) return null
        if (!validExpectedHost(expectedHost) || !validExpectedPath(expectedPath)) return null

        val envelope = parseUri(data) ?: return null
        if (envelope.scheme != "https" || envelope.isOpaque || envelope.rawAuthority != expectedHost ||
            envelope.host != expectedHost || envelope.rawPath != expectedPath ||
            envelope.rawUserInfo != null || envelope.port != -1 || envelope.rawQuery != null
        ) return null

        val rawFragment = envelope.rawFragment ?: return null
        if (rawFragment.isEmpty() || rawFragment.length > MAX_FRAGMENT_CHARS || '#' in rawFragment) return null
        val payload = if (rawFragment.startsWith("https://")) {
            // Remnawave substitutes the literal subscription URL. Preserve its
            // own percent escapes verbatim; decoding here would change the URL.
            rawFragment
        } else {
            // Also accept the component-encoded form used by existing links.
            if (rawFragment.any { it != '%' && !it.isUriComponentUnescaped() }) return null
            decodeFragmentOnce(rawFragment) ?: return null
        }
        if (payload.isEmpty() || containsControlOrFormat(payload)) return null

        val target = parseUri(payload) ?: return null
        if (target.scheme != "https" || target.isOpaque || target.host.isNullOrEmpty() ||
            target.rawUserInfo != null
        ) return null
        return payload
    }

    private fun validExpectedHost(host: String): Boolean =
        host.length in 1..253 && host.contains('.') && host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first().isAsciiLowerOrDigit() &&
                label.last().isAsciiLowerOrDigit() && label.all { it.isAsciiLowerOrDigit() || it == '-' }
        }

    private fun Char.isAsciiLowerOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private fun Char.isUriComponentUnescaped(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
            this == '-' || this == '_' || this == '.' || this == '!' || this == '~' ||
            this == '*' || this == '\'' || this == '(' || this == ')'

    private fun validExpectedPath(path: String): Boolean {
        if (!path.startsWith('/')) return false
        val uri = parseUri("https://owned.example.test$path") ?: return false
        return uri.rawPath == path && uri.rawQuery == null && uri.rawFragment == null &&
            uri.normalize().rawPath == path
    }

    private fun parseUri(value: String): URI? = try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }

    private fun decodeFragmentOnce(raw: String): String? {
        val bytes = ByteArray(raw.length)
        var count = 0
        var cursor = 0
        while (cursor < raw.length) {
            val char = raw[cursor]
            if (char == '%') {
                if (cursor + 2 >= raw.length) return null
                val high = raw[cursor + 1].digitToIntOrNull(16) ?: return null
                val low = raw[cursor + 2].digitToIntOrNull(16) ?: return null
                bytes[count++] = ((high shl 4) or low).toByte()
                cursor += 3
            } else {
                if (char.code > 0x7e || char.code < 0x21) return null
                bytes[count++] = char.code.toByte()
                cursor++
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, count)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun containsControlOrFormat(value: String): Boolean {
        var cursor = 0
        while (cursor < value.length) {
            val codePoint = value.codePointAt(cursor)
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()) {
                return true
            }
            cursor += Character.charCount(codePoint)
        }
        return false
    }
}
