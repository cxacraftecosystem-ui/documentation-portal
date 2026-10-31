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
| Vercel dashboard | the frontend build (via `vercel pull` on the CI runner) | n/a | No — see the "public means public" rule below, and rule 3 on the variable **type** |
| GitHub Actions secrets | `.github/workflows/*` | n/a | **Yes** — `BACKEND_ENV`, `EC2_SSH_KEY`, Supabase URL |

Setup, in order, for a fresh machine:

```powershell
docker compose up -d                                    # Postgres :55432, MinIO :9000
cd backend;  Copy-Item .env.example .env                # then edit
cd ..\frontend; Copy-Item .env.local.example .env.local # then edit
```

### Three rules that cause most of the confusion

1. **`NEXT_PUBLIC_*` is not private.** Next.js inlines those values into the JavaScript bundle at
   build time. Anyone can read them in devtools, and changing one requires a **rebuild/redeploy** to
   take effect. Never put a real secret behind that prefix.
2. **The backend caches its settings.** `get_settings()` is `@lru_cache`d, so a running uvicorn
   process never notices an edited `.env`. Always restart (`bash /home/ubuntu/app/backend/deploy/blue_green.sh restart` on the
   box, or Ctrl-C the dev server) after a change.
3. **In Vercel, every `NEXT_PUBLIC_*` variable must be type `Encrypted`, never `Sensitive`.**
   Sensitive is write-only: Vercel will not return that value to anyone afterwards, including to the
   `vercel pull` our CI runs before it builds. Because the build happens on a GitHub runner rather
   than on Vercel, a sensitive variable simply is not present when Next.js compiles, and Next.js
   inlines `undefined` instead of failing — a green pipeline over a site that cannot log anyone in.
   And it buys nothing even in principle, since rule 1 says the value is published to every visitor
   regardless. Audit it with `vercel env ls production`; the full story is
   [DEPLOYMENT_VERCEL.md §2.2](DEPLOYMENT_VERCEL.md).

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
| `TWO_FACTOR_ENCRYPTION_KEY` | No (recommended) | unset → legacy key derived from `JWT_SECRET`, with a WARNING at start-up | **Yes** | `<key id>:<32 bytes base64url>` sealing every two-step (authenticator) secret (`app/core/two_factor_keys.py`); the key id is written into each ciphertext (`v2.<key id>.…`). Make one with `cd backend && python -m scripts.reencrypt_two_factor_secrets --generate-key`. A malformed value refuses to start. Production: keep it in the `TWO_FACTOR_ENCRYPTION_KEY` Actions secret (or in `BACKEND_ENV`), which `deploy-backend.yml` pins onto the box; a key only set by hand on the box is carried forward by the deploy, but the Actions secret is the copy that survives a rebuilt box. Every key seen on the box, in `BACKEND_ENV` or in the secret that is not the current one is carried into `_PREVIOUS_KEYS`, a key id with two different keys fails the deploy, a malformed secret fails it before the box is touched, and after the restart a dry run fails it if any secret opens with no configured key. Unset, **rotating `JWT_SECRET` locks every enrolled person out of their authenticator**. |
| `TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS` | No | empty | **Yes** | Comma-separated keys in the same format that still OPEN secrets sealed with them. Rotation: new key in `TWO_FACTOR_ENCRYPTION_KEY`, old one here, restart; each secret moves to the new key the next time it is used, or all at once with `python -m scripts.reencrypt_two_factor_secrets --apply` (a dry run without `--apply`; idempotent). Drop the old key once a dry run says `would move: 0` and `unreadable: 0`. Legacy `v1` secrets are always read with the `JWT_SECRET`-derived key. |
| `AADHAAR_FINGERPRINT_KEY` | **Yes, to store or match an Aadhaar** | — | **Yes** | HMAC key the artisan register fingerprints Aadhaar numbers under (`app/services/aadhaar_fingerprint.py`): 32 random bytes, base64 (`python -c "import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"`). **The same value as the designer portal's**, so the two registers match. Unset or under 32 characters: any write or lookup carrying an Aadhaar answers 503. Never rotate it without re-entering every number; losing it makes every stored fingerprint unmatchable. Actions secret `AADHAAR_FINGERPRINT_KEY` (pinned onto the box by `deploy-backend.yml`), SSM backup `/iit/shared/aadhaar-fingerprint-key`. |
| `TRUSTED_PROXY_HOPS` | No | `2` | No | How many proxies of ours append to `X-Forwarded-For` (CloudFront, then nginx). The per-client limits key on the entry the outermost wrote. Safe only while the box admits ports 80 and 443 from CloudFront's origin-facing prefix list alone (the CloudFront lock); `1` on any box reachable directly. Pinned by `deploy-backend.yml`: 2 when the box does not answer the runner directly, 1 when it does. |
| `SECRETS_ENCRYPTION_KEY` | No | unset → derived from `JWT_SECRET` | **Yes** | Fernet key encrypting the runtime-editable provider keys stored in `ManagedSecret` (`app/services/managed_secrets.py`). Left unset it is derived deterministically from `JWT_SECRET`, which is why the feature needs no setup — but it also means **rotating `JWT_SECRET` makes every stored secret undecryptable** and each one has to be re-entered in the Settings hub. Set it explicitly *before* you ever rotate. Accepts a Fernet key or any high-entropy passphrase. |

### Authenticated-identity cache

`get_current_user` reads the user row on **every** authenticated request, and that read is one
cross-region round trip (200–400 ms) before any of the request's real work starts. This cache removes
it for a few seconds at a time. Unlike everything under `SCALE_*`, it is **on by default**, because
the cost it removes is being paid right now and a flag that had to be switched on would leave it
there.

Read [SECURITY.md §4.1](SECURITY.md) before changing any of these — the row being cached is the one
authorisation decisions read, so the TTL is a revocation window.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `AUTH_USER_CACHE_ENABLED` | No | `true` | No | `false` restores one query per request. **This is the kill switch**: if the cache is ever suspected of serving a stale role during an incident, this reverts the behaviour with a restart and no deploy. |
| `AUTH_USER_CACHE_TTL_SECONDS` | No | `5.0` | No | Seconds, and deliberately single-digit. Writes *this process* makes invalidate explicitly and have no window at all; the TTL is only the backstop for writes it cannot see — a `psql` session, the seed script, another worker. Every second added here is a second a demoted or deleted account keeps working after such a write. |
| `AUTH_USER_CACHE_MAX_ENTRIES` | No | `512` | No | LRU ceiling. A cached row is the `User` model's scalar columns, order 1 KB, so 512 identities is well under a megabyte on a 1 GiB box. |

### API schema exposure

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `BACKEND_EXPOSE_DOCS` | No | **`false`** | No | Serve `/docs`, `/redoc` and `/openapi.json`. FastAPI serves all three to anyone by default; the schema names every route, parameter and model field including admin-only ones. Closed by default because the production `.env` lives in a GitHub secret this repository cannot edit, so a default-on flag would leave them exposed exactly where it matters. Set `true` for local development. See [SECURITY.md §1.4](SECURITY.md). |

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
| `AWS_S3_SSE_ALGORITHM` | No | `AES256` | No | Server-side encryption requested on uploads the **API** starts (multipart create). `aws:kms` needs a key policy granting the media IAM user. Off-site replication copies SSE-S3 objects only, so an `aws:kms` object is never copied to the off-site bucket (SSE-KMS for the off-site copies is deferred to the production launch: decision D1 in the IIT workspace deferred-for-launch log). Set it **empty** for local MinIO without a KMS backend, which rejects the header outright. Presigned single PUTs cannot carry it — the bucket's default-encryption setting covers those. |

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
| `MICROSOFT_CLIENT_ID` | No | unset | No | Application (client) ID of the Microsoft Entra app registration. With `MICROSOFT_CLIENT_SECRET` it turns Microsoft sign-in on (`app/services/oidc_sign_in.py`): the clients bring back an authorization code and this server redeems it and verifies the ID token against Microsoft's keys. Either one unset ⇒ every Microsoft sign-in is refused with "not available here". Same value as the frontend's `NEXT_PUBLIC_MICROSOFT_CLIENT_ID` and Android's `microsoftClientId`. |
| `MICROSOFT_CLIENT_SECRET` | With the above | unset | **Yes** | A client secret of that registration (Certificates & secrets). Backend only — never in a client. It expires (24 months at most); a lapsed one turns every Microsoft sign-in into "did not complete" and logs `redemption-refused` with `invalid_client`. |
| `MICROSOFT_TENANT` | No | `common` | No | `common` (work, school and personal accounts), `organizations` (work and school only), `consumers` (personal only) or one tenant's ID. Must match "Supported account types" on the registration, and `NEXT_PUBLIC_MICROSOFT_TENANT` / Android's `microsoftTenant`. A tenant given by domain name is refused (Microsoft sign-in stays off and an ERROR is logged): tokens name their tenant by ID. |
| `YAHOO_CLIENT_ID` | No | unset | No | Client ID (Consumer Key) of the Yahoo app. With `YAHOO_CLIENT_SECRET` it turns Yahoo sign-in on. Same value as `NEXT_PUBLIC_YAHOO_CLIENT_ID` and Android's `yahooClientId`. |
| `YAHOO_CLIENT_SECRET` | With the above | unset | **Yes** | Client Secret (Consumer Secret) of that app. Yahoo's token endpoint accepts nothing but a secret, which is why the code is redeemed here and never on a phone or in a browser. |
| `MASTER_ADMIN_EMAIL` | **Yes** | — | No | Google account permanently at `MASTER_ADMIN` (rank 60). The app will not start without it. |
| `MASTER_ADMIN_NAME` | No | `Ankit Kumar` | No | Display name for that account. |
| `DEFAULT_SIGNUP_ROLE` | No | `CROWDSOURCE_VOLUNTEER` | No | Tier given to brand-new self-registered Google accounts on the six-tier ladder. Set `RESEARCHER` to restore the old open-signup behaviour. |

### Speech-to-text and AI (all optional)

Providers are tried highest-priority-first with automatic failover. With **none** of these set,
uploads still succeed — transcripts and grid measurements simply stay empty.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `ELEVENLABS_API_KEY` | No | unset | **Yes** | Priority 1: ElevenLabs Scribe. Auto language detection, ~1 GB files, no chunking. |
| `ELEVENLABS_STT_MODEL` | No | `scribe_v2` | No | Read the note below before setting it. |
| `DEEPGRAM_API_KEY` | No | unset | **Yes** | Priority 2: Deepgram Nova-3. Handles code-switched Hindi + English. |
| `DEEPGRAM_STT_MODEL` | No | `nova-3` | No | |
| `OPENAI_API_KEY` | No | unset | **Yes** | Primary role is transcript **refinement/translation**; only transcribes when neither dedicated STT key is set (priority 3, Whisper). |
| `OPENAI_TRANSCRIPTION_MODEL` | No | `whisper-1` | No | |
| `OPENAI_CHAT_MODEL` | No | `gpt-4o-mini` | No | Rewrites raw transcripts into clean interviewer/interviewee dialogue. |
| `GEMINI_API_KEY` | No | unset | **Yes** | Single legacy key for grid measurement. |
| `GEMINI_API_KEYS` | No | `""` | **Yes** | Any number of comma- or newline-separated keys; the worker rotates and fails over across them. Combined with `GEMINI_API_KEY` and de-duplicated. |
| `GEMINI_MEASUREMENT_MODEL` | No | `gemini-2.5-flash-lite` | No | Pin an id that still exists — `gemini-1.5-flash` now 404s. |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | No | unset | No | Read by the backend only so one `.env` can feed both apps; the browser gets it from the frontend build. |

> **`ELEVENLABS_STT_MODEL` defaults to `scribe_v2`** (since 31 Oct 2026; it used to read `scribe_v1`
> in `config.py` while `scribe_v2` ran). `_elevenlabs_model` in `app/services/ai.py` still treats
> `scribe_v1` as "unset", so writing it into a `.env` does **not** pin the old model. (`scribe_v1` is
> still used as the conservative retry when a `scribe_v2` request is rejected.) A deployment that
> genuinely needs the legacy model must change the code, not the variable.

Provider ordering is a **runtime** setting, not an environment variable: a master admin ranks the
three providers in the Settings hub, stored on `AppSetting`. The default order is generated into
[REPO_FACTS.md](REPO_FACTS.md). A provider whose key is unset is skipped wherever it is ranked, and
keys resolve through the managed-secret layer, so adding one in the UI extends the chain immediately
with no restart. Full semantics: [ARCHITECTURE.md §6](ARCHITECTURE.md).

### Media processing queue

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `MEDIA_QUEUE_WORKER_ENABLED` | No | `true` | No | **Must be `false` on the production web process** — a separate `fieldrepo-queue` systemd unit runs `python -m app.worker` so ffmpeg + AI work never blocks HTTP requests. `true` is right for local dev. |
| `MEDIA_QUEUE_INTERVAL_SECONDS` | No | `5.0` | No | Poll interval between queue sweeps. |
| `MEDIA_QUEUE_BATCH_SIZE` | No | `3` | No | Jobs claimed per sweep. |
| `MEDIA_QUEUE_JOB_MAX_ATTEMPTS` | No | `3` | No | Retries before a job is marked failed. Provider throttling (HTTP 429/503) requeues **without** burning an attempt. |

### E-mail (Amazon SES)

Mail is **on exactly when `MAIL_FROM_ADDRESS` is set**. Unset, nothing is queued, `GET /api/preferences/notifications` answers `available: false`, and the web hides every e-mail control (the Settings switch, the two "E-mail" choices on the access roster) without a sentence about it. Messages are queued as `EmailMessage` rows and sent by the same `fieldrepo-queue` drain as the media queue (`services/email_outbox.py`, `services/mailer.py`). The SES call uses the `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` above, so that IAM user needs `ses:SendEmail` on the verified identity. What is e-mailed is in [SECURITY.md](SECURITY.md) §4B.

**In-app notices (C25).** Every notice also lands in the person's in-app activity list — the web's bell and `/notifications`, Android's Notifications screen (kept offline, with a local alert after each sync; no push service) — whether or not mail is on. One helper does both: `notify(user, kind, title, body, link, email_kind=…, email_params=…, preference=…)` in `backend/app/services/notifications.py` writes the `Notification` row and queues the e-mail beside it, honouring the e-mail opt-out for the e-mail only. New notices go through it; `email_outbox.enqueue` alone is for a password link and for an address with no account. The list is served by `backend/app/api/routes/notifications.py`, the same four routes on both portals.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `MAIL_FROM_ADDRESS` | No | unset (mail off) | No | The sender. Must be an SES-verified identity (the address or its domain) in `MAIL_SES_REGION`. |
| `MAIL_FROM_NAME` | No | `Field Repository` | No | Display name in the From header. |
| `MAIL_REPLY_TO` | No | unset | No | Where replies go. Unset means the From address. |
| `MAIL_SES_REGION` | No | `ap-south-1` | No | The SES region; identities and production access are per region. |
| `MAIL_SES_CONFIGURATION_SET` | No | unset | No | An SES configuration set, for bounce and complaint events. |
| `MAIL_MAX_ATTEMPTS` | No | `5` | No | Sends per message before `FAILED`. A throttle or SES fault is retried after 1, 2, 4, 8 … minutes (capped at an hour); a rejection fails at once. |
| `MAIL_BATCH_SIZE` | No | `10` | No | Messages sent per queue sweep. |
| `WEEKLY_DIGEST_ENABLED` | No | `true` | No | Kill switch for the Monday summary's scheduler only. Off, the worker writes no new summary; the Settings switch and summaries already written still work. Deployed from the Actions **variable** of the same name (`true`/`false`; unset keeps the box's value). |

**The Monday summary.** Every Monday at 08:00 IST the `fieldrepo-queue` worker writes one summary for each Master Admin, Admin, Professor and holder of a review grant: records to review and tasks to check, what is overdue (reviews waiting over 7 days, tasks past due), what came in during the week, and how many districts approved artisans cover. It lands in the activity list always and is e-mailed (`WEEKLY_DIGEST`) when mail is on. Counts only: no record label, name or place. A worker that was down at 08:00 catches up the same day; after Monday that week is skipped, never sent late. One `WeeklyDigest` row per person per week (`(userId, weekStart)` unique) makes it idempotent; a week with nothing waiting, overdue or new sends nothing; rows are purged after 26 weeks. Each person can switch it off in Settings (`UserPreference.weeklyDigest`, on by default, read at send time) through `GET`/`PUT /api/notifications/digest`, which works with mail off. The summary page is `/digest/<id>` (`GET /api/notifications/digests/{id}`, the owner's only; anyone else gets a 404). Code: `backend/app/services/weekly_digest.py`. The designer portal has the same table, routes and schedule.

**The owner's steps to turn it on** (nothing in this repository creates AWS resources): verify the sending domain (DKIM) or address in SES **ap-south-1**; request SES production access there; grant the backend IAM user `ses:SendEmail` on the identity; add `MAIL_FROM_ADDRESS` (and optionally the others) to the `BACKEND_ENV` secret and let the next deploy restart both units.

### Optional scaling layer — `SCALE_*` and the read replica

**Every one of these is off or unset by default**, and a fresh clone that sets none of them runs
exactly the code paths it runs today. The package they configure is not imported on the request path
until a route opts in.

They are **not tabulated here**, deliberately: `backend/app/scale/README.md` documents each one — what
it buys, what it costs, how to verify it — and [SCALABILITY.md](SCALABILITY.md) explains when any of
them is the right answer. Duplicating them would create a second place to be wrong.

The names, so this file remains a complete index of what `config.py` reads:

- Response cache: `SCALE_CACHE_ENABLED`, `SCALE_CACHE_BACKEND`, `SCALE_CACHE_TTL_SECONDS`,
  `SCALE_CACHE_MAX_ENTRIES`, `SCALE_CACHE_MAX_BYTES`, `SCALE_CACHE_MAX_ENTRY_BYTES`,
  `SCALE_CACHE_SINGLEFLIGHT_TIMEOUT_SECONDS`
- Redis backend: `SCALE_REDIS_URL`, `SCALE_REDIS_TIMEOUT_SECONDS`
- Rate limiting: `SCALE_RATE_LIMIT_ENABLED`, `SCALE_RATE_LIMIT_REQUESTS`,
  `SCALE_RATE_LIMIT_WINDOW_SECONDS`
- Pagination and counts: `SCALE_KEYSET_PAGINATION_ENABLED`, `SCALE_APPROX_COUNT_ENABLED`,
  `SCALE_APPROX_COUNT_THRESHOLD`
- `DATABASE_READ_REPLICA_URL` — unset by default; a replica connection string for read-only queries.

### Cross-portal artisan identity (C27) — shipped OFF

One artisan across the two portals. A designer types the Aadhaar or Pehchan number, confirms the
artisan agreed, and the designer portal's server asks the documentation portal's server for the
artisan; the form is filled and the records linked. The documentation portal is the source of truth
for name, local name, gender, date of birth and the Pehchan pair; a linked record is refreshed from
it. The raw Aadhaar number never leaves either server: the designer portal sends
`HMAC-SHA256(AADHAAR_FINGERPRINT_KEY, digits)`, which is exactly the `aadhaarFingerprint` the artisan register stores, so each side compares stored fingerprints and needs no number. Each server proves itself to the other with
`CROSS_PORTAL_TOKEN` (a bearer on the request, an HMAC of the body on the answer). The documentation
portal answers only for an artisan whose "Share with the Designer Portal" consent is recorded.
Every answer is signed over the request it answers (method, path and a fresh nonce), every peer
request is written to `CrossPortalAccessLog` (which designer-portal account asked and which artisan
it reached, never a number), and every consent change is a revision in the artisan's history.

**Nothing works until all of it is set on BOTH servers** — the flag, `CROSS_PORTAL_TOKEN` and
`AADHAAR_FINGERPRINT_KEY` (32 characters or more, the same values on the two servers). Anything missing and every `/api/cross-portal/*`
route answers 404 and no client draws anything. Secrets go to the GitHub Actions secrets **and** the
box `.env`; none is created by this repository.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `CROSS_PORTAL_IDENTITY_ENABLED` | No | **`false`** | No | The switch. Off: the feature does not exist. |
| `CROSS_PORTAL_TOKEN` | With the flag on | — | **Yes** | Shared by both servers. Authenticates requests and signs answers. 32+ characters. |
| `AADHAAR_FINGERPRINT_KEY` | With the flag on | — | **Yes** | Not a cross-portal variable: the artisan register's Aadhaar fingerprint key (SSM `/iit/shared/aadhaar-fingerprint-key`, the same value on both servers), which the lookup reuses. Without it the feature stays off. Never rotate it: every stored fingerprint, and so every cross-portal match, depends on it. There is no `CROSS_PORTAL_AADHAAR_HMAC_KEY`. |

### One search across both portals — shipped OFF

One index per portal (`SearchDocument`), kept current by database triggers and drained by the queue
worker every 15 seconds; ranked by Postgres full-text and trigram, and by meaning when embeddings
are on. The designer portal's results arrive over the C27 signed channel for the roles allowed to
see them, and this portal answers the designer portal's searches only with records it may share
(every artisan named has granted sharing consent). [SEARCH.md](SEARCH.md) has the whole contract.
Roll out in this order: both backends with every flag off; `UNIFIED_SEARCH_ENABLED` (let the queue
drain); the cross-portal values on both servers, then `CROSS_PORTAL_SEARCH_ENABLED`; embeddings last.
Every value goes to the GitHub Actions secrets **and** the box `.env`.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `UNIFIED_SEARCH_ENABLED` | No | **`false`** | No | Opens `GET /api/search/unified` and lets the worker build the index. Off: the route answers 404, clients use `GET /api/search`, and the queue only fills. |
| `SEARCH_EMBEDDINGS_ENABLED` | No | **`false`** | No | The AI flag. Meaning ranking runs only when this is on, the provider has a key (`OPENAI_API_KEY`, or the Gemini keys) and the `embedding` column exists (pgvector). Off: `"mode": "text"` and no provider call. |
| `SEARCH_EMBEDDING_PROVIDER` | No | `openai` | No | `openai` or `gemini`. Gemini's free tier may train on inputs; keep it opt-in. |
| `SEARCH_EMBEDDING_MODEL` | No | `text-embedding-3-small` | No | Asked for 768 dimensions, fixed in code and in the migration. |
| `CROSS_PORTAL_SEARCH_ENABLED` | No | **`false`** | No | Asks the designer portal and answers its searches (`/api/cross-portal/peer/search*`). Also needs `CROSS_PORTAL_TOKEN`, `AADHAAR_FINGERPRINT_KEY` and `DESIGNER_PORTAL_API_URL`; the designer portal needs its own flag, the same secrets and `FIELD_REPOSITORY_API_URL`. |
| `DESIGNER_PORTAL_API_URL` | With cross-portal search | — | No | The designer portal's API base up to `/api`, e.g. `https://<designer-api-host>/api`. Only cross-portal search calls it. |

### Cross-portal coverage feed — shipped OFF

The designer portal's ministry coverage map can show this portal's figures beside its own. With the
flag on, its server may call `GET /api/cross-portal/peer/coverage`, which answers per-district
**aggregate counts only** (workshops, artisans, products, tools, media) and the shared craft codes
documented — no ids, names or records. The request carries the same bearer, nonce and signed answer
as the identity feed, and is written to `CrossPortalAccessLog` as `COVERAGE`. It is independent of
`CROSS_PORTAL_IDENTITY_ENABLED`. Flag off, or token missing or shorter than 32 characters: the route
answers 404. This portal's own coverage map (`/map?view=coverage`) needs no flag.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `CROSS_PORTAL_COVERAGE_ENABLED` | No | **`false`** | No | The switch. Also needs `CROSS_PORTAL_TOKEN` (above), the same value as on the designer portal. Set it in the GitHub Actions secrets **and** the box `.env`. |

### Duplicate artisans

The server scores every new or edited artisan against the register (`app/services/artisan_duplicates.py`)
and stores likely pairs for the admin review screen (`/admin/duplicates`); a pair is merged on
`/admin/artisan-merge`. A create that looks like an
artisan already recorded (score 0.75 or more) is held with a 409 until the person saving says
"Different person".

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `ARTISAN_DUPLICATE_HOLD` | No | **`true`** | No | Kill switch for the hold at save time. Off: every create saves straight away, and its likely pairs are still stored for review. Scoring and the admin screen stay on. The thresholds (show 0.60, hold 0.75) and the 30-day undo window are constants in code, not settings. |

### Artisan cards — on once the key is set

A printable card per artisan (ID-1, A6, or A4 sheets of ten for a workshop) with a QR and a short
code under it. Both carry an opaque card code and, in the QR, a tag signed with `ARTISAN_CARD_KEY` —
never the record id or anything personal. Typing the short code works without the QR. A card from the
Design Prototype Workshop opens an artisan here only when that portal has linked the two records
(cross-portal link, above); this portal answers that portal's card lookups only for an artisan whose
sharing consent is recorded.

Without a key of 32 characters or more every card route answers 404, `/api/artisan-cards/status`
says `enabled: false`, and no client draws the card. Store the key in the GitHub Actions secrets,
the box `.env` and an SSM SecureString backup. Then run `python -m scripts.backfill_artisan_cards
--apply` from `backend/` once, so every artisan has a card before phones cache the register.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `ARTISAN_CARDS_ENABLED` | No | `true` | No | The switch. Cards still need the key. |
| `ARTISAN_CARD_KEY` | For cards | — | **Yes** | 32 random bytes, base64. **Different from the designer portal's**; never shared. Losing it stops QR verification only: typed codes still resolve. |
| `ARTISAN_CARD_KEY_VERSION` | No | `1` | No | One digit, printed in every QR. Bump it to rotate. |
| `ARTISAN_CARD_KEY_PREVIOUS` | No | — | **Yes** | The key before the last rotation, so cards printed under it keep verifying. |
| `ARTISAN_CARD_RESOLVE_MISS_LIMIT` | No | `20` | No | Unmatched card lookups one account may make in ten minutes before 429. Hits never count. |
| `ARTISAN_CARD_ISSUER` | No | `F` | No | The letter on this portal's cards. `D` belongs to the designer portal. |

### AI-drafted interview answers (C28) — shipped OFF

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `AI_ANSWER_DRAFTS_ENABLED` | No | **`false`** | No | Lets a researcher draft a section's unanswered questions from the interview's transcript. Drafts are inert until a person accepts or edits the section; accepted answers carry `answerOrigin` `MACHINE_SUGGESTED` / `MACHINE_SUGGESTED_EDITED` and are labelled "Machine-suggested". Runs on the researcher's own AI key or the deployment's (the keys above). Off: every `/api/questionnaire/*/drafts` route answers 404. |

### Questionnaire v2 — shipped dark

The redesign of `C:/dev/IIT/docs/questionnaire/DESIGN_2026-10-18.md` (sessions, recordings, computed
coverage, canonical answers). Off: every v2 route answers 404, `POST /api/questionnaire/interviews`
and `/api/media/complete` behave exactly as before, and `GET /api/questionnaire/config` tells both
clients to draw the old screens. The consent routes (`/api/artisans/{id}/consent`) and the
transcription pause work either way. See `docs/questionnaire/API.md`.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `QUESTIONNAIRE_V2` | No | **`false`** | No | The switch. The owner turns it on after the legacy backfill has been applied (rollout steps 3 and 4). |
| `QUESTIONNAIRE_MIN_ANDROID_VERSION_CODE_FOR_CREATE` | No | unset | No | Owner decision 5: once every active Android device runs the new build (plus 30 days; `GET /api/admin/app-versions` shows `accountsBelow`), set this to that build's **versionCode**. A session create from a lower `X-App-Version-Code`, or an Android build that sends none, then gets `503` + `Retry-After: 86400` and the update banner; its queued clips are always accepted. The version name is never compared (it was reset to 0.0.1 on 21 Oct 2026). |
| `QUESTIONNAIRE_MIN_WEB_VERSION_CODE_FOR_CREATE` | No | unset | No | The same for the web, compared with the web's build number. A web request that sends no code is never refused (the page ships with the server). |

The transcription pause is not a variable: it is `AppSetting.transcriptionPaused`, which migration
`20261022090000_consent_and_pause` adds as `true` (owner correction (d), 18 Oct 2026).

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

**Six** variables are read by application code, and **all of them are public** (see rule 1 above):
`NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID`, `NEXT_PUBLIC_MAPTILER_API_KEY`, and since
2026-10-10 `NEXT_PUBLIC_MICROSOFT_CLIENT_ID`, `NEXT_PUBLIC_MICROSOFT_TENANT` and
`NEXT_PUBLIC_YAHOO_CLIENT_ID` (`lib/oidcSignIn.ts`). A seventh,
`NEXT_PUBLIC_APP_URL`, is documented here because the **backend** reads it — no frontend code does.

Re-verify the list rather than trusting it, since a new page can add one at any time:

```bash
grep -rhoP "process\.env\.[A-Z_0-9]+" frontend/app frontend/components frontend/lib \
  frontend/next.config.ts | sort -u
```

As of 2026-07-27 that returns exactly those three plus `NODE_ENV` (in `next.config.ts`). The
Playwright scripts additionally read three `PW_*` variables from the shell, but they are never
bundled.

| Variable | Required | Default in code | Local value | Production value | Secret | Notes |
|---|---|---|---|---|---|---|
| `NEXT_PUBLIC_API_URL` | **Yes** in production | `http://localhost:8000` | `http://localhost:8000` | `https://d2b34i3e92al6i.cloudfront.net` | No | **ORIGIN ONLY.** `lib/api.ts` appends `/api` itself, so a trailing `/api` or `/` makes every request 404. Must be `https://` in production or the browser blocks it as mixed content. |
| `NEXT_PUBLIC_APP_URL` | No (frontend) | n/a — **no frontend code reads it** | `http://localhost:3000` | your Vercel/custom domain | No | Shares its name with the backend variable so one `.env` can feed both; only the backend (`config.py`) actually reads it. Setting it in Vercel changes nothing today — it is there so the value stays in sync with the backend and with `BACKEND_CORS_ORIGINS`. |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | No | none | blank | Google web client ID | No | Blank hides the Google button and leaves email/password login. Must equal the backend's `GOOGLE_CLIENT_ID`, and the origin must be an "Authorized JavaScript origin" on that client or GSI returns 403. |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | No | none | blank | MapTiler key | No (restrict by domain) | Blank ⇒ the map coordinate picker degrades to manual latitude/longitude entry. Never blocks data entry. |
| `NEXT_PUBLIC_MICROSOFT_CLIENT_ID` | No | none | blank | the Entra app's client ID | No | Blank ⇒ **no** "Continue with Microsoft" button at all. Must equal the backend's `MICROSOFT_CLIENT_ID`, and `https://<this domain>/login/callback` must be a **Web** redirect URI on that registration. Set it only once the backend has the client ID and the secret, or the button signs nobody in. |
| `NEXT_PUBLIC_MICROSOFT_TENANT` | No | `common` | blank | as the backend's `MICROSOFT_TENANT` | No | The tenant segment of the authorize URL. Anything but `common`, `organizations`, `consumers` or a tenant ID hides the Microsoft button. |
| `NEXT_PUBLIC_YAHOO_CLIENT_ID` | No | none | blank | the Yahoo app's client ID | No | Blank ⇒ **no** "Continue with Yahoo" button. Must equal the backend's `YAHOO_CLIENT_ID`; `https://<this domain>/login/callback` must be the app's redirect URI. |

On Vercel the first three exist as **Encrypted** variables (the three sign-in ones belong there too, once
the owner registers the apps — Production at least, and Preview only if a preview domain is registered
as a redirect URI), on Production, Preview and Development;
`NEXT_PUBLIC_APP_URL` is deliberately not set there at all, because nothing in the bundle would use
it. Never re-create any of them as **Sensitive** — see rule 3 above. That mistake does not surface
as a failed build or a failed deploy; it surfaces as a live site that cannot authenticate, days
later, with every error message pointing at Google or the backend instead.

There are no server-side environment variables in the frontend: no server actions, and nothing reads
a non-`NEXT_PUBLIC_` value. There is one route handler, `app/login/callback/route.ts`, the Microsoft
and Yahoo redirect URI: it reads only the request's query and redirects (to `/login` with the answer in
the fragment, or to the Android app's scheme for a `state` the app minted).

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

**Microsoft and Yahoo sign-in** (since 2026-10-10, `data/OidcSignIn.kt`). Public values, compiled
into `BuildConfig`; each provider's button is drawn only when its client ID AND `oidcRedirectUri` are
set. Read from `local.properties` or, for CI, the environment variable named in brackets —
`publish-android.yml` passes the repository **variables** (Settings → Secrets and variables → Actions
→ Variables) of those names.

| Property | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `microsoftClientId` (`MICROSOFT_CLIENT_ID`) | No | blank | No | The backend's `MICROSOFT_CLIENT_ID`. |
| `microsoftTenant` (`MICROSOFT_TENANT`) | No | `common` | No | The backend's `MICROSOFT_TENANT`. |
| `yahooClientId` (`YAHOO_CLIENT_ID`) | No | blank | No | The backend's `YAHOO_CLIENT_ID`. |
| `oidcRedirectUri` (`OIDC_REDIRECT_URI`) | With either | blank | No | The web app's callback, e.g. `https://<the web app's domain>/login/callback` — the same URI the providers hold for the web. The browser tab returns there and the route hands the code to the app on `com.fieldrepository.app.signin://oidc/callback` (AppAuth's receiver, `AndroidManifest.xml`). |

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
| `VERCEL_TOKEN` | `deploy-frontend.yml` | Yes | **Yes** | Vercel → Account Settings → Tokens. Must be scoped to the **team** owning `field-repository`, not a personal account, or the CLI 403s. The only genuinely sensitive one of the three. Absent, stage 2 skips with instructions rather than failing. |
| `VERCEL_ORG_ID` | same | Yes | No | `team_pcTf4Alb2DCIwq2IZcdu00dS`. An identifier, not a credential. |
| `VERCEL_PROJECT_ID` | same | Yes | No | `prj_EzXN8hhGKpMciFBrZRdxpcgUUzN0`. An identifier, not a credential. |
| `SUPABASE_KEEPALIVE_URL` / `SUPABASE_DATABASE_URL` | `keep-supabase-active.yml` → `scripts/keep-supabase-active.mjs` | One of them (falls back to `DATABASE_URL`) | **Yes** | The script rewrites a Supabase pooler URL from `:5432` to `:6543`, because a session-mode keep-alive is rejected with `EMAXCONNSESSION` while the live backend holds those 15 slots. |
| `SUPABASE_KEEPALIVE_NO_REWRITE` | same | No | No | `"true"` disables that `:5432 → :6543` rewrite. Only for a non-Supabase database. |
| `SUPABASE_DB_SSL` | same | No | No | `"false"` disables TLS for the keep-alive connection; any other value keeps SSL on. |

The `NEXT_PUBLIC_*` values are deliberately **not** in this table. They live in the Vercel project
and `vercel pull` fetches them into the runner at build time, so the Environment Variables screen
stays their single source of truth and nobody has to keep the same value correct in two places.

Terraform (`infra/terraform/`) additionally reads `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
from your shell — use an IAM admin user's key pair, never root keys. `terraform.tfstate` and
`*.tfvars` are gitignored because they contain the generated media access key.

---

## Quick triage

| Symptom | Almost always |
|---|---|
| Green pipeline, live site, and **nobody can log in** — Google says `invalid_client`, password login fails too | A `NEXT_PUBLIC_*` variable in Vercel is typed **Sensitive**, so `vercel pull` never gave it to the build and Next.js inlined `undefined`. Grep the live bundle before touching Google: [DEPLOYMENT_VERCEL.md §2.2.1](DEPLOYMENT_VERCEL.md) |
| Requests go to `http://localhost:8000` from the deployed site | Same cause — `NEXT_PUBLIC_API_URL` never reached the build, so `lib/api.ts`'s local fallback got compiled in |
| Every page loads but all lists are empty and login fails with 404 | `NEXT_PUBLIC_API_URL` has a trailing `/api` or `/` |
| Requests never leave the browser; console says "Mixed Content" | `NEXT_PUBLIC_API_URL` is `http://` on an `https://` page |
| "blocked by CORS policy" but curl works | The origin is missing from `BACKEND_CORS_ORIGINS`, or the backend wasn't restarted |
| Env change in Vercel had no effect | `NEXT_PUBLIC_*` is compiled in — redeploy without the build cache. If a fresh redeploy *still* has no effect, the variable is typed Sensitive and the build never receives it |
| Backend won't start | A required variable is missing: `DATABASE_URL`, `JWT_SECRET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_BUCKET`, `MASTER_ADMIN_EMAIL` |
| `EMAXCONNSESSION` / `EMAXCONN` in the logs | `DATABASE_CONNECTION_LIMIT` raised, or more than one uvicorn worker; keep 1 web worker + the separate queue service |
| Works on Wi-Fi, fails on mobile data | An IPv4-only host slipped in — use the CloudFront and `s3.dualstack.…` hostnames |
| Google button missing, or GSI returns **403** | `NEXT_PUBLIC_GOOGLE_CLIENT_ID` blank, or the origin is not an Authorized JavaScript origin. A **401 `invalid_client`** is a different fault — see the first row |
| Transcripts stay empty | No STT key set (`ELEVENLABS_API_KEY` / `DEEPGRAM_API_KEY` / `OPENAI_API_KEY`), or the queue worker isn't running. Check the Settings hub's provider panel first: it shows which keys are actually resolvable, through the same lookup the chain uses |
| Transcripts are slow but do arrive | Working as designed. Transcription waits for the box to be idle and backs off behind a growing cooldown after a provider 429s — a throttled clip is requeued **without** spending an attempt |
| A provider key was added in the Settings hub and nothing changed | It should take effect immediately with no restart. If it did not, the value is in `.env` rather than `ManagedSecret`, and `.env` is only the fallback — but `.env` needs a restart because `get_settings()` is `@lru_cache`d (rule 2) |
| `/docs` 404s after a deploy | Correct. `BACKEND_EXPOSE_DOCS` defaults to `false`; set it `true` locally |
| A demoted user still has their old privileges | For at most `AUTH_USER_CACHE_TTL_SECONDS` (5 s), and only if the demotion happened in a *different* process — `psql`, the seed script. A demotion through the API invalidates immediately. `AUTH_USER_CACHE_ENABLED=false` removes the window entirely |

---

## How this document is kept true

There is exactly one source for the backend list: **`backend/app/core/config.py`**. Everything else in
this file is prose about those names. So the maintenance procedure is a set difference, and it takes
one command:

```bash
# Every variable config.py reads. Diff this against the tables above — anything in the output
# that is not in this document is undocumented, and anything documented that is not in the
# output has been deleted from the code.
grep -oP 'alias="\K[A-Z_0-9]+' backend/app/core/config.py | sort
```

| Claim class | Kept true by |
|---|---|
| The backend variable list is complete | The command above. Run it after any change to `config.py`. |
| The frontend variable list is complete | The `grep` in the Frontend section, which is dated. |
| Defaults | The `Field(default=…)` in `config.py`. **One default is a lie by design** and is called out in a blockquote: `ELEVENLABS_STT_MODEL`. If another such case appears, it needs the same treatment — a table cell cannot express "the config default is not what runs". |
| `SCALE_*` and the AI-feature keys | Owned elsewhere: `backend/app/scale/README.md` and [AI_FEATURES.md](AI_FEATURES.md). This file lists the names only, so it stays a complete index without becoming a second source of truth. |
| GitHub Actions secrets | `.github/workflows/*.yml`. `grep -ho 'secrets\.[A-Z_]*' .github/workflows/*.yml \| sort -u` is the equivalent set difference. |
| Vercel variables | The Vercel dashboard — **UNVERIFIED from this repository**. `vercel env ls production` is the check, and rule 3 (Encrypted, never Sensitive) is the thing it exists to catch. |

**Review triggers:** `backend/app/core/config.py`, `backend/.env.example`, any new `process.env.`
reference under `frontend/`, or a new workflow secret.

**Known unverified:** every value stated as a *production* value — the bucket name, the region, the
CloudFront hostname, the Vercel org and project ids — is recorded from a deployment, not read from
code. They are correct as of 2026-07-27 to the extent the deployment has not changed under them.
