// Logic shared by the Android app and the Linux app: plain Kotlin, no Android classes.
plugins {
    id("org.jetbrains.kotlin.jvm")
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
}
