// AGP 9 has built-in Kotlin and defaults to an older KGP. Raising it is done once, here, via
// the buildscript classpath (documented mechanism). The Kotlin plugins themselves are applied
// without a version in the modules (Pitfall 14).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
