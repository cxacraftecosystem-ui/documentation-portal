import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Single source of truth for the app version. Scheme is MAJOR.MINOR.PATCH where PATCH runs 0→100,
// then MINOR rolls forward (…1.1.100 → 1.2.0…) all the way to 1.100.0 before MAJOR turns over to
// 2.0.0. versionCode is DERIVED from the name so it always increases monotonically with the version
// — that is exactly what the over-the-air updater compares (a higher published versionCode triggers
// the in-app update). To cut a release, bump `appVersionName` only; the code follows automatically.
val appVersionName = "1.1.9"
val appVersionCode = appVersionName.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    // minor and patch are each capped at 100 by the scheme, so the 1_000-wide buckets never collide.
    major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "com.fieldrepository.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fieldrepository.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // Default to the production backend through CloudFront over HTTPS. CloudFront is dual-stack
        // (publishes a native IPv6 / AAAA record), so it connects on IPv6-only mobile networks
        // (e.g. Jio/Airtel) where the IPv4-only EC2 origin — whether addressed by literal IP or its
        // AWS hostname — fails (no IPv4 route, and no AAAA to use). HTTPS also clears the web app's
        // mixed-content block. Emulator/local devs override this with
        // apiBaseUrl=http://10.0.2.2:8000/api/ in local.properties.
        val apiBaseUrl = localProperties.getProperty(
            "apiBaseUrl",
            "https://d2b34i3e92al6i.cloudfront.net/api/"
        )
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"614092441670-3e5k15srupq9mfpg3aktqfkjvkavu0g3.apps.googleusercontent.com\"")
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"614092441670-5rckig6t1al6plbfll8irn9prcmp446t.apps.googleusercontent.com\"")
        buildConfigField("String", "MAPTILER_API_KEY", "\"OJJYFRqCD2HD2k3BbXGF\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // In-app video/audio playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
