plugins {
    // What: apply Android app support in :app. Where/when: during this module's
    // Gradle configuration. Why/how: the applied plugin provides android{} and
    // implementation{}; the root only pins its version. Who: this app module.
    id("com.android.application")
}

val backendBaseUrl = providers.gradleProperty("systemset.backendBaseUrl")
    .orElse("https://worsening-basin-footrest.ngrok-free.dev")
    .get()
val deviceUserSlug = providers.gradleProperty("systemset.deviceUserSlug")
    .orElse("kampala-home")
    .get()
val iceMode = providers.gradleProperty("systemset.iceMode")
    .orElse("turn")
    .get()
    .lowercase()
val stunServerUrls = providers.gradleProperty("systemset.stunServerUrls")
    .orElse("stun:stun.l.google.com:19302")
    .get()
require(iceMode == "turn" || iceMode == "stun") {
    "systemset.iceMode must be either 'turn' or 'stun'."
}
fun asBuildConfigString(value: String) =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.systemset"
    compileSdk = 37
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.systemset"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.2"
        buildConfigField("String", "BACKEND_BASE_URL", asBuildConfigString(backendBaseUrl))
        buildConfigField("String", "DEVICE_USER_SLUG", asBuildConfigString(deviceUserSlug))
        buildConfigField("String", "ICE_MODE", asBuildConfigString(iceMode))
        buildConfigField("String", "STUN_SERVER_URLS", asBuildConfigString(stunServerUrls))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")

    // ADD THIS: OkHttp for HTTP signaling payload transmission
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // Google WebRTC library for Android stream handling
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("androidx.security:security-crypto:1.1.0")

    // WorkManager retries idempotent device enrollment after connectivity returns.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
