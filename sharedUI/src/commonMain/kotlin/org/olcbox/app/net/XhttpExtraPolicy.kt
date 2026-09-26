package org.olcbox.app.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The XHTTP `extra` schema accepted by the pinned Xray core, with mobile-safe
 * bounds for fields which allocate per request/connection. Xray ignores unknown
 * JSON fields; accepting them here would make a new required server option look
 * like a working host on an older client.
 */
internal object XhttpExtraPolicy {
    const val MAX_UPLOAD_BYTES = 200_000
    private const val MIN_UPLOAD_BYTES = 8_193 // Xray v25.3.6 panics at <= buf.Size (8192).
    private const val MAX_PADDING_BYTES = 4_096
    private const val MAX_EXTRA_CHARS = 16_384

    private val fields = setOf(
        "headers", "xPaddingBytes", "noGRPCHeader", "noSSEHeader",
        "scMaxEachPostBytes", "scMinPostsIntervalMs", "scMaxBufferedPosts",
        "scStreamUpServerSecs", "xmux",
    )
    private val xmuxFields = setOf(
        "maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes",
        "hMaxReusableSecs", "hKeepAlivePeriod",
    )

    fun isCompatible(extra: JsonObject): Boolean = runCatching { bounded(extra) }.isSuccess

    fun bounded(extra: JsonObject): JsonObject {
        require(extra.toString().length <= MAX_EXTRA_CHARS) { "XHTTP extra is too large" }
        require(extra.keys.all { it in fields }) { "XHTTP extra contains an unsupported field" }

        range(extra["xPaddingBytes"], "xPaddingBytes", 1, MAX_PADDING_BYTES)
        range(extra["scMaxEachPostBytes"], "scMaxEachPostBytes", MIN_UPLOAD_BYTES, MAX_UPLOAD_BYTES)
        range(extra["scMinPostsIntervalMs"], "scMinPostsIntervalMs", 0, Int.MAX_VALUE)
        range(extra["scStreamUpServerSecs"], "scStreamUpServerSecs", 0, Int.MAX_VALUE)
        val buffered = extra["scMaxBufferedPosts"]
        if (buffered != null) {
            val value = (buffered as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
            require(value != null && value >= 0) { "XHTTP scMaxBufferedPosts must be a non-negative integer" }
        }
        for (key in listOf("noGRPCHeader", "noSSEHeader")) {
            val value = extra[key]
            if (value != null) {
                require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
                    "XHTTP $key must be a boolean"
                }
            }
        }
        val headers = extra["headers"]
        if (headers != null) {
            require(headers is JsonObject && headers.all { (key, value) ->
                key.isNotBlank() && !key.equals("host", ignoreCase = true) &&
                    value is JsonPrimitive && value.isString
            }) { "XHTTP headers must be strings and cannot override Host" }
        }
        val xmux = extra["xmux"]
        if (xmux != null) {
            require(xmux is JsonObject && xmux.keys.all { it in xmuxFields }) {
                "XHTTP xmux contains an unsupported field"
            }
            for (key in xmuxFields - "hKeepAlivePeriod") {
                range(xmux[key], "xmux.$key", 0, Int.MAX_VALUE)
            }
            val keepAlive = xmux["hKeepAlivePeriod"]
            if (keepAlive != null) {
                val value = (keepAlive as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
                require(value != null && value >= 0) { "XHTTP xmux.hKeepAlivePeriod must be non-negative" }
            }
        }

        return buildJsonObject {
            extra.forEach { (key, value) -> put(key, value) }
            if ("scMaxEachPostBytes" !in extra) put("scMaxEachPostBytes", MAX_UPLOAD_BYTES)
        }
    }

    private fun range(value: kotlinx.serialization.json.JsonElement?, key: String, minimum: Int, maximum: Int) {
        if (value == null) return
        val raw = (value as? JsonPrimitive)?.content.orEmpty()
        val parts = raw.split('-')
        val numbers = parts.map { it.toIntOrNull() }
        require(parts.size in 1..2 && numbers.all { it != null && it in minimum..maximum } &&
            (numbers.size == 1 || numbers[0]!! <= numbers[1]!!)) {
            "XHTTP $key must be an ordered range within the supported mobile bounds"
        }
    }
}
