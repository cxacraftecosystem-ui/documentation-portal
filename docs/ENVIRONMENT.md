# Environment variables — handover reference

**Who this is for:** a developer picking this project up for the first time. It answers, for every
service, "which variables exist, which ones must I set, what happens if I don't, and which ones are
secrets I must never commit or paste into a ticket".

Read it top to bottom once; after that use it as a lookup table. Deployment click-paths live in
[DEPLOYMENT_VERCEL.md](DEPLOYMENT_VERCEL.md) (web) and
[../backend/DEPLOY_AWS.md](../backend/DEPLOY_AWS.md) (API, storage, CI).

---

## Where each file lives

| File | Loaded by | Committed? | Contains secrets? |
|---|---|---|---|
| `backend/.env` | FastAPI (`pydantic-settings`, `app/core/config.py`) and `backend/scripts/*.py` | **No** (gitignored) | **Yes** — DB URL, JWT secret, AWS keys, AI keys |
| `backend/.env.example` | nothing — template | Yes | No, placeholders only |
| `frontend/.env.local` | `next dev` / `next build` | **No** (gitignored via `*.local`) | No — everything in it ships to the browser |
| `frontend/.env.local.example` | nothing — template for local dev | Yes | No |
| `frontend/.env.example` | nothing — template documenting the production shape | Yes | No |
| `.env.example` (repo root) | nothing — aggregate reference of every variable in the monorepo | Yes | No |
| `android/local.properties` | Gradle | **No** (gitignored) | No |
| Vercel dashboard | the frontend build | n/a | No — see the "public means public" rule below |
| GitHub Actions secrets | `.github/workflows/*` | n/a | **Yes** — `BACKEND_ENV`, `EC2_SSH_KEY`, Supabase URL |

Setup, in order, for a fresh machine:

```powershell
docker compose up -d                                    # Postgres :55432, MinIO :9000
cd backend;  Copy-Item .env.example .env                # then edit
cd ..\frontend; Copy-Item .env.local.example .env.local # then edit
```

### Two rules that cause most of the confusion

1. **`NEXT_PUBLIC_*` is not private.** Next.js inlines those values into the JavaScript bundle at
   build time. Anyone can read them in devtools, and changing one requires a **rebuild/redeploy** to
   take effect. Never put a real secret behind that prefix.
2. **The backend caches its settings.** `get_settings()` is `@lru_cache`d, so a running uvicorn
   process never notices an edited `.env`. Always restart (`sudo systemctl restart fieldrepo
   fieldrepo-queue`, or Ctrl-C the dev server) after a change.

---

## Backend — FastAPI (`backend/.env`)

Source of truth: `backend/app/core/config.py`. "Default" is the value the code uses when the
variable is absent; a blank default means the app **refuses to start** without it.

### Database

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `DATABASE_URL` | **Yes** | — | **Yes** | Prisma/Postgres connection string. Use the Supabase **session** pooler URL (`…pooler.supabase.com:5432`) — migrations need session mode for advisory locks and DDL. Not the Supabase REST URL. |
| `DATABASE_USE_TRANSACTION_POOLER` | No | `true` | No | Re-routes *runtime* queries to the transaction pooler (`:6543`, `pgbouncer=true`) so a few workers can't exhaust the 15-connection session pool (`EMAXCONNSESSION`). Leave it on for Supabase. |
| `DATABASE_CONNECTION_LIMIT` | No | `10` | No | Client connections **per uvicorn worker**. Do not raise to 40 — that tripped the pooler's 200-client ceiling (`EMAXCONN`) and crash-looped startup. |
| `DATABASE_POOL_TIMEOUT` | No | unset → Prisma's own (10 s) | No | Seconds to wait for a pooled connection. |
| `DATABASE_REQUIRE_SSL` | No | unset → automatic | No | Forces `sslmode=require` on/off. Unset means: append it for a **remote** host, leave a loopback/private host alone (docker-compose Postgres ships no certificate). A URL that already carries an `ssl*` parameter always wins. Matters because libpq/Prisma default to `sslmode=prefer`, which silently falls back to plaintext. |

### Auth

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `JWT_SECRET` | **Yes** | — | **Yes** | HMAC key signing every access token. Generate with `python -c "import secrets; print(secrets.token_urlsafe(48))"`. Rotating it logs every user out on web and Android. |
| `JWT_EXPIRES_MINUTES` | No | `10080` (7 days) | No | Access-token lifetime. |
| `JWT_ALGORITHM` | No | `HS256` | No | Only `HS256`/`HS384`/`HS512` are accepted; anything else (notably `none`, or an `RS*`/`ES*` algorithm) makes the app refuse to start. That is the algorithm-confusion guard — see `Settings._normalise_jwt_algorithm`. |
| `ALLOW_WEAK_JWT_SECRET` | No | `false` | No | **Local development only.** Lets the API boot with a short/placeholder `JWT_SECRET` instead of refusing to start (`app/core/security.py::verify_jwt_configuration`). Never set it in a deployed environment — a guessable secret lets anyone mint a master-admin token. |

### Security response headers

Emitted by `app.main.SecurityHeadersMiddleware`. Defaults are correct for local development.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `SECURITY_HSTS_ENABLED` | No | `true` | No | Emit `Strict-Transport-Security`. `false` removes the header entirely. |
| `SECURITY_HSTS_MAX_AGE` | No | `63072000` (2 years) | No | HSTS `max-age`, in seconds. |
| `SECURITY_FORCE_HSTS` | No | `false` | No | **Set `true` on the EC2 box.** Production is browser →HTTPS→ CloudFront →HTTP→ nginx →HTTP→ uvicorn, and nginx overwrites `X-Forwarded-Proto` with its own scheme, so the app cannot otherwise tell the viewer used TLS and never emits HSTS. |

### Object storage

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `AWS_ACCESS_KEY_ID` | **Yes** | — | Treat as secret | IAM user (or MinIO account) with `PutObject`/`GetObject`/`DeleteObject` on the media bucket. Local MinIO: `minioadmin`. |
| `AWS_SECRET_ACCESS_KEY` | **Yes** | — | **Yes** | Local MinIO: `minioadmin`. |
| `AWS_REGION` | No | `us-east-1` | No | Production: `ap-south-1`. Must match the bucket's region or presigned URLs 403. |
| `AWS_S3_BUCKET` | **Yes** | — | No | Production: `fieldrepo-media-626159998512`. Local: `field-repository`. |
| `AWS_S3_ENDPOINT` | No | unset | No | **Set only for MinIO/non-AWS storage** (`http://localhost:9000`). Leave it UNSET on AWS so boto3 signs against the dual-stack regional endpoint — that is what makes uploads work from IPv6-only mobile networks. |
| `AWS_S3_PUBLIC_BASE_URL` | No | unset | No | Base URL used to build readable media links. On AWS use the dual-stack host: `https://fieldrepo-media-626159998512.s3.dualstack.ap-south-1.amazonaws.com`. |
| `AWS_S3_SSE_ALGORITHM` | No | `AES256` | No | Server-side encryption requested on uploads the **API** starts (multipart create). `aws:kms` needs a key policy granting the media IAM user. Set it **empty** for local MinIO without a KMS backend, which rejects the header outright. Presigned single PUTs cannot carry it — the bucket's default-encryption setting covers those. |

### Web origins and CORS

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `NEXT_PUBLIC_APP_URL` | No | `http://localhost:3000` | No | Public origin of the web app. Same name as the frontend variable so one `.env` can feed both. |
| `BACKEND_CORS_ORIGINS` | No | `http://localhost:3000` | No | **Comma-separated exact origins** allowed to call the API from a browser. No trailing slash, no path, no wildcard. Every Vercel production/preview/custom domain must be listed or the browser blocks the preflight. |

### Identity and roles

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `GOOGLE_CLIENT_ID` | No | unset | No | Google **web** OAuth client ID; ID tokens from web and Android are verified against it. Same value as the frontend's `NEXT_PUBLIC_GOOGLE_CLIENT_ID`. Unset ⇒ Google login rejected. |
| `GOOGLE_ANDROID_CLIENT_ID` | No | unset | No | Extra accepted audience if Android tokens arrive with the Android client ID. |
| `MASTER_ADMIN_EMAIL` | **Yes** | — | No | Google account permanently at `MASTER_ADMIN` (rank 60). The app will not start without it. |
| `MASTER_ADMIN_NAME` | No | `Ankit Kumar` | No | Display name for that account. |
| `DEFAULT_SIGNUP_ROLE` | No | `CROWDSOURCE_VOLUNTEER` | No | Tier given to brand-new self-registered Google accounts on the six-tier ladder. Set `RESEARCHER` to restore the old open-signup behaviour. |

### Speech-to-text and AI (all optional)

Providers are tried highest-priority-first with automatic failover. With **none** of these set,
uploads still succeed — transcripts and grid measurements simply stay empty.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `ELEVENLABS_API_KEY` | No | unset | **Yes** | Priority 1: ElevenLabs Scribe. Auto language detection, ~1 GB files, no chunking. |
| `ELEVENLABS_STT_MODEL` | No | `scribe_v1` | No | |
| `DEEPGRAM_API_KEY` | No | unset | **Yes** | Priority 2: Deepgram Nova-3. Handles code-switched Hindi + English. |
| `DEEPGRAM_STT_MODEL` | No | `nova-3` | No | |
| `OPENAI_API_KEY` | No | unset | **Yes** | Primary role is transcript **refinement/translation**; only transcribes when neither dedicated STT key is set (priority 3, Whisper). |
| `OPENAI_TRANSCRIPTION_MODEL` | No | `whisper-1` | No | |
| `OPENAI_CHAT_MODEL` | No | `gpt-4o-mini` | No | Rewrites raw transcripts into clean interviewer/interviewee dialogue. |
| `GEMINI_API_KEY` | No | unset | **Yes** | Single legacy key for grid measurement. |
| `GEMINI_API_KEYS` | No | `""` | **Yes** | Any number of comma- or newline-separated keys; the worker rotates and fails over across them. Combined with `GEMINI_API_KEY` and de-duplicated. |
| `GEMINI_MEASUREMENT_MODEL` | No | `gemini-2.5-flash-lite` | No | Pin an id that still exists — `gemini-1.5-flash` now 404s. |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | No | unset | No | Read by the backend only so one `.env` can feed both apps; the browser gets it from the frontend build. |

### Media processing queue

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `MEDIA_QUEUE_WORKER_ENABLED` | No | `true` | No | **Must be `false` on the production web process** — a separate `fieldrepo-queue` systemd unit runs `python -m app.worker` so ffmpeg + AI work never blocks HTTP requests. `true` is right for local dev. |
| `MEDIA_QUEUE_INTERVAL_SECONDS` | No | `5.0` | No | Poll interval between queue sweeps. |
| `MEDIA_QUEUE_BATCH_SIZE` | No | `3` | No | Jobs claimed per sweep. |
| `MEDIA_QUEUE_JOB_MAX_ATTEMPTS` | No | `3` | No | Retries before a job is marked failed. Provider throttling (HTTP 429/503) requeues **without** burning an attempt. |

### Not read by `config.py`

| Variable | Read by | Required | Default | Secret | Notes |
|---|---|---|---|---|---|
| `ADMIN_EMAIL` | `backend/scripts/seed_admin.py` | No | `admin@example.com` | No | First email/password admin, so you can log in before Google OAuth exists. |
| `ADMIN_NAME` | same | No | `Repository Admin` | No | |
| `ADMIN_PASSWORD` | same | No (script skips without it) | — | **Yes** | Change it before any real data is entered. |
| `SUPABASE_REST_URL` | nothing today | No | — | No | Kept so a deployment needing Supabase REST has one place for the values. |
| `SUPABASE_PUBLISHABLE_KEY` | nothing today | No | — | No | Anon/publishable key. |
| `SUPABASE_SECRET_KEY` | nothing today | No | — | **Yes** | Service-role key. Runtime secrets only. |

---

## Frontend — Next.js (`frontend/.env.local`, or the Vercel dashboard)

**Three** variables are read by application code, and **all of them are public** (see rule 1 above).
Sources: `frontend/lib/api.ts:3`, `frontend/app/login/page.tsx:115`,
`frontend/components/forms/LocationFields.tsx:10`. A fourth, `NEXT_PUBLIC_APP_URL`, is documented
here because the **backend** reads it — no frontend code does (verified by grepping `process.env.`
across `frontend/`).

| Variable | Required | Default in code | Local value | Production value | Secret | Notes |
|---|---|---|---|---|---|---|
| `NEXT_PUBLIC_API_URL` | **Yes** in production | `http://localhost:8000` | `http://localhost:8000` | `https://d2b34i3e92al6i.cloudfront.net` | No | **ORIGIN ONLY.** `lib/api.ts` appends `/api` itself, so a trailing `/api` or `/` makes every request 404. Must be `https://` in production or the browser blocks it as mixed content. |
| `NEXT_PUBLIC_APP_URL` | No (frontend) | n/a — **no frontend code reads it** | `http://localhost:3000` | your Vercel/custom domain | No | Shares its name with the backend variable so one `.env` can feed both; only the backend (`config.py`) actually reads it. Setting it in Vercel changes nothing today — it is there so the value stays in sync with the backend and with `BACKEND_CORS_ORIGINS`. |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | No | none | blank | Google web client ID | No | Blank hides the Google button and leaves email/password login. Must equal the backend's `GOOGLE_CLIENT_ID`, and the origin must be an "Authorized JavaScript origin" on that client or GSI returns 403. |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | No | none | blank | MapTiler key | No (restrict by domain) | Blank ⇒ the map coordinate picker degrades to manual latitude/longitude entry. Never blocks data entry. |

There are no server-side environment variables in the frontend: no route handlers, no server
actions, nothing reads a non-`NEXT_PUBLIC_` value.

`frontend/scripts/pw-smoke.mjs` (a Playwright smoke script, never bundled into the app) reads
`PW_BASE` (default `http://localhost:3000`), `PW_EMAIL` (default `admin@example.com`) and
`PW_PASSWORD` (no default) from the shell.

---

## Android (`android/local.properties`)

Gradle properties, not environment variables — one line, gitignored.

| Property | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `apiBaseUrl` | No | `https://d2b34i3e92al6i.cloudfront.net/api/` (compiled into `BuildConfig.DEFAULT_API_BASE_URL`) | No | Note this one **does** include the trailing `/api/` — the opposite of the web variable. Emulator: `http://10.0.2.2:8000/api/`. Physical device on your LAN: `http://192.168.1.x:8000/api/`, with the backend started as `--host 0.0.0.0`. |

The Google web client ID is compiled in from `android/app/build.gradle.kts`
(`GOOGLE_WEB_CLIENT_ID`), not supplied via a property.

---

## Local infrastructure (`docker-compose.yml`)

Fixed values, listed so nothing looks mysterious. Change them only if you also change
`backend/.env`.

| Service | Setting | Value |
|---|---|---|
| postgres | `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `postgres` / `postgres` / `field_repository`, published on host port **55432** |
| minio | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | `minioadmin` / `minioadmin`, API on **9000**, console on **9001** |
| create-bucket | — | one-shot job creating the public-download bucket `field-repository` |

---

## Repository automation (GitHub Actions secrets)

Set at **Settings → Secrets and variables → Actions**. Never in a file.

| Secret | Used by | Required | Secret | Notes |
|---|---|---|---|---|
| `EC2_HOST` | `deploy-backend.yml` | Yes | No | Elastic IP of the API box. |
| `EC2_SSH_KEY` | `deploy-backend.yml` | Yes | **Yes** | Private `.pem` contents for the EC2 key pair. |
| `BACKEND_ENV` | `deploy-backend.yml` | Yes | **Yes** | The **entire** `backend/.env` file. Piped to the box over SSH — never echoed to logs. This is where you edit `BACKEND_CORS_ORIGINS` for production. |
| `SUPABASE_KEEPALIVE_URL` / `SUPABASE_DATABASE_URL` | `keep-supabase-active.yml` → `scripts/keep-supabase-active.mjs` | One of them (falls back to `DATABASE_URL`) | **Yes** | The script rewrites a Supabase pooler URL from `:5432` to `:6543`, because a session-mode keep-alive is rejected with `EMAXCONNSESSION` while the live backend holds those 15 slots. |
| `SUPABASE_KEEPALIVE_NO_REWRITE` | same | No | No | `"true"` disables that `:5432 → :6543` rewrite. Only for a non-Supabase database. |
| `SUPABASE_DB_SSL` | same | No | No | `"false"` disables TLS for the keep-alive connection; any other value keeps SSL on. |

Terraform (`infra/terraform/`) additionally reads `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
from your shell — use an IAM admin user's key pair, never root keys. `terraform.tfstate` and
`*.tfvars` are gitignored because they contain the generated media access key.

---

## Quick triage

| Symptom | Almost always |
|---|---|
| Every page loads but all lists are empty and login fails with 404 | `NEXT_PUBLIC_API_URL` has a trailing `/api` or `/` |
| Requests never leave the browser; console says "Mixed Content" | `NEXT_PUBLIC_API_URL` is `http://` on an `https://` page |
| "blocked by CORS policy" but curl works | The origin is missing from `BACKEND_CORS_ORIGINS`, or the backend wasn't restarted |
| Env change in Vercel had no effect | `NEXT_PUBLIC_*` is compiled in — redeploy without the build cache |
| Backend won't start | A required variable is missing: `DATABASE_URL`, `JWT_SECRET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_BUCKET`, `MASTER_ADMIN_EMAIL` |
| `EMAXCONNSESSION` / `EMAXCONN` in the logs | `DATABASE_CONNECTION_LIMIT` raised, or more than one uvicorn worker; keep 1 web worker + the separate queue service |
| Works on Wi-Fi, fails on mobile data | An IPv4-only host slipped in — use the CloudFront and `s3.dualstack.…` hostnames |
| Google button missing or 403 | `NEXT_PUBLIC_GOOGLE_CLIENT_ID` blank, or the origin is not an Authorized JavaScript origin |
| Transcripts stay empty | No STT key set (`ELEVENLABS_API_KEY` / `DEEPGRAM_API_KEY` / `OPENAI_API_KEY`), or the queue worker isn't running |
