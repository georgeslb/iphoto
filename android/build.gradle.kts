// AGP 9's built-in Kotlin support replaces the org.jetbrains.kotlin.android plugin; only the
// Compose compiler plugin still needs to be applied explicitly. This buildscript block pins the
// Kotlin Gradle plugin version used for that built-in compilation to the latest release.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
