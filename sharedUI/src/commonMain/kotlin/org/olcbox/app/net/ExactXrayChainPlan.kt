package org.olcbox.app.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Runtime-only chain for a validated XRAY_JSON subscription profile.
 *
 * The imported provider graph stays inside Xray. When the user selected a
 * rule-based policy, the existing sing-box front handles device-side routing
 * and DNS before handing proxy traffic to Xray's authenticated SOCKS inbound.
 */
internal object ExactXrayChainPlan {
    class UnsupportedProviderRouting : IllegalArgumentException("Xray subscription routing is incompatible with local policy")

    class Built(
        val xrayConfig: JsonObject,
        val frontConfig: String?,
        val ingressPort: Int,
        val backendPort: Int?,
    ) {
        override fun toString(): String = "ExactXrayChainPlan.Built"
    }

    fun build(
        config: JsonObject,
        ingressPort: Int,
        backendPort: Int?,
        login: SocksLogin?,
        routing: Routing,
        tunMode: Boolean,
    ): Built {
        if (routing == Routing.Global) {
            val xray = XrayJsonRuntimeConfig.adapt(config, ingressPort, login, tunMode).config
            return Built(xray, null, ingressPort, null)
        }

        require(routing is Routing.Rules) { "Unsupported exact Xray routing mode" }
        require(backendPort != null && backendPort in 1..65535 && backendPort != ingressPort) {
            "Invalid exact Xray backend port"
        }
        val xray = XrayJsonRuntimeConfig.adapt(config, backendPort, login, tunMode).config
        requireProxyDefaultAndOnlyBittorrentDirect(config)
        val authenticatedLogin = login
            ?: throw XrayJsonRuntimeConfig.Rejected(XrayJsonRuntimeConfig.Reason.INVALID_LOGIN)
        val policy = routing.copy(
            mandatoryTunnelSuffixes = (listOf("cc", "at") + routing.mandatoryTunnelSuffixes).distinct()
        )
        val front = SingBoxConfig.buildSocksChain(
            upstreamPort = backendPort,
            socksPort = ingressPort,
            username = authenticatedLogin.username,
            password = authenticatedLogin.password,
            routing = policy,
            login = authenticatedLogin,
        )
        return Built(xray, front, ingressPort, backendPort)
    }

    /** The local front cannot override a second direct decision inside Xray.
     * Admit the observed Remnawave graph, but fail closed if a future profile
     * adds domain/IP/port rules that could undo the selected exit policy.
     */
    private fun requireProxyDefaultAndOnlyBittorrentDirect(config: JsonObject) {
        val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }
        val proxyTag = outbounds.first().getValue("tag").jsonPrimitive.content
        val directTags = outbounds.filter { it.getValue("protocol").jsonPrimitive.content == "freedom" }
            .map { it.getValue("tag").jsonPrimitive.content }.toSet()
        val bittorrent = JsonArray(listOf(JsonPrimitive("bittorrent")))
        for (rule in config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }) {
            val target = rule.getValue("outboundTag").jsonPrimitive.content
            if (target == proxyTag) continue
            val knownException = target in directTags &&
                rule.keys == setOf("type", "protocol", "outboundTag") &&
                rule["protocol"] == bittorrent
            if (!knownException) throw UnsupportedProviderRouting()
        }
    }
}
