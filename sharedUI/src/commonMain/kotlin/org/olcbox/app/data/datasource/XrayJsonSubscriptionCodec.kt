package org.olcbox.app.data.datasource

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal object XrayJsonSubscriptionCodec {
    enum class Protocol { VLESS, HYSTERIA2 }
    enum class Reason { INVALID_JSON, INVALID_ROOT, INVALID_PROFILE, UNSUPPORTED_PROTOCOL, UNSUPPORTED_TRANSPORT, INVALID_DNS, INVALID_ROUTING, INVALID_TAG_REFERENCE }
    sealed interface Result
    class Success(val profiles: List<Profile>) : Result {
        override fun toString() = "XraySubscription.Success(count=${profiles.size})"
    }
    data class Rejected(val reason: Reason, val profileIndex: Int? = null) : Result
    class Profile(val displayName: String, val protocol: Protocol, val effectiveConfig: JsonObject) {
        override fun toString() = "XraySubscription.Profile(protocol=$protocol)"
    }
    /** All-or-nothing structural admission for the supported Xray subscription subset.
     * The original object is retained; this does not replace a core config check.
     * Neither results' string representations nor errors include subscription material.
     */
    fun decode(body: String): Result {
        val root = try { Json.parseToJsonElement(body) } catch (_: SerializationException) {
            return Rejected(Reason.INVALID_JSON)
        } catch (_: IllegalArgumentException) { return Rejected(Reason.INVALID_JSON) }
        if (root !is JsonArray || root.isEmpty()) return Rejected(Reason.INVALID_ROOT)
        val profiles = mutableListOf<Profile>()
        for ((index, value) in root.withIndex()) {
            try { profiles += validate(value.obj()) }
            catch (e: Invalid) { return Rejected(e.reason, index) }
        }
        return Success(profiles.toList())
    }

    private class Invalid(val reason: Reason) : Exception()
    private fun check(ok: Boolean, reason: Reason = Reason.INVALID_PROFILE) { if (!ok) throw Invalid(reason) }
    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: throw Invalid(Reason.INVALID_PROFILE)
    private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: throw Invalid(Reason.INVALID_PROFILE)
    private fun JsonElement?.string(): String {
        val p = this as? JsonPrimitive
        check(p != null && p.isString && p.content.isNotBlank())
        return p!!.content
    }
    private fun JsonElement?.number(): Int {
        val p = this as? JsonPrimitive
        check(p != null && !p.isString && p.intOrNull != null)
        return p!!.int
    }
    private fun JsonObject.keysOnly(vararg allowed: String) { check(keys.all { it in allowed }) }
    private fun JsonElement?.strings(): List<String> = array().map { it.string() }.also { check(it.isNotEmpty()) }
    private fun endpoint(settings: JsonObject) { settings["address"].string(); check(settings["port"].number() in 1..65535) }
    private fun optionalChoice(o: JsonObject, key: String, values: Set<String>) { o[key]?.let { check(it.string() in values) } }
    private fun booleans(o: JsonObject, vararg keys: String) {
        for (key in keys) o[key]?.let { check(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) }
    }
    private fun validate(c: JsonObject): Profile {
        c.keysOnly("remarks", "log", "dns", "routing", "inbounds", "outbounds", "policy", "stats")
        val name = c["remarks"].string()
        val outbounds = c["outbounds"].array().map { it.obj() }
        check(outbounds.isNotEmpty())
        val tags = outbounds.map { it["tag"].string() }
        check(tags.toSet().size == tags.size, Reason.INVALID_TAG_REFERENCE)
        val inbounds = c["inbounds"].array().map { it.obj() }
        val inTags = inbounds.map { it["tag"].string() }
        check(inTags.toSet().size == inTags.size, Reason.INVALID_TAG_REFERENCE)
        inbounds.forEach { inbound ->
            check(inbound["protocol"].string() in setOf("socks", "http"))
            check(inbound["port"].number() in 1..65535)
            inbound["settings"].obj()
        }
        val tunnels = mutableListOf<Protocol>()
        for (out in outbounds) {
            out.keysOnly("tag", "protocol", "settings", "streamSettings", "mux")
            val protocol = out["protocol"].string()
            // Xray defaults an omitted blackhole settings object to {}. Keep the
            // source config untouched; this object exists only for validation.
            val settings = if (protocol == "blackhole" && "settings" !in out) JsonObject(emptyMap())
                else out["settings"].obj()
            when (protocol) {
                "freedom" -> {
                    check(out["streamSettings"] == null)
                    settings.keysOnly("domainStrategy")
                    optionalChoice(settings, "domainStrategy", setOf("AsIs", "UseIP", "UseIPv4", "UseIPv6"))
                }
                "blackhole" -> {
                    check(out["streamSettings"] == null)
                    settings.keysOnly()
                }
                "vless" -> {
                    settings.keysOnly("vnext")
                    val servers = settings["vnext"].array()
                    check(servers.isNotEmpty())
                    servers.forEach { server ->
                        val s = server.obj(); s.keysOnly("address", "port", "users"); endpoint(s)
                        val users = s["users"].array(); check(users.isNotEmpty())
                        users.forEach { user ->
                            val u = user.obj(); u["id"].string()
                            u.keysOnly("id", "encryption", "flow", "level", "email")
                            check(u["encryption"].string() == "none")
                            u["flow"]?.let { check(it is JsonPrimitive && it.isString && it.content.isEmpty()) }
                        }
                    }
                    val stream = out["streamSettings"].obj()
                    check(stream["network"].string() == "xhttp", Reason.UNSUPPORTED_TRANSPORT)
                    stream.keysOnly("network", "security", "xhttpSettings", "realitySettings", "sockopt")
                    check(stream["security"].string() == "reality")
                    val xhttp = stream["xhttpSettings"].obj()
                    xhttp.keysOnly("mode", "host", "path", "extra")
                    check(xhttp["mode"].string() in setOf("auto", "packet-up", "stream-up", "stream-one"))
                    xhttp["path"].string()
                    xhttp["host"]?.let { check(it is JsonPrimitive && it.isString) }
                    xhttp["extra"]?.obj() // Opaque core-owned object, retained exactly, never rebuilt.
                    val reality = stream["realitySettings"].obj()
                    reality.keysOnly("serverName", "publicKey", "shortId", "fingerprint", "spiderX", "show")
                    booleans(reality, "show")
                    reality["serverName"].string(); reality["publicKey"].string(); reality["fingerprint"].string()
                    val shortId = reality["shortId"] as? JsonPrimitive
                    check(shortId != null && shortId.isString && shortId.content.length <= 16 && shortId.content.length % 2 == 0 && shortId.content.all { it in "0123456789abcdefABCDEF" })
                    tunnels += Protocol.VLESS
                }
                "hysteria" -> {
                    settings.keysOnly("address", "port", "version")
                    endpoint(settings); check(settings["version"].number() == 2)
                    val stream = out["streamSettings"].obj()
                    check(stream["network"].string() == "hysteria", Reason.UNSUPPORTED_TRANSPORT)
                    stream.keysOnly("network", "security", "hysteriaSettings", "tlsSettings", "sockopt")
                    check(stream["security"].string() == "tls")
                    val hy = stream["hysteriaSettings"].obj()
                    // Admit only the observed subset; optional extensions need explicit validation.
                    hy.keysOnly("version", "auth")
                    check(hy["version"].number() == 2); hy["auth"].string()
                    val tls = stream["tlsSettings"].obj(); tls["serverName"].string()
                    tls.keysOnly("serverName", "enableSessionResumption", "fingerprint", "alpn", "pinnedPeerCertSha256", "allowInsecure", "disableSystemRoot")
                    booleans(tls, "enableSessionResumption", "allowInsecure", "disableSystemRoot")
                    tls["fingerprint"]?.string()
                    tls["alpn"]?.strings()
                    tls["pinnedPeerCertSha256"]?.string()
                    tunnels += Protocol.HYSTERIA2
                }
                else -> throw Invalid(Reason.UNSUPPORTED_PROTOCOL)
            }
        }
        // A profile represents one exit, with a deterministic default tunnel.
        check(tunnels.size == 1 && outbounds.first()["protocol"]?.string() in setOf("vless", "hysteria"))
        try { validateDns(c["dns"].obj()) } catch (_: Invalid) { throw Invalid(Reason.INVALID_DNS) }
        validateRouting(c["routing"].obj(), tags.toSet(), inTags.toSet())
        return Profile(name, tunnels.single(), c)
    }

    private fun validateDns(dns: JsonObject) {
        dns.keysOnly("servers", "hosts", "queryStrategy", "disableCache", "disableFallback", "disableFallbackIfMatch", "tag")
        optionalChoice(dns, "queryStrategy", setOf("UseIP", "UseIPv4", "UseIPv6"))
        booleans(dns, "disableCache", "disableFallback", "disableFallbackIfMatch")
        val servers = dns["servers"].array(); check(servers.isNotEmpty())
        servers.forEach { s ->
            if (s is JsonPrimitive) s.string() else {
                val o = s.obj()
                o.keysOnly("address", "port", "domains", "expectIPs", "skipFallback", "queryStrategy", "timeoutMs", "disableCache", "finalQuery", "tag")
                o["address"].string()
                o["port"]?.let { check(it.number() in 1..65535) }
                o["timeoutMs"]?.let { check(it.number() >= 0) }
                o["domains"]?.strings(); o["expectIPs"]?.strings()
                optionalChoice(o, "queryStrategy", setOf("UseIP", "UseIPv4", "UseIPv6"))
                booleans(o, "skipFallback", "disableCache", "finalQuery")
            }
        }
        dns["hosts"]?.obj()?.values?.forEach { if (it is JsonArray) it.strings() else it.string() }
    }

    private fun validateRouting(routing: JsonObject, tags: Set<String>, inTags: Set<String>) {
        try {
            routing.keysOnly("domainStrategy", "domainMatcher", "rules")
            optionalChoice(routing, "domainStrategy", setOf("AsIs", "IPIfNonMatch", "IPOnDemand"))
            optionalChoice(routing, "domainMatcher", setOf("hybrid", "linear"))
            routing["rules"].array().forEach { r ->
                val rule = r.obj()
                rule.keysOnly("type", "domain", "ip", "port", "sourcePort", "network", "source", "user", "inboundTag", "protocol", "attrs", "outboundTag", "domainMatcher")
                check(rule["type"].string() == "field")
                optionalChoice(rule, "domainMatcher", setOf("hybrid", "linear"))
                check(rule["outboundTag"].string() in tags, Reason.INVALID_TAG_REFERENCE)
                rule["inboundTag"]?.strings()?.let { check(it.all { tag -> tag in inTags }, Reason.INVALID_TAG_REFERENCE) }
                check(rule.keys.any { it in setOf("domain", "ip", "port", "sourcePort", "network", "source", "user", "inboundTag", "protocol", "attrs") })
                for (key in listOf("domain", "ip", "source", "user", "protocol")) rule[key]?.strings()
                for (key in listOf("port", "sourcePort", "network")) rule[key]?.string()
                rule["network"]?.let { check(it.string().split(',').all { network -> network.trim() in setOf("tcp", "udp") }) }
                rule["attrs"]?.obj()?.values?.forEach { it.string() }
            }
        } catch (e: Invalid) {
            throw Invalid(if (e.reason == Reason.INVALID_TAG_REFERENCE) e.reason else Reason.INVALID_ROUTING)
        }
    }
}
