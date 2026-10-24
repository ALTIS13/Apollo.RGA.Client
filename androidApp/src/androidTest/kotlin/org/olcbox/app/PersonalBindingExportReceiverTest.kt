package org.olcbox.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PermissionInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.debug.PersonalBindingExportReceiver

/** Verifies that a personal debug binding can be provisioned without exporting its private key. */
@RunWith(AndroidJUnit4::class)
class PersonalBindingExportReceiverTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file: File get() = File(context.noBackupFilesDir, "olcrtc_personal_binding_v1.json")

    @Test
    @Suppress("DEPRECATION") // `protectionLevel` is the API-23-compatible permission check.
    fun manifestRequiresSignatureDumpPermissionForExport() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, PersonalBindingExportReceiver::class.java), 0
        )
        assertTrue(receiver.exported)
        assertEquals(Manifest.permission.DUMP, receiver.permission)

        val permission = context.packageManager.getPermissionInfo(Manifest.permission.DUMP, 0)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        )
    }

    @Test
    fun exportWritesOnlyTheCurrentPublicBindingForPersonalDebugPackage() {
        file.delete()
        assertFalse("Stale binding prevented a meaningful receiver test", file.exists())
        PersonalBindingExportReceiver().onReceive(
            context, Intent(PersonalBindingExportReceiver.ACTION_EXPORT)
        )

        if (context.packageName != PERSONAL_PACKAGE) {
            assertFalse("Ordinary debug package must not create a binding file", file.exists())
            return
        }

        assertTrue("Personal debug package did not create binding file", file.isFile)
        val payload = JSONObject(file.readText())
        assertEquals(setOf("installationId", "publicKeySha256"), payload.keys().asSequence().toSet())
        assertTrue(payload.getString("installationId").matches(Regex("[A-Za-z0-9_-]{16,128}")))

        val expected = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getCertificate("org.olcbox.app.olcrtc_personal_join_p256_v1")
            .publicKey.encoded.let { der ->
                MessageDigest.getInstance("SHA-256").digest(der)
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
        assertEquals(expected, payload.getString("publicKeySha256"))
    }

    @Test
    fun unrelatedBroadcastActionDoesNotCreateBindingFile() {
        val before = file.takeIf { it.isFile }?.readBytes()
        PersonalBindingExportReceiver().onReceive(context, Intent("unrelated.action"))
        val after = file.takeIf { it.isFile }?.readBytes()
        if (before == null) assertFalse(file.exists()) else assertArrayEquals(before, after)
    }

    private companion object {
        const val PERSONAL_PACKAGE = "tech.gatealtas.rga.personal"
    }
}
