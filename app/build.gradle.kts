plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.aiwatch.probe"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.aiwatch.probe"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-phase0b"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-audio"))
}
