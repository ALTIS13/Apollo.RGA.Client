package org.olcbox.app.net

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Immutable metadata only; neither the room key nor this DTO grants admission. */
internal class GeneralBootstrapV1 private constructor(
    val schemaVersion: Int, val panelInstance: String, val userId: Long,
    val installationId: String, val provider: String, val transport: String,
    val roomId: String, val country: String, val roomKey: String,
    val expiresAtEpochMs: Long, val policyRevision: Long, val roomUrl: String?
) {
    val usesManagedLease: Boolean get() = schemaVersion == 3
    val hasValidProviderLocator: Boolean get() = roomUrl != null &&
        validProviderProfile(schemaVersion, provider, transport, roomUrl)
    // Discovery only; retain accepted receiver clock-skew allowance, never a lease.
    fun isUsableAt(now: Long) = now > 0 && expiresAtEpochMs > now && expiresAtEpochMs - now <= 330_000
    override fun toString() = "GeneralBootstrapV1([redacted])"
    companion object {
        private val fields = setOf("schemaVersion", "panelInstance", "userId", "installationId", "provider",
            "transport", "roomId", "country", "roomKey", "expiresAtEpochMs", "policyRevision")
        internal val panelPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
        internal val installationPattern = Regex("[A-Za-z0-9_-]{16,128}")
        internal val roomPattern = Regex("[A-Za-z0-9_-]{8,128}")
        internal val providerRoomPattern = Regex("https://telemost\\.yandex\\.ru/j/[0-9]{14}")
        private val jazzRoomPattern = Regex("[a-z0-9]{4,16}:[a-z0-9]{4,16}")
        private val vkRoomPattern = Regex("https://(?:vk\\.ru|vk\\.com)/call/join/[A-Za-z0-9_-]{1,256}")
        private val mtsRoomPattern = Regex("https://my\\.mts-link\\.ru/j/[1-9][0-9]{0,18}/[1-9][0-9]{0,18}/session/[1-9][0-9]{0,18}\\?ice=tcp")
        internal val countryPattern = Regex("[A-Z]{2}")
        internal val hex32 = Regex("[0-9a-f]{64}")

        // Deliberately not a generic URI parser. Provider locators cannot choose
        // arbitrary network hosts; the SaluteJazz password never becomes a URL.
        private fun validProviderProfile(schema: Int, provider: String, transport: String, locator: String?): Boolean {
            return when (provider) {
                "telemost" -> transport == "vp8channel" &&
                    (if (schema == 1) locator == null else locator != null && providerRoomPattern.matches(locator))
                // The pinned engine has data channels, not VideoTrackCapable.
                "salutejazz" -> transport == "datachannel" && schema == 3 &&
                    locator != null && jazzRoomPattern.matches(locator)
                "vkcalls" -> transport == "vp8channel" && schema == 3 &&
                    locator != null && vkRoomPattern.matches(locator)
                "mtslink" -> transport == "vp8channel" && schema == 3 &&
                    locator != null && mtsRoomPattern.matches(locator)
                else -> false
            }
        }

        fun fromPrivateBytes(bytes: ByteArray): GeneralBootstrapV1? {
            return try {
                val j = GeneralJson.parse(bytes, 8192)
                val schema = GeneralJson.long(j, "schemaVersion")
                val expected = when (schema) {
                    1L -> fields
                    2L -> fields + "roomUrl" // Accepted legacy GENERAL locator schema.
                    3L -> fields + setOf("roomUrl", "admissionMode")
                    else -> throw IllegalArgumentException("Bootstrap schema denied [redacted]")
                }
                require(j.keys == expected)
                if (schema == 3L) require(GeneralJson.string(j, "admissionMode") == "managed-lease-v2")
                val roomUrl = if (schema >= 2L) GeneralJson.string(j, "roomUrl") else null
                val panel = GeneralJson.string(j, "panelInstance")
                val user = GeneralJson.long(j, "userId")
                val installation = GeneralJson.string(j, "installationId")
                val provider = GeneralJson.string(j, "provider")
                val transport = GeneralJson.string(j, "transport")
                val room = GeneralJson.string(j, "roomId")
                val country = GeneralJson.string(j, "country")
                val key = GeneralJson.string(j, "roomKey")
                val expiry = GeneralJson.long(j, "expiresAtEpochMs")
                val revision = GeneralJson.long(j, "policyRevision")
                require(user > 0 && revision > 0 && expiry > 0 &&
                    panelPattern.matches(panel) && installationPattern.matches(installation) &&
                    validProviderProfile(schema.toInt(), provider, transport, roomUrl) && roomPattern.matches(room) &&
                    countryPattern.matches(country) && hex32.matches(key) && key.any { it != '0' })
                GeneralBootstrapV1(schema.toInt(), panel, user, installation, provider, transport, room, country, key, expiry, revision, roomUrl)
            } catch (_: Exception) { null }
        }
    }
}

/** Small strict object-only JSON reader: duplicates are rejected before a map can erase them. */
internal object GeneralJson {
    private const val DENIED = "GENERAL input denied [redacted]"
    fun parse(bytes: ByteArray, max: Int): Map<String, Any?> {
        require(bytes.isNotEmpty() && bytes.size <= max) { DENIED }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.copyOf())).toString()
        return Reader(text).read()
    }
    fun string(j: Map<String, Any?>, name: String): String = j[name] as? String
        ?: throw IllegalArgumentException(DENIED)
    fun long(j: Map<String, Any?>, name: String): Long = j[name] as? Long
        ?: throw IllegalArgumentException(DENIED)
    @Suppress("UNCHECKED_CAST")
    fun objectValue(j: Map<String, Any?>, name: String): Map<String, Any?> = j[name] as? Map<String, Any?>
        ?: throw IllegalArgumentException(DENIED)

    // Go encoding/json field order comes from the selected struct, not JSONObject iteration.
    fun canonical(j: Map<String, Any?>): String = j.entries.joinToString(",", "{", "}") { (k, v) ->
        quote(k) + ":" + when (v) {
            is String -> quote(v)
            is Long -> v.toString()
            is Boolean -> v.toString()
            null -> "null"
            is Map<*, *> -> { @Suppress("UNCHECKED_CAST") canonical(v as Map<String, Any?>) }
            else -> throw IllegalArgumentException(DENIED)
        }
    }
    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\b' -> append("\\b"); '\u000c' -> append("\\f"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t")
            '<', '>', '&', '\u2028', '\u2029' -> append("\\u" + c.code.toString(16).padStart(4, '0'))
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    fun utcMillis(s: String, canonical: Boolean = false, microsecond: Boolean = false): Long =
        utcTimestamp(s, canonical, microsecond).first

    /** Milliseconds plus sub-millisecond nanoseconds preserves Go RFC3339Nano ordering. */
    fun utcTimestamp(s: String, canonical: Boolean = false, microsecond: Boolean = false): Pair<Long, Int> {
        val match = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.([0-9]{1,9}))?Z").matchEntire(s)
            ?: throw IllegalArgumentException(DENIED)
        val fraction = match.groupValues[1]
        require(!canonical || !fraction.endsWith('0')) { DENIED }
        require(!microsecond || fraction.length <= 6) { DENIED }
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
        }
        val seconds = s.substring(0, 19)
        val position = ParsePosition(0)
        val value = parser.parse(seconds, position)?.time ?: throw IllegalArgumentException(DENIED)
        require(position.index == 19 && parser.format(java.util.Date(value)) == seconds && value > 0) { DENIED }
        // A validated four-digit year is far below Long overflow; no newer Android Math API.
        val nanos = fraction.padEnd(9, '0')
        return Pair(value + nanos.take(3).toLong(), nanos.drop(3).toInt())
    }

    private class Reader(private val s: String) {
        private var i = 0
        fun read(): Map<String, Any?> {
            space(); val result = obj(0); space(); require(i == s.length) { DENIED }; return result
        }
        private fun space() { while (i < s.length && s[i] in " \t\r\n") i++ }
        private fun take(c: Char) { require(i < s.length && s[i++] == c) { DENIED } }
        private fun obj(depth: Int): Map<String, Any?> {
            require(depth <= 5) { DENIED }; take('{'); space()
            val result = linkedMapOf<String, Any?>()
            if (i < s.length && s[i] == '}') { i++; return result }
            while (true) {
                val key = text(); require(key.length <= 64 && !result.containsKey(key)) { DENIED }
                space(); take(':'); space(); result[key] = value(depth + 1); space()
                if (i < s.length && s[i] == '}') { i++; return result }
                take(','); space()
            }
        }
        private fun value(depth: Int): Any? {
            require(i < s.length) { DENIED }
            return when (s[i]) {
                '{' -> obj(depth)
                '"' -> text()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = i
                    if (s[i] == '-') i++
                    require(i < s.length && s[i] in '0'..'9') { DENIED }
                    if (s[i] == '0') i++ else while (i < s.length && s[i] in '0'..'9') i++
                    val token = s.substring(start, i)
                    require(token != "-0") { DENIED }
                    token.toLongOrNull() ?: throw IllegalArgumentException(DENIED)
                }
            }
        }
        private fun literal(token: String, value: Any?): Any? {
            require(s.startsWith(token, i)) { DENIED }; i += token.length; return value
        }
        private fun text(): String {
            take('"'); val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') {
                    val text = out.toString()
                    var n = 0
                    while (n < text.length) {
                        val ch = text[n++]
                        if (ch.isHighSurrogate()) { require(n < text.length && text[n++].isLowSurrogate()) { DENIED } }
                        else require(!ch.isLowSurrogate()) { DENIED }
                    }
                    return text
                }
                require(c >= ' ' && out.length < 12000) { DENIED }
                if (c != '\\') out.append(c) else {
                    require(i < s.length) { DENIED }
                    out.append(when (val e = s[i++]) {
                        '"', '\\', '/' -> e
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { require(i + 4 <= s.length) { DENIED }
                            val hex = s.substring(i, i + 4); require(Regex("[0-9a-fA-F]{4}").matches(hex)) { DENIED }
                            i += 4; hex.toInt(16).toChar() }
                        else -> throw IllegalArgumentException(DENIED)
                    })
                }
            }
            throw IllegalArgumentException(DENIED)
        }
    }
}
