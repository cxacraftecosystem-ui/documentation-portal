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
- AMI: **Ubuntu Server 26.04 LTS** (Terraform's filter since 2026-10-09; the box running that day is 24.04, see §9). Storage: **30 GiB gp3** (free-tier max).
- Add a **2 GiB swap file** (below) so `pip install` / `prisma generate` don't get OOM-killed.

> The "Free tier eligible" badge on larger types (m7i-flex.large etc.) refers to the new account
> credits plan, not the classic 750-hour free tier. For a genuinely free box, choose `t3.micro`.

---

## 2. Launch + network

1. **Launch instance** → Ubuntu 26.04, `t3.micro`, new key pair (download the `.pem`).
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

sudo apt update && sudo apt install -y git python3.14 python3.14-venv   # on 24.04: add-apt-repository ppa:deadsnakes/ppa first
git clone <YOUR_REPO_URL> app && cd app/backend
python3.14 -m venv .venv   # by hand a plain directory; the deploy builds its own under ~/app/venvs (§9)
./.venv/bin/python -m pip install -r requirements.lock && ./.venv/bin/python -m pip install --no-deps -e .
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
chunking), **Python 3.14** (Ubuntu 26.04's own; deadsnakes on an older AMI), plus the `fieldrepo`
and `fieldrepo-queue` systemd units. The DB stays on Supabase.

> **Changing the AMI filter replaces the instance on the next `apply`.** `main.tf` moved from the
> 24.04 (noble) filter to 26.04 (resolute) on 2026-10-09 without anything being applied, and the box
> running that day is still the noble one. Applying is the planned way to rebuild onto 26.04 — the
> box is stateless and the Elastic IP is reattached — but it is a production rebuild: do it after a
> deploy has run the API on the 3.14 venv (§9), and re-create nginx/TLS and the `.env` afterwards.
> A read-only `terraform plan` against the live resources on 2026-10-09 (import blocks in a scratch
> copy; this repository holds no state) showed exactly that: `aws_instance.api` replaced for the
> AMI, the Elastic IP re-associated, and the bucket, its policy, CORS, lifecycle and public-access
> block, the IAM user and its policy, and the security group all matching this code with no change.
> It also showed the box's SSM instance profile (`fieldrepo-ssm`, made by hand) missing from the
> code, which would have left a rebuilt box unreachable over SSM; `main.tf` names it now.

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

`.github/workflows/deploy-backend.yml`, on every push to `main` that touches `backend/` (once Checks
is green on it): makes sure python3.14 is on the box, builds the venv from
`backend/requirements.lock` **beside** the one the API is running on (§9), rsyncs `backend/`, writes
`.env`, installs the project and generates the Prisma client into the new venv, runs
`prisma migrate deploy`, points `backend/.venv` at the new venv, and restarts both services. Set
these repo secrets
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

---

## 9. Python 3.14, and the venv the deploy builds

Since 2026-10-09 the API runs on **Python 3.14**, the interpreter CI tests on, from a venv built
from `backend/requirements.lock`. Before that the box ran Python 3.12 from a venv created once on
2026-06-17 and never rebuilt, because the deploy only created `.venv` when it was missing and
`pip install -e .` never upgrades what is already installed: production sat on June's versions,
security advisories included, while CI tested whatever the index offered that day.

**The layout on the box** (all owned by `ubuntu`):

| Path | What it is |
|---|---|
| `/home/ubuntu/app/venvs/py3.14-<16 hex>` | One venv per interpreter minor and lock: the hex is the start of the lock's SHA-256. `.complete` inside it is written last, holding `python -VV`; a directory without it is a build that did not finish and is rebuilt. |
| `/home/ubuntu/app/backend/.venv` | A **symlink** to the venv in use. The two systemd units run `.venv/bin/python`, so they never change when the venv does. |
| `/home/ubuntu/app/venvs/py3.12-legacy` | The 3.12 venv every deploy before 2026-10-09 built in place, moved aside by the first 3.14 deploy and kept for rollback. |
| `/home/ubuntu/app/venv-wanted`, `venv-previous` | The venv the last deploy built or chose, and the one `.venv` pointed at before it. |
| `/home/ubuntu/.cache/prisma-python/nodeenv` | A symlink to `nodeenv-<version>`, the Node the Prisma CLI runs on, pinned in the deploy (26.11.1 on 2026-10-09; it had been 26.3.0, downloaded once and never refreshed). |

**Python itself.** On this 24.04 box `python3.14` and `python3.14-venv` come from the deadsnakes PPA
(3.14.8 on 2026-10-09); the deploy installs them the first time it does not find them, and
`python3.12` — 24.04's system Python — stays installed. On a 26.04 box they come from Ubuntu's own
archive, where 3.14 is the system Python (3.14.4 there on 2026-10-09, patched by Ubuntu); deadsnakes
does not build 3.14 for 26.04. Everything else in the deploy keys on the minor version only.

**Why the build cannot take the API down.** The venv is built before `backend/` is synced, while the
API keeps serving from the old one, with `fieldrepo-queue` stopped for the install to spare memory
(the box has 911 MB) and started again whatever the outcome. If pip fails, that step fails and
nothing the API can see has changed. The switch to the new venv happens later, with both services
stopped, as one atomic `rename(2)` of a symlink. A deploy that changed only code finds its venv
already built and installs nothing. After a healthy restart the deploy keeps the venv in use, the
one before it and `py3.12-legacy`, and deletes older ones.

**Going back.** A venv is only the dependencies; the code on disk is whatever the last deploy synced.
So the real rollback is the same as it has always been — redeploy the commit you want — and the
venvs are there to make that fast. To point the services at the previous venv by hand (for example
while that redeploy runs):

```bash
cat /home/ubuntu/app/venv-previous                     # the venv .venv pointed at before
ln -sfn "$(cat /home/ubuntu/app/venv-previous)" /home/ubuntu/app/backend/.venv
sudo systemctl restart fieldrepo fieldrepo-queue
```

Going back to `py3.12-legacy` only works together with a redeploy of a commit from before
2026-10-09: the current code imports PyJWT and calls bcrypt 5 directly, neither of which is in that
venv. Once 3.14 has served for a while, delete it: `rm -rf /home/ubuntu/app/venvs/py3.12-legacy`.

**Looking without touching** (read-only, safe on the live box):

```bash
readlink -f /home/ubuntu/app/backend/.venv && cat "$(readlink -f /home/ubuntu/app/backend/.venv)/.complete"
ls -1 /home/ubuntu/app/venvs
/home/ubuntu/app/backend/.venv/bin/python -m pip list --format=freeze | head
/home/ubuntu/.cache/prisma-python/nodeenv/bin/node --version
```
