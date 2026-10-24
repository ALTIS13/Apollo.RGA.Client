package org.olcbox.app.vpn.service

/** Existing probe owns these reads; no timer/thread or cancellation/close-path JNI. */
internal class GeneralLoadProbeRequest(
    private val request: GeneralProbeRequest,
    private val read: () -> String,
    private val log: (String) -> Unit
) : GeneralProbeRequest {
    override fun body(originalEndElapsedMs: Long): ByteArray? {
        observe("BEGIN")
        return try { request.body(originalEndElapsedMs) } finally { observe("END") }
    }
    override fun close() = request.close()

    private fun observe(phase: String) {
        try {
            val snapshot = read()
            if (snapshot.isEmpty() || snapshot.length > 32768) return
            // Native collector emits at most 90 finite metric slots plus SUMMARY.
            // Reject unknown fields outright instead of scrubbing arbitrary native text.
            snapshot.lineSequence().take(91).forEach { row ->
                if (summary.matches(row) || metric.matches(row)) {
                    try { log("loadPhase=$phase $row") } catch (_: Throwable) { }
                }
            }
        } catch (_: Throwable) { /* Telemetry is never admission, body or cleanup authority. */ }
    }

    private companion object {
        const val n = "[0-9]{1,20}"
        val summary = Regex("RGA_LOAD_V1 stage=SUMMARY saturated=$n ticks=$n flush=$n skip_control=$n skip_udp=$n ack_fallback=$n data_depth=$n ack_depth=$n udp_depth=$n control_depth=$n ack=(SEEN|UNSEEN) ack_age_ms=$n")
        val metric = Regex("RGA_LOAD_V1 stage=(CONNECT_OPEN|CONNECT_WRITE|CONNECT_ACK|HEAD_WRITE|AWAIT_LINK|MUX_SEND|MUX_TAKE|KCP_ENQUEUE|TRACK_MUTEX|TRACK_WRITE) class=(NUMERIC_TCP|NAMED_TCP|OTHER) lane=(DATA|CONTROL|SHARED) active=$n age_ms=$n last_ms=$n max_ms=$n ok=$n failed=$n")
    }
}
