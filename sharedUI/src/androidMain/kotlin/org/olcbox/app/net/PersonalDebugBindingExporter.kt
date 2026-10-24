package org.olcbox.app.net

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

/** Public only for the debug module; ordinary packages always refuse the export. */
object PersonalDebugBindingExporter {
    @JvmStatic
    fun writeForPersonalDebug(context: Context): Boolean {
        val app = context.applicationContext
        if (app.packageName != "tech.gatealtas.rga.personal" ||
            AndroidRuntimeLocationPolicy.forApp(app) != RuntimeLocationPolicy.ExperimentalDebug
        ) return false
        return try {
            val identity = AndroidKeystoreInstallationIdentity.forApp(app).loadOrCreate()
            val der = identity.publicKeyDer
            canonicalP256PublicKey(der)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(der)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val payload = JSONObject()
                .put("installationId", identity.installationId)
                .put("publicKeySha256", fingerprint)
                .toString().toByteArray(Charsets.UTF_8)
            val file = AtomicFile(File(app.noBackupFilesDir, "olcrtc_personal_binding_v1.json"))
            var stream: FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(payload)
                file.finishWrite(stream)
                true
            } catch (_: Exception) {
                stream?.let { file.failWrite(it) }
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
