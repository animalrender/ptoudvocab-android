plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional locally. CI sets RELEASE_KEYSTORE_PATH and friends
// from GitHub secrets; without them the release variant is left unsigned.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.proudvocab.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proudvocab.android"
        minSdk = 28
        targetSdk = 35
        versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0.0"
        vectorDrawables.useSupportLibrary = false
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
