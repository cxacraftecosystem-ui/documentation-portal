# Field Repository — distribution & install guide (for researchers)

The backend is live at **http://15.207.145.174/api/** (AWS). The Android app and the
web app both talk to it. Nothing needs to run on the researcher's machine except the app.

---

## Can I hand this to researchers now? Yes — here's how

### Android app

1. **Get the APK** to each researcher: `android/app/build/outputs/apk/debug/app-debug.apk`
   (share via Drive / WhatsApp / email / USB).
2. **Install a non-Play APK** (one-time per device): when they tap the file, Android asks to
   allow installs from that app (Files / Chrome / WhatsApp, whichever opened it):
   - Tap the APK → "For your security, your phone is not allowed to install unknown apps from
     this source" → **Settings** → toggle **Allow from this source** → back → **Install**.
   - (Path varies slightly by phone: *Settings → Apps → Special access → Install unknown apps*.)
3. **First launch** the app requests **Camera, Microphone, Location, and Media** permissions —
   accept them so capture and GPS work.
4. **Internet** is required (the app calls the AWS API). Works on Wi-Fi or mobile data, anywhere
   — it is no longer tied to a local network.

No other settings. It is a **debug build** (fine for internal distribution). For a Play Store
listing you'd produce a signed **release** build instead.

### Web app

Once Vercel is configured (see DEPLOY_AWS.md §8.3), researchers just open the Vercel URL in a
browser and sign in. **Important:** the web app is HTTPS and the API is currently HTTP, so until
the API has TLS (a domain + cert) browsers will block the calls (mixed content). The **Android app
is unaffected**.

---

## How researchers get an account (two options)

- **Google sign-in (easiest):** tapping *Continue with Google* auto-creates a **RESEARCHER**
  account on first sign-in. Requires the app/web origin to be registered in Google Cloud (below).
- **Email + password:** an **admin** creates the account from the **Users** screen, then shares
  the credentials. (There is no public self-signup for email/password.)

### Enabling Google sign-in (project owner, one-time, in Google Cloud Console)

The OAuth clients must list the app's identifiers, or Google sign-in returns 403/invalid:

- **Android OAuth client** → add package name `com.fieldrepository.app` and the signing
  **SHA-1**. For the current debug APK that SHA-1 is:
  `36:06:29:E3:5B:9D:74:BF:5B:AC:C9:1D:FC:11:12:34:AE:F5:38:F1`
  (If you later ship a release build, register that keystore's SHA-1 too.)
- **Web OAuth client** → add the web origin (e.g. `https://<your-app>.vercel.app` and, for local
  testing, `http://localhost:3000`) under **Authorized JavaScript origins**.

Until these are registered, use **email/password** accounts.

---

## Roles

- **RESEARCHER** — create and edit their own records; contribute fields to others' records.
- **ADMIN / MASTER_ADMIN** — everything, plus **edit and delete any record** (the red *Danger
  zone → Delete* button on the edit screen, Android and web), manage users, and manage the
  questionnaire. `MASTER_ADMIN` is whoever signs in as `MASTER_ADMIN_EMAIL`.
