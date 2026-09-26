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
        val code = flagCode ?: nameCode
        val flag = if (flagCode != null) parsedFlag else code?.let(::flagForCode).orEmpty()
        val country = code?.let(::countryName) ?: "Страна не указана"
        val name = withoutFlag.ifBlank { country }
        val relay = when {
            VIA_RU.containsMatchIn(name) || RU_RELAY.containsMatchIn(name) -> "через RU Relay"
            DIRECT.containsMatchIn(name) -> "напрямую"
            else -> null
        }
        val routeLine = if (relay == "через RU Relay") {
            "Устройство → RU Relay → $country"
        } else {
            "Устройство → $country"
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
}

private val URL_OR_SECRET = Regex(
    "(?i)://|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:[:/?#]\\S*)?|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|" +
        "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b|" +
        "\\b(?:token|key|password|uuid|bearer)\\s*[:=]|\\b[a-z0-9_-]{24,}\\b"
)
private val VIA_RU = Regex("(?i)\\bvia\\s+RU\\b")
private val RU_RELAY = Regex("(?i)\\bRU\\s+Relay\\b")
private val DIRECT = Regex("(?i)\\bDirect\\b")

private fun safeDisplayText(raw: String): String? = raw.trim()
    .replace(Regex("\\s+"), " ")
    .takeIf { it.isNotEmpty() && !URL_OR_SECRET.containsMatchIn(it) }

private fun countryCodeFromFlag(flag: String): String? {
    if (flag.length != 4 || flag[0] != '\uD83C' || flag[2] != '\uD83C' ||
        flag[1] !in '\uDDE6'..'\uDDFF' || flag[3] !in '\uDDE6'..'\uDDFF') return null
    return "${('A'.code + flag[1].code - '\uDDE6'.code).toChar()}${('A'.code + flag[3].code - '\uDDE6'.code).toChar()}"
}

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

enum class SubscriptionDisplayState { ACTIVE, EXPIRING, EXPIRED, LIMITED, UNKNOWN, REFRESH_FAILED }

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
            access == SubscriptionMetadata.AccessState.EXPIRED -> SubscriptionDisplayState.EXPIRED
            access == SubscriptionMetadata.AccessState.LIMITED -> SubscriptionDisplayState.LIMITED
            refreshError != null -> SubscriptionDisplayState.REFRESH_FAILED
            access == SubscriptionMetadata.AccessState.UNKNOWN -> SubscriptionDisplayState.UNKNOWN
            metadata?.expiresAtEpochMs != null && metadata.expiresAtEpochMs - nowEpochMs <= EXPIRING_WINDOW_MS ->
                SubscriptionDisplayState.EXPIRING
            else -> SubscriptionDisplayState.ACTIVE
        }
        val remaining = if (metadata?.quotaKnown == true && metadata.totalBytes != null && metadata.usedBytes != null) {
            (metadata.totalBytes - metadata.usedBytes).coerceAtLeast(0)
        } else null
        return SubscriptionPresentation(state, access, remaining, metadata?.expiresAtEpochMs, refreshError)
    }
}
