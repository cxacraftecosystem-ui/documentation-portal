import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

private fun signingProperty(props: Properties, propertyName: String, environmentName: String): String? =
    (props.getProperty(propertyName) ?: System.getenv(environmentName))?.trim()?.takeIf { it.isNotEmpty() }

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val appVersionName = "0.0.1"
val appVersionCode = 37

val releaseKeystorePath = signingProperty(localProperties, "releaseKeystore", "ANDROID_RELEASE_KEYSTORE")
val releaseKeystorePassword = signingProperty(localProperties, "releaseKeystorePassword", "ANDROID_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty(localProperties, "releaseKeyAlias", "ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty(localProperties, "releaseKeyPassword", "ANDROID_RELEASE_KEY_PASSWORD")
    ?: releaseKeystorePassword

val releaseKeystoreFile = releaseKeystorePath?.let { path ->
    File(path).let { candidate -> if (candidate.isAbsolute) candidate else rootProject.file(path) }
}
val hasReleaseSigningKey =
    releaseKeystoreFile != null &&
        releaseKeystoreFile.isFile &&
        releaseKeystorePassword != null &&
        releaseKeyAlias != null
if (releaseKeystorePath != null && !hasReleaseSigningKey) {
    logger.warn(
        "release signing: `releaseKeystore` is set to '${releaseKeystorePath}' but the key is not " +
            "usable (file present: ${releaseKeystoreFile?.isFile == true}, password set: " +
            "${releaseKeystorePassword != null}, alias set: ${releaseKeyAlias != null}). " +
            "The release build will be UNSIGNED."
    )
}

android {
    namespace = "com.fieldrepository.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.fieldrepository.app"
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = appVersionCode
        versionName = appVersionName
        val apiBaseUrl = localProperties.getProperty(
            "apiBaseUrl",
            "https://d2b34i3e92al6i.cloudfront.net/api/"
        )
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"614092441670-3e5k15srupq9mfpg3aktqfkjvkavu0g3.apps.googleusercontent.com\"")
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"614092441670-5rckig6t1al6plbfll8irn9prcmp446t.apps.googleusercontent.com\"")
        buildConfigField("String", "MAPTILER_API_KEY", "\"OJJYFRqCD2HD2k3BbXGF\"")

        fun oidcValue(property: String, environment: String): String =
            signingProperty(localProperties, property, environment) ?: ""
        buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"${oidcValue("microsoftClientId", "MICROSOFT_CLIENT_ID")}\"")
        buildConfigField("String", "MICROSOFT_TENANT", "\"${oidcValue("microsoftTenant", "MICROSOFT_TENANT").ifEmpty { "common" }}\"")
        buildConfigField("String", "YAHOO_CLIENT_ID", "\"${oidcValue("yahooClientId", "YAHOO_CLIENT_ID")}\"")
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"${oidcValue("oidcRedirectUri", "OIDC_REDIRECT_URI")}\"")
        manifestPlaceholders["appAuthRedirectScheme"] = "com.fieldrepository.app.signin"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (hasReleaseSigningKey) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigningKey) {
                signingConfig = signingConfigs.getByName("release")
                logger.lifecycle(
                    "release: signing with the RELEASE key (${releaseKeystoreFile?.name}, " +
                        "alias ${releaseKeyAlias}). This APK is distributable."
                )
            } else if (localProperties.getProperty("debugSignRelease", "false").toBoolean()) {
                val onCi = !System.getenv("GITHUB_ACTIONS").isNullOrBlank() ||
                    !System.getenv("CI").isNullOrBlank()
                if (onCi) {
                    throw GradleException(
                        "release: debugSignRelease=true was found on a CI runner and no release key " +
                            "resolved. The debug keystore ships inside every Android SDK, so an APK " +
                            "signed with it can be updated by anybody — it must never leave an " +
                            "automated pipeline. Refusing to configure the release variant. Fix the " +
                            "release key instead: ANDROID_RELEASE_KEYSTORE, " +
                            "ANDROID_RELEASE_KEYSTORE_PASSWORD and ANDROID_RELEASE_KEY_ALIAS. " +
                            "(This escape hatch is for a developer's own machine, where " +
                            "local.properties is gitignored and never reaches a runner.)"
                    )
                }
                signingConfig = signingConfigs.getByName("debug")
                logger.lifecycle(
                    "release: signing with the DEBUG keystore (debugSignRelease=true, and no release " +
                        "key is configured). For on-device testing only — this APK is not distributable."
                )
            } else {
                logger.lifecycle(
                    "release: UNSIGNED — no release key configured and debugSignRelease is off. " +
                        "The APK will build and will not install."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation(project(":core-imaging"))
    implementation(project(":core-vector"))
    implementation(project(":core-pipeline"))
    implementation(project(":core-export"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation("net.openid:appauth:0.11.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-compose:1.6.2")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.browser:browser:1.9.0")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.camera:camera-view:1.6.2")

    implementation("com.anyascii:anyascii:0.3.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
