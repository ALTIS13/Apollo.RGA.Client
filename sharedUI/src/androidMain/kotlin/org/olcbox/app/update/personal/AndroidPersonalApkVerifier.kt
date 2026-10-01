package org.olcbox.app.update.personal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.ZipFile

sealed interface VerifiedPersonalApk
enum class PersonalApkRefusal { FILE, BASELINE, METADATA, EXPIRED, UNSUPPORTED_PLATFORM }
sealed interface PersonalApkVerification {
    data class Verified(val apk: VerifiedPersonalApk) : PersonalApkVerification
    data class Refused(val reason: PersonalApkRefusal) : PersonalApkVerification
}
sealed interface PersonalBaselineRead {
    data class Ready(val baseline: PersonalUpdateBaseline) : PersonalBaselineRead
    data object Refused : PersonalBaselineRead
}
internal data class PersonalApkMetadata(val packageName: String, val versionCode: Long,
    val certSha256: String, val minSdk: Int, val abis: Set<String>, val source: File? = null)
internal interface PersonalApkMetadataReader {
    fun installed(packageName: String): PersonalApkMetadata?
    fun archive(file: File): PersonalApkMetadata?
}
class AndroidPersonalApkVerifier internal constructor(
    private val cacheDir: File, private val reader: PersonalApkMetadataReader,
    private val sdk: Int, private val abis: Set<String>, private val now: () -> Long,
) {
    constructor(context: Context) : this(File(context.applicationContext.cacheDir, "updates/personal"),
        PackageManagerMetadataReader(context.applicationContext.packageManager), Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS.toSet(), { System.currentTimeMillis() / 1000 })

    private val downloads = mutableSetOf<String>()
    private val tokens = Collections.newSetFromMap(IdentityHashMap<VerifiedPersonalApk, Boolean>())
    private class Frozen(val file: File, val verified: PersonalUpdateVerification.Success,
        val baseline: PersonalUpdateBaseline, val fileStamp: SourceStamp,
        val companionStamp: SourceStamp) : VerifiedPersonalApk
    private data class SourceStamp(val path: String, val size: Long, val modified: Long)
    private fun stamp(file: File) = SourceStamp(file.canonicalPath, file.length(), file.lastModified())

    fun readBaseline(): PersonalBaselineRead = try {
        if (sdk < 24) return PersonalBaselineRead.Refused
        val main = reader.installed(PACKAGE) ?: return PersonalBaselineRead.Refused
        val companion = reader.installed(COMPANION) ?: return PersonalBaselineRead.Refused
        val source = companion.source ?: return PersonalBaselineRead.Refused
        if (main.packageName != PACKAGE || main.versionCode < 1 || main.certSha256 != CERT ||
            companion.packageName != COMPANION || companion.versionCode != 0L || companion.certSha256 != CERT ||
            !source.isFile || source.length() !in 1..MAX_BYTES || "arm64-v8a" !in abis
        ) return PersonalBaselineRead.Refused
        PersonalBaselineRead.Ready(PersonalUpdateBaseline(main.packageName, main.versionCode, main.certSha256,
            sdk, abis.toSet(), companion.packageName, companion.versionCode, sha256(source), companion.certSha256))
    } catch (_: Exception) { PersonalBaselineRead.Refused }

    @Synchronized fun newDownloadFile(): File {
        check(cacheDir.mkdirs() || cacheDir.isDirectory) { "Personal update cache unavailable" }
        return File.createTempFile("download-", ".apk", cacheDir).also { downloads += it.canonicalPath }
    }

    @Synchronized
    fun verifyApk(file: File, verifiedManifest: PersonalUpdateVerification.Success,
        baseline: PersonalUpdateBaseline): PersonalApkVerification {
        if (sdk < 24) return refused(PersonalApkRefusal.UNSUPPORTED_PLATFORM)
        var frozen: File? = null
        return try {
            if (!owned(file) || file.canonicalPath !in downloads || !file.isFile) return refused(PersonalApkRefusal.FILE)
            if (!currentBaseline(baseline)) return refused(PersonalApkRefusal.BASELINE)
            if (!validTime(verifiedManifest.manifest)) return refused(PersonalApkRefusal.EXPIRED)
            if (!bytesMatch(file, verifiedManifest.manifest)) return refused(PersonalApkRefusal.FILE)
            frozen = File.createTempFile("verified-", ".apk", cacheDir)
            // Never lend the caller's download inode to the system installer.
            file.inputStream().use { input -> frozen.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; if (total > verifiedManifest.manifest.apkBytes) return refused(PersonalApkRefusal.FILE)
                    output.write(buffer, 0, n)
                }
            } }
            if (!bytesMatch(frozen, verifiedManifest.manifest)) return refused(PersonalApkRefusal.FILE)
            if (!metadataMatch(frozen, verifiedManifest.manifest, baseline)) return refused(PersonalApkRefusal.METADATA)
            if (!frozen.setReadOnly()) return refused(PersonalApkRefusal.FILE)
            // Closing the write stream and chmod precede the final digest/metadata boundary.
            if (!bytesMatch(frozen, verifiedManifest.manifest)) return refused(PersonalApkRefusal.FILE)
            if (!currentBaseline(baseline)) return refused(PersonalApkRefusal.BASELINE)
            val companionSource = reader.installed(COMPANION)?.source ?: return refused(PersonalApkRefusal.BASELINE)
            val token = Frozen(frozen, verifiedManifest, baseline, stamp(frozen), stamp(companionSource))
            tokens += token
            frozen = null // Accepted file remains frozen until consciously handed to Android.
            PersonalApkVerification.Verified(token)
        } catch (_: Exception) { refused(PersonalApkRefusal.FILE) }
        finally { frozen?.let { it.setWritable(true); it.delete() } }
    }

    @Synchronized fun revalidate(apk: VerifiedPersonalApk): PersonalApkVerification {
        val frozen = apk as? Frozen ?: return refused(PersonalApkRefusal.FILE)
        if (apk !in tokens) return refused(PersonalApkRefusal.FILE)
        return try {
            if (!owned(frozen.file) || !bytesMatch(frozen.file, frozen.verified.manifest)) return refused(PersonalApkRefusal.FILE)
            if (!validTime(frozen.verified.manifest)) return refused(PersonalApkRefusal.EXPIRED)
            if (!currentBaseline(frozen.baseline)) return refused(PersonalApkRefusal.BASELINE)
            if (!metadataMatch(frozen.file, frozen.verified.manifest, frozen.baseline)) return refused(PersonalApkRefusal.METADATA)
            PersonalApkVerification.Verified(apk)
        } catch (_: Exception) { refused(PersonalApkRefusal.FILE) }
    }

    /** No public file accessor: installer gets a file only through the gate, after revalidation. */
    @Synchronized internal fun installFile(apk: VerifiedPersonalApk): File? =
        if (revalidate(apk) is PersonalApkVerification.Verified) (apk as Frozen).file else null

    /** Full digest validation is done immediately outside the service monitor.
     * These cheap final checks run inside it; accepted files have no writable producer. */
    @Synchronized internal fun stillInstallable(apk: VerifiedPersonalApk, file: File): Boolean = try {
        val token = apk as? Frozen
        if (token == null || token !in tokens || file != token.file || !owned(file) ||
            !file.isFile || file.canWrite() || stamp(file) != token.fileStamp || !validTime(token.verified.manifest)) false
        else {
            val main = reader.installed(PACKAGE)
            val companion = reader.installed(COMPANION)
            main?.packageName == token.baseline.packageName && main.versionCode == token.baseline.versionCode &&
                main.certSha256 == token.baseline.apkCertSha256 && companion?.packageName == token.baseline.companionPackage &&
                companion.versionCode == token.baseline.companionVersionCode && companion.certSha256 == token.baseline.companionCertSha256 &&
                companion.source?.let { it.isFile && stamp(it) == token.companionStamp } == true
        }
    } catch (_: Exception) { false }

    private fun owned(file: File): Boolean = file.absoluteFile == file.canonicalFile &&
        file.canonicalFile.parentFile == cacheDir.canonicalFile
    private fun currentBaseline(baseline: PersonalUpdateBaseline): Boolean =
        (readBaseline() as? PersonalBaselineRead.Ready)?.baseline == baseline
    private fun bytesMatch(file: File, manifest: PersonalUpdateManifest): Boolean = file.isFile &&
        manifest.apkBytes in 1..MAX_BYTES && file.length() == manifest.apkBytes && sha256(file) == manifest.apkSha256
    private fun validTime(manifest: PersonalUpdateManifest): Boolean {
        val at = now()
        return at >= 0 && manifest.expiresAt > at && manifest.issuedAt >= 0 &&
            (manifest.issuedAt <= at || manifest.issuedAt - at <= 300) &&
            manifest.expiresAt > manifest.issuedAt && manifest.expiresAt - manifest.issuedAt <= 604800
    }
    private fun metadataMatch(file: File, m: PersonalUpdateManifest, b: PersonalUpdateBaseline): Boolean {
        val archive = reader.archive(file) ?: return false
        return m.packageName == PACKAGE && archive.packageName == m.packageName &&
            archive.versionCode == m.versionCode && m.versionCode > b.versionCode &&
            m.apkCertSha256 == b.apkCertSha256 && archive.certSha256 == m.apkCertSha256 &&
            archive.minSdk == m.minSdk && m.minSdk in 23..sdk && m.abi == "arm64-v8a" &&
            archive.abis == setOf(m.abi) && zipAbis(file) == setOf(m.abi) && m.abi in abis &&
            m.apkPath == "/updates/personal/v1/${m.versionCode}/apollo-rga-personal.apk" &&
            m.companionPackage == b.companionPackage && m.companionVersionCode == b.companionVersionCode &&
            m.companionSha256 == b.companionSha256 && m.companionCertSha256 == b.companionCertSha256
    }
    private fun refused(reason: PersonalApkRefusal) = PersonalApkVerification.Refused(reason)

    private class PackageManagerMetadataReader(val manager: PackageManager) : PersonalApkMetadataReader {
        override fun installed(packageName: String): PersonalApkMetadata? {
            val info = manager.getPackageInfo(packageName, flags())
            // v1 intentionally does not accept split packages or signer rotation/multiple signers.
            if (!info.applicationInfo?.splitSourceDirs.isNullOrEmpty()) return null
            return metadata(info)?.copy(source = info.applicationInfo?.sourceDir?.let(::File))
        }
        override fun archive(file: File): PersonalApkMetadata? {
            val info = manager.getPackageArchiveInfo(file.absolutePath, flags()) ?: return null
            return metadata(info)?.copy(abis = zipAbis(file))
        }
        @Suppress("DEPRECATION")
        private fun flags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        private fun metadata(info: PackageInfo): PersonalApkMetadata? {
            if (Build.VERSION.SDK_INT < 24) return null
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val signing = info.signingInfo ?: return null
                if (signing.hasMultipleSigners() || signing.signingCertificateHistory?.size != 1) return null
                signing.apkContentsSigners
            } else info.signatures
            if (signatures?.size != 1) return null
            val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val application = info.applicationInfo ?: return null
            return PersonalApkMetadata(info.packageName, version,
                hex(MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())),
                application.minSdkVersion, emptySet())
        }
    }
    companion object {
        private const val PACKAGE = "tech.gatealtas.rga.personal"
        private const val COMPANION = "tech.gatealtas.rga.personal.test"
        private const val CERT = "11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017"
        private const val MAX_BYTES = 268435456L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; if (total > MAX_BYTES) throw IllegalArgumentException("Invalid APK size")
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }
        private fun zipAbis(file: File): Set<String> = ZipFile(file).use { zip ->
            val result = mutableSetOf<String>(); val entries = zip.entries(); var count = 0
            while (entries.hasMoreElements()) {
                if (++count > 100000) throw IllegalArgumentException("Invalid APK archive")
                val entry = entries.nextElement(); val name = entry.name
                if (name.length > 4096) throw IllegalArgumentException("Invalid APK archive")
                if (!entry.isDirectory && name.startsWith("lib/")) {
                    val parts = name.split('/')
                    if (parts.size != 3 || parts[2].isEmpty() || !parts[2].endsWith(".so")) throw IllegalArgumentException("Invalid APK archive")
                    result += parts[1]
                }
            }
            result.toSet()
        }
    }
}
