# Deploying the Field Repository backend + media storage on AWS (free tier)

Architecture for the cheapest durable setup:

| Concern        | Service                | Persistence |
|----------------|------------------------|-------------|
| Database       | **Supabase** (already) | Managed Postgres, already persistent |
| Object storage | **AWS S3**             | Durable, 11 9's |
| API server     | **AWS EC2** (t3.micro; t3.small from the 26.04 rebuild, §10) | The only piece you host |
| Web frontend   | **Vercel** (free) or the same EC2 | — |

Keep the DB on Supabase and media on S3 so the EC2 box is stateless and can be rebuilt anytime
without data loss.

---

## 1. Which EC2 instance

- **This project: `t3.small`** (2 vCPU burstable, 2 GiB RAM, *not* free tier), approved on
  2026-10-09 with the rebuild onto 26.04 (§10). The box running that day is a **`t3.micro`** (1 GiB,
  free-tier eligible): it runs the FastAPI/uvicorn API and the queue (DB + storage are off-box), but
  with 122 MB available and 215 MB in swap on 2026-10-09, and a deploy building a venv beside the
  live one needs more room than that.
- `t2.micro` is the older free-tier option; `t3.micro` is newer/faster — for a free demo box, pick `t3.micro`.
- **Do NOT** try to `npm run build` the Next.js frontend on 1 GiB — it OOMs. Either deploy the
  frontend to **Vercel**, or use a `t3.small` (2 GiB, *not* free) if everything must live on one box.
- AMI: **Ubuntu Server 26.04 LTS** (Terraform's filter since 2026-10-09; the box running that day is 24.04, see §9). Storage: **30 GiB gp3** (free-tier max), **encrypted** on any box built since 2026-10-09 (the 24.04 box's is not).
- Add a **2 GiB swap file** (below) so `pip install` / `prisma generate` don't get OOM-killed.

> The "Free tier eligible" badge on larger types (m7i-flex.large etc.) refers to the new account
> credits plan, not the classic 750-hour free tier. For a genuinely free box, choose `t3.micro`.

---

## 2. Launch + network

1. **Launch instance** → Ubuntu 26.04, `t3.small` (§1), new key pair (download the `.pem`), and
   *Advanced → Storage → Encrypted*. For this project's own box, §10 is the exact launch.
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

# swap (protects a small box during installs)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

sudo apt update && sudo apt install -y git libatomic1 curl
git clone <YOUR_REPO_URL> app && cd app/backend
# The interpreter, exactly as a new box gets it: upstream CPython from the pinned, checksummed
# python-build-standalone build (§9), NOT apt's python3.14. The last block of user_data.sh is
# self-contained: it downloads the pin, refuses a wrong SHA-256, and unpacks into /opt/cpython.
sed -n "/^# --- The API's interpreter/,\$p" ../infra/terraform/user_data.sh | sudo bash -euo pipefail
/opt/cpython/3.14.8+20261009/bin/python3.14 -m venv .venv   # the directory that block printed; by hand
                                                           # a plain .venv, the deploy builds its own (§9)
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

# E-mail through Amazon SES (optional). Unset MAIL_FROM_ADDRESS = no e-mail and no e-mail controls.
# The address must be SES-verified in MAIL_SES_REGION, with SES production access there, and the
# AWS_ACCESS_KEY_ID user needs ses:SendEmail. docs/ENVIRONMENT.md "E-mail (Amazon SES)".
# MAIL_FROM_ADDRESS=no-reply@your-domain
# MAIL_FROM_NAME=Field Repository
# MAIL_REPLY_TO=support@your-domain
# MAIL_SES_REGION=ap-south-1

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
`PutObject/GetObject/DeleteObject` and a fresh **access key**, and an EC2 box (**t3.small** with an
**encrypted** gp3 root since 2026-10-09; the box running that day is a t3.micro, §10) with an
**Elastic IP**, a 2 GiB swap file, **nginx** (reverse proxy on 80,
so port 8000 is never exposed) and **ffmpeg** (needed for Whisper long-audio
chunking), **upstream CPython 3.14.8** (a pinned, checksummed python-build-standalone build under
`/opt/cpython`, not apt's; §9), plus the `fieldrepo`
and `fieldrepo-queue` systemd units. The DB stays on Supabase.

> **Changing the AMI filter, the instance type or the root volume's encryption replaces the instance
> on the next `apply`.** `main.tf` moved from the
> 24.04 (noble) filter to 26.04 (resolute) on 2026-10-09 without anything being applied, and the box
> running that day is still the noble one; the t3.small and the encrypted root followed the same
> evening, also unapplied. The approved rebuild is done with the AWS CLI instead, blue/green, because
> this repository holds no state: §10 has the exact launch. Either way it is a production rebuild —
> the box is stateless and the Elastic IP is reattached — so do it after a
> deploy has run the API on the pinned interpreter (§9), and re-create nginx/TLS and the `.env` afterwards.
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
is green on it): installs the pinned CPython under `/opt/cpython` if the box does not have it yet,
builds the venv from
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

## 9. The interpreter, and the venv the deploy builds

Since 2026-10-09 the API runs on **upstream CPython 3.14.8**, the exact release CI tests on
(`checks.yml` pins the same version), from a venv built from `backend/requirements.lock`. Before that
day the box ran Python 3.12 from a venv created once on 2026-06-17 and never rebuilt, because the
deploy only created `.venv` when it was missing and `pip install -e .` never upgrades what is already
installed: production sat on June's versions, security advisories included, while CI tested whatever
the index offered that day. The first fix, that morning, took `python3.14` from apt — deadsnakes'
3.14.8 on 24.04, Ubuntu's own 3.14.4 on 26.04, two different interpreters — and the same evening the
owner chose upstream 3.14.8 on both. apt is no longer where the API's Python comes from.

**Where it comes from.** [python-build-standalone](https://github.com/astral-sh/python-build-standalone),
Astral's relocatable CPython builds (the ones `uv python install` fetches): release `20261009`, the
newest carrying 3.14.8 on 2026-10-09 (published at 16:38 UTC that day; `20261003` and `20261001` carry
3.14.8 too, and `20261009` adds expat 2.9.0 and libffi 3.8.0 to it); asset
`cpython-3.14.8+20261009-x86_64-unknown-linux-gnu-install_only.tar.gz`; SHA-256
`83f9cb480b702548c592443f86209cf0fc03448d90692df257f095c01791dccc`, that release's `SHA256SUMS`
line, which matched GitHub's own digest for the asset, a download hashed by hand, and the
Sigstore-signed build provenance the project attaches to it (*Moving the pin*, below). The x86_64
linux-gnu build needs nothing from the box but glibc: OpenSSL (3.5.9), SQLite (3.53.1), expat, xz,
libffi and zlib are built in, so the 24.04 box (system OpenSSL 3.0.13) and a 26.04 one (OpenSSL 3.5,
sudo-rs, uutils coreutils, no `python3.12`) run the same bytes. The pin is four lines in
`deploy-backend.yml`'s build step and the same four at the end of `infra/terraform/user_data.sh`;
`backend/tests/test_interpreter_pin.py` fails when those two, or `checks.yml`'s `python-version`,
disagree. **Why not 3.15:** 3.15.0 reached python.org on 2026-10-09 and python-build-standalone
shipped it that evening (`20261009`), but setup-python's manifest (what CI installs from) stopped at
3.15.0rc3, the official Docker image had only `3.15-rc` tags, the lock's `httptools` 0.8.0 and
`PyYAML` 6.0.3 had no cp315 wheels, and `prisma-client-py` 0.15.0 classifies itself only up to 3.12
— the build step's comment keeps that list next to the pin.

**How it is installed** — by the deploy's build step whenever the box lacks it, and by
`user_data.sh` on a new box, the same way: downloaded, then hashed as root inside a root-only staging
directory under `/opt/cpython`, and **refused** unless the hash is the pinned one: not unpacked,
nothing changed, the API and the queue untouched, because this runs before anything is stopped. What
passes is unpacked root-owned, its standard library byte-compiled there as root, nothing left group-
or world-writable, proved as `ubuntu` (the version, and `ssl`, `sqlite3`, `lzma`, `ctypes`,
`pyexpat`, `ensurepip`, `venv`), and only then renamed into place, with `INSTALLED_FROM` inside it
naming the URL and hash. A box that has it downloads nothing. A directory of that name that is not
the pinned build stops the deploy instead of being replaced, because a venv may be running on it.
**Why the bytecode is compiled by the installer:** the tarball ships the standard library's sources
and no `__pycache__`, and the prefix is root's, so the services, which run as `ubuntu`, can never
write a cache of their own: without it every process start compiles from source whatever it imports,
for as long as the build is installed (in a container on 2026-10-09, 2.0 s against 1.4 s for the
API's imports, at every start; compiling all of it took 2 to 4 s, once). Debian compiles deadsnakes'
copy at install for the same reason. **What the install costs the box** (measured in an
`ubuntu:24.04` container the same day, the way the deploy runs it): hashing and unpacking under 4 MB
of memory, compiling the standard library 45 MB at its peak, the proof 21 MB, so the API and the
queue keep running through it; on disk, the 75 MB download (deleted once it is unpacked) and 270 MB
that stay under `/opt/cpython` (249 MB unpacked, 21 MB of bytecode). The interpreter links nothing
but glibc (`ldd`: libc, libm, libpthread, libdl, librt, libutil).

**The layout on the box:**

| Path | What it is |
|---|---|
| `/opt/cpython/3.14.8+20261009` | The interpreter, owned by root, its standard library's bytecode compiled at install. Never changed in place: a new pin is a new directory beside it. A deploy deletes one only after a healthy restart, and only when no venv under `venvs/` runs on it. |
| `/home/ubuntu/app/venvs/cpython-3.14.8+20261009-<16 hex>` | One venv per interpreter build and lock: the hex is the start of the lock's SHA-256. `.complete` inside it is written last (`python -VV`, then the interpreter's path); a directory without it is a build that did not finish and is rebuilt. A venv whose `bin/python` does not resolve to the pinned interpreter is refused, never reused. Owned by `ubuntu`, like everything under `/home/ubuntu/app`. |
| `/home/ubuntu/app/backend/.venv` | A **symlink** to the venv in use. The two systemd units run `.venv/bin/python`, so they never change when the venv does. |
| `/home/ubuntu/app/venvs/py3.14-<16 hex>` | 24.04 box only: the venv the apt-based deploy built on the morning of 2026-10-09, on deadsnakes' `/usr/bin/python3.14`. The first deploy on the pinned interpreter switches away from it and names it in `venv-previous`, which keeps it until a later deploy moves on. |
| `/home/ubuntu/app/venvs/py3.12-legacy` | 24.04 box only: the 3.12 venv every deploy before 2026-10-09 built in place, moved aside by the first 3.14 deploy and kept for rollback. |
| `/home/ubuntu/app/venv-wanted`, `venv-previous` | The venv the last deploy built or chose, and the one `.venv` pointed at before it. |
| `/home/ubuntu/.cache/prisma-python/nodeenv` | A symlink to `nodeenv-<version>`, the Node the Prisma CLI runs on, pinned in the deploy (26.11.1 on 2026-10-09; it had been 26.3.0, downloaded once and never refreshed). |

**On 24.04 and on 26.04:**

| | 24.04 (noble): the box running on 2026-10-09 | 26.04 (resolute): the approved rebuild (§10) |
|---|---|---|
| The API's interpreter | `/opt/cpython/3.14.8+20261009`, installed by the first deploy after this change | the same, installed by `user_data.sh` at first boot (or by the first deploy, if first boot could not fetch it) |
| apt's `python3.14` | deadsnakes' 3.14.8, left installed, PPA and all: the rollback venv runs on it. Nothing upgrades it any more | Ubuntu's 3.14.4, the system `python3`: untouched, and not used by the API |
| `python3.12` | installed (the system Python); `py3.12-legacy` runs on it | absent |
| `libatomic1` (Node 26 links it) | installed | in the cloud image already (Canonical's manifest lists it); `user_data.sh` names it too, and the deploy installs it whenever it is missing |

**The deadsnakes PPA on the 24.04 box: left in place, harmless, until the box goes.** Nothing adds it
any more and nothing removes it, because two rollback paths need its `python3.14`: `venv-previous`
(deadsnakes' venv, after the first deploy on the pinned interpreter) and a redeploy of an apt-era
commit (*Going back*, below). Nothing upgrades that interpreter now, which is harmless for one that
nothing runs on until somebody rolls back. The rebuild onto 26.04 retires the box, PPA and all. To
remove it before that, once no venv resolves to it (`readlink -f /home/ubuntu/app/venvs/*/bin/python`
lists none under `/usr/bin/python3.14`) and no apt-era commit will be deployed again:
`sudo rm /etc/apt/sources.list.d/deadsnakes-ubuntu-ppa-noble.sources && sudo apt-get update`, then
purge deadsnakes' `python3.14` packages (`dpkg -l | grep 3.14` lists them).

**Moving the pin** (a newer 3.14.x, or 3.15 once its blockers are gone):

1. Take the newest python-build-standalone release that carries the version, and its `install_only`
   asset for `x86_64-unknown-linux-gnu`.
2. Read the asset's SHA-256 from that release's `SHA256SUMS`, and check it three more ways:
   `gh api repos/astral-sh/python-build-standalone/releases/tags/<release> --jq '.assets[] | select(.name=="<asset>") | .digest'`,
   `sha256sum` of a download, and `gh attestation verify <download> --repo astral-sh/python-build-standalone`,
   which checks the Sigstore-signed build provenance the project's own `release.yml` workflow attaches
   to every asset. That last one is the check that does not come from the same release page as the
   asset: `20261009`'s asset passed it on 2026-10-09.
3. Change the four lines in `deploy-backend.yml`'s build step and at the end of
   `infra/terraform/user_data.sh`, and `python-version` in `checks.yml`; from `backend/`, run
   `python -m pytest tests/test_interpreter_pin.py`.
4. The next deploy installs the new build beside the old one, builds a venv on it (a new name, so the
   old venv is never reused), switches, and keeps the old venv as `venv-previous` — and with it the old
   interpreter, until a later deploy no longer needs either.

**Missing is installed; nothing is upgraded in place.** The interpreter and `libatomic1` (what the
official Node 26 binary the Prisma CLI runs on links against; the live box and the 26.04 cloud image
have it, a minimal image does not) are installed when missing, or the step fails. A different interpreter only ever arrives as a new pin
in a commit, never as an apt upgrade in the middle of a deploy, so apt runs only for `libatomic1`,
with needrestart's hook suspended so an upgraded library cannot restart the API mid-deploy.

**Proved in containers on 2026-10-09**, on the scripts as committed: the deploy's four remote scripts,
extracted verbatim from `deploy-backend.yml` and piped to `bash -s` as `ubuntu` the way the runner's
`ssh` sends them, from `git archive` exports so every file had the bytes the runner checks out, with a
line after each script so that one cut short could not pass, and systemd running the two real units
as `ubuntu` beside `postgres:17`:

* **`ubuntu:24.04` set up like the live box**, by replaying its history: a 3.12 `.venv` built in
  place, then `origin/main`'s own deploy (deadsnakes 3.14.8, `venvs/py3.14-201b3cd786cbef6a`,
  `py3.12-legacy`, Node 26.11.1). Then three first deploys that must fail, each stopping at the build
  step with nothing installed or left behind, apt never run, and the API and the queue the same
  processes: the release download failing (404, after curl's retries), a pinned SHA-256 the download
  does not match (REFUSED), and a directory squatting on the pinned name (refused and left alone).
  Then the first real deploy, which also swept away a staging directory and a download directory
  left by a run killed with SIGKILL: download, verify, compile, prove, rename, venv, migrate, switch,
  with the API and the queue on `/opt/cpython/3.14.8+20261009/bin/python3.14` as `ubuntu`, loading
  the standard library from the installer's bytecode and never trying to write any, `/health/ready`
  green, and deadsnakes, its PPA and `python3.12` untouched. Then a re-run that downloaded and built
  nothing; a rollback by hand onto `venv-previous` and forward again; a rollback by redeploying
  `origin/main` and forward again; an impostor venv refused and left in place; and the pin moving
  both ways with correct hashes (to `20261003`, installed beside `20261009`; a new lock; back), until
  `20261003`'s venv and then its interpreter were retired by the tidy-up. The API and the queue
  settled at 320 and 292 MB on the pinned build, against 322 and 290 MB on deadsnakes. Every
  scenario check passed.
* **`ubuntu:26.04` set up like the cloud image** (sudo-rs, uutils coreutils, OpenSSL 3.5; and no
  `libatomic1`, which the cloud image does have): `user_data.sh` run verbatim as root with the first
  deploy started five seconds into it, which waited for it, found the interpreter it had installed
  (compiled, root's) and ran no apt; a re-run; a pin bump with a wrong hash, refused with the API
  untouched; the deploy's own install path through sudo-rs, with the pin moved to `20261003` (its
  correct hash) and back; and `user_data.sh` refusing a wrong hash itself, after nginx and the units,
  and leaving alone a directory of its pinned name that is not its build. All 20 checks passed.
* **A fresh `ubuntu:26.04` whose first boot could not reach GitHub**: `user_data.sh` put everything
  else in place and failed last, leaving a staging directory; the first deploy saw cloud-init's
  error, carried on, cleaned up, installed the pin itself through sudo-rs and brought the API up on
  it, directly and through nginx.
* **The health poll is the weak point, and it is not new.** On a host loaded by other jobs, the API
  took 80 s and once over 140 s to answer `/health` after a restart, on deadsnakes and on the pinned
  build alike, and the deploy's 40 × 2 s poll then failed the job while the API came up anyway
  (the 2026-09-17 thrashing measurement says the same of the t3.micro). A red deploy whose log ends
  `service did not become healthy` is to be checked against `/health/ready` before it is called an
  outage.
* On both releases every top-level module of every package in the lock imported in the new venv,
  with `ssl` verifying `https://pypi.org`, `sqlite3` (FTS5), `lzma`, `pyexpat` and botocore's parser,
  `ctypes` calling back into Python through libffi, `cffi`, `uvloop`, `pydantic-core`,
  `cryptography`, `bcrypt`, PyYAML's C loader and `pydub` with `audioop` exercised, and Prisma's CLI
  and query engine both got as far as `P1001: Can't reach database server` with no database behind
  them, and reported the schema up to date against the real one. Two stand-ins, both logged: a
  `cloud-init status` that blocks until `user_data.sh` ends and then answers `done` or `error`, and a
  `swapon` that only records its call, since a container cannot enable a swap file.

**Why the build cannot take the API down.** The interpreter is installed, and the venv built, before
`backend/` is synced, while the API keeps serving from the old one, with `fieldrepo-queue` stopped for
the venv install to spare memory (the box has 911 MB) and started again whatever the outcome. If the
download is refused or pip fails, that step fails and nothing the API can see has changed. The switch
to the new venv happens later, with both services stopped, as one atomic `rename(2)` of a symlink; an
exit trap restarts both if anything between their stop and their restart fails, so a failed switch
fails the deploy without leaving the API stopped (it did, before 2026-10-09's review, when run in a
container with the one-line write of `venv-previous` made to fail). A deploy that changed only code
finds its venv already built and installs nothing. After a healthy restart the deploy keeps the venv
in use, the one before it and `py3.12-legacy`, deletes older ones, and deletes any `/opt/cpython`
build that none of the kept venvs runs on.

**Going back.** A venv is only the dependencies; the code on disk is whatever the last deploy synced.
So the real rollback is the same as it has always been — redeploy the commit you want — and the
venvs are there to make that fast. What to do by hand depends on the commit:

* **A commit from this change on** carries this deploy. It installs its own pin if the box lacks it,
  picks the venv for that pin and lock itself (an older one is still there if it is `venv-previous`)
  and switches `.venv` to it. Nothing to do by hand.
* **A commit from 2026-10-09 before this change** (the apt-based deploy, `2a4a959` to `ea2a61c`)
  builds on apt's `python3.14`: on the 24.04 box deadsnakes', still installed for exactly this, and
  reusing `venvs/py3.14-<hash>` when its lock matches; on a 26.04 box Ubuntu's 3.14.4, which it
  installs `python3.14-venv` for. It works on both, but on 26.04 it is not the interpreter CI tests:
  prefer a later commit there.
* **A commit from before 2026-10-09, and that includes a `git revert` of the change that brought
  Python 3.14,** carries the OLD deploy, whose install step runs `pip install -e .` into whatever
  `.venv` points at. So point `.venv` at `py3.12-legacy` BEFORE that deploy starts, never while it
  runs:

  ```bash
  ln -sfn /home/ubuntu/app/venvs/py3.12-legacy /home/ubuntu/app/backend/.venv
  ```

  Left on a `py3.14-*` or `cpython-*` venv, the old deploy installs the old `pyproject.toml` into it
  (bcrypt 4.0.1, python-jose, passlib). That venv's name and `.complete` still say it holds the lock,
  so the next forward deploy reuses it and stops at `pip check` (`bcrypt>=5.0.0`), after its code
  sync. To recover: point `.venv` at a different venv, `rm -rf` the polluted one, and re-run the
  deploy, whose build step makes it again. A 26.04 box has no `py3.12-legacy` to point at: roll back
  there by redeploying a commit from 2026-10-09 on.

To point the services at the previous venv by hand, without a deploy:

```bash
cat /home/ubuntu/app/venv-previous                     # the venv .venv pointed at before
ln -sfn "$(cat /home/ubuntu/app/venv-previous)" /home/ubuntu/app/backend/.venv
sudo systemctl restart fieldrepo fieldrepo-queue
```

That only helps when the code on disk runs on that venv. It never does on `py3.12-legacy` under
code from 2026-10-09 on, which imports PyJWT and calls bcrypt 5 directly, neither of which is in that
venv. Once 3.14 has served for a while, delete it: `rm -rf /home/ubuntu/app/venvs/py3.12-legacy`.

**Looking without touching** (read-only, safe on the live box):

```bash
readlink -f /home/ubuntu/app/backend/.venv && cat "$(readlink -f /home/ubuntu/app/backend/.venv)/.complete"
ls -1 /home/ubuntu/app/venvs
ls -l /opt/cpython && cat /opt/cpython/*/INSTALLED_FROM
/home/ubuntu/app/backend/.venv/bin/python -m pip list --format=freeze | head
/home/ubuntu/.cache/prisma-python/nodeenv/bin/node --version
```

---

## 10. Rebuilding the box on 26.04 (approved 2026-10-09)

The owner approved rebuilding this box on Ubuntu 26.04 as a **t3.small** with an **encrypted** gp3
root, blue/green: build the new box, deploy to it, verify it, move the Elastic IP, stop (not
terminate) the old one. This repository holds no Terraform state, so the launch is an AWS CLI call;
`infra/terraform/main.tf` describes the same instance. Everything else below was read, read-only,
from i-06f177db5c4e3b0af on 2026-10-09, and the new box takes the same values:

| | The live box (i-06f177db5c4e3b0af) | The rebuild |
|---|---|---|
| Region / AZ | ap-south-1 / ap-south-1a | the same |
| AMI | `ami-006f82a1d5a27da54` (noble 24.04, 20260610) | Canonical's current 26.04 amd64 gp3 image, resolved at launch from the public SSM parameter below (`ami-039bcc649e447aea2`, `ubuntu-resolute-26.04-amd64-server-20261003`, on 2026-10-09) |
| Instance type | t3.micro (CPU credits: unlimited) | **t3.small** (unlimited) |
| Subnet | `subnet-09cf3361e0a18df70` (the default subnet of `vpc-0010b4077934ea06d`, public IPv4 on launch) | the same |
| Security group | `sg-0a3ffc8a00c9246f1` (`fieldrepo-api`: 22, 80 and 443 from anywhere) | the same |
| Key pair | `fieldrepo-deploy` (its private half is the `EC2_SSH_KEY` secret) | the same |
| Instance profile | `fieldrepo-ssm` (AmazonSSMManagedInstanceCore; SSM is the only shell that does not need the key) | the same |
| Instance metadata | IMDSv2 required, hop limit 2 | the same |
| Root volume | `/dev/sda1`, 30 GiB gp3, 3000 IOPS, 125 MB/s, **not encrypted** | the same, **encrypted** (the account default KMS key, `alias/aws/ebs`; EBS encryption by default is off in this account, so it must be asked for) |
| User data | the old `user_data.sh` (2026-06-17) | `infra/terraform/user_data.sh` from the commit being deployed |
| Elastic IP | `15.207.145.174` (`eipalloc-03a87f17914bfa85d`), the CloudFront origin | moved to the new box at cutover |
| Tags | `Name=fieldrepo-api`, `Project=fieldrepo` | `Name=fieldrepo-api-2604` until cutover, then `fieldrepo-api` |

**1. Launch**, from the repository root of a checkout of the commit that will be deployed (its
`user_data.sh` must be LF-only, which `.gitattributes` guarantees for `*.sh`). This exact call, with
`--dry-run` added, answered `DryRunOperation: Request would have succeeded` on 2026-10-09:

```bash
aws ec2 run-instances --region ap-south-1 \
  --image-id resolve:ssm:/aws/service/canonical/ubuntu/server/26.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
  --instance-type t3.small \
  --credit-specification CpuCredits=unlimited \
  --key-name fieldrepo-deploy \
  --subnet-id subnet-09cf3361e0a18df70 \
  --security-group-ids sg-0a3ffc8a00c9246f1 \
  --iam-instance-profile Name=fieldrepo-ssm \
  --metadata-options HttpTokens=required,HttpEndpoint=enabled,HttpPutResponseHopLimit=2 \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":30,"VolumeType":"gp3","Iops":3000,"Throughput":125,"Encrypted":true,"DeleteOnTermination":true}}]' \
  --user-data file://infra/terraform/user_data.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=fieldrepo-api-2604},{Key=Project,Value=fieldrepo}]' \
                       'ResourceType=volume,Tags=[{Key=Name,Value=fieldrepo-api-2604},{Key=Project,Value=fieldrepo}]' \
  --count 1
```

**2. Wait for first boot**, then check what it built (SSM, so no key is needed):
`cloud-init status --wait` must end `status: done`; `cat /opt/cpython/*/INSTALLED_FROM`,
`systemctl is-enabled fieldrepo fieldrepo-queue`, `systemctl is-active nginx`. A `user_data.sh`
that could not fetch or verify the interpreter fails last, after nginx and the units, and the first
deploy installs it (or refuses it) the same way.

**3. Deploy to it:** point the `EC2_HOST` secret at the new box's public IP, run *Deploy backend to
EC2* from the Actions tab (a dispatch deploys without waiting for Checks), and check `/health` and
`/health/ready` on that IP. **4. Cut over:** `aws ec2 associate-address --region ap-south-1
--allocation-id eipalloc-03a87f17914bfa85d --instance-id <new id> --allow-reassociation`, set
`EC2_HOST` back to `15.207.145.174`, check the API through CloudFront, retag both boxes, and **stop**
the old one (`aws ec2 stop-instances`), so going back is a start and an `associate-address`.
