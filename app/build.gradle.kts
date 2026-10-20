import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 built-in Kotlin: do NOT apply the legacy Kotlin-for-Android plugin, no kapt.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.t2u.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.t2u.spike"
        minSdk = 26
        targetSdk = 36 // stay on 36: Android 17 local-network enforcement starts at 37 (CLAUDE.md)
        versionCode = 1
        versionName = "0.1-spike"

        ndk {
            abiFilters += "arm64-v8a"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    runtimeOnly(libs.slf4j.jdk14)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
