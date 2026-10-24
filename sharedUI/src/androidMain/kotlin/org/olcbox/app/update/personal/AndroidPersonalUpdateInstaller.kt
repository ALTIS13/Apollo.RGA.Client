package org.olcbox.app.update.personal

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException

enum class PersonalInstallRefusal { OWNER_ACTION_REQUIRED, AUTHORITY_DENIED, ARTIFACT_REJECTED, SYSTEM_UNAVAILABLE }
sealed interface PersonalInstallResult {
    data object HandedOff : PersonalInstallResult
    data class PermissionRequired(val settingsIntent: Intent) : PersonalInstallResult
    data class Refused(val reason: PersonalInstallRefusal) : PersonalInstallResult
}
class AndroidPersonalUpdateInstaller internal constructor(private val context: Context,
    private val verifier: AndroidPersonalApkVerifier, private val contentUri: (File) -> Uri) {
    constructor(context: Context, verifier: AndroidPersonalApkVerifier) : this(context, verifier,
        { file -> androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) })
    /** HandedOff only means Android received the explicit request, never installed-success. */
    @Suppress("DEPRECATION")
    fun install(apk: VerifiedPersonalApk, ownerAction: Boolean,
        authorityFence: (action: () -> Unit) -> Boolean): PersonalInstallResult {
        if (!ownerAction) return refused(PersonalInstallRefusal.OWNER_ACTION_REQUIRED)
        // Hashes and archive reads are deliberately outside the selected-session monitor.
        val file = verifier.installFile(apk) ?: return refused(PersonalInstallRefusal.ARTIFACT_REJECTED)
        var result: PersonalInstallResult = refused(PersonalInstallRefusal.AUTHORITY_DENIED)
        var entered = false
        return try {
            val allowed = authorityFence {
                check(!entered) { "Invalid update install fence" }; entered = true
                if (!verifier.stillInstallable(apk, file)) result = refused(PersonalInstallRefusal.ARTIFACT_REJECTED)
                else if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) result = PersonalInstallResult.PermissionRequired(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else {
                    val uri = contentUri(file)
                    if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider") {
                        result = refused(PersonalInstallRefusal.SYSTEM_UNAVAILABLE)
                    } else if (!verifier.stillInstallable(apk, file)) {
                        result = refused(PersonalInstallRefusal.ARTIFACT_REJECTED)
                    } else {
                        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // Live authority covers the actual handoff, not merely Intent preparation.
                        context.startActivity(installIntent)
                        result = PersonalInstallResult.HandedOff
                    }
                }
            }
            if (allowed && entered) result else refused(PersonalInstallRefusal.AUTHORITY_DENIED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { refused(PersonalInstallRefusal.SYSTEM_UNAVAILABLE) }
    }
    private fun refused(reason: PersonalInstallRefusal) = PersonalInstallResult.Refused(reason)
}
