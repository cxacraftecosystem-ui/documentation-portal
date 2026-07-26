# Deploying the Field Repository backend + media storage on AWS (free tier)

Architecture for the cheapest durable setup:

| Concern        | Service                | Persistence |
|----------------|------------------------|-------------|
| Database       | **Supabase** (already) | Managed Postgres, already persistent |
| Object storage | **AWS S3**             | Durable, 11 9's |
| API server     | **AWS EC2 (t3.micro)** | The only piece you host |
| Web frontend   | **Vercel** (free) or the same EC2 | — |

Keep the DB on Supabase and media on S3 so the EC2 box is stateless and can be rebuilt anytime
without data loss.

---

## 1. Which EC2 instance

- **Recommended: `t3.micro`** — 2 vCPU (burstable), **1 GiB RAM**, free-tier eligible (750 hrs/month
  for 12 months). Enough to run the FastAPI/uvicorn API (DB + storage are off-box).
- `t2.micro` is the older free-tier option; `t3.micro` is newer/faster — pick `t3.micro`.
- **Do NOT** try to `npm run build` the Next.js frontend on 1 GiB — it OOMs. Either deploy the
  frontend to **Vercel**, or use a `t3.small` (2 GiB, *not* free) if everything must live on one box.
- AMI: **Ubuntu Server 24.04 LTS**. Storage: **30 GiB gp3** (free-tier max).
- Add a **2 GiB swap file** (below) so `pip install` / `prisma generate` don't get OOM-killed.

> The "Free tier eligible" badge on larger types (m7i-flex.large etc.) refers to the new account
> credits plan, not the classic 750-hour free tier. For a genuinely free box, choose `t3.micro`.

---

## 2. Launch + network

1. **Launch instance** → Ubuntu 24.04, `t3.micro`, new key pair (download the `.pem`).
2. **Elastic IP**: Allocate one and **associate it** with the instance. This gives a *stable* public
   IP (DHCP-style changes were exactly the LAN problem earlier — don't repeat it in the cloud).
3. **Security group (inbound rules):**
   - `22/tcp` SSH — **source: My IP** only.
   - `8000/tcp` API — source `0.0.0.0/0` for a quick demo (or restrict to your IP). If you put nginx
     in front, open `80`/`443` instead and keep 8000 internal.

---

## 3. Provision the box

```bash
ssh -i your-key.pem ubuntu@<ELASTIC_IP>

# swap (protects 1 GiB box during installs)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

sudo apt update && sudo apt install -y python3.12-venv python3-pip git
git clone <YOUR_REPO_URL> app && cd app/backend
python3.12 -m venv .venv
./.venv/bin/pip install -e .          # or: pip install -r requirements
PATH="$PWD/.venv/bin:$PATH" ./.venv/bin/python -m prisma generate
```

Create `backend/.env` (see template in section 5).

Smoke test, then run as a service:

```bash
./.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000   # Ctrl-C after /health works
```

### systemd unit (keeps it running + restarts on reboot)

`/etc/systemd/system/fieldrepo.service`:

```ini
[Unit]
Description=Field Repository API
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/backend
EnvironmentFile=/home/ubuntu/app/backend/.env
# ONE worker, bound to localhost (nginx fronts it). Two workers reintroduced the
# SIGKILL/orphaned-Prisma-engine 500 outage (commit 44923bc); the media queue runs in its own
# service below, so the web process must have MEDIA_QUEUE_WORKER_ENABLED=false in .env.
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
ExecStart=/home/ubuntu/app/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
KillMode=control-group
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/fieldrepo-queue.service` (the media/transcription queue, decoupled from the
web process so ffmpeg + AI work never blocks requests):

```ini
[Unit]
Description=Field Repository media queue worker
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/backend
EnvironmentFile=/home/ubuntu/app/backend/.env
ExecStart=/home/ubuntu/app/backend/.venv/bin/python -m app.worker
KillMode=control-group
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now fieldrepo fieldrepo-queue
sudo systemctl status fieldrepo fieldrepo-queue
curl http://localhost:8000/health
```

---

## 4. S3 bucket

1. **Create bucket** (globally-unique name), in the **same region** as `AWS_REGION`.
2. **Public read for media** (simplest so the app can show images/audio/video by URL):
   - Bucket → Permissions → **uncheck "Block all public access"**.
   - Add this bucket policy (read-only GET on the `media/` prefix; uploads stay private via presign):
     ```json
     {
       "Version": "2012-10-17",
       "Statement": [{
         "Sid": "PublicReadMedia",
         "Effect": "Allow",
         "Principal": "*",
         "Action": "s3:GetObject",
         "Resource": "arn:aws:s3:::YOUR_BUCKET/media/*"
       }]
     }
     ```
   - (More locked-down alternative: keep private and serve via presigned GET URLs — needs a small code
     addition; ask if you want it.)
3. **CORS** (needed for the **web** browser's presigned PUT/GET; the Android app uses OkHttp and is
   unaffected). Bucket → Permissions → CORS:
   ```json
   [{
     "AllowedHeaders": ["*"],
     "AllowedMethods": ["PUT", "GET", "HEAD"],
     "AllowedOrigins": ["https://your-frontend-domain"],
     "ExposeHeaders": ["ETag"]
   }]
   ```
4. **IAM user** (programmatic access) with this policy, then create an access key:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
       "Resource": "arn:aws:s3:::YOUR_BUCKET/*"
     }]
   }
   ```
   (`DeleteObject` is required for the cancel-staged-upload cleanup.)

---

## 5. backend/.env template (production)

Fully annotated version: `backend/.env.example`. Every variable with its default, whether it is
required and whether it is a secret: [docs/ENVIRONMENT.md](../docs/ENVIRONMENT.md).

```dotenv
# SESSION pooler URL (:5432) — migrations need it; the app re-routes runtime queries to the
# transaction pooler (:6543) automatically (DATABASE_USE_TRANSACTION_POOLER, default true).
DATABASE_URL=postgresql://...supabase-pooler...:5432/postgres   # keep Supabase
# DATABASE_CONNECTION_LIMIT=10   # per worker; do NOT raise to 40 — that exhausted the pooler
JWT_SECRET=<long-random>
MASTER_ADMIN_EMAIL=you@example.com
# DEFAULT_SIGNUP_ROLE=CROWDSOURCE_VOLUNTEER  # tier for brand-new Google sign-ins

# Real S3 — leave AWS_S3_ENDPOINT UNSET so boto3 talks to AWS (it was localhost:9000 for MinIO).
# Use the DUAL-STACK public base URL so media loads on IPv6-only mobile networks.
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=...
AWS_REGION=ap-south-1
AWS_S3_BUCKET=your-bucket
AWS_S3_PUBLIC_BASE_URL=https://your-bucket.s3.dualstack.ap-south-1.amazonaws.com

# Speech-to-text provider chain (any subset; priority: ElevenLabs -> Deepgram -> Whisper).
# OPENAI_API_KEY also powers transcript refinement/translation.
ELEVENLABS_API_KEY=...
DEEPGRAM_API_KEY=...
OPENAI_API_KEY=...
GEMINI_API_KEYS=...,...

# The web service must NOT drain the media queue (fieldrepo-queue does).
MEDIA_QUEUE_WORKER_ENABLED=false

BACKEND_CORS_ORIGINS=https://your-frontend-domain
```

Do **not** commit `.env`. Presigned PUTs use SigV4 (already configured in `services/s3.py`), so any
region works.

---

## 6. Point the apps at it

- **Android**: set `android/local.properties` → `apiBaseUrl=https://<YOUR_HTTPS_DOMAIN>/api/`, then
  `./gradlew.bat :app:assembleDebug` and reinstall. **Plain `http://` to a production host no longer
  works**: the manifest sets `android:usesCleartextTraffic="false"` and
  `res/xml/network_security_config.xml` permits cleartext only for `10.0.2.2`, `127.0.0.1` and
  `localhost`, so an `http://<ELASTIC_IP>:8000/api/` call fails with a
  `CLEARTEXT communication not permitted` error. Front the API with TLS (CloudFront, or nginx +
  certbot per §7) and use `https`. Developing against a LAN backend from a real phone: add your
  machine's private IP as an extra `<domain>` in the network security config **temporarily**, and do
  not commit it. Rationale in [docs/SECURITY.md](../docs/SECURITY.md) §1.4.
- **Web**: set `NEXT_PUBLIC_API_URL` to the API **origin only** — `https://d2b34i3e92al6i.cloudfront.net`,
  with no `/api` suffix and no trailing slash, because `frontend/lib/api.ts` appends `/api` itself.
  Then add the frontend origin to `BACKEND_CORS_ORIGINS` and to the bucket CORS. Full runbook:
  [docs/DEPLOYMENT_VERCEL.md](../docs/DEPLOYMENT_VERCEL.md).

---

## 7. (Optional) HTTPS

Put nginx in front of uvicorn and run certbot:

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
# proxy_pass http://127.0.0.1:8000; in a server block for your domain, then:
sudo certbot --nginx -d api.yourdomain.com
```

Then the API is `https://api.yourdomain.com/api/`, which is what the Android app already requires —
no change to the network security config is needed.

---

## 8. Automated deploy (Terraform + GitHub Actions + Vercel + nginx)

Everything below is codified in the repo so the only manual inputs are credentials.

### 8.1 Provision with Terraform (`infra/terraform/`)

Creates the **S3 bucket** (public-read `media/*` + CORS), an **IAM user** with
`PutObject/GetObject/DeleteObject` and a fresh **access key**, and a **t3.micro**
EC2 box with an **Elastic IP**, a 2 GiB swap file, **nginx** (reverse proxy on 80,
so port 8000 is never exposed) and **ffmpeg** (needed for Whisper long-audio
chunking), plus the `fieldrepo` systemd unit. The DB stays on Supabase.

> Terraform/AWS auth needs an **IAM access key pair**, not the console
> email+password. Create an IAM admin user in the console first, then:
> `export AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=…` (do **not** use root keys).

```bash
cd infra/terraform
terraform init
terraform apply \
  -var="aws_region=ap-south-1" \
  -var="bucket_name=YOUR-GLOBALLY-UNIQUE-BUCKET" \
  -var="ssh_key_name=your-ec2-keypair" \
  -var="ssh_ingress_cidr=YOUR.IP/32" \
  -var='cors_allowed_origins=["https://your-app.vercel.app"]'

terraform output api_public_ip            # -> EC2_HOST
terraform output s3_bucket                # -> AWS_S3_BUCKET
terraform output s3_public_base_url       # -> AWS_S3_PUBLIC_BASE_URL
terraform output media_access_key_id      # -> AWS_ACCESS_KEY_ID
terraform output -raw media_secret_access_key   # -> AWS_SECRET_ACCESS_KEY (sensitive)
```

`terraform.tfstate` and `*.tfvars` are gitignored — they hold the generated
secret key; never commit them.

### 8.2 GitHub Actions secrets (auto-deploy on push)

`.github/workflows/deploy-backend.yml` rsyncs `backend/` to the box, writes
`.env`, installs deps, runs `prisma migrate deploy`, and restarts the service on
every push to `main` that touches `backend/`. Set these repo secrets
(**Settings → Secrets and variables → Actions**):

| Secret | Value |
|--------|-------|
| `EC2_HOST` | the Elastic IP (`terraform output api_public_ip`) |
| `EC2_SSH_KEY` | the **private** `.pem` contents for the EC2 key pair |
| `BACKEND_ENV` | the entire `backend/.env` file (template in §5, with the Terraform S3 values) |

The `.env` is piped to the server over the SSH tunnel — it is never written to
the workflow logs or a command line.

### 8.3 Vercel (frontend)

> Full step-by-step runbook — import, env vars, preview vs production, custom domain, redeploy,
> troubleshooting — is **[docs/DEPLOYMENT_VERCEL.md](../docs/DEPLOYMENT_VERCEL.md)**. The summary
> below is what matters from the backend's point of view.

The Vercel project is linked to this GitHub repo (same account), so each push to
`main` auto-deploys. In the Vercel project settings:

- **Root Directory:** `frontend` (this is a monorepo; `frontend/vercel.json` pins the Next.js
  framework, `npm ci` install and `next build`). Leaving it at the repo root fails the build with
  "No Next.js version detected".
- **Environment variables:** `NEXT_PUBLIC_API_URL = https://d2b34i3e92al6i.cloudfront.net` — the
  **origin only, without** `/api` and without a trailing slash (the web client appends `/api`
  itself; `…/api` produces `…/api/api/…` and every screen 404s). Plus the optional
  `NEXT_PUBLIC_GOOGLE_CLIENT_ID` and `NEXT_PUBLIC_MAPTILER_API_KEY`. Do **not** add
  `NEXT_PUBLIC_APP_URL` here expecting an effect — no code under `frontend/` reads it, so setting it
  in Vercel changes nothing. What lets the Vercel origin reach the API is its presence in
  `BACKEND_CORS_ORIGINS` (last bullet below), not its name in this dashboard.
- **Redeploy after any env change.** `NEXT_PUBLIC_*` values are inlined into the bundle at build
  time, so editing them in the dashboard changes nothing until a fresh build runs (redeploy with
  the build cache disabled).
- **Mixed content:** an HTTPS Vercel page cannot call an HTTP API — browsers block it. The API is now
  fronted by **CloudFront over HTTPS** (`https://d2b34i3e92al6i.cloudfront.net`, dual-stack), so use
  that `https://…` value above and the block is gone. (Hitting the raw EC2 origin over `http://…`
  would still be blocked.) The Android app talks to the same CloudFront URL.
- **Google sign-in:** add the Vercel origin to the Google OAuth web client's *Authorized JavaScript
  origins*, or the GSI button returns 403.
- Add the resulting Vercel URL to `BACKEND_CORS_ORIGINS` (in `BACKEND_ENV`) and to
  the bucket's `cors_allowed_origins` Terraform var. Both take **exact** origins — scheme + host,
  no trailing slash, no wildcards — so preview deployments (per-deployment hostnames) are not
  covered by the production entry.

### 8.4 Point the Android app at the box

`android/local.properties` → `apiBaseUrl=http://<EC2_HOST>/api/` (port 80 via
nginx — no `:8000`), then `./gradlew.bat :app:assembleDebug` and reinstall.

### 8.5 ffmpeg note

The long-audio Whisper chunking (`pydub`) needs the **ffmpeg** binary. Terraform's
`user_data.sh` installs it (`apt-get install -y ffmpeg`). If you provision a box
by hand, run `sudo apt install -y ffmpeg`, otherwise long recordings fall back to
a single-shot transcription attempt.
