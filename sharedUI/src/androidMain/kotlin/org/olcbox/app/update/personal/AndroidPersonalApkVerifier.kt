package org.olcbox.app.update.personal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
    val certSha256: String, val minSdk: Int, val abis: Set<String>, val source: File? = null,
    val channel: String? = null)
internal interface PersonalApkMetadataReader {
    fun installed(packageName: String): PersonalApkMetadata?
    fun archive(file: File): PersonalApkMetadata?
}
class AndroidPersonalApkVerifier internal constructor(
    cacheDir: File, private val reader: PersonalApkMetadataReader,
    private val sdk: Int, private val abis: Set<String>, private val now: () -> Long,
    private val profile: OwnServerUpdateProfile,
) {
    internal constructor(cacheDir: File, reader: PersonalApkMetadataReader, sdk: Int,
        abis: Set<String>, now: () -> Long) : this(cacheDir, reader, sdk, abis, now, OwnServerUpdateProfile.Personal)
    // Normalize the producer root, never caller-supplied APK paths; owned() stays strict.
    private val cacheDir = cacheDir.canonicalFile

    constructor(context: Context, profile: OwnServerUpdateProfile = OwnServerUpdateProfile.Personal) :
        this(File(context.applicationContext.cacheDir, profile.cacheName),
            PackageManagerMetadataReader(context.applicationContext.packageManager, profile), Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.toSet(), { System.currentTimeMillis() / 1000 }, profile)

    private val downloads = mutableSetOf<String>()
    private val tokens = Collections.newSetFromMap(IdentityHashMap<VerifiedPersonalApk, Boolean>())
    private class Frozen(val file: File, val verified: PersonalUpdateVerification.Success,
        val baseline: PersonalUpdateBaseline, val fileStamp: SourceStamp,
        val companionStamp: SourceStamp) : VerifiedPersonalApk
    private data class SourceStamp(val path: String, val size: Long, val modified: Long)
    private fun stamp(file: File) = SourceStamp(file.canonicalPath, file.length(), file.lastModified())

    fun readBaseline(): PersonalBaselineRead {
        return try {
            if (sdk < 24) return PersonalBaselineRead.Refused
            val main = reader.installed(profile.packageName) ?: return PersonalBaselineRead.Refused
            if (profile is OwnServerUpdateProfile.PublicOwnServer) {
                val source = main.source ?: return PersonalBaselineRead.Refused
                if (sdk !in 31..37 || main.packageName != profile.packageName || main.versionCode < 1 ||
                    main.certSha256 != profile.releaseSignerSha256 || main.channel != profile.channel ||
                    !source.isFile || source.length() !in 1..MAX_BYTES || "arm64-v8a" !in abis)
                    return PersonalBaselineRead.Refused
                return PersonalBaselineRead.Ready(PersonalUpdateBaseline(main.packageName, main.versionCode,
                    main.certSha256, sdk, abis.toSet(), "", 0, "", "", profile.channel, sha256(source)))
            }
            val companion = reader.installed(COMPANION) ?: return PersonalBaselineRead.Refused
            val source = companion.source ?: return PersonalBaselineRead.Refused
            if (main.packageName != PACKAGE || main.versionCode < 1 || main.certSha256 != CERT ||
                companion.packageName != COMPANION || companion.versionCode != 0L || companion.certSha256 != CERT ||
                !source.isFile || source.length() !in 1..MAX_BYTES || "arm64-v8a" !in abis
            ) return PersonalBaselineRead.Refused
            PersonalBaselineRead.Ready(PersonalUpdateBaseline(main.packageName, main.versionCode, main.certSha256,
                sdk, abis.toSet(), companion.packageName, companion.versionCode, sha256(source), companion.certSha256))
        } catch (_: Exception) { PersonalBaselineRead.Refused }
    }

    @Synchronized fun newDownloadFile(): File {
        check(cacheDir.mkdirs() || cacheDir.isDirectory) { "Personal update cache unavailable" }
        return File.createTempFile("download-", ".apk", cacheDir).also { downloads += it.canonicalPath }
    }

    @Synchronized
    fun verifyApk(file: File, verifiedManifest: PersonalUpdateVerification.Success,
        baseline: PersonalUpdateBaseline): PersonalApkVerification {
        if (sdk < 24) return refused(PersonalApkRefusal.UNSUPPORTED_PLATFORM)
        var frozen: File? = null
        var failureStage = Diagnostic.EXCEPTION_OWNERSHIP
        return try {
            if (!fileCheck(owned(file), Diagnostic.OWNERSHIP) ||
                !fileCheck(file.canonicalPath in downloads, Diagnostic.DOWNLOAD_MEMBERSHIP) ||
                !fileCheck(file.isFile, Diagnostic.IS_FILE)) return refused(PersonalApkRefusal.FILE)
            failureStage = Diagnostic.EXCEPTION_INITIAL_BASELINE
            if (!currentBaseline(baseline)) return refused(PersonalApkRefusal.BASELINE)
            failureStage = Diagnostic.EXCEPTION_TIME
            if (!validTime(verifiedManifest.manifest)) return refused(PersonalApkRefusal.EXPIRED)
            failureStage = Diagnostic.EXCEPTION_INITIAL_HASH
            if (!bytesMatch(file, verifiedManifest.manifest)) return refused(PersonalApkRefusal.FILE)
            failureStage = Diagnostic.EXCEPTION_BEFORE_COPY
            frozen = File.createTempFile("verified-", ".apk", cacheDir)
            failureStage = Diagnostic.EXCEPTION_COPY
            // Never lend the caller's download inode to the system installer.
            file.inputStream().use { input -> frozen.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; if (total > verifiedManifest.manifest.apkBytes) {
                        diagnostic(Diagnostic.COPY_SIZE); return refused(PersonalApkRefusal.FILE)
                    }
                    output.write(buffer, 0, n)
                }
            } }
            failureStage = Diagnostic.EXCEPTION_COPIED_HASH
            if (!bytesMatch(frozen, verifiedManifest.manifest)) return refused(PersonalApkRefusal.FILE)
            failureStage = Diagnostic.EXCEPTION_METADATA
            if (!metadataMatch(frozen, verifiedManifest.manifest, baseline)) return refused(PersonalApkRefusal.METADATA)
            failureStage = Diagnostic.EXCEPTION_CHMOD
            if (!fileCheck(frozen.setReadOnly(), Diagnostic.CHMOD)) return refused(PersonalApkRefusal.FILE)
            // Closing the write stream and chmod precede the final digest/metadata boundary.
            failureStage = Diagnostic.EXCEPTION_FINAL_HASH
            if (!bytesMatch(frozen, verifiedManifest.manifest)) return refused(PersonalApkRefusal.FILE)
            failureStage = Diagnostic.EXCEPTION_FINAL_BASELINE
            if (!currentBaseline(baseline)) return refused(PersonalApkRefusal.BASELINE)
            val companionSource = reader.installed(if (profile === OwnServerUpdateProfile.Personal) COMPANION
                else profile.packageName)?.source ?: return refused(PersonalApkRefusal.BASELINE)
            failureStage = Diagnostic.EXCEPTION_STAMP
            val token = Frozen(frozen, verifiedManifest, baseline, stamp(frozen), stamp(companionSource))
            tokens += token
            frozen = null // Accepted file remains frozen until consciously handed to Android.
            PersonalApkVerification.Verified(token)
        } catch (_: Exception) { diagnostic(failureStage); refused(PersonalApkRefusal.FILE) }
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
            val main = reader.installed(profile.packageName)
            if (profile is OwnServerUpdateProfile.PublicOwnServer) {
                main?.packageName == token.baseline.packageName && main.versionCode == token.baseline.versionCode &&
                    main.certSha256 == profile.releaseSignerSha256 && main.certSha256 == token.baseline.apkCertSha256 &&
                    main.channel == profile.channel && main.source?.let { it.isFile && stamp(it) == token.companionStamp } == true
            } else {
            val companion = reader.installed(COMPANION)
            main?.packageName == token.baseline.packageName && main.versionCode == token.baseline.versionCode &&
                main.certSha256 == token.baseline.apkCertSha256 && companion?.packageName == token.baseline.companionPackage &&
                companion.versionCode == token.baseline.companionVersionCode && companion.certSha256 == token.baseline.companionCertSha256 &&
                companion.source?.let { it.isFile && stamp(it) == token.companionStamp } == true
            }
        }
    } catch (_: Exception) { false }

    private fun owned(file: File): Boolean = file.absoluteFile == file.canonicalFile &&
        file.canonicalFile.parentFile == cacheDir.canonicalFile
    private fun currentBaseline(baseline: PersonalUpdateBaseline): Boolean =
        (readBaseline() as? PersonalBaselineRead.Ready)?.baseline == baseline
    private fun bytesMatch(file: File, manifest: PersonalUpdateManifest): Boolean =
        fileCheck(file.isFile, Diagnostic.BYTES_IS_FILE) &&
        fileCheck(manifest.apkBytes in 1..MAX_BYTES, Diagnostic.BYTES_SIZE_RANGE) &&
        fileCheck(file.length() == manifest.apkBytes, Diagnostic.BYTES_SIZE) &&
        fileCheck(sha256(file) == manifest.apkSha256, Diagnostic.BYTES_HASH)
    private fun fileCheck(matches: Boolean, code: Diagnostic): Boolean {
        if (!matches) diagnostic(code)
        return matches
    }
    private fun validTime(manifest: PersonalUpdateManifest): Boolean {
        val at = now()
        return at >= 0 && manifest.expiresAt > at && manifest.issuedAt >= 0 &&
            (manifest.issuedAt <= at || manifest.issuedAt - at <= 300) &&
            manifest.expiresAt > manifest.issuedAt && manifest.expiresAt - manifest.issuedAt <= 604800
    }
    private fun metadataMatch(file: File, m: PersonalUpdateManifest, b: PersonalUpdateBaseline): Boolean {
        val archive = reader.archive(file) ?: run { diagnostic(Diagnostic.ARCHIVE_NULL); return false }
        return metadataCheck(m.packageName == profile.packageName && archive.packageName == m.packageName, Diagnostic.PACKAGE) &&
            metadataCheck(profile !is OwnServerUpdateProfile.PublicOwnServer ||
                m.channel == profile.channel && archive.channel == profile.channel && b.channel == profile.channel &&
                m.apkCertSha256 == profile.releaseSignerSha256, Diagnostic.CERT) &&
            metadataCheck(archive.versionCode == m.versionCode && m.versionCode > b.versionCode, Diagnostic.VERSION) &&
            metadataCheck(m.apkCertSha256 == b.apkCertSha256 && archive.certSha256 == m.apkCertSha256, Diagnostic.CERT) &&
            metadataCheck(archive.minSdk == m.minSdk && m.minSdk in 23..sdk, Diagnostic.MIN_SDK) &&
            metadataCheck(m.abi == "arm64-v8a" && archive.abis == setOf(m.abi), Diagnostic.ABI) &&
            metadataCheck(zipAbis(file) == setOf(m.abi), Diagnostic.ZIP_ABI) &&
            metadataCheck(m.abi in abis, Diagnostic.ABI) &&
            metadataCheck(m.apkPath == profile.apkPath(m.versionCode), Diagnostic.PATH) &&
            metadataCheck(profile is OwnServerUpdateProfile.PublicOwnServer && profile.acceptsManifest(m) ||
            profile === OwnServerUpdateProfile.Personal && m.companionPackage == b.companionPackage && m.companionVersionCode == b.companionVersionCode &&
            m.companionSha256 == b.companionSha256 && m.companionCertSha256 == b.companionCertSha256, Diagnostic.COMPANION)
    }
    private enum class Diagnostic {
        FILE, BASELINE, METADATA, EXPIRED, UNSUPPORTED_PLATFORM,
        ARCHIVE_NULL, PACKAGE, VERSION, CERT, MIN_SDK, ABI, ZIP_ABI, ZIP_ABI_READ, PATH, COMPANION,
        ARCHIVE_INFO_NULL, METADATA_SDK, SIGNING_INFO_NULL, MULTIPLE_SIGNERS, SIGNING_HISTORY,
        SIGNATURE_COUNT, APPLICATION_INFO_NULL,
        OWNERSHIP, DOWNLOAD_MEMBERSHIP, IS_FILE, BYTES_IS_FILE, BYTES_SIZE_RANGE, BYTES_SIZE, BYTES_HASH,
        COPY_SIZE, CHMOD,
        EXCEPTION_OWNERSHIP, EXCEPTION_INITIAL_BASELINE, EXCEPTION_TIME, EXCEPTION_INITIAL_HASH,
        EXCEPTION_BEFORE_COPY, EXCEPTION_COPY, EXCEPTION_COPIED_HASH, EXCEPTION_METADATA,
        EXCEPTION_CHMOD, EXCEPTION_FINAL_HASH, EXCEPTION_FINAL_BASELINE, EXCEPTION_STAMP,
    }
    private fun metadataCheck(matches: Boolean, code: Diagnostic): Boolean {
        if (!matches) diagnostic(code)
        return matches
    }
    private fun refused(reason: PersonalApkRefusal): PersonalApkVerification.Refused {
        diagnostic(when (reason) {
            PersonalApkRefusal.FILE -> Diagnostic.FILE
            PersonalApkRefusal.BASELINE -> Diagnostic.BASELINE
            PersonalApkRefusal.METADATA -> Diagnostic.METADATA
            PersonalApkRefusal.EXPIRED -> Diagnostic.EXPIRED
            PersonalApkRefusal.UNSUPPORTED_PLATFORM -> Diagnostic.UNSUPPORTED_PLATFORM
        })
        return PersonalApkVerification.Refused(reason)
    }

    private class PackageManagerMetadataReader(val manager: PackageManager, val profile: OwnServerUpdateProfile) : PersonalApkMetadataReader {
        override fun installed(packageName: String): PersonalApkMetadata? {
            val info = manager.getPackageInfo(packageName, flags())
            // v1 intentionally does not accept split packages or signer rotation/multiple signers.
            if (!info.applicationInfo?.splitSourceDirs.isNullOrEmpty()) return null
            return metadata(info)?.copy(source = info.applicationInfo?.sourceDir?.let(::File))
        }
        override fun archive(file: File): PersonalApkMetadata? {
            val info = manager.getPackageArchiveInfo(file.absolutePath, flags()) ?: run {
                diagnostic(Diagnostic.ARCHIVE_INFO_NULL); return null
            }
            return metadata(info)?.copy(abis = zipAbis(file))
        }
        @Suppress("DEPRECATION")
        private fun flags(): Int = (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES) or
            (if (profile is OwnServerUpdateProfile.PublicOwnServer) PackageManager.GET_META_DATA else 0)
        @Suppress("DEPRECATION")
        private fun metadata(info: PackageInfo): PersonalApkMetadata? {
            if (Build.VERSION.SDK_INT < 24) { diagnostic(Diagnostic.METADATA_SDK); return null }
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val signing = info.signingInfo ?: run { diagnostic(Diagnostic.SIGNING_INFO_NULL); return null }
                if (signing.hasMultipleSigners()) { diagnostic(Diagnostic.MULTIPLE_SIGNERS); return null }
                if (signing.signingCertificateHistory?.size != 1) { diagnostic(Diagnostic.SIGNING_HISTORY); return null }
                signing.apkContentsSigners
            } else info.signatures
            if (signatures?.size != 1) { diagnostic(Diagnostic.SIGNATURE_COUNT); return null }
            val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val application = info.applicationInfo ?: run { diagnostic(Diagnostic.APPLICATION_INFO_NULL); return null }
            return PersonalApkMetadata(info.packageName, version,
                hex(MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())),
                application.minSdkVersion, emptySet(), channel =
                    if (profile is OwnServerUpdateProfile.PublicOwnServer)
                        application.metaData?.getString(OwnServerUpdateProfile.CHANNEL_METADATA) else null)
        }
    }
    companion object {
        private fun diagnostic(code: Diagnostic) {
            // No metadata values, paths or exception details, even with the tag explicitly enabled.
            runCatching {
                if (Log.isLoggable("RgaPersonalUpdate", Log.DEBUG)) Log.d("RgaPersonalUpdate", when (code) {
                    Diagnostic.FILE -> "APK_REFUSED_FILE"
                    Diagnostic.BASELINE -> "APK_REFUSED_BASELINE"
                    Diagnostic.METADATA -> "APK_REFUSED_METADATA"
                    Diagnostic.EXPIRED -> "APK_REFUSED_EXPIRED"
                    Diagnostic.UNSUPPORTED_PLATFORM -> "APK_REFUSED_UNSUPPORTED_PLATFORM"
                    Diagnostic.ARCHIVE_NULL -> "APK_METADATA_ARCHIVE_NULL"
                    Diagnostic.PACKAGE -> "APK_METADATA_PACKAGE_MISMATCH"
                    Diagnostic.VERSION -> "APK_METADATA_VERSION_MISMATCH"
                    Diagnostic.CERT -> "APK_METADATA_CERT_MISMATCH"
                    Diagnostic.MIN_SDK -> "APK_METADATA_MIN_SDK_MISMATCH"
                    Diagnostic.ABI -> "APK_METADATA_ABI_MISMATCH"
                    Diagnostic.ZIP_ABI -> "APK_METADATA_ZIP_ABI_MISMATCH"
                    Diagnostic.ZIP_ABI_READ -> "APK_METADATA_ZIP_ABI_READ_REFUSED"
                    Diagnostic.PATH -> "APK_METADATA_PATH_MISMATCH"
                    Diagnostic.COMPANION -> "APK_METADATA_COMPANION_MISMATCH"
                    Diagnostic.ARCHIVE_INFO_NULL -> "APK_METADATA_ARCHIVE_INFO_NULL"
                    Diagnostic.METADATA_SDK -> "APK_METADATA_SDK_REFUSED"
                    Diagnostic.SIGNING_INFO_NULL -> "APK_METADATA_SIGNING_INFO_NULL"
                    Diagnostic.MULTIPLE_SIGNERS -> "APK_METADATA_MULTIPLE_SIGNERS_REFUSED"
                    Diagnostic.SIGNING_HISTORY -> "APK_METADATA_SIGNING_HISTORY_REFUSED"
                    Diagnostic.SIGNATURE_COUNT -> "APK_METADATA_SIGNATURE_COUNT_REFUSED"
                    Diagnostic.APPLICATION_INFO_NULL -> "APK_METADATA_APPLICATION_INFO_NULL"
                    Diagnostic.OWNERSHIP -> "APK_FILE_OWNERSHIP_REFUSED"
                    Diagnostic.DOWNLOAD_MEMBERSHIP -> "APK_FILE_DOWNLOAD_MEMBERSHIP_REFUSED"
                    Diagnostic.IS_FILE -> "APK_FILE_IS_FILE_REFUSED"
                    Diagnostic.BYTES_IS_FILE -> "APK_BYTES_IS_FILE_REFUSED"
                    Diagnostic.BYTES_SIZE_RANGE -> "APK_BYTES_SIZE_RANGE_REFUSED"
                    Diagnostic.BYTES_SIZE -> "APK_BYTES_SIZE_MISMATCH"
                    Diagnostic.BYTES_HASH -> "APK_BYTES_HASH_MISMATCH"
                    Diagnostic.COPY_SIZE -> "APK_FILE_COPY_SIZE_REFUSED"
                    Diagnostic.CHMOD -> "APK_FILE_CHMOD_REFUSED"
                    Diagnostic.EXCEPTION_OWNERSHIP -> "APK_FILE_EXCEPTION_OWNERSHIP"
                    Diagnostic.EXCEPTION_INITIAL_BASELINE -> "APK_FILE_EXCEPTION_INITIAL_BASELINE"
                    Diagnostic.EXCEPTION_TIME -> "APK_FILE_EXCEPTION_TIME"
                    Diagnostic.EXCEPTION_INITIAL_HASH -> "APK_FILE_EXCEPTION_INITIAL_HASH"
                    Diagnostic.EXCEPTION_BEFORE_COPY -> "APK_FILE_EXCEPTION_BEFORE_COPY"
                    Diagnostic.EXCEPTION_COPY -> "APK_FILE_EXCEPTION_COPY"
                    Diagnostic.EXCEPTION_COPIED_HASH -> "APK_FILE_EXCEPTION_COPIED_HASH"
                    Diagnostic.EXCEPTION_METADATA -> "APK_FILE_EXCEPTION_METADATA"
                    Diagnostic.EXCEPTION_CHMOD -> "APK_FILE_EXCEPTION_CHMOD"
                    Diagnostic.EXCEPTION_FINAL_HASH -> "APK_FILE_EXCEPTION_FINAL_HASH"
                    Diagnostic.EXCEPTION_FINAL_BASELINE -> "APK_FILE_EXCEPTION_FINAL_BASELINE"
                    Diagnostic.EXCEPTION_STAMP -> "APK_FILE_EXCEPTION_STAMP"
                })
            }
        }
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
        private fun zipAbis(file: File): Set<String> = try { ZipFile(file).use { zip ->
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
        } } catch (failure: Exception) {
            diagnostic(Diagnostic.ZIP_ABI_READ)
            throw failure // Preserve the existing exception and outer FILE refusal; never log it.
        }
    }
}
