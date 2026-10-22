plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.davplayer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.davplayer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.3.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Match transitive native libraries to the ABI(s) provided by libmpv.
        ndk { abiFilters.addAll(providers.gradleProperty("mpvAbis").orElse("arm64-v8a").get().split(',').map(String::trim)) }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    signingConfigs {
        providers.environmentVariable("KPLAYER_KEYSTORE").orNull?.let { keystore ->
            create("maintainerRelease") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("KPLAYER_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KPLAYER_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KPLAYER_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("maintainerRelease")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":playback:api"))
    implementation(project(":playback:mpv"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
