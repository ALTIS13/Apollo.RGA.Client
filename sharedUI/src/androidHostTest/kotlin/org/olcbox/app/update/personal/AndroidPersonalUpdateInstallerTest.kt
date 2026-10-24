package org.olcbox.app.update.personal

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class AndroidPersonalUpdateInstallerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = object : ContextWrapper(app) {
        override fun getPackageName() = AndroidPersonalApkVerifierTest.PACKAGE
    }
    @Test fun constructionAndMissingOwnerActionNeverOpenUi() {
        val f = AndroidPersonalApkVerifierTest.Fixture()
        val token = (f.verify() as PersonalApkVerification.Verified).apk
        val installer = AndroidPersonalUpdateInstaller(context, f.gate)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(PersonalInstallResult.Refused(PersonalInstallRefusal.OWNER_ACTION_REQUIRED),
            installer.install(token, false) { action -> action(); true })
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun missingPermissionOnlyReturnsConsciousSettingsIntentWithoutOpeningIt() {
        val f = AndroidPersonalApkVerifierTest.Fixture(); val token = (f.verify() as PersonalApkVerification.Verified).apk
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val result = AndroidPersonalUpdateInstaller(context, f.gate).install(token, true) { action -> action(); true }
        assertTrue(result is PersonalInstallResult.PermissionRequired)
        val intent = (result as PersonalInstallResult.PermissionRequired).settingsIntent
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:tech.gatealtas.rga.personal", intent.data.toString())
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun changedArtifactOrDeniedAuthorityNeverLaunchesInstaller() {
        val f = AndroidPersonalApkVerifierTest.Fixture(); val token = (f.verify() as PersonalApkVerification.Verified).apk
        val installer = AndroidPersonalUpdateInstaller(context, f.gate)
        assertEquals(PersonalInstallResult.Refused(PersonalInstallRefusal.AUTHORITY_DENIED), installer.install(token, true) { false })
        f.companion.writeText("changed companion")
        assertEquals(PersonalInstallResult.Refused(PersonalInstallRefusal.ARTIFACT_REJECTED), installer.install(token, true) { action -> action(); true })
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun permittedOwnerActionHandsOnlyValidatedContentUriToSystem() {
        val f = AndroidPersonalApkVerifierTest.Fixture(); val token = (f.verify() as PersonalApkVerification.Verified).apk
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        // Host library has no app FileProvider XML. Only URI boundary is injected;
        // admission, actual bytes, Android Intent and startActivity remain real.
        val installer = AndroidPersonalUpdateInstaller(context, f.gate) { file ->
            assertTrue(file.name.startsWith("verified-"))
            assertTrue(file.canonicalPath.startsWith(f.dir.canonicalPath + java.io.File.separator))
            Uri.parse("content://tech.gatealtas.rga.personal.fileprovider/updates/personal/${file.name}")
        }
        val result = installer.install(token, true) { action -> action(); true }
        assertEquals(PersonalInstallResult.HandedOff, result)
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("tech.gatealtas.rga.personal.fileprovider", intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }
    @Test fun changeAfterDigestBeforeActualHandoffIsRefusedInsideFence() {
        val f = AndroidPersonalApkVerifierTest.Fixture(); val token = (f.verify() as PersonalApkVerification.Verified).apk
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val installer = AndroidPersonalUpdateInstaller(context, f.gate) { file ->
            Uri.parse("content://tech.gatealtas.rga.personal.fileprovider/updates/personal/${file.name}")
        }
        val result = installer.install(token, true) { action ->
            val frozen = f.dir.listFiles()!!.single { it.name.startsWith("verified-") }
            frozen.setWritable(true); frozen.appendText("late mutation")
            action(); true
        }
        assertEquals(PersonalInstallResult.Refused(PersonalInstallRefusal.ARTIFACT_REJECTED), result)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun missingAppFileProviderReturnsFixedFailureWithoutLaunch() {
        val f = AndroidPersonalApkVerifierTest.Fixture(); val token = (f.verify() as PersonalApkVerification.Verified).apk
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals(PersonalInstallResult.Refused(PersonalInstallRefusal.SYSTEM_UNAVAILABLE),
            AndroidPersonalUpdateInstaller(context, f.gate).install(token, true) { action -> action(); true })
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
