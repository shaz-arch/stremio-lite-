plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.stremiolite"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.stremiolite"
        minSdk = 22          // Android 5.1: every Fire TV Stick that runs Stremio
        targetSdk = 35
        // CI passes the GitHub build number, so the app can tell when a newer build is out.
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.28.1 (build $build)"
    }

    // One fixed key for every build, so each new version installs over the last.
    // The key is never in the repo: CI writes it from the GitHub secrets SIGNING_KEY (base64) and SIGNING_PASSWORD.
    val keyFile = System.getenv("SIGNING_KEY_FILE")?.let { file(it) }
    signingConfigs {
        create("fixed") {
            if (keyFile != null && keyFile.isFile) {
                storeFile = keyFile
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "stremonino"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without the key (a local build), the release APK is left unsigned rather than signed with a public key.
            if (keyFile != null && keyFile.isFile) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Plain Android views and the Kotlin standard library, plus ZXing's core encoder for QR codes
// (3.3.3 is the last release that runs on Android 5).
dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.3.3")
}
