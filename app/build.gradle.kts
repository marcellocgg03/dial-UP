import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Release signing material lives outside the repository (D-06): keystore.properties in the repo root (ignored by git)
// or the four DIALUP_* environment variables. With neither, the release build is left unsigned. Values are never logged.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    (signingProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "DIALUP_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "DIALUP_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "DIALUP_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "DIALUP_KEY_PASSWORD")
val releaseSigningComplete =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

// AGP 9 built-in Kotlin: do NOT apply the legacy Kotlin-for-Android plugin, no kapt.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The last stable release's code; see versionCode in defaultConfig.
val stableVersionCode = 5

// Nightly build number (1..999): the commits on main since the last stable tag, passed by the nightly workflow.
val nightlyBuild: Int? = (findProperty("nightlyBuild") as String?)?.let {
    requireNotNull(it.toIntOrNull()?.takeIf { n -> n in 1..999 }) { "nightlyBuild must be 1..999, got '$it'" }
}

android {
    namespace = "dev.dialup.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dialup"
        minSdk = 26
        targetSdk = 36 // stay on 36: Android 17 local-network enforcement starts at 37
        // versionCode is stableVersionCode * 1000 + the nightly build number (0 for a stable build). stableVersionCode is a
        // plain integer raised by one for every stable release (D-01), so a nightly (4001..4999) installs over its
        // stable release and the next stable release (5000) installs over every nightly. versionName of a stable build
        // follows the git tag v<versionName>.
        versionCode = stableVersionCode * 1000 + (nightlyBuild ?: 0)
        versionName = "1.1.1"
        // A nightly (-PnightlyBuild=N from the nightly workflow) is the NEXT patch version as a pre-release: it ranks above
        // the stable release it grew from and below the one it leads to, which is what the update check compares.
        if (nightlyBuild != null) {
            val parts = versionName!!.split('.')
            versionName = "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}-nightly.$nightlyBuild"
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningComplete) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // A separate package, so the debug build installs next to the release one (its data and settings are separate too).
            // Both would bind the same ports: run the bridge in one at a time (tools/dev-install.sh warns).
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "DialUP (debug)"
        }
        release {
            manifestPlaceholders["appLabel"] = "DialUP"
            if (releaseSigningComplete) signingConfig = signingConfigs.getByName("release")
            // D-06: the app depends on youtubedl-android payloads and Ktor engines that load classes by name, and
            // nothing has proven them under R8, so the beta ships unminified. Revisit after 1.0.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // youtubedl-android executes lib*.so from nativeLibraryDir and unzips the lib*.zip.so
        // payloads; AGP injects extractNativeLibs=true into the merged manifest from this flag.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":upnp"))
    implementation(project(":dial"))
    implementation(project(":media"))
    implementation(project(":lounge"))
    implementation(project(":renderer"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    runtimeOnly(libs.slf4j.jdk14)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
