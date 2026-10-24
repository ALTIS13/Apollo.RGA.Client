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

/** Compatibility USB transport: exact DER pin plus platform IP SAN verification. */
internal class AndroidPinnedLoopbackBootstrapTransport(
    pinnedCertificateSha256: String
) : PersonalBootstrapHttpTransport by AndroidPersonalBootstrapTransport(
    PersonalBootstrapEndpoint.usb(pinnedCertificateSha256)
)

/** Remote keeps platform CA/hostname validation; USB alone overrides certificate trust. */
internal class AndroidPersonalBootstrapTransport(
    private val endpoint: PersonalBootstrapEndpoint,
    private val openConnection: (URL) -> HttpsURLConnection = { url ->
        url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
    }
) : PersonalBootstrapHttpTransport {
    private val usbSocketFactory = endpoint.usbCertificatePin?.let { pin ->
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(ExactCertificateTrustManager(pin)), SecureRandom())
        }.socketFactory
    }

    override fun post(path: String, body: ByteArray): PersonalBootstrapHttpResponse? {
        val url = endpoint.urlFor(path) ?: return null
        if (body.isEmpty() || body.size > MAX_REQUEST_BYTES) return null
        var connection: HttpsURLConnection? = null
        return try {
            connection = openConnection(url).apply {
                // Never override hostnameVerifier in either mode. Public CA trust
                // stays untouched remotely; USB cert must carry IP SAN 127.0.0.1.
                usbSocketFactory?.let { sslSocketFactory = it }
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
