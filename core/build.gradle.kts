// Logic shared by the Android app and the Linux app: plain Kotlin, no Android classes.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.vyarus.animalsniffer")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Android has org.json built in; the Linux app adds the library itself.
    compileOnly("org.json:json:20240303")
    // Android 5.1's Java (API 22). Anything newer (like Map.putIfAbsent, Android 7+) fails the
    // build: run ./gradlew :core:animalsnifferMain
    signature("net.sf.androidscents.signature:android-api-level-22:5.1.1_r2@signature")
}

animalsniffer {
    sourceSets = listOf(project.sourceSets.getByName("main"))
}
