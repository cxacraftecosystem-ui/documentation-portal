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
ExecStart=/home/ubuntu/app/backend/.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 2
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now fieldrepo
sudo systemctl status fieldrepo
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

```dotenv
DATABASE_URL=postgresql://...supabase-pooler...:5432/postgres   # keep Supabase
JWT_SECRET=<long-random>
MASTER_ADMIN_EMAIL=you@example.com

# Real S3 — leave AWS_S3_ENDPOINT UNSET so boto3 talks to AWS (it was localhost:9000 for MinIO)
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=...
AWS_REGION=ap-south-1
AWS_S3_BUCKET=your-bucket
AWS_S3_PUBLIC_BASE_URL=https://your-bucket.s3.ap-south-1.amazonaws.com

OPENAI_API_KEY=...
GEMINI_API_KEYS=...,...

BACKEND_CORS_ORIGINS=https://your-frontend-domain
```

Do **not** commit `.env`. Presigned PUTs use SigV4 (already configured in `services/s3.py`), so any
region works.

---

## 6. Point the apps at it

- **Android**: set `android/local.properties` → `apiBaseUrl=http://<ELASTIC_IP>:8000/api/` (or your
  HTTPS domain), then `./gradlew.bat :app:assembleDebug` and reinstall. If you stay on plain HTTP,
  keep `usesCleartextTraffic="true"` (already set) or front the API with nginx + TLS and use `https`.
- **Web**: set `NEXT_PUBLIC_API_BASE_URL` (or equivalent) to the API URL and add the frontend origin
  to `BACKEND_CORS_ORIGINS` and the bucket CORS.

---

## 7. (Optional) HTTPS

Put nginx in front of uvicorn and run certbot:

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
# proxy_pass http://127.0.0.1:8000; in a server block for your domain, then:
sudo certbot --nginx -d api.yourdomain.com
```

Then the API is `https://api.yourdomain.com/api/` and you can drop the cleartext allowance.

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

The Vercel project is linked to this GitHub repo (same account), so each push to
`main` auto-deploys. In the Vercel project settings:

- **Root Directory:** `frontend` (this is a monorepo; `frontend/vercel.json`
  pins the Next.js framework).
- **Environment variables:** `NEXT_PUBLIC_API_URL = https://d2b34i3e92al6i.cloudfront.net` — the
  **origin only, without** `/api` (the web client appends `/api` itself). Also set
  `NEXT_PUBLIC_GOOGLE_CLIENT_ID = …`.
- **Mixed content:** an HTTPS Vercel page cannot call an HTTP API — browsers block it. The API is now
  fronted by **CloudFront over HTTPS** (`https://d2b34i3e92al6i.cloudfront.net`, dual-stack), so use
  that `https://…` value above and the block is gone. (Hitting the raw EC2 origin over `http://…`
  would still be blocked.) The Android app talks to the same CloudFront URL.
- **Google sign-in:** add the Vercel origin to the Google OAuth web client's *Authorized JavaScript
  origins*, or the GSI button returns 403.
- Add the resulting Vercel URL to `BACKEND_CORS_ORIGINS` (in `BACKEND_ENV`) and to
  the bucket's `cors_allowed_origins` Terraform var.

### 8.4 Point the Android app at the box

`android/local.properties` → `apiBaseUrl=http://<EC2_HOST>/api/` (port 80 via
nginx — no `:8000`), then `./gradlew.bat :app:assembleDebug` and reinstall.

### 8.5 ffmpeg note

The long-audio Whisper chunking (`pydub`) needs the **ffmpeg** binary. Terraform's
`user_data.sh` installs it (`apt-get install -y ffmpeg`). If you provision a box
by hand, run `sudo apt install -y ffmpeg`, otherwise long recordings fall back to
a single-shot transcription attempt.
