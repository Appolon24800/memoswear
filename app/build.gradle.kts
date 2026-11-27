import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore/signing.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull ?: localSigningProperties.getProperty(name)

val useDebugSigning = providers.gradleProperty("useDebugSigning").orNull == "true"

android {
    namespace = "dev.appolon.memoswear"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.appolon.memoswear"
        minSdk = 30
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("1.0.0").get()
        // Debug builds may talk to a plain-HTTP server on a LAN address; release builds require HTTPS.
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    // With a keystore, release builds are signed. Without one they are still built, unsigned.
    val releaseKeystore = rootProject.file(signingValue("ANDROID_KEYSTORE_FILE") ?: "keystore/memoswear.jks")
    val releaseSigning = signingConfigs.create("release") {
        if (useDebugSigning) {
            storeFile = rootProject.file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        } else {
            storeFile = releaseKeystore
            storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = signingValue("ANDROID_KEY_ALIAS")
            keyPassword = signingValue("ANDROID_KEY_PASSWORD")
        }
    }
    val hasReleaseSigning = useDebugSigning || (releaseKeystore.isFile && signingValue("ANDROID_KEYSTORE_PASSWORD") != null)
    // Release builds require HTTPS. This switch exists only to test a minified build against a
    // local HTTP server; never turn it on for a build that leaves the machine.
    val allowCleartextInRelease = providers.gradleProperty("allowCleartext").orNull == "true"

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) signingConfig = releaseSigning
            manifestPlaceholders["usesCleartextTraffic"] = allowCleartextInRelease.toString()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Tile layouts take a Context they never use; let plain JVM tests hand them one.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.complications.source.ktx)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Tests read back the layouts the tile builders produce, which are proto messages.
    testImplementation(libs.androidx.wear.protolayout.proto)
}
