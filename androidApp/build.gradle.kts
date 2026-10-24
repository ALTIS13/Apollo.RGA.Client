import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.util.Properties
import java.io.FileInputStream
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val externalReleaseSigning = providers.gradleProperty("apollo.rga.externalSigning")
    .map { it.toBooleanStrict() }
    .getOrElse(false)

if (!externalReleaseSigning && keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

val hasReleaseKeystore =
    !externalReleaseSigning && keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionCode = providers.gradleProperty("olcbox.versionCode")
    .map { it.toInt() }
    .orElse(1)
// Opt-in personal QA package beside both the signed release and normal debug app.
// This marker is installation isolation only; Bridge still requires A063 PoP.
val rgaPersonalTest = providers.gradleProperty("rgaPersonalTest")
    .map { it.toBooleanStrict() }
    .getOrElse(false)
val defaultAndroidAbiFilters = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidAbiFilters = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(defaultAndroidAbiFilters)

require(androidAbiFilters.isNotEmpty()) {
    "olcbox.android.abiFilters must contain at least one Android ABI"
}

android {
    namespace = "org.olcbox.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 23
        targetSdk = 37

        // Apollo.RGA is installed beside INCY and the upstream Ghostlane app.
        // The Kotlin namespace remains upstream's; only the Android identity changes.
        applicationId = "tech.gatealtas.rga"
        versionCode = olcboxVersionCode.get()
        versionName = olcboxVersion.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += androidAbiFilters
        }
    }

    // One app, two channels. `github` is the build on the releases page and
    // updates itself from there, which is what REQUEST_INSTALL_PACKAGES is
    // for. `play` is the bundle Google Play distributes: Play owns updates and
    // refuses that permission — and QUERY_ALL_PACKAGES — at upload, before a
    // human looks (Device and Network Abuse; package visibility). Both are
    // removed in src/play/AndroidManifest.xml, and src/play/res overrides the
    // store_self_update bool so AppActivity builds no updater. Same
    // applicationId, version code and signing key on both, so a phone can move
    // between them and update. (Plain res files rather than resValue: AGP 9
    // ships with buildFeatures.resValues off.)
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
        }
        create("play") {
            dimension = "store"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = if (rgaPersonalTest) ".personal" else ".debug"
            if (hasReleaseKeystore && !rgaPersonalTest) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "jniLibs")
        }
        // Stated explicitly rather than relying on the plugin default, so the
        // instrumented sources cannot silently stop being compiled.
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
            // Only invented schema fixtures; never subscription payloads.
            assets.srcDir(rootProject.file("scripts/fixtures"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Executable Go cores are already stripped by the pinned build.
            // Preserve their reviewed bytes for final APK/AAB provenance checks.
            keepDebugSymbols += setOf("**/libsingboxcore.so", "**/libxraycore.so")
        }
    }
}

// Android-only, opt-in resources. No shared GeneratedAppInfo or global resValues.
abstract class GeneratePersonalOtaResourcesTask : DefaultTask() {
    @get:Input abstract val publicSpki: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:OutputFile abstract val outputManifest: RegularFileProperty

    @TaskAction fun generate() {
        val pin = publicSpki.get()
        val enabled = pin.isNotEmpty()
        val values = outputDirectory.file("values/personal_ota.xml").get().asFile
        values.parentFile.mkdirs()
        values.writeText("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <bool name="personal_ota_enabled">$enabled</bool>
    <string name="personal_ota_compiled_variant" translatable="false">${if (enabled) "githubDebug" else ""}</string>
    <string name="personal_ota_public_spki" translatable="false">$pin</string>
</resources>
""")
        val manifest = outputManifest.get().asFile
        manifest.parentFile.mkdirs()
        manifest.writeText("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
${if (enabled) "    <uses-permission android:name=\"android.permission.REQUEST_INSTALL_PACKAGES\" />" else ""}
</manifest>
""")
    }
}

fun validPersonalOtaSpki(encoded: String): Boolean = try {
    if (encoded.length !in 1..512) false else {
        val bytes = Base64.getDecoder().decode(encoded)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        key != null && Base64.getEncoder().encodeToString(bytes) == encoded && bytes.contentEquals(key.encoded) &&
            key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor
    }
} catch (_: Exception) { false }

androidComponents.onVariants(androidComponents.selector().withBuildType("debug").withFlavor("store", "github")) { variant ->
    val requestedPin = if (rgaPersonalTest) providers.gradleProperty("rgaPersonalOtaSpki").getOrElse("") else ""
    val pin = requestedPin.takeIf(::validPersonalOtaSpki).orEmpty()
    val resourcesTask = tasks.register<GeneratePersonalOtaResourcesTask>("generateGithubDebugPersonalOtaResources") {
        publicSpki.set(pin)
        outputDirectory.set(layout.buildDirectory.dir("generated/personalOta/githubDebug/res"))
        outputManifest.set(layout.buildDirectory.file("generated/personalOta/githubDebug/AndroidManifest.xml"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(resourcesTask, GeneratePersonalOtaResourcesTask::outputDirectory)
    variant.sources.manifests.addGeneratedManifestFile(resourcesTask, GeneratePersonalOtaResourcesTask::outputManifest)
}

// Separate own-server release opt-in. Missing/invalid public bindings produce no updater or permission.
// No signing material is read here and the upstream store_self_update switch stays false.
abstract class GeneratePublicOwnServerOtaResourcesTask : DefaultTask() {
    @get:Input abstract val publicSpki: Property<String>
    @get:Input abstract val releaseSignerSha256: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:OutputFile abstract val outputManifest: RegularFileProperty
    @TaskAction fun generate() {
        val pin = publicSpki.get()
        val signer = releaseSignerSha256.get()
        val enabled = pin.isNotEmpty() && signer.isNotEmpty()
        val values = outputDirectory.file("values/public_own_server_ota.xml").get().asFile
        values.parentFile.mkdirs()
        values.writeText("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <bool name="public_own_server_ota_enabled">$enabled</bool>
    <string name="public_own_server_ota_compiled_variant" translatable="false">${if (enabled) "githubRelease" else ""}</string>
    <string name="public_own_server_ota_public_spki" translatable="false">$pin</string>
    <string name="public_own_server_ota_release_signer_sha256" translatable="false">$signer</string>
</resources>
""")
        val manifest = outputManifest.get().asFile
        manifest.parentFile.mkdirs()
        manifest.writeText("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
${if (enabled) """    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <application>
        <meta-data android:name="tech.gatealtas.rga.OWN_SERVER_UPDATE_CHANNEL" android:value="own-server-stable" />
    </application>""" else ""}
</manifest>
""")
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release").withFlavor("store", "github")) { variant ->
    val requestedPin = providers.gradleProperty("rgaPublicOwnServerOtaSpki").getOrElse("")
    val requestedSigner = providers.gradleProperty("rgaPublicOwnServerReleaseSignerSha256").getOrElse("")
    val bound = validPersonalOtaSpki(requestedPin) && Regex("[0-9a-f]{64}").matches(requestedSigner) &&
        requestedSigner != "11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017" &&
        androidAbiFilters == listOf("arm64-v8a")
    val resourcesTask = tasks.register<GeneratePublicOwnServerOtaResourcesTask>("generateGithubReleasePublicOwnServerOtaResources") {
        publicSpki.set(if (bound) requestedPin else "")
        releaseSignerSha256.set(if (bound) requestedSigner else "")
        outputDirectory.set(layout.buildDirectory.dir("generated/publicOwnServerOta/githubRelease/res"))
        outputManifest.set(layout.buildDirectory.file("generated/publicOwnServerOta/githubRelease/AndroidManifest.xml"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(resourcesTask, GeneratePublicOwnServerOtaResourcesTask::outputDirectory)
    variant.sources.manifests.addGeneratedManifestFile(resourcesTask, GeneratePublicOwnServerOtaResourcesTask::outputManifest)
}

// In AGP 9.0+ Kotlin settings for Android are configured like this:
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation("androidx.startup:startup-runtime:1.1.1")
    // The gomobile binding is an isolated experimental transport. Ordinary
    // release variants must neither build nor package it.
    if (!providers.gradleProperty("rgaGeneralRuntime").orElse("false").get().toBooleanStrict()) {
        debugImplementation(project(":sharedUI:olcrtc-bin"))
    }
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.datastore.preferences)

    // Instrumented tests: the only way to exercise the packaged core binary the way
    // the app does — extracted into nativeLibraryDir and exec'd on a real Android.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Only the real selected-path subscription acceptance test uses this API.
    androidTestImplementation(libs.ktor.client.core)
    // Instrumentation runs against debug; pin the optional binding's liveness.
    if (!providers.gradleProperty("rgaGeneralRuntime").orElse("false").get().toBooleanStrict()) {
        androidTestImplementation(project(":sharedUI:olcrtc-bin"))
    }
}

// The release workflow stages these executables before Gradle runs. Local APK
// builds must have the same gate: without either file the app installs normally
// but every non-OLC connection fails only when the user presses Connect.
abstract class VerifyAndroidCoreBinariesTask : DefaultTask() {
    @get:Input abstract val abis: ListProperty<String>
    @get:Internal abstract val jniLibsDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val elfMachineByAbi = mapOf(
            "armeabi-v7a" to 40,
            "arm64-v8a" to 183,
            "x86_64" to 62,
        )
        abis.get().forEach { abi ->
            val expectedMachine = elfMachineByAbi[abi]
                ?: error("Unsupported Android ABI in olcbox.android.abiFilters: $abi")
            listOf("libsingboxcore.so", "libxraycore.so").forEach { name ->
                val core = jniLibsDirectory.file("$abi/$name").get().asFile
                check(core.isFile && core.length() >= 20) {
                    "Missing Android core $abi/$name. Stage both pinned executable cores in " +
                        "androidApp/jniLibs/$abi before assembling an APK."
                }
                val header = core.inputStream().use { it.readNBytes(20) }
                val isElf = header[0] == 0x7f.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[5] == 1.toByte()
                val elfType = (header[16].toInt() and 0xff) or
                    ((header[17].toInt() and 0xff) shl 8)
                val elfMachine = (header[18].toInt() and 0xff) or
                    ((header[19].toInt() and 0xff) shl 8)
                check(isElf && elfType in 2..3 && elfMachine == expectedMachine) {
                    "Android core $abi/$name is not an executable ELF for $abi."
                }
            }
        }
    }
}

val verifyAndroidCoreBinaries = tasks.register<VerifyAndroidCoreBinariesTask>("verifyAndroidCoreBinaries") {
    group = "verification"
    description = "Rejects Android APKs missing the executable sing-box or Xray core"
    abis.set(androidAbiFilters)
    jniLibsDirectory.set(layout.projectDirectory.dir("jniLibs"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach { dependsOn(verifyAndroidCoreBinaries) }
