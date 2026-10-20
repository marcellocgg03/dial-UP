import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM module (D-01): no android.* and no JDK APIs above 17, so the code also runs on
// Android API 26+. Main code must not use java.net.http (absent on Android); tests may.
// YouTube lounge receiver (port of yt-cast-receiver 2.1.0); every protocol constant lives in one file.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":upnp")) // EventSink and OkHttp
    implementation(project(":dial"))
    api(libs.kotlinx.serialization.json) // the DataStore contract uses JsonElement
    implementation(libs.kotlinx.coroutines.core)

    // fake lounge server shared with :renderer tests
    testFixturesImplementation(libs.ktor.server.core)
    testFixturesImplementation(libs.ktor.server.cio)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // catalog alias junit-platform-launcher: Gradle 9 needs it on the test runtime classpath (Pitfall 13)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.jdk14)
}

tasks.test {
    useJUnitPlatform()
}
