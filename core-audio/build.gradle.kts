import java.security.MessageDigest

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.aiwatch.audio"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 28
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

// Unmodified frozen source dependency; no extra Android module or downloaded snapshot version.
val concentusArchive = rootProject.file("third_party/concentus/concentus-3885c4e-java.zip")
val verifyConcentus by tasks.registering {
    inputs.file(concentusArchive)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(concentusArchive.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == "9451c28c1c592ef5a81b646e805df3e88ed75bfb2c2c9615c8207ac1f0413a9f") {
            "Frozen Concentus archive checksum mismatch"
        }
    }
}
val compileConcentus by tasks.registering(JavaCompile::class) {
    dependsOn(verifyConcentus)
    source(zipTree(concentusArchive).matching { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("concentus/classes"))
    options.release.set(8)
    options.encoding = "UTF-8"
}
val concentusJar by tasks.registering(Jar::class) {
    archiveFileName.set("concentus-3885c4e.jar")
    destinationDirectory.set(layout.buildDirectory.dir("concentus"))
    from(compileConcentus.flatMap { it.destinationDirectory })
    from(zipTree(concentusArchive)) { include("LICENSE"); into("META-INF/concentus") }
}

dependencies {
    implementation(files(concentusJar))
    implementation(project(":core-protocol"))
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
