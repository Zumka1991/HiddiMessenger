plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val hiddiProductionServerUrl = providers.gradleProperty("hiddiServerUrl")
    .orElse("https://hiddi.myaifriend.su")
    .get()
val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}
val releaseKeystoreFile = providers.gradleProperty("hiddiKeystorePath")
    .orNull
    ?.let(rootProject::file)
val releasePasswordFile = providers.gradleProperty("hiddiKeystorePasswordFile")
    .orNull
    ?.let(rootProject::file)
val releaseKeystorePassword = releasePasswordFile
    ?.takeIf { it.isFile }
    ?.readText()
    ?.trim()
val releaseKeyAlias = providers.gradleProperty("hiddiKeyAlias")
    .orElse("hiddi-release")
    .get()

if (releaseRequested) {
    require(hiddiProductionServerUrl.startsWith("https://")) {
        "Release APK requires -PhiddiServerUrl=https://your-server.example"
    }
    require(releaseKeystoreFile?.isFile == true && !releaseKeystorePassword.isNullOrBlank()) {
        "Release APK requires -PhiddiKeystorePath and -PhiddiKeystorePasswordFile"
    }
}

android {
    namespace = "ru.hiddi.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.hiddi.messenger"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha.1"
        ndk {
            // Development APKs target physical modern Android devices. Release
            // distribution will use ABI splits/App Bundle instead of a universal APK.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (releaseKeystoreFile != null && !releaseKeystorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            buildConfigField(
                "String",
                "DEFAULT_SERVER_URL",
                "\"http://127.0.0.1:3000\"",
            )
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "DEFAULT_SERVER_URL",
                "\"${hiddiProductionServerUrl.replace("\"", "\\\"")}\"",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf(
                "libsignal_jni*.dylib",
                "signal_jni*.dll",
                "libsignal_jni_testing.so",
            )
        }
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.libsignal.client)
    implementation(libs.libsignal.android)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
