// Genera el Baseline Profile de la app en un teléfono conectado: ./gradlew :app:generateReleaseBaselineProfile
// El perfil queda en app/src/release/generated/baselineProfiles/ y entra en cada release (profileinstaller lo
// compila al instalar). Volver a generarlo cuando cambien mucho las pantallas principales.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.kevshupp.kevmusicplayer.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    targetProjectPath = ":app"
}

baselineProfile {
    // El teléfono conectado por adb (el moto g35), no un emulador administrado
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
