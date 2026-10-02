plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "de.corespace.shroud.upstub"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.corespace.shroud.upstub"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
