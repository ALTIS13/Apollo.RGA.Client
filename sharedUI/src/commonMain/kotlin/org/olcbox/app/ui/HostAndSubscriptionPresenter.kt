package org.olcbox.app.ui

import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.TransportGroup
import org.olcbox.app.util.parseEmojiAndName
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

data class HostPresentation(
    val flag: String,
    val country: String,
    val name: String,
    val purpose: String?,
    val relay: String?,
    val protocol: String,
    val routeLine: String
)

object HostPresenter {
    fun present(entry: LocationEntry): HostPresentation? {
        val config = entry.location
        if (config.kind == LocationKind.Olcrtc) return null
        val rawName = entry.name.ifBlank { config.name }
        val safeName = safeDisplayText(TransportGroup.baseName(rawName)) ?: "Хост без названия"
        val (parsedFlag, withoutFlag) = parseEmojiAndName(safeName)
        val flagCode = countryCodeFromFlag(parsedFlag)
        val nameCode = withoutFlag.take(2).takeIf { code ->
            code.length == 2 && code.all { it in 'A'..'Z' } &&
                (withoutFlag.length == 2 || !withoutFlag[2].isLetter())
        }
        // Remnawave's current remarks put the destination name before its flag,
        // e.g. "Netherlands 🇳🇱 · RU Relay". Only the first title segment and
        // an exact matching name/flag pair may supply a destination here; a
        // transit flag later in the label must never change the exit country.
        val code = flagCode ?: nameCode ?: countryCodeFromNamedFlag(withoutFlag)
        val flag = if (flagCode != null) parsedFlag else code?.let(::flagForCode).orEmpty()
        val country = code?.let(::countryName) ?: "Страна не указана"
        val name = withoutFlag.ifBlank { country }
        val relay = if (configuredOwnedRelay(entry)) "через RU Relay" else null
        val routeLine = if (relay == "через RU Relay") {
            "Настроенный вход: RU Relay · назначение $country"
        } else {
            "Маршрут не подтверждён"
        }
        return HostPresentation(
            flag = flag,
            country = country,
            name = name,
            purpose = entry.metadata?.comment?.let(::safeDisplayText),
            relay = relay,
            protocol = protocolLabel(entry),
            routeLine = routeLine
        )
    }

    private fun protocolLabel(entry: LocationEntry): String {
        val config = entry.location
        if (config.kind == LocationKind.Hysteria2) return "Hysteria2"
        if (config.kind != LocationKind.Vless) return config.protocolLabels().firstOrNull() ?: "Протокол не указан"
        val stream = runCatching {
            config.xrayConfig?.get("outbounds")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("streamSettings")?.jsonObject
        }.getOrNull()
        val network = runCatching { stream?.get("network")?.jsonPrimitive?.content?.lowercase() }.getOrNull()
        val security = runCatching { stream?.get("security")?.jsonPrimitive?.content?.lowercase() }.getOrNull()
        val protection = if (security == "reality") "Reality" else null
        val transport = when (network) {
            "xhttp" -> "XHTTP"
            "grpc" -> "gRPC"
            else -> config.protocolLabels().drop(1).firstOrNull().takeUnless { it == protection }
        }
        return listOfNotNull("VLESS", transport, protection).joinToString(" · ")
    }

    /** A configured owned ingress is not proof of the packet's actual egress path. */
    private fun configuredOwnedRelay(entry: LocationEntry): Boolean = runCatching {
        val outbound = entry.location.xrayConfig?.get("outbounds")?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@runCatching false
        val protocol = outbound["protocol"]?.jsonPrimitive?.content?.lowercase()
        val settings = outbound["settings"]?.jsonObject ?: return@runCatching false
        val address = when {
            entry.location.kind == LocationKind.Vless && protocol == "vless" ->
                settings["vnext"]?.jsonArray?.singleOrNull()?.jsonObject?.get("address")?.jsonPrimitive?.content
            entry.location.kind == LocationKind.Hysteria2 && protocol == "hysteria" &&
                settings["version"]?.jsonPrimitive?.intOrNull == 2 ->
                settings["address"]?.jsonPrimitive?.content
            else -> null
        }
        address?.lowercase() in OWNED_RELAY_ENDPOINTS
    }.getOrDefault(false)
}

private val URL_OR_SECRET = Regex(
    "(?i)://|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:[:/?#]\\S*)?|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|" +
        "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b|" +
        "\\b(?:token|key|password|uuid|bearer)(?:\\s*[:=]|\\s+)|\\b[a-z0-9_-]{24,}\\b"
)
private val OWNED_RELAY_ENDPOINTS = setOf(
    "rw-fin-relay.gate-altas.tech",
    "rw-nl-relay.gate-altas.tech",
    "rw-de-relay.gate-altas.tech",
    "rw-sg-relay.gate-altas.tech",
    "rw-sgl-relay.gate-altas.tech"
)

private fun safeDisplayText(raw: String): String? = raw.trim()
    .replace(Regex("\\s+"), " ")
    .takeIf { it.isNotEmpty() && !URL_OR_SECRET.containsMatchIn(it) }

private fun countryCodeFromFlag(flag: String): String? {
    if (flag.length != 4 || flag[0] != '\uD83C' || flag[2] != '\uD83C' ||
        flag[1] !in '\uDDE6'..'\uDDFF' || flag[3] !in '\uDDE6'..'\uDDFF') return null
    return "${('A'.code + flag[1].code - '\uDDE6'.code).toChar()}${('A'.code + flag[3].code - '\uDDE6'.code).toChar()}"
}

private fun countryCodeFromNamedFlag(name: String): String? {
    val destination = name.substringBefore('·').trim()
    return DISPLAY_COUNTRY_CODES.firstOrNull { code ->
        destination == "${countryName(code)} ${flagForCode(code)}"
    }
}

private val DISPLAY_COUNTRY_CODES = listOf(
    "NL", "US", "DE", "JP", "IT", "IN", "RU", "GB", "FR", "FI", "SG", "BY"
)

private fun flagForCode(code: String): String = buildString {
    code.forEach { letter ->
        append('\uD83C')
        append(('\uDDE6'.code + letter.code - 'A'.code).toChar())
    }
}

private fun countryName(code: String): String = when (code) {
    "NL" -> "Netherlands"
    "US" -> "United States"
    "DE" -> "Germany"
    "JP" -> "Japan"
    "IT" -> "Italy"
    "IN" -> "India"
    "RU" -> "Russia"
    "GB" -> "United Kingdom"
    "FR" -> "France"
    "FI" -> "Finland"
    "SG" -> "Singapore"
    "BY" -> "Belarus"
    else -> code
}

enum class SubscriptionDisplayState { ACTIVE, EXPIRING, EXPIRED, LIMITED, DEVICE_DENIED, UNKNOWN, REFRESH_FAILED }

data class SubscriptionPresentation(
    val state: SubscriptionDisplayState,
    val accessState: SubscriptionMetadata.AccessState,
    val remainingBytes: Long?,
    val expiresAtEpochMs: Long?,
    val refreshError: SubscriptionRefreshError?
)

object SubscriptionPresenter {
    private const val EXPIRING_WINDOW_MS = 3 * 86_400_000L

    fun present(
        metadata: SubscriptionMetadata?,
        nowEpochMs: Long,
        refreshError: SubscriptionRefreshError? = null
    ): SubscriptionPresentation {
        val access = metadata?.accessStateAt(nowEpochMs) ?: SubscriptionMetadata.AccessState.UNKNOWN
        val state = when {
            access == SubscriptionMetadata.AccessState.DEVICE_DENIED -> SubscriptionDisplayState.DEVICE_DENIED
            access == SubscriptionMetadata.AccessState.EXPIRED -> SubscriptionDisplayState.EXPIRED
            access == SubscriptionMetadata.AccessState.LIMITED -> SubscriptionDisplayState.LIMITED
            refreshError != null -> SubscriptionDisplayState.REFRESH_FAILED
            metadata?.expiresAtEpochMs != null && metadata.expiresAtEpochMs - nowEpochMs <= EXPIRING_WINDOW_MS ->
                SubscriptionDisplayState.EXPIRING
            access == SubscriptionMetadata.AccessState.UNKNOWN -> SubscriptionDisplayState.UNKNOWN
            else -> SubscriptionDisplayState.ACTIVE
        }
        val remaining = if (metadata?.quotaKnown == true && metadata.totalBytes != null &&
            metadata.totalBytes > 0 && metadata.usedBytes != null
        ) {
            (metadata.totalBytes - metadata.usedBytes).coerceAtLeast(0)
        } else null
        return SubscriptionPresentation(state, access, remaining, metadata?.expiresAtEpochMs, refreshError)
    }
}
