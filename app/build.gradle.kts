plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

fun requiredReleaseEnvironmentVariable(name: String): String {
    val value = providers.environmentVariable(name).orNull
    if (releaseRequested && value.isNullOrBlank()) {
        throw GradleException(
            "Release signing requires the user-scoped environment variable $name."
        )
    }
    return value.orEmpty()
}

val releaseKeystorePath = requiredReleaseEnvironmentVariable("HUSH_KEYSTORE_PATH")
val releaseStorePassword = requiredReleaseEnvironmentVariable("HUSH_STORE_PASSWORD")
val releaseKeyAlias = requiredReleaseEnvironmentVariable("HUSH_KEY_ALIAS")
val releaseKeyPassword = requiredReleaseEnvironmentVariable("HUSH_KEY_PASSWORD")

android {
    namespace = "space.tejaswi.hush"
    compileSdk = 35

    defaultConfig {
        applicationId = "space.tejaswi.hush"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    signingConfigs {
        create("release") {
            if (releaseRequested) {
                val keystore = file(releaseKeystorePath)
                if (!keystore.isFile) {
                    throw GradleException(
                        "Release keystore does not exist: $releaseKeystorePath"
                    )
                }
                storeFile = keystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
}
