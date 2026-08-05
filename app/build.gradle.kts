import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use(::load)
    }

android {
    namespace = "com.llsl.viper4android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.llsl.viper4android"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.5.5"
    }

    androidResources {
        generateLocaleConfig = true
    }

    signingConfigs {
        // Debug keystore — always present in every Android SDK installation.
        // Used as a fallback for release builds when no production keystore is set.
        val debugKeystorePath = "${System.getProperty("user.home")}/.android/debug.keystore"

        val keystoreFile = localProps.getProperty("KEYSTORE_FILE", "")
        if (keystoreFile.isNotEmpty()) {
            // Production keystore provided via local.properties
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = localProps.getProperty("KEY_ALIAS", "")
                keyPassword = localProps.getProperty("KEY_PASSWORD", "")
            }
        } else {
            // No production keystore — sign with the debug keystore so the APK
            // is always installable (both sideload and adb install work fine).
            create("release") {
                storeFile = file(debugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    defaultConfig {
        // NDK/CMake for ViPERCapture JNI bridge
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
                abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.coroutines.android)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
