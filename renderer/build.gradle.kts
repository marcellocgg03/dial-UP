import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM module (D-01): no android.* and no JDK APIs above 17, so the code also runs on
// Android API 26+. Main code must not use java.net.http (absent on Android); tests may.
// Renderer, registry, ports and the composition root (ports of renderer.js, index.js, ports.js).
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
    api(project(":lounge"))
    api(project(":media"))
    implementation(project(":upnp"))
    implementation(project(":dial"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":upnp")))
    testImplementation(testFixtures(project(":lounge")))
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
