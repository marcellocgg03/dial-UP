import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM module (D-01): no android.* and no JDK APIs above 17, so the code also runs on
// Android API 26+. Main code must not use java.net.http (absent on Android); tests may.
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
    implementation(libs.kotlinx.coroutines.core)
    // The SOAP API takes an OkHttpClient (every client in the app derives from lanHttpClient(), SVC-05).
    api(libs.okhttp)
    // JsonObject is the receiver type of the JsonFields helpers, so it is part of the public signature.
    api(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    // FakeRenderer (port of test/fakeRenderer.js), shared with :lounge and :renderer tests
    testFixturesImplementation(libs.ktor.server.core)
    testFixturesImplementation(libs.ktor.server.cio)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    // catalog alias junit-platform-launcher: Gradle 9 needs it on the test runtime classpath (Pitfall 13)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.jdk14)
}

tasks.test {
    useJUnitPlatform()
}

// Plan 03-26: a fake speaker on the LAN for the phone app to cast to (tools/fake-renderer.sh passes --args).
// slf4j-jdk14 goes on this task's classpath only, so no existing test classpath changes.
val fakeRendererRuntime: Configuration by configurations.creating
dependencies.addProvider("fakeRendererRuntime", libs.slf4j.jdk14)

tasks.register<JavaExec>("runFakeRenderer") {
    group = "verification"
    description = "Runs the fake UPnP speaker on the LAN (see tools/fake-renderer.sh --help); pass options with --args."
    classpath = sourceSets["testFixtures"].runtimeClasspath + fakeRendererRuntime
    mainClass.set("dev.t2u.upnp.FakeRendererMainKt")
}
