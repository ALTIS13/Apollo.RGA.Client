package org.olcbox.app.update.personal

/** Closed build-owned profiles, not a caller URL, channel, signer-rotation or Olc grant. */
sealed class OwnServerUpdateProfile private constructor(
    val packageName: String, val channel: String, val payloadHeader: String,
    val pathRoot: String, val apkName: String, val cacheName: String,
    val highWaterName: String, val highWaterHeader: String, val checkPreferences: String,
) {
    val manifestUrl: String get() = "https://rga.apollot.ru$pathRoot/manifest.json"
    internal fun apkPath(version: Long) = "$pathRoot/$version/$apkName"
    internal fun allowsUrl(url: String): Boolean = url == manifestUrl ||
        Regex("https://rga\\.apollot\\.ru${Regex.escape(pathRoot)}/[1-9][0-9]*/${Regex.escape(apkName)}").matches(url)
    internal fun acceptsManifest(m: PersonalUpdateManifest): Boolean =
        m.packageName == packageName && m.channel == channel && m.versionCode > 0 && m.apkPath == apkPath(m.versionCode) &&
            (this !is PublicOwnServer || m.apkCertSha256 == releaseSignerSha256 && m.companionPackage.isEmpty() &&
                m.companionVersionCode == 0L && m.companionSha256.isEmpty() && m.companionCertSha256.isEmpty())

    data object Personal : OwnServerUpdateProfile(
        "tech.gatealtas.rga.personal", "personal", "APOLLO-RGA-PERSONAL-OTA/1",
        "/updates/personal/v1", "apollo-rga-personal.apk", "updates/personal",
        "personal-update-high-water-v1", "APOLLO-RGA-PERSONAL-HIGH-WATER/1", "personal-update-check")

    class PublicOwnServer internal constructor(val releaseSignerSha256: String) : OwnServerUpdateProfile(
        "tech.gatealtas.rga", "own-server-stable", "APOLLO-RGA-PUBLIC-OTA/1",
        "/updates/public/v1", "apollo-rga.apk", "updates/public-own-server",
        "public-own-server-update-high-water-v1", "APOLLO-RGA-PUBLIC-HIGH-WATER/1", "public-own-server-update-check")

    companion object {
        internal const val CHANNEL_METADATA = "tech.gatealtas.rga.OWN_SERVER_UPDATE_CHANNEL"
        internal const val PERSONAL_DEBUG_CERT = "11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017"
        internal fun publicBinding(signer: String): PublicOwnServer? =
            signer.takeIf { Regex("[0-9a-f]{64}").matches(it) && it != PERSONAL_DEBUG_CERT }?.let(::PublicOwnServer)
    }
}

/** Same updater UI/lifecycle engine; the factory owns the immutable authority profile. */
interface AndroidOwnServerUpdateRuntimeFactory {
    val publicOwnServer: Boolean
    suspend fun createController(): PersonalUpdateController
}
