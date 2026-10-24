package org.olcbox.app.net

import org.json.JSONObject

/** Room metadata from a verified Bridge CompleteLinkAndPrefetch, never an OlcRTC grant. */
internal class PersonalBootstrapV1(
    val schemaVersion: Int,
    val provider: String,
    val transport: String,
    val roomId: String,
    val country: String,
    val roomKey: String,
    val expiresAtEpochMs: Long,
    val policyRevision: Long
) {
    fun isUsableAt(nowEpochMs: Long): Boolean =
        schemaVersion == 1 && provider == "telemost" && transport == "vp8channel" &&
            ROOM_ID.matches(roomId) && COUNTRY.matches(country) && validRoomKey(roomKey) &&
            policyRevision > 0 && expiresAtEpochMs > nowEpochMs && nowEpochMs > 0

    internal fun toPrivateJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("provider", provider)
        .put("transport", transport)
        .put("roomId", roomId)
        .put("country", country)
        .put("roomKey", roomKey)
        .put("expiresAtEpochMs", expiresAtEpochMs)
        .put("policyRevision", policyRevision)

    override fun toString(): String = "PersonalBootstrapV1([redacted])"

    companion object {
        private val ROOM_ID = Regex("[A-Za-z0-9_-]{8,128}")
        private val COUNTRY = Regex("[A-Z]{2}")
        private val ROOM_KEY = Regex("[0-9a-f]{64}")
        private val FIELDS = setOf(
            "schemaVersion", "provider", "transport", "roomId", "country", "roomKey",
            "expiresAtEpochMs", "policyRevision"
        )

        internal fun fromPrivateJson(json: JSONObject): PersonalBootstrapV1? {
            if (json.keys().asSequence().toSet() != FIELDS) return null
            return PersonalBootstrapV1(
                schemaVersion = json.opt("schemaVersion").strictLong()?.takeIf {
                    it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong()
                }?.toInt() ?: return null,
                provider = json.opt("provider") as? String ?: return null,
                transport = json.opt("transport") as? String ?: return null,
                roomId = json.opt("roomId") as? String ?: return null,
                country = json.opt("country") as? String ?: return null,
                roomKey = json.opt("roomKey") as? String ?: return null,
                expiresAtEpochMs = json.opt("expiresAtEpochMs").strictLong() ?: return null,
                policyRevision = json.opt("policyRevision").strictLong() ?: return null
            )
        }

        private fun validRoomKey(value: String): Boolean =
            ROOM_KEY.matches(value) && value.any { it != '0' }
    }
}

/** Implement only with an authenticated Bridge response to the same link and installation PoP. */
internal fun interface VerifiedPersonalBootstrapProducer {
    suspend fun completeLinkAndPrefetch(
        importedSubscriptionUrl: String,
        installation: PersonalJoinSigningKey
    ): PersonalBootstrapV1?
}

internal fun Any?.strictLong(): Long? = when (this) {
    is Int -> toLong()
    is Long -> this
    else -> null
}
