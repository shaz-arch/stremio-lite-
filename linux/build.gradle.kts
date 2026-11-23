// Stremonino for Linux: a terminal app (same DOS look) that plays through mpv.
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
    implementation(project(":core"))
    implementation("org.json:json:20240303")
    implementation("com.googlecode.lanterna:lanterna:3.1.2")
    implementation("com.google.zxing:core:3.5.3")
}

val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0

// The build number, readable at run time (for the About screen and update checks).
val versionFile = tasks.register("versionFile") {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    inputs.property("build", build)
    doLast {
        val f = out.get().file("stremonino-version.txt").asFile
        f.parentFile.mkdirs()
        f.writeText(build.toString())
    }
}
sourceSets["main"].resources.srcDir(versionFile)

// One runnable file: java -jar stremonino-linux.jar
tasks.register<Jar>("fatJar") {
    archiveFileName.set("stremonino-linux.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    manifest { attributes("Main-Class" to "app.stremiolite.linux.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
    }
}
