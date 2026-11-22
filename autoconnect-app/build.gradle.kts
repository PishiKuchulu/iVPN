import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// The core pin is the single source of truth (mirrored from
// autoconnect-core/research/CORE_VERSION — see UPDATE-PLAYBOOK.md there).
// It is read here and baked into BuildConfig; nothing else in the build
// hard-codes the core version.
val corePin = Properties().apply {
    rootProject.file("CORE_VERSION").inputStream().use { load(it) }
}
val coreVersionPin: String = corePin.getProperty("CORE_VERSION") ?: "unknown"
val coreTagPin: String = corePin.getProperty("CORE_TAG") ?: "unknown"

android {
    namespace = "autoconnect.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "autoconnect.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "CORE_VERSION_PIN", "\"$coreVersionPin\"")
        buildConfigField("String", "CORE_TAG_PIN", "\"$coreTagPin\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"https://autoconnect-backend.vercel.app\"")

        // The pinned proof AAR is an arm64-v8a-only build (see CORE_VERSION /
        // BUILD-REPORT.md). The release AAR build must cover all ABIs.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The core-adapter module is reused AS-IS (single source of truth in
    // autoconnect-core/adapter): its sources are compiled into the app,
    // never copied or forked here.
    sourceSets {
        getByName("main") {
            java.srcDirs(
                "src/main/java",
                "../autoconnect-core/adapter/src/main/kotlin",
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Pinned libbox AAR (sing-box core) — the ONLY core contact artifact.
    // Swapping this file + bumping CORE_VERSION is a core update.
    implementation(files("libs/libbox.aar"))
}
