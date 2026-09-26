package org.olcbox.app.net

import kotlinx.serialization.json.*
import org.olcbox.app.data.datasource.XrayJsonSubscriptionCodec

internal object XrayJsonRuntimeConfig {
    class Adapted(val config: JsonObject) {
        override fun toString() = "XrayJsonRuntimeConfig.Adapted"
    }
    enum class Reason { INVALID_PROFILE, INVALID_INBOUND, REFERENCED_HTTP_INBOUND, INVALID_LOGIN, INVALID_PORT, UNSUPPORTED_PROXY_MODE }
    class Rejected(val reason: Reason) : IllegalArgumentException("Xray runtime rejected: $reason")

    /** Re-admit persisted data, retaining every network JSON value verbatim.
     * Android TUN has exactly one authenticated SOCKS ingress. An unused HTTP
     * listener may be removed, but no rule graph may be changed to achieve it.
     * Runtime logging is the sole additional override: provider paths and
     * payload logging must never execute with the Android app's permissions.
     * The persisted provider object remains untouched.
     */
    fun adapt(config: JsonObject, port: Int, login: SocksLogin?, tunMode: Boolean = true): Adapted {
        requireSafe(tunMode, Reason.UNSUPPORTED_PROXY_MODE)
        requireSafe(port in 1..65535, Reason.INVALID_PORT)
        requireSafe(login != null && login.username.isNotBlank() && login.password.isNotBlank() &&
            login.username.encodeToByteArray().size <= 255 && login.password.encodeToByteArray().size <= 255 &&
            (login.username + login.password).none { it.code < 32 || it.code == 127 }, Reason.INVALID_LOGIN)
        requireSafe(XrayJsonSubscriptionCodec.decode(JsonArray(listOf(config)).toString()) is XrayJsonSubscriptionCodec.Success, Reason.INVALID_PROFILE)
        val inbounds = config.getValue("inbounds").jsonArray.map { it.jsonObject }
        requireSafe(inbounds.count { it["protocol"]?.jsonPrimitive?.content == "socks" } == 1)
        requireSafe(inbounds.count { it["protocol"]?.jsonPrimitive?.content == "http" } <= 1)
        inbounds.forEach(::validateInbound)
        val socks = inbounds.single { it.getValue("protocol").jsonPrimitive.content == "socks" }
        val http = inbounds.singleOrNull { it.getValue("protocol").jsonPrimitive.content == "http" }
        if (http != null) {
            val tag = http.getValue("tag").jsonPrimitive.content
            // Xray treats sniffing.destOverride as protocol names, not inbound tags.
            // Exclude only that path from the conservative scan; all other retained
            // values, including opaque core-owned extensions, must remain checked.
            val scanSocks = socks["sniffing"]?.jsonObject?.let { sniffing ->
                JsonObject(socks + ("sniffing" to JsonObject(sniffing - "destOverride")))
            } ?: socks
            val referenceScan = JsonObject(config + ("inbounds" to JsonArray(listOf(scanSocks))))
            requireSafe(!containsString(referenceScan, tag), Reason.REFERENCED_HTTP_INBOUND)
        }
        val settings = JsonObject(socks.getValue("settings").jsonObject + mapOf(
            "auth" to JsonPrimitive("password"),
            "accounts" to buildJsonArray { add(buildJsonObject {
                put("user", login!!.username); put("pass", login.password)
            }) }
        ))
        val adapted = JsonObject(socks + mapOf("listen" to JsonPrimitive("127.0.0.1"), "port" to JsonPrimitive(port), "settings" to settings))
        val noPayloadLogs = buildJsonObject {
            put("access", "none")
            put("error", "none")
            put("loglevel", "none")
            put("dnsLog", false)
        }
        return Adapted(JsonObject(config + mapOf("inbounds" to JsonArray(listOf(adapted)), "log" to noPayloadLogs)))
    }

    private fun requireSafe(value: Boolean, reason: Reason = Reason.INVALID_INBOUND) {
        if (!value) throw Rejected(reason)
    }

    private fun containsString(element: JsonElement, value: String): Boolean = when (element) {
        is JsonPrimitive -> element.isString && element.content == value
        is JsonArray -> element.any { containsString(it, value) }
        is JsonObject -> element.values.any { containsString(it, value) }
    }

    private fun validateInbound(inbound: JsonObject) {
        requireSafe(inbound.keys.all { it in setOf("tag", "listen", "port", "protocol", "settings", "sniffing") })
        requireSafe(inbound["listen"] == JsonPrimitive("127.0.0.1") || inbound["listen"] == JsonPrimitive("::1"))
        val settings = inbound.getValue("settings").jsonObject
        val socks = inbound.getValue("protocol").jsonPrimitive.content == "socks"
        requireSafe(settings.keys.all { it in if (socks) setOf("auth", "accounts", "udp", "userLevel") else setOf("allowTransparent", "accounts", "userLevel") })
        if (socks) {
            requireSafe(settings["udp"] == JsonPrimitive(true))
            requireSafe(settings["auth"] in listOf(null, JsonPrimitive("noauth"), JsonPrimitive("password")))
        }
        settings["allowTransparent"]?.let { requireSafe(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) }
        settings["userLevel"]?.let { requireSafe(it is JsonPrimitive && !it.isString && (it.intOrNull ?: -1) >= 0) }
        settings["accounts"]?.let { accounts ->
            requireSafe(accounts is JsonArray)
            (accounts as JsonArray).forEach { account ->
                requireSafe(account is JsonObject)
                val a = account as JsonObject
                requireSafe(a.keys == setOf("user", "pass"))
                requireSafe(a.values.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() })
            }
        }
        inbound["sniffing"]?.let { value ->
            requireSafe(value is JsonObject)
            val sniffing = value as JsonObject
            requireSafe(sniffing.keys.all { it in setOf("enabled", "routeOnly", "metadataOnly", "destOverride", "domainsExcluded") })
            for (key in listOf("enabled", "routeOnly", "metadataOnly")) sniffing[key]?.let {
                requireSafe(it is JsonPrimitive && !it.isString && it.booleanOrNull != null)
            }
            for (key in listOf("destOverride", "domainsExcluded")) sniffing[key]?.let {
                requireSafe(it is JsonArray && it.all { p -> p is JsonPrimitive && p.isString && p.content.isNotBlank() })
            }
        }
    }
}
