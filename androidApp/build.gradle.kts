import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.util.Properties
import java.io.FileInputStream

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
            applicationIdSuffix = ".debug"
            if (hasReleaseKeystore) {
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
    debugImplementation(project(":sharedUI:olcrtc-bin"))
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.datastore.preferences)

    // Instrumented tests: the only way to exercise the packaged core binary the way
    // the app does — extracted into nativeLibraryDir and exec'd on a real Android.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Instrumentation runs against debug; pin the optional binding's liveness.
    androidTestImplementation(project(":sharedUI:olcrtc-bin"))
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
