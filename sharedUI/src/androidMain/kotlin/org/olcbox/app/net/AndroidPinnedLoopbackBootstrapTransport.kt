package org.olcbox.app.net

import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Personal USB test only: ADB reverse exposes this fixed loopback port, never a remote Bridge. */
internal class AndroidPinnedLoopbackBootstrapTransport(
    pinnedCertificateSha256: String
) : PersonalBootstrapHttpTransport {
    private val trustManager = ExactCertificateTrustManager(pinnedCertificateSha256)
    private val socketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }.socketFactory

    override fun post(path: String, body: ByteArray): PersonalBootstrapHttpResponse? {
        if (path !in PATHS || body.isEmpty() || body.size > MAX_REQUEST_BYTES) return null
        var connection: HttpsURLConnection? = null
        return try {
            connection = (URL("https://127.0.0.1:18443$path")
                .openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                // Keep platform hostname verification: the certificate must carry IP SAN 127.0.0.1.
                sslSocketFactory = socketFactory
                instanceFollowRedirects = false
                connectTimeout = 3_000
                readTimeout = 5_000
                useCaches = false
                doOutput = true
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
                setFixedLengthStreamingMode(body.size)
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..599 || code in 300..399) return null
            val responseBytes = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(1_024)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) return null
                        output.write(chunk, 0, count)
                    }
                    output.toByteArray()
                } ?: return null
            PersonalBootstrapHttpResponse(
                code, connection.contentType, responseBytes, connection.getHeaderField("Cache-Control")
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        val PATHS = setOf("/v1/personal/link/begin", "/v1/personal/bootstrap")
        const val MAX_REQUEST_BYTES = 4_096
        const val MAX_RESPONSE_BYTES = 8_192
    }
}

/** A self-signed loopback certificate is trusted only by exact DER pin and validity interval. */
internal class ExactCertificateTrustManager(pinnedCertificateSha256: String) : X509TrustManager {
    private val pin = pinnedCertificateSha256.takeIf { HEX.matches(it) }?.chunked(2)
        ?.map { it.toInt(16).toByte() }?.toByteArray()
        ?: throw IllegalArgumentException("Personal bootstrap certificate pin unavailable")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain == null || chain.size != 1 || authType.isNullOrBlank()) {
            throw CertificateException("Untrusted personal bootstrap certificate")
        }
        try {
            chain[0].checkValidity()
            val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            if (!MessageDigest.isEqual(actual, pin)) {
                throw CertificateException("Untrusted personal bootstrap certificate")
            }
        } catch (_: Exception) {
            throw CertificateException("Untrusted personal bootstrap certificate")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificate authentication is not configured")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        val HEX = Regex("[0-9a-f]{64}")
    }
}
