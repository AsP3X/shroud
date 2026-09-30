plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.corespace.shroud"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.corespace.shroud"
        // Android 11: the oldest version in the test matrix (no RenderEffect blur there — the
        // design's "Glass — Without Blur" fallback). Keystore auth parameters need API 30.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Debug builds point at a local Compose stack from the emulator (10.0.2.2 is the host).
        buildConfigField("String", "DEFAULT_SELF_HOSTED_HOST", "\"10.0.2.2\"")
        buildConfigField("int", "DEFAULT_SELF_HOSTED_PORT", "8080")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    // Reproducible builds: no build-time values baked into the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
