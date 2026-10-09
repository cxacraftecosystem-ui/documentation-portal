# Field Repository Android App

Kotlin + Jetpack Compose Android client for the same FastAPI backend used by the web app.

## Capabilities

- Login with `POST /api/auth/login`
- Google sign-in through Android Credential Manager
- Persist JWT locally
- Load dashboard stats with `GET /api/dashboard/stats`
- Create craft records with `POST /api/crafts`
- Create artisan records with `POST /api/artisans`
- Create workshop records with `POST /api/workshops`
- Create product records with `POST /api/products`
- Create tool records with `POST /api/tools`
- Document processes with ordered steps (each step has media + an optional "record additional information" notes box) via `POST /api/processes`; the form cascades artisan → that artisan's products
- "Document using grid": length + breadth from one top-down photo, height from a side-on photo (`POST /api/media/analyze-measurement`), auto-filling the fields
- Split long audio/video into `PART_1`, `PART_2`, … (re-mux at sync frames) before streaming upload, so each part stays under the transcription/upload limits and large videos never exhaust the heap
- Preview previously-uploaded media with uploader/date provenance and a **Save to device** download
- Create questionnaire interviews with `POST /api/questionnaire/interviews`
- Send `Authorization: Bearer <token>` on every protected API call
- Requests camera, audio and location permissions for field capture workflows

## Run

1. Start backend from the repo root:

```powershell
docker compose up -d
cd backend
.\.venv\Scripts\Activate.ps1
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

2. Open `android/` in Android Studio.
3. Sync Gradle.
4. Run the `app` configuration on an emulator.
5. Log in with the admin email and password from your private backend `.env`, or use Google sign-in after OAuth is configured.

With no override the app talks to **production** (the CloudFront HTTPS base compiled into
`app/build.gradle.kts`). To point a build at your own backend, keep source code unchanged and add an
ignored local override in `android/local.properties`. Three ways, simplest first:

```properties
# 1. Any emulator or USB-connected phone, after `adb reverse tcp:8000 tcp:8000`.
#    Loopback: no extra permission, no network config edit, works for release-variant builds too.
apiBaseUrl=http://127.0.0.1:8000/api/

# 2. The emulator's alias for the host computer.
apiBaseUrl=http://10.0.2.2:8000/api/

# 3. A physical device on the same Wi-Fi: run the backend on 0.0.0.0, and also add the IP to
#    app/src/main/res/xml/network_security_config.xml temporarily (that file says how).
apiBaseUrl=http://YOUR_COMPUTER_LAN_IP:8000/api/
```

**On Android 17, options 2 and 3 need a permission.** From targetSdk 37 a connection to a
local-network address times out unless the app holds `ACCESS_LOCAL_NETWORK` (Settings → Apps → Field
Repository → Permissions → Nearby devices). Debug builds declare it (`app/src/debug/AndroidManifest.xml`)
and ask for it at launch when the API base is such an address (`ui/LocalNetworkAccess.kt`); allow it.
Release builds never declare it, so a release-variant build aimed at your machine must use option 1.

Command-line debug build:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Toolchain

| | |
|---|---|
| Gradle | 9.8.1 (the wrapper; its distribution is checksum-pinned in `gradle/wrapper/gradle-wrapper.properties`) |
| JDK that runs the build | 25 (Temurin LTS), in CI and locally. Gradle 9.8 itself runs on 17–27, but Kotlin 2.4 documents Java only up to 26 |
| Android Gradle Plugin | 9.4.1, with built-in Kotlin — there is no `org.jetbrains.kotlin.android` plugin |
| Kotlin | 2.4.21 (Compose compiler and serialization plugins at the same version) |
| Bytecode | Java 17 for every module, the newest level Android documents |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |

Install the `Android SDK Platform 37.0` and `Build-Tools 37.0.0` packages. Dependabot proposes Gradle,
AGP, Kotlin and library updates monthly (`.github/dependabot.yml`); the JDK and the SDK levels are moved
by hand, in the build scripts and the three Android workflows together.
`.github/workflows/android-emulator.yml` runs the debug build on an emulator when asked.

## Google OAuth

The Android application ID and OAuth package name are:

```text
com.fieldrepository.app
```

Create an Android OAuth client in Google Cloud Console with that package name and the SHA-1 fingerprint for the certificate used to sign the build. For a local debug build, get the SHA-1 with:

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

The app uses `GOOGLE_WEB_CLIENT_ID` from `app/build.gradle.kts` as Credential Manager's server client ID. The backend must also have the same web client ID set as `GOOGLE_CLIENT_ID` so it can verify Google ID tokens.

The Android OAuth client ID configured for the package is:

```text
614092441670-5rckig6t1al6plbfll8irn9prcmp446t.apps.googleusercontent.com
```

## Capture Notes

The Android manifest includes permissions for precise location, camera, audio recording and Android 13 media reads. The compact Compose UI supports field data entry, craft assignment, dimensions, UTC record timestamps and questionnaire submission through the same backend used by the web app. The web record forms provide the complete embedded batch upload, waveform recording, transcription and Gemini grid-measurement workflow.
