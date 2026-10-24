package org.olcbox.app.vpn

/** A JNI exception may contain a room password or network target. Retain only
 * this closed diagnosis before crossing the Kotlin runtime boundary. It is
 * telemetry, never a subscription denial or a reason to bypass admission. */
internal class GeneralNativeFailure private constructor(val code: Code, val providerBoundary: VkStartupBoundary? = null) : IllegalStateException(GENERAL_RUNTIME_DENIED) {
    enum class Code { PROVIDER_BOOTSTRAP, PROVIDER_SIGNAL, PROVIDER_MEDIA, CAPABILITY,
        DNS, TIMEOUT, CONFIGURATION, CANCELLED, HANDSHAKE, OTHER }
    companion object {
        private val vkBoundary = Regex("vkcalls: rga_vk_startup stage=(auth_anon|auth_preview|auth_call_token|auth_sdk_login|auth_join|signal_dial|signal_join|signal_allocate|signal_settings|media_offer|media_settings|negotiation|unknown) class=(dns|protect_rejected|dial|tls|http_status|provider_code|response|signaling|negotiation|request|timeout|cancelled) code=(0|[1-9][0-9]{0,4})(?=\\s|$)")
        fun from(error: Throwable): GeneralNativeFailure {
            if (error is GeneralNativeFailure) return error
            val message = runCatching { error.message }.getOrNull()
                ?.takeIf { it.length <= 8192 }?.lowercase(java.util.Locale.ROOT).orEmpty()
            val matches = vkBoundary.findAll(message).take(2).toList()
            val boundary = matches.singleOrNull()?.let {
                VkStartupBoundary(it.groupValues[1].uppercase(java.util.Locale.ROOT),
                    it.groupValues[2].uppercase(java.util.Locale.ROOT), it.groupValues[3].toInt())
            }
            val code = when {
                boundary?.failureClass == "CANCELLED" -> Code.CANCELLED
                boundary?.failureClass == "TIMEOUT" -> Code.TIMEOUT
                boundary?.stage?.startsWith("AUTH_") == true -> Code.PROVIDER_BOOTSTRAP
                boundary?.stage?.startsWith("SIGNAL_") == true -> Code.PROVIDER_SIGNAL
                boundary?.stage?.startsWith("MEDIA_") == true || boundary?.stage == "NEGOTIATION" -> Code.PROVIDER_MEDIA
                "preconnect" in message -> Code.PROVIDER_BOOTSTRAP
                "signaling websocket" in message || "websocket: bad handshake" in message -> Code.PROVIDER_SIGNAL
                "salutejazz join timeout" in message || "salutejazz publisher offer" in message -> Code.PROVIDER_MEDIA
                "provider does not support video tracks" in message || "unsupported transport" in message ||
                    "transport options type mismatch" in message -> Code.CAPABILITY
                "no such host" in message || "dns lookup" in message -> Code.DNS
                "timeout" in message || "deadline exceeded" in message -> Code.TIMEOUT
                "invalid mobile runtime configuration" in message -> Code.CONFIGURATION
                "context canceled" in message || "context cancelled" in message -> Code.CANCELLED
                "managed handshake" in message -> Code.HANDSHAKE
                else -> Code.OTHER
            }
            return GeneralNativeFailure(code, boundary) // No cause or raw text is retained.
        }
    }
}

/** Constructed only after closed-vocabulary parsing; never authority or raw data. */
internal data class VkStartupBoundary(val stage: String, val failureClass: String, val code: Int) {
    fun diagnostic() = "providerStage=$stage providerClass=$failureClass providerCode=$code"
}

/** Closed diagnostic vocabulary only. Never carries target, identity or error text. */
internal object GeneralTransportDiagnostics {
    enum class NativeEvent {
        OPEN_STREAM_FAILED, CONNECT_WRITE_FAILED, CONNECT_ACK_READ_FAILED, CONNECT_ACK_REJECTED,
        CONNECT_ACK_DATA_NO_DNS_CALLBACK_CLOSE_REQUEST, CONNECT_ACK_DNS_NO_DNS_CALLBACK_CLOSE_REQUEST,
        CONNECT_ACK_DNS_TIMER_CLOSE_REQUESTED, CONNECT_ACK_DNS_CONTEXT_CLOSE_REQUESTED,
        CONNECT_ACK_DNS_TIMER_AND_CONTEXT_CLOSE_REQUESTED,
        CONTROL_EOF, CONTROL_CLOSED_PIPE, CONTROL_TIMEOUT, CONTROL_PEER_CLOSE,
        CONTROL_UNHEALTHY, CONTROL_ENDED_OTHER, CONTROL_SEND_STALLED, CONTROL_MISSED_PONG,
        RECONNECT_LIVENESS, RECONNECT_PROVIDER, RECONNECT_PEER_CLOSE, CONFERENCE_END, OTHER,
        TUN_ROUTE_DOMAIN_TUNNEL, TUN_ROUTE_DOMAIN_DIRECT, TUN_ROUTE_DOMAIN_SNIFF,
        TUN_ROUTE_IP_TUNNEL, TUN_ROUTE_IP_DIRECT, TUN_ROUTE_IP_SNIFF,
        TUN_HEAD_NONE, TUN_HEAD_OK, TUN_HEAD_FAILED, TUN_HEAD_REPLY_FAILED, TUN_BODY_ENTERED,
        GENERAL_CLIENT_HELLO_WRITE_ENTER, GENERAL_CLIENT_HELLO_WRITE_RETURN, GENERAL_CLIENT_HELLO_WRITE_FAILED,
        GENERAL_CLIENT_CHALLENGE_READ_ENTER, GENERAL_CLIENT_CHALLENGE_READ_RETURN, GENERAL_CLIENT_CHALLENGE_READ_FAILED,
        GENERAL_CLIENT_VALIDATE_PREPARED_ENTER, GENERAL_CLIENT_VALIDATE_PREPARED_RETURN, GENERAL_CLIENT_VALIDATE_PREPARED_FAILED,
        GENERAL_CLIENT_SIGN_ENTER, GENERAL_CLIENT_SIGN_RETURN, GENERAL_CLIENT_SIGN_FAILED,
        GENERAL_CLIENT_VERIFY_PROOF_ENTER, GENERAL_CLIENT_VERIFY_PROOF_RETURN, GENERAL_CLIENT_VERIFY_PROOF_FAILED,
        GENERAL_CLIENT_PROOF_WRITE_ENTER, GENERAL_CLIENT_PROOF_WRITE_RETURN, GENERAL_CLIENT_PROOF_WRITE_FAILED,
        GENERAL_CLIENT_WELCOME_READ_ENTER, GENERAL_CLIENT_WELCOME_READ_RETURN, GENERAL_CLIENT_WELCOME_READ_FAILED,
        GENERAL_CLIENT_VALIDATE_WELCOME_ENTER, GENERAL_CLIENT_VALIDATE_WELCOME_RETURN, GENERAL_CLIENT_VALIDATE_WELCOME_FAILED
    }
    enum class SocksReply { SUCCESS, NETWORK_UNREACHABLE, HOST_UNREACHABLE, REFUSED, OTHER_REJECTION, INVALID }
    enum class AckReadClass { SMUX_TIMEOUT, EOF, CLOSED_PIPE, OTHER }
    data class AckReadWitness(val classification: AckReadClass, val ownEndExpired: Boolean)
    private val ackReadGrammar = Regex("(?:[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} )?RGA_TASK27_ACK_READ class=(SMUX_TIMEOUT|EOF|CLOSED_PIPE|OTHER) ownEndExpired=(true|false)(?:\\r?\\n)?")
    fun ackRead(message: String): AckReadWitness? {
        if (message.length > 160) return null
        val match = ackReadGrammar.matchEntire(message) ?: return null
        return AckReadWitness(AckReadClass.valueOf(match.groupValues[1]), match.groupValues[2] == "true")
    }
    fun native(message: String): NativeEvent {
        if (message.length > 4096) return NativeEvent.OTHER
        // A malformed marker must not fall through to permissive legacy matching.
        if (message.contains("RGA_GENERAL_CLIENT81")) return clientHandshake81(message)
        if (message.contains("RGA_TASK27_TUN_")) return tunBoundary78(message)
        // Same-call native witness. No stream/target/error retention; this only
        // projects a local Close request, never its completion or cause of EOF.
        if (message.contains("RGA_TASK27_ACK_SCOPE52")) return ackLocalScope52(message)
        lifecycle(message)?.let { return it }
        return when {
            message.contains("OpenStream failed:") -> NativeEvent.OPEN_STREAM_FAILED
            !message.contains("connect failed:") -> NativeEvent.OTHER
            message.contains("write connect req:") -> NativeEvent.CONNECT_WRITE_FAILED
            message.contains("read_err=") -> NativeEvent.CONNECT_ACK_READ_FAILED
            message.contains("connect ack=0x") -> NativeEvent.CONNECT_ACK_REJECTED
            else -> NativeEvent.OTHER
        }
    }
    private val clientHandshakeGrammar81 = Regex("(?:[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} )?RGA_GENERAL_CLIENT81 stage=(HELLO_WRITE|CHALLENGE_READ|VALIDATE_PREPARED|SIGN|VERIFY_PROOF|PROOF_WRITE|WELCOME_READ|VALIDATE_WELCOME) state=(ENTER|RETURN|FAILED) elapsedMs=(?:0|[1-9][0-9]{0,5})(?:\\r?\\n)?")
    /** Anchored fixed vocabulary only; elapsed, identities and errors never escape. */
    private fun clientHandshake81(message: String): NativeEvent {
        if (message.length > 180) return NativeEvent.OTHER
        val match = clientHandshakeGrammar81.matchEntire(message) ?: return NativeEvent.OTHER
        return NativeEvent.valueOf("GENERAL_CLIENT_${match.groupValues[1]}_${match.groupValues[2]}")
    }
    private val tunDate78 = "(?:[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} )?"
    private val tunRoute78 = Regex(tunDate78 + "RGA_TASK27_TUN_ROUTE class=(DOMAIN|IP) decision=(TUNNEL|DIRECT|SNIFF)(?:\\r?\\n)?")
    private val tunHead78 = Regex(tunDate78 + "RGA_TASK27_TUN_HEAD result=(NONE|OK|FAILED|REPLY_FAILED)(?:\\r?\\n)?")
    private val tunBody78 = Regex(tunDate78 + "RGA_TASK27_TUN_BODY result=ENTERED(?:\\r?\\n)?")
    /** One bounded line; only closed enums escape the existing General redaction callback. */
    private fun tunBoundary78(message: String): NativeEvent {
        if (message.length > 160) return NativeEvent.OTHER
        tunRoute78.matchEntire(message)?.let { match ->
            return NativeEvent.valueOf("TUN_ROUTE_${match.groupValues[1]}_${match.groupValues[2]}")
        }
        tunHead78.matchEntire(message)?.let { match ->
            return NativeEvent.valueOf("TUN_HEAD_${match.groupValues[1]}")
        }
        return if (tunBody78.matches(message)) NativeEvent.TUN_BODY_ENTERED else NativeEvent.OTHER
    }
    private val ackScopeGrammar52 = Regex("(?:[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} )?RGA_TASK27_ACK_SCOPE52 origin=(DATA|DNS) localTimerCloseRequested=(true|false) localContextCloseRequested=(true|false) class=(SMUX_TIMEOUT|EOF|CLOSED_PIPE|OTHER) ownEndExpired=(true|false)(?:\\r?\\n)?")
    private fun ackLocalScope52(message: String): NativeEvent {
        if (message.length > 256) return NativeEvent.OTHER
        val match = ackScopeGrammar52.matchEntire(message) ?: return NativeEvent.OTHER
        val timer = match.groupValues[2] == "true"
        val context = match.groupValues[3] == "true"
        if (match.groupValues[1] == "DATA") return if (timer || context) NativeEvent.OTHER
            else NativeEvent.CONNECT_ACK_DATA_NO_DNS_CALLBACK_CLOSE_REQUEST
        return when {
            timer && context -> NativeEvent.CONNECT_ACK_DNS_TIMER_AND_CONTEXT_CLOSE_REQUESTED
            timer -> NativeEvent.CONNECT_ACK_DNS_TIMER_CLOSE_REQUESTED
            context -> NativeEvent.CONNECT_ACK_DNS_CONTEXT_CLOSE_REQUESTED
            else -> NativeEvent.CONNECT_ACK_DNS_NO_DNS_CALLBACK_CLOSE_REQUEST
        }
    }
    private val nativeDate = Regex("^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} ")
    private const val clientFields = "role=client session=[^\\s:]{0,128}"
    private val controlEnd = Regex("control stream ended $clientFields: (.{1,256})")
    private val missedPong = Regex("control missed pong $clientFields missed=[1-9][0-9]{0,2}")
    private val sendStalled = Regex("control healthy $clientFields but the data plane has stopped sending — tearing the session down")
    private val reconnect = Regex("client reconnect reason=(liveness|provider|peer-close) - tearing down smux session")
    private val conferenceEnd = Regex("Client link reported conference end: .{0,256}")
    /** Consumes existing warnings; one bounded line, fixed output, no identity/error retention. */
    private fun lifecycle(message: String): NativeEvent? {
        if (message.length > 512) return null
        val line = nativeDate.replaceFirst(message.removeSuffix("\n").removeSuffix("\r"), "")
        if (line.any { it == '\n' || it == '\r' }) return null
        controlEnd.matchEntire(line)?.let { match ->
            return when (val cause = match.groupValues[1]) {
                "EOF", "control: EOF" -> NativeEvent.CONTROL_EOF
                "io: read/write on closed pipe", "control: io: read/write on closed pipe" -> NativeEvent.CONTROL_CLOSED_PIPE
                "timeout", "control: timeout", "i/o timeout", "control: i/o timeout" -> NativeEvent.CONTROL_TIMEOUT
                "control stream closed by peer" -> NativeEvent.CONTROL_PEER_CLOSE
                else -> if (Regex("control stream unhealthy: missed [1-9][0-9]{0,2} pong\\(s\\)").matches(cause))
                    NativeEvent.CONTROL_UNHEALTHY else NativeEvent.CONTROL_ENDED_OTHER
            }
        }
        if (sendStalled.matches(line)) return NativeEvent.CONTROL_SEND_STALLED
        if (missedPong.matches(line)) return NativeEvent.CONTROL_MISSED_PONG
        reconnect.matchEntire(line)?.let { match ->
            return when (match.groupValues[1]) {
                "liveness" -> NativeEvent.RECONNECT_LIVENESS
                "provider" -> NativeEvent.RECONNECT_PROVIDER
                else -> NativeEvent.RECONNECT_PEER_CLOSE
            }
        }
        return if (conferenceEnd.matches(line)) NativeEvent.CONFERENCE_END else null
    }
    fun socksReply(reply: ByteArray): SocksReply {
        if (reply.size != 4 || reply[0] != 5.toByte() || reply[2] != 0.toByte()) return SocksReply.INVALID
        return when (reply[1].toInt() and 255) {
            0 -> SocksReply.SUCCESS
            3 -> SocksReply.NETWORK_UNREACHABLE
            4 -> SocksReply.HOST_UNREACHABLE
            5 -> SocksReply.REFUSED
            else -> SocksReply.OTHER_REJECTION
        }
    }
}
