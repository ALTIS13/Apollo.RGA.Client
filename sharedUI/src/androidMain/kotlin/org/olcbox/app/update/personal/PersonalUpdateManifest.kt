package org.olcbox.app.update.personal

data class PersonalUpdateManifest(
    val sequence: Long, val issuedAt: Long, val expiresAt: Long,
    val packageName: String, val versionCode: Long, val versionName: String,
    val minSdk: Int, val abi: String, val apkPath: String, val apkBytes: Long,
    val apkSha256: String, val apkCertSha256: String,
    val companionPackage: String, val companionVersionCode: Long,
    val companionSha256: String, val companionCertSha256: String,
    val channel: String = "personal",
)

data class PersonalUpdateBaseline(
    val packageName: String, val versionCode: Long, val apkCertSha256: String,
    val sdk: Int, val supportedAbis: Set<String>, val companionPackage: String,
    val companionVersionCode: Long, val companionSha256: String, val companionCertSha256: String,
    val channel: String = "personal", val installedApkSha256: String? = null,
)

data class PersonalUpdateHighWater(val sequence: Long, val payloadSha256: String?)

enum class PersonalUpdateFailure {
    OVERSIZE, INVALID_ENVELOPE, INVALID_KEY, INVALID_SIGNATURE, INVALID_SCHEMA,
    INVALID_TIME, DOWNGRADE, BASELINE_MISMATCH, INCOMPATIBLE_DEVICE, INVALID_PATH,
    COMPANION_MISMATCH, SEQUENCE_ROLLBACK, SEQUENCE_CONFLICT,
}

sealed interface PersonalUpdateVerification {
    data class Success(val manifest: PersonalUpdateManifest, val payloadSha256: String) : PersonalUpdateVerification
    data class Failure(val reason: PersonalUpdateFailure) : PersonalUpdateVerification
}
