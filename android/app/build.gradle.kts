import java.security.MessageDigest

plugins {
    id("com.android.application")
}

val sherpaAsrRuntime = layout.projectDirectory.file("libs/sherpa-onnx-asr-1.13.4.aar").asFile
val expectedSherpaAsrRuntimeSha256 =
    "35af2790bfcb39a1bfe6d0d495193b7fadc367c5c6f07e5e95996ba210cb9196"
require(sherpaAsrRuntime.isFile) {
    "Missing pinned ASR-only runtime: ${sherpaAsrRuntime.path}"
}
val actualSherpaAsrRuntimeSha256 = MessageDigest.getInstance("SHA-256")
    .digest(sherpaAsrRuntime.readBytes())
    .joinToString("") { "%02x".format(it) }
require(actualSherpaAsrRuntimeSha256 == expectedSherpaAsrRuntimeSha256) {
    "ASR-only runtime checksum mismatch: expected $expectedSherpaAsrRuntimeSha256, " +
        "got $actualSherpaAsrRuntimeSha256"
}

// Normal CI/release builds remain universal. Direct device handoffs can request one pinned ABI
// so the native ASR runtime stays below messaging-platform attachment limits.
val deliveryAbi = providers.gradleProperty("opentypeless.deliveryAbi").orNull
val supportedDeliveryAbis = setOf("arm64-v8a", "x86_64")
require(deliveryAbi == null || deliveryAbi in supportedDeliveryAbis) {
    "Unsupported OpenTypeless delivery ABI: $deliveryAbi"
}

val releaseStorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseValues = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseValues.all { !it.isNullOrBlank() }
require(releaseValues.none { !it.isNullOrBlank() } || releaseSigningConfigured) {
    "Android release signing requires keystore path, store password, key alias, and key password"
}

android {
    namespace = "com.opentypeless.android"
    compileSdk = 35

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        applicationId = "com.opentypeless.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        if (deliveryAbi != null) {
            ndk {
                abiFilters += deliveryAbi
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(files(sherpaAsrRuntime))
    //noinspection GradleDependency -- matches the pinned sherpa runtime's Kotlin ABI.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.20")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
