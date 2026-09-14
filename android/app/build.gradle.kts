import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * THE RELEASE SIGNING KEY, AND WHY IT IS NOT IN THIS REPOSITORY.
 *
 * Until 2026-09-13 there was no release key and no `signingConfigs` block at all, so `assembleRelease`
 * produced `app-release-unsigned.apk` and every build that ever reached a handset was signed with the
 * ANDROID DEBUG KEY. That is not a guess: the live published v1.1.20 was read with
 * `apksigner verify --print-certs` and answers
 *
 *     Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
 *     Signer #1 certificate SHA-256 digest: 691257a01e834122645488888798d6816479490e22b379692c83d98c09232c74
 *
 * The debug keystore ships with every Android SDK on earth, so ANYBODY can produce an update the
 * installed fleet would accept as genuine. There is now a real key, and `docs/RELEASING.md` states
 * the one-off cost of moving to it — an uninstall and reinstall on every handset, because Android
 * never lets an app change its signing certificate — along with the sync-to-empty step that has to
 * happen first.
 *
 * THE KEY LIVES OUTSIDE THE WORKING TREE. `.gitignore` (the "ANDROID SIGNING MATERIAL" block) refuses
 * `*.p12`, `*.jks`, `*.keystore` and `fieldrepo-release.*` outright. Its location and password arrive
 * through `local.properties` (gitignored) or through the environment for CI. Four properties:
 *
 *     releaseKeystore=C:/path/to/fieldrepo-release.p12
 *     releaseKeystorePassword=...
 *     releaseKeyAlias=fieldrepo-release
 *     releaseKeyPassword=...            # optional; defaults to the store password
 *
 * or ANDROID_RELEASE_KEYSTORE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD in the environment. The
 * publish workflow sets the first three from repository secrets and deliberately does not set the
 * fourth — the key in this project's store uses the store password, and an ABSENT secret renders as
 * an EMPTY environment variable, which `takeIf { it.isNotEmpty() }` below reads as unset. Absence is
 * therefore safe by construction rather than by anyone remembering.
 *
 * ABSENT IS A VALID STATE AND MUST STAY ONE. A clean checkout has none of this, and the release build
 * there stays unsigned exactly as before — which fails loudly at install time instead of quietly
 * producing something that looks shippable. A missing key must NEVER silently fall back to the debug
 * one; that silent fallback is precisely what put a debug-signed APK on the fleet.
 */
private fun signingProperty(props: Properties, propertyName: String, environmentName: String): String? =
    (props.getProperty(propertyName) ?: System.getenv(environmentName))?.trim()?.takeIf { it.isNotEmpty() }

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
val appVersionName = "0.0.2"
val appVersionCode = appVersionName.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    // minor and patch are each capped at 100 by the scheme, so the 1_000-wide buckets never collide.
    major * 1_000_000 + minor * 1_000 + patch
}

val releaseKeystorePath = signingProperty(localProperties, "releaseKeystore", "ANDROID_RELEASE_KEYSTORE")
val releaseKeystorePassword = signingProperty(localProperties, "releaseKeystorePassword", "ANDROID_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty(localProperties, "releaseKeyAlias", "ANDROID_RELEASE_KEY_ALIAS")
// Defaults to the store password, which is how `keytool` is almost always driven and what this
// project's key actually uses. Kept separately settable because a PKCS#12 store CAN hold a key under
// a different password, and discovering that at the signing step is a confusing place to find out.
val releaseKeyPassword = signingProperty(localProperties, "releaseKeyPassword", "ANDROID_RELEASE_KEY_PASSWORD")
    ?: releaseKeystorePassword

// Resolved here rather than inside the signing config so that "the key is configured" and "the file
// is actually there" are one question with one answer. A property pointing at a keystore that does
// not exist would otherwise be indistinguishable from no property at all, and the build would simply
// produce an unsigned APK — the failure this whole arrangement exists to make loud.
val releaseKeystoreFile = releaseKeystorePath?.let { path ->
    // An absolute path is what a key kept outside the repository needs; a relative one is resolved
    // against the `android/` directory, which is what `../fieldrepo-release.p12` in a developer's
    // local.properties means to the person who wrote it.
    //
    // `File`, IMPORTED at the top of this file, not `java.io.File` written out. In the Gradle Kotlin
    // DSL `java` is already taken — it is the JavaPluginExtension accessor on Project — so the fully
    // qualified form parses as that extension followed by a `.io` property and fails with
    // "Unresolved reference: io".
    File(path).let { candidate -> if (candidate.isAbsolute) candidate else rootProject.file(path) }
}
val hasReleaseSigningKey =
    releaseKeystoreFile != null &&
        releaseKeystoreFile.isFile &&
        releaseKeystorePassword != null &&
        releaseKeyAlias != null
if (releaseKeystorePath != null && !hasReleaseSigningKey) {
    // Named loudly rather than left as a silent unsigned build: somebody set the property, so they
    // intended a signed release and would otherwise get an APK that cannot be installed at all.
    // `.github/workflows/publish-android.yml` greps the build log for this exact wording and fails
    // the publish on it, because on a runner a half-configured key can only mean a broken secret.
    logger.warn(
        "release signing: `releaseKeystore` is set to '${releaseKeystorePath}' but the key is not " +
            "usable (file present: ${releaseKeystoreFile?.isFile == true}, password set: " +
            "${releaseKeystorePassword != null}, alias set: ${releaseKeyAlias != null}). " +
            "The release build will be UNSIGNED."
    )
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

    /**
     * Declared before `buildTypes` on purpose: the release build type below looks this config up by
     * name, and a config created afterwards is not there to be found.
     *
     * Created ONLY when a real key resolved. An empty-but-present "release" signing config is worse
     * than none — Gradle accepts it and produces an APK signed with nothing, which is the exact
     * outcome the block in `buildTypes.release` spends its length warning about.
     */
    signingConfigs {
        if (hasReleaseSigningKey) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Both signature schemes. v1 (the JAR signature) is what pre-Nougat installers read;
                // v2/v3 are what everything since prefers, and v3 is what allows key rotation later
                // (rotation that this app has never been able to use, because the fielded build was
                // debug-signed by a key nobody holds a rotation lineage for). minSdk here is 26, so
                // v1 is not strictly required — it is left on because dropping it buys nothing and an
                // APK a sideloader refuses to install is a support conversation nobody wants to have
                // with somebody in a village.
                //
                // MEASURED, not assumed, on the live v1.1.20: it verifies under v2 ONLY (v1 false,
                // v3 false), which is what AGP's debug signing produces at minSdk 26. So the first
                // release-key build is the first APK this project has shipped with a v3 signature,
                // and the publish workflow asserts a v2-or-v3 scheme rather than pinning one.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    /**
     * THE FIRST `buildTypes` BLOCK THIS MODULE HAS EVER HAD, and it exists for exactly one reason:
     * to put the release key on the release variant. Everything it does NOT do is deliberate.
     *
     * NO R8, NO RESOURCE SHRINKING. `isMinifyEnabled` stays off, which is the behaviour every build
     * of this app has had to date, and turning it on is a separate change that must be argued and
     * measured on its own. R8's failure mode is not a compile error — it is a `SerializationException`
     * or a `NoSuchMethodError` at the first sync, on a build that assembled and installed perfectly —
     * and this repository has NO device CI and no instrumented tests to catch it. Bundling that with
     * a signing-key change would mean the first release-key APK is also the first shrunk one, and a
     * field failure would have two candidate causes instead of none.
     *
     * NO `ndk { abiFilters }`. The shipped APK packages all four ABIs — read out of the live v1.1.20
     * with `zipfile`, which carries exactly `lib/<abi>/libandroidx.graphics.path.so` four times over
     * and nothing else native. That is roughly 20 KB of waste, not the 22 MB a native-model app pays,
     * so there is no saving here worth the risk of guessing a handset's architecture wrong. Keeping
     * x86_64 also keeps the emulator, which is the only machine a contributor without a handset has.
     */
    buildTypes {
        release {
            /**
             * THE THREE OUTCOMES OF THIS BLOCK, IN THE ORDER THEY ARE TESTED.
             *
             *  1. A real key resolved  -> sign with it. Distributable.
             *  2. `debugSignRelease=true` in a developer's own gitignored local.properties, and NO
             *     real key -> sign with the DEBUG keystore. Installable, and NOT distributable.
             *  3. Neither -> unsigned. The APK builds and installs nowhere, which is loud at the
             *     right moment.
             *
             * THE ORDER IS LOAD-BEARING. `debugSignRelease=true` is a flag somebody sets once and
             * forgets for months. If it were tested first, the presence of a real key would be
             * silently ignored and the build that went to the website and to every handset would be
             * the undistributable one, announced by one lifecycle line among several hundred. THE
             * REAL KEY WINS, ALWAYS.
             *
             * WHY OUTCOME 2 EXISTS AT ALL: a release variant that nobody can install is a release
             * variant nobody ever runs, and "it assembled" is not evidence about a build that behaves
             * differently from debug. A developer with no access to the release key still needs to be
             * able to put a release build on a phone.
             *
             * AND WHY CI CANNOT REACH IT. Two independent bars, because one of them is a file this
             * build cannot see and the other is a check that lives in another repository's file:
             *   • `local.properties` is gitignored (.gitignore, "Android / Gradle" block) and simply
             *     does not exist on a runner, so the flag cannot be set there by accident.
             *   • Should anyone ever write one — a "helpful" CI step, a self-hosted runner with a
             *     stale workspace — the branch below REFUSES rather than signs. `GITHUB_ACTIONS` and
             *     `CI` are set by GitHub Actions on every runner; a build that would otherwise put a
             *     debug-signed APK into an automated pipeline stops here with a sentence naming why.
             * `.github/workflows/publish-android.yml` ALSO greps the build log for outcome 2's
             * lifecycle line and fails the publish on it. That is a third bar and it is not
             * redundant: it is the one that keeps working if this branch is ever edited.
             */
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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            // ── THE UNIT-TEST JVM RUNS AS en_US, DELIBERATELY, AND IT MUST NOT BE en_IN ──────────
            //
            // Not a preference: it is the only way a locale bug in a formatter can be caught by a
            // test at all, and one shipped because it was not here.
            //
            // `DateTimeFormatter.ofPattern(pattern)` with no Locale captures `Locale.getDefault()`
            // AT CONSTRUCTION, and the formatters in this app are `private val`s initialised once at
            // class load. So a test that calls `Locale.setDefault(Locale.US)` in its own body proves
            // NOTHING — the formatter was built before the test method ran, under whatever the
            // machine's locale was. Measured: with the locale pin removed from
            // ui/WorkshopOptions.kt, the whole suite still passed on an en_IN laptop.
            //
            // The locale therefore has to be wrong before the JVM starts, which is what
            // `systemProperty` does — it becomes `-Duser.language=en -Duser.country=US` on the forked
            // test JVM's command line, read at startup.
            //
            // en_US because it is what the GitHub runner uses, so a local run and a CI run agree; and
            // because it differs from en_IN in exactly the way that matters — CLDR abbreviates
            // September "Sep" under en_US and "Sept" under en_IN. That one letter is the whole of the
            // bug that reached `main`: `WorkshopWindowTest` asserted "23 Sept 2026", passed on this
            // developer's machine for weeks, and failed on the first CI run that had Android unit
            // tests to run.
            //
            // ⚠ DO NOT "FIX" A FAILING DATE TEST BY CHANGING THIS TO en_IN. A test that fails here is
            // telling you a formatter is reading the handset's locale, which means a researcher with
            // their phone in Hindi or Bengali sees that script's month inside an English sentence.
            // Pin the FORMATTER's locale instead — see WORKSHOP_DISPLAY_LOCALE in
            // ui/WorkshopOptions.kt. `WorkshopWindowTest` asserts this pin is still in force, so
            // removing these two lines fails the suite rather than quietly weakening it.
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    /*
     * THE VENDORED TRACE ENGINE. See the block in settings.gradle.kts for what these four are and
     * why they are Kotlin/JVM rather than Android library modules.
     *
     * ALL FOUR ARE NAMED THOUGH ONE WOULD COMPILE. `:core-pipeline` declares `api(...)` on the
     * other three, so `implementation(project(":core-pipeline"))` alone would already put every
     * symbol on the compile classpath. They are listed anyway because this file is where somebody
     * looks to find out what :app is built from, and a transitive dependency that only appears in
     * another module's build script is a dependency nobody reads.
     */
    implementation(project(":core-imaging"))
    implementation(project(":core-vector"))
    implementation(project(":core-pipeline"))
    implementation(project(":core-export"))

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

    // A JVM unit-test source set, for the rules that are too important to be verifiable only by
    // looking at a screen.
    //
    // This module had no test dependency at all, which is why `src/test/` did not exist. The rule
    // that made one necessary is `ui/RecordPickers.craftChangeClearsArtisan`: its predecessor
    // silently DELETED a stored artisan link whenever the picker happened not to hold the artisan,
    // and reproducing that by hand needs a repository with more than 100 artisans and a record old
    // enough to sort off page one. That is not a thing anybody re-checks before a release, so the
    // rule is a pure function and this dependency is how it stays checked. Compose is deliberately
    // not on the test classpath — nothing worth asserting here needs a renderer.
    testImplementation("junit:junit:4.13.2")
}
