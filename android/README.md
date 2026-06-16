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

The default emulator API base URL is `http://10.0.2.2:8000/api/`, which routes from the Android emulator to the host computer. For a physical device, change `DEFAULT_API_BASE_URL` in `app/build.gradle.kts` to your computer LAN address.

Command-line debug build:

```powershell
.\gradlew.bat :app:assembleDebug
```

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
