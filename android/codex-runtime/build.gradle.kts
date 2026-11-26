plugins {
    id("com.android.application")
}

android {
    namespace = "com.codexrm.runtime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codexrm.runtime"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
    }

    // Android's package installer extracts these PIEs into its read-only native library
    // directory. A target-35 app cannot execute a binary in its writable files directory.
    packaging.jniLibs.useLegacyPackaging = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
