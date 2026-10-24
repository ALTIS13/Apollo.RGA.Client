package org.olcbox.app.vpn.service

/** A companion receipt check, not proof that packets crossed the OlcRTC TUN or exit. */
internal object PersonalTunProof {
    /** All fields change when a same-generation native worker or network snapshot is replaced. */
    data class RuntimeStamp(
        val generation: Long,
        val networkId: Long,
        val snapshotRevision: Long,
        val transportEpoch: Long,
        val bridgeEpoch: Long,
        val proofEpoch: Long,
    )

    /** Only the validating factory can create evidence usable by the state publisher. */
    class Validated private constructor(val stamp: RuntimeStamp, val observerEpoch: Long) {
        companion object {
            internal fun create(stamp: RuntimeStamp, observerEpoch: Long) = Validated(stamp, observerEpoch)
        }
    }

    /** Native indices: epoch, tx/rx packets, tx/rx TCP payload, IPv4 source observed after SYN. */
    fun validateExactFlow(
        expected: RuntimeStamp,
        current: RuntimeStamp,
        observerEpoch: Long,
        receiptLocalIpv4: String,
        expectedTunIpv4: String,
        native: LongArray?,
    ): Validated? {
        if (expected != current || expected.generation <= 0 || expected.networkId <= 0 ||
            expected.transportEpoch <= 0 || expected.bridgeEpoch <= 0 || expected.proofEpoch <= 0 ||
            observerEpoch <= 0 || native?.size != 6 || receiptLocalIpv4 != expectedTunIpv4 ||
            !isIpv4(expectedTunIpv4) || expectedTunIpv4 == "0.0.0.0"
        ) return null
        val local = ipv4Number(expectedTunIpv4) ?: return null
        if (native[0] != observerEpoch || native[1] <= 0 || native[2] <= 0 ||
            native[3] <= 0 || native[4] <= 0 || native[5] != local
        ) return null
        return Validated.create(expected, observerEpoch)
    }

    data class Prepared(val remoteIpv4: String, val localPort: Int)

    data class Reply(
        val sendingUid: Int,
        val claimedUid: Int,
        val nonce: String,
        val httpStatus: Int,
        val egressMatched: Boolean,
        val localIpv4: String,
        val remoteIpv4: String,
        val localPort: Int,
        val failure: String?,
    )

    fun accepts(
        expectedCompanionUid: Int,
        expectedNonce: String,
        prepared: Prepared,
        ownerUid: Int,
        reply: Reply,
    ): Boolean =
        expectedCompanionUid > 0 &&
            expectedNonce.matches(Regex("[0-9a-f]{32}")) &&
            prepared.localPort in 1..65535 &&
            isPublicProbeIpv4(prepared.remoteIpv4) &&
            reply.sendingUid == expectedCompanionUid &&
            reply.claimedUid == expectedCompanionUid &&
            ownerUid == expectedCompanionUid &&
            reply.nonce == expectedNonce &&
            reply.httpStatus == 200 &&
            reply.egressMatched &&
            reply.failure == null &&
            isIpv4(reply.localIpv4) && reply.localIpv4 != "0.0.0.0" &&
            reply.remoteIpv4 == prepared.remoteIpv4 &&
            reply.localPort == prepared.localPort

    fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                (part.length == 1 || part[0] != '0') &&
                part.all { it in '0'..'9' } &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    /** Fail closed for the personal health probe: special/local literals may bypass the native tunnel. */
    fun isPublicProbeIpv4(value: String): Boolean {
        if (!isIpv4(value)) return false
        val octets = value.split('.').map(String::toInt)
        val (a, b, c) = octets
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false // shared CGNAT address space
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 0 && (c == 0 || c == 2) -> false
            a == 198 && (b in 18..19 || b == 51 && c == 100) -> false
            a == 203 && b == 0 && c == 113 -> false
            else -> true
        }
    }

    fun ipv4Number(value: String): Long? {
        if (!isIpv4(value)) return null
        return value.split('.').fold(0L) { number, octet -> (number shl 8) or octet.toLong() }
    }
}
