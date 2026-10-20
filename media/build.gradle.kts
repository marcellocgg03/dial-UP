import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM module (D-01): no android.* and no JDK APIs above 17, so the code also runs on
// Android API 26+. Main code must not use java.net.http (absent on Android); tests may.
plugins {
    id("org.jetbrains.kotlin.jvm")
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    // catalog alias junit-platform-launcher: Gradle 9 needs it on the test runtime classpath (Pitfall 13)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.jdk14)
}

tasks.test {
    useJUnitPlatform()
}
