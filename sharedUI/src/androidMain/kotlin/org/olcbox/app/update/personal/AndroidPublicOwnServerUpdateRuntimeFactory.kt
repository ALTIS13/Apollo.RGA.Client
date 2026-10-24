package org.olcbox.app.update.personal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Base64
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

/** githubRelease only. No key/baseline/network/disk work while the binding is absent. */
class AndroidPublicOwnServerUpdateRuntimeFactory private constructor(
    private val context: Context, private val pin: ByteArray,
    private val profile: OwnServerUpdateProfile.PublicOwnServer,
    private val routeSupplier: (OwnServerUpdateProfile.PublicOwnServer) -> PersonalUpdateFetchRoute,
) : AndroidOwnServerUpdateRuntimeFactory {
    override val publicOwnServer = true
    private val mutex = Mutex()
    private var controller: PersonalUpdateController? = null

    override suspend fun createController(): PersonalUpdateController = withContext(Dispatchers.IO) {
        mutex.withLock {
            controller ?: run {
                val app = context.applicationContext
                val verifier = AndroidPersonalApkVerifier(app, profile)
                PersonalUpdateController(pin, verifier::readBaseline,
                    PersonalUpdateHighWaterStore(File(app.filesDir, profile.highWaterName), profile),
                    verifier, AndroidPersonalUpdateInstaller(app, verifier), { routeSupplier(profile) },
                    AndroidPersonalUpdateLastCheckStore(app, profile), { System.currentTimeMillis() / 1000 },
                    profile = profile).also { controller = it }
            }
        }
    }

    companion object {
        fun create(context: Context, enabled: Boolean, compiledVariant: String, pinnedSpkiBase64: String,
            releaseSignerSha256: String, debuggableBuild: Boolean,
            routeSupplier: (OwnServerUpdateProfile.PublicOwnServer) -> PersonalUpdateFetchRoute,
        ): AndroidPublicOwnServerUpdateRuntimeFactory? {
            if (!enabled || compiledVariant != "githubRelease" || context.packageName != "tech.gatealtas.rga" ||
                debuggableBuild || context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
                Build.VERSION.SDK_INT !in 31..37 || "arm64-v8a" !in Build.SUPPORTED_ABIS) return null
            val profile = OwnServerUpdateProfile.publicBinding(releaseSignerSha256) ?: return null
            val pin = decodeP256(pinnedSpkiBase64) ?: return null
            return AndroidPublicOwnServerUpdateRuntimeFactory(context.applicationContext, pin, profile, routeSupplier)
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
