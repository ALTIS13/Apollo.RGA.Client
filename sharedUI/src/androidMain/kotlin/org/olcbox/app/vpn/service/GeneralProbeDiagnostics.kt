package org.olcbox.app.vpn.service

internal enum class GeneralProbeStage { LOOPBACK, AUTH, DESTINATION, TLS, STATUS, HEADERS, BODY, COMPLETE }
internal class GeneralProbeDiagnostics(private val log: (String) -> Unit) {
    fun mark(stage: GeneralProbeStage) = emit("probe=${stage.name}")
    fun failed(error: Exception) {
        val kind = when (error) {
            is java.net.SocketTimeoutException -> "TIMEOUT"
            is javax.net.ssl.SSLException -> "TLS"
            is java.io.IOException -> "IO"
            is IllegalArgumentException, is IllegalStateException -> "VALIDATION"
            else -> "OTHER"
        }
        emit("probeFailure=$kind") // Never exception message, response, URL, or endpoint identity.
    }
    private fun emit(value: String) { try { log(value) } catch (_: Throwable) { } }
}
