package org.olcbox.app.update.personal

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The Activity gate creates no disk/network dependencies and starts no check. */
class AndroidPersonalUpdateRuntimeFactory private constructor(private val context: Context,
    private val pinnedSpki: ByteArray, private val routeSupplier: () -> PersonalUpdateFetchRoute) : AndroidOwnServerUpdateRuntimeFactory {
    override val publicOwnServer = false
    private val mutex = Mutex()
    private var controller: PersonalUpdateController? = null

    override suspend fun createController(): PersonalUpdateController = withContext(Dispatchers.IO) {
        mutex.withLock {
            controller ?: run {
                val app = context.applicationContext
                val verifier = AndroidPersonalApkVerifier(app)
                PersonalUpdateController(pinnedSpki, verifier::readBaseline,
                    PersonalUpdateHighWaterStore(File(app.filesDir, "personal-update-high-water-v1")),
                    verifier, AndroidPersonalUpdateInstaller(app, verifier), routeSupplier,
                    AndroidPersonalUpdateLastCheckStore(app), { System.currentTimeMillis() / 1000 },
                    diagnostics = if (Log.isLoggable(PERSONAL_UPDATE_DIAGNOSTIC_TAG, Log.DEBUG))
                        PersonalUpdateDiagnosticRecords.newOwner() else null)
                    .also { controller = it }
            }
        }
    }
    companion object {
        fun create(context: Context, enabled: Boolean, compiledVariant: String,
            pinnedSpkiBase64: String, routeSupplier: () -> PersonalUpdateFetchRoute): AndroidPersonalUpdateRuntimeFactory? {
            if (!enabled || compiledVariant != "githubDebug" || context.packageName != "tech.gatealtas.rga.personal") return null
            val pin = decodeP256(pinnedSpkiBase64) ?: return null
            return AndroidPersonalUpdateRuntimeFactory(context, pin, routeSupplier)
        }
        private fun decodeP256(encoded: String): ByteArray? {
            return try {
                if (encoded.length !in 1..512) return null
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                if (Base64.encodeToString(bytes, Base64.NO_WRAP) != encoded) return null
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey ?: return null
                val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                    .getParameterSpec(ECParameterSpec::class.java)
                bytes.takeIf { it.contentEquals(key.encoded) && key.params.curve == expected.curve &&
                    key.params.generator == expected.generator && key.params.order == expected.order &&
                    key.params.cofactor == expected.cofactor }
            } catch (_: Exception) { null }
        }
    }
}
