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
        versionName = "1.22.0 (build $build)"
    }

    // One fixed key for every build, so each new version installs over the last.
    // (A GitHub secret SIGNING_PASSWORD overrides the built-in password if you ever move the key out.)
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/stremio-lite.jks")
            storePassword = System.getenv("SIGNING_PASSWORD") ?: "03818f3fe24f9ddb7d608e5b0a975dd0"
            keyAlias = "stremiolite"
            keyPassword = System.getenv("SIGNING_PASSWORD") ?: "03818f3fe24f9ddb7d608e5b0a975dd0"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("fixed")
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
