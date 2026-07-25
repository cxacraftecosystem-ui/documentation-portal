# Deploying the web frontend to Vercel

This is the complete runbook for putting `frontend/` (Next.js 16) on Vercel in front of the live
FastAPI backend. It assumes nothing beyond a GitHub account with access to this repository and a
Vercel account. Sister documents:

- [docs/ENVIRONMENT.md](ENVIRONMENT.md) — every environment variable, per service, with defaults.
- [backend/DEPLOY_AWS.md](../backend/DEPLOY_AWS.md) — the EC2/S3/CloudFront side.
- [docs/QA_AUDIT.md](QA_AUDIT.md) — historic failure modes and their resolutions.

---

## 0. What is being deployed, and what is not

| Piece | Where it runs | Notes |
|---|---|---|
| Next.js web app | **Vercel** | Deployed by `.github/workflows/deploy-frontend.yml`, **after** the backend deploy succeeds ([docs/CI.md](CI.md)) — not by Vercel's Git integration (§3). 25 of 28 routes prerender to static HTML; the three `[id]/edit` routes render on demand (§8). No route handlers, no server actions, no `fs` access — see §8. |
| FastAPI API | **AWS EC2** `t3.micro`, behind nginx | Not deployed by Vercel. Auto-deployed by `.github/workflows/deploy-backend.yml`. |
| HTTPS + IPv6 edge for the API | **CloudFront** `https://d2b34i3e92al6i.cloudfront.net` | The value the browser actually talks to. |
| Database | **Supabase** Postgres | Reached only by the backend. |
| Media | **S3** `fieldrepo-media-626159998512` (dual-stack endpoints) | Browsers upload straight to S3 with signed PUT URLs — the bytes never pass through Vercel. |

The browser calls the backend **directly**; there is no Next.js proxy or rewrite in front of it.
That is deliberate: no API traffic and no media bytes pass through a Vercel Function, so the
deployment stays off Vercel's bandwidth and function-invocation budget. It also means **CORS on the
backend is not optional** (§4).

---

## 1. Import the repository

1. Sign in to <https://vercel.com> with the GitHub account that can read this repository.
2. **Add New… → Project**, then **Import** `documentation-portal`.
3. On the configure screen, set:

   | Setting | Value | Why |
   |---|---|---|
   | **Root Directory** | `frontend` | This is a monorepo. Leave it at the repo root and the build fails immediately — there is no `package.json` with Next.js there. Click **Edit** next to Root Directory and pick `frontend`. |
   | **Framework Preset** | Next.js | Auto-detected from `frontend/vercel.json` (`"framework": "nextjs"`). |
   | **Build Command** | `next build` | From `frontend/vercel.json`. Leave the override off. |
   | **Install Command** | `npm ci` | From `frontend/vercel.json`; installs exactly what `frontend/package-lock.json` pins. If the build fails with `npm ci can only install packages when your package.json and package-lock.json are in sync`, run `npm install` locally and commit the updated lockfile. |
   | **Output Directory** | `.next` | Default for Next.js. |
   | **Node.js Version** | 20.x or newer | Next 16 requires ≥ 20. Vercel's current default is fine. |

4. **Do not deploy yet** — add the environment variables first (§2). A first build without
   `NEXT_PUBLIC_API_URL` succeeds but silently ships a bundle pointing at `http://localhost:8000`.

---

## 2. Environment variables

Set these under **Project → Settings → Environment Variables**. Names are case-sensitive and must
match exactly.

| Name | Value (production) | Required | Environments |
|---|---|---|---|
| `NEXT_PUBLIC_API_URL` | `https://d2b34i3e92al6i.cloudfront.net` | **Yes** | Production, Preview, Development |
| `NEXT_PUBLIC_APP_URL` | `https://<your-project>.vercel.app` (or the custom domain) | No — see below | Production, Preview, Development |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | the Google **web** OAuth client ID | Only for Google sign-in | Production, Preview, Development |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | MapTiler key | No | Production, Preview, Development |

### 2.1 The trailing `/api` trap — read this before anything else

`frontend/lib/api.ts` line 3 reads the variable and every request is built as:

```ts
export const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000";
…
fetch(`${API_BASE}/api${path}`, …)
```

The client appends `/api` **itself**. So `NEXT_PUBLIC_API_URL` must be the **origin only**:

| Value you set | Request that goes out | Result |
|---|---|---|
| `https://d2b34i3e92al6i.cloudfront.net` | `https://d2b34i3e92al6i.cloudfront.net/api/artisans` | ✅ correct |
| `https://d2b34i3e92al6i.cloudfront.net/api` | `https://…/api/api/artisans` | ❌ every screen 404s |
| `https://d2b34i3e92al6i.cloudfront.net/` | `https://…//api/artisans` | ❌ every screen 404s |
| `http://15.207.145.174` | blocked before it leaves the browser | ❌ mixed content (§6.1) |

This has broken the deployment more than once. If the app loads but every list is empty and login
fails, open devtools → Network and look for a doubled `/api/api/` segment before debugging anything
else.

`NEXT_PUBLIC_APP_URL` is listed for completeness only: **no code under `frontend/` reads it**
(`grep -rn "process.env" frontend/` returns `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID`
and `NEXT_PUBLIC_MAPTILER_API_KEY`). It is the **backend's** variable of the same name
(`app/core/config.py`), reused here so one value stays in sync. Setting it in Vercel is harmless
and keeps the two aligned, but leaving it out breaks nothing. What actually has to know the Vercel
origin is `BACKEND_CORS_ORIGINS` on the API box (§4.1).

### 2.2 Public means public

Every variable is prefixed `NEXT_PUBLIC_`, which means **Next.js inlines the literal value into the
JavaScript bundle at build time**. Two consequences:

1. None of them can hold a secret. Never add `JWT_SECRET`, `DATABASE_URL`, an AWS key, or a Google
   client *secret* to the Vercel frontend project — those belong to the backend only.
2. **Changing a value in the dashboard does nothing until you redeploy.** The old value is already
   compiled into the shipped bundle. See §5.

### 2.3 Preview vs Production environments

Vercel builds three scopes and you tick which ones each variable applies to:

- **Production** — the `main` branch → your production domain.
- **Preview** — every other branch and every pull request → a throwaway
  `https://<project>-<hash>-<team>.vercel.app` URL.
- **Development** — `vercel dev` on a laptop. Local work normally uses `frontend/.env.local`
  (copy `frontend/.env.local.example`) and ignores this scope entirely.

Ticking all three for the four variables above is the simplest correct setup and is what this
project does. Two caveats if you later split them:

- Preview deployments get a **new hostname per deployment**. Those hostnames are not in
  `BACKEND_CORS_ORIGINS`, so previews cannot call the production API unless you add them (§4) or
  point previews at a separate staging API.
- `NEXT_PUBLIC_APP_URL` cannot be correct for previews in the general case — set it to the
  production domain and accept that previews report the production origin, or leave it out of the
  Preview scope.

---

## 3. Deploy

> **Production deploys are now done by GitHub Actions, not by Vercel's Git integration.**
> `.github/workflows/deploy-frontend.yml` runs `vercel pull` → `vercel build --prod` →
> `vercel deploy --prebuilt --prod`, and it is chained to run **only after the backend deploy
> succeeds**. That ordering is the whole point: the browser calls the FastAPI box directly (§0), so
> a frontend published ahead of its backend spends the gap calling endpoints that answer 404 — and
> the backend deploy stops the API service to run migrations, so the gap is real. Full pipeline,
> secrets and troubleshooting: **[docs/CI.md](CI.md)**.
>
> This means step 2 below is a **required** one-time change to the project: Vercel's own auto-deploy
> for `main` must be switched off, or it races ahead of the backend and re-creates exactly the bug
> the pipeline prevents. Preview deployments for pull requests are unaffected — leave them on.

1. Click **Deploy** for the first build (1–3 minutes) so the project exists and the domain is
   assigned.
2. Then disable dashboard-triggered production builds: **Settings → Git → Ignored Build Step** →
   `exit 0` (or set `"git": { "deploymentEnabled": { "main": false } }` in `frontend/vercel.json`).
3. Add the `VERCEL_TOKEN`, `VERCEL_ORG_ID` and `VERCEL_PROJECT_ID` repository secrets — see
   [docs/CI.md §2](CI.md#2-required-repository-secrets) for exactly where each value comes from.

From then on, a push to `main` deploys the backend first and this project second, automatically.

Verify the deployment:

1. Open the deployment URL — the landing page should render.
2. Go to `/login`, open devtools → Network, and attempt a login.
3. The request must be `POST https://d2b34i3e92al6i.cloudfront.net/api/auth/login` — HTTPS, single
   `/api`, and a `200` (or a `401` for wrong credentials, which still proves connectivity).

---

## 4. Backend changes required for the Vercel origin

The API rejects browser origins it does not know. Two allowlists must contain the Vercel origin.

### 4.1 API CORS

`backend/app/core/config.py` reads `BACKEND_CORS_ORIGINS` — a comma-separated list of exact
origins (scheme + host, **no trailing slash, no path, no wildcards**) that FastAPI's
`CORSMiddleware` accepts:

```dotenv
BACKEND_CORS_ORIGINS=https://your-project.vercel.app,https://repo.example.org,http://localhost:3000
```

To apply it on the live box, edit the `BACKEND_ENV` GitHub Actions secret (**Settings → Secrets and
variables → Actions**), which holds the whole `backend/.env` file, then re-run
`.github/workflows/deploy-backend.yml` (or push any change under `backend/`). The workflow rewrites
`.env` on the instance and restarts `fieldrepo` and `fieldrepo-queue`.

To apply it by hand, SSH to the box, edit `/home/ubuntu/app/backend/.env` and
`sudo systemctl restart fieldrepo fieldrepo-queue`.

### 4.2 S3 bucket CORS

The browser PUTs media straight to S3, so the bucket's CORS rule needs the Vercel origin too.
Bucket → **Permissions → CORS**:

```json
[{
  "AllowedHeaders": ["*"],
  "AllowedMethods": ["PUT", "GET", "HEAD"],
  "AllowedOrigins": ["https://your-project.vercel.app"],
  "ExposeHeaders": ["ETag"]
}]
```

In Terraform this is the `cors_allowed_origins` variable (`infra/terraform/`).

### 4.3 Google OAuth authorised origins

Add the Vercel origin to the Google Cloud console → **APIs & Services → Credentials → your web
OAuth client → Authorized JavaScript origins**. Without it, Google Identity Services returns `403`
and the sign-in button never renders. Add both `https://your-project.vercel.app` and any custom
domain.

---

## 5. Redeploy after an environment change

Because `NEXT_PUBLIC_*` values are compiled in, editing them in the dashboard has **no effect on the
live site** until a new build runs:

**Preferred, now that CI owns production:** GitHub → Actions → *Deploy frontend to Vercel* →
**Run workflow** (leave `force` = true). It runs `vercel pull` first, so it always picks up the
current dashboard values. Then hard-reload the site (Ctrl-F5) to drop the cached bundle.

From the Vercel dashboard instead — note this only works if you have not disabled dashboard builds
per §3, and it bypasses the backend-first ordering:

1. **Deployments** tab → the most recent production deployment → **⋯ → Redeploy**.
2. **Untick "Use existing Build Cache"** so the variables are read fresh.
3. Wait for the build, then hard-reload the site (Ctrl-F5) to drop the cached bundle.

Pushing an empty commit (`git commit --allow-empty -m "chore: redeploy"`) also works and goes
through the full pipeline — but the change detector sees no `frontend/**` files and skips the
deploy, so use **Run workflow** rather than an empty commit.

---

## 6. Custom domain

1. **Project → Settings → Domains → Add**, enter e.g. `repo.example.org`.
2. Add the DNS record Vercel shows at your registrar (`CNAME` → `cname.vercel-dns.com` for a
   subdomain; `A` → `76.76.21.21` for an apex domain).
3. Wait for the certificate to be issued (usually minutes; Vercel provisions Let's Encrypt).
4. Then, in this order:
   - update `NEXT_PUBLIC_APP_URL` to the custom domain and redeploy (§5);
   - add the domain to `BACKEND_CORS_ORIGINS` (§4.1) and redeploy the backend;
   - add it to the S3 bucket CORS (§4.2);
   - add it to the Google OAuth authorised origins (§4.3).

Skipping any of the last three leaves the site loading but unable to log in or upload.

---

## 7. Troubleshooting

### 7.1 Mixed content — "blocked loading mixed active content"

An HTTPS page may not call an HTTP endpoint; the browser blocks the request before it is sent, so
the server logs show nothing at all. This is why `NEXT_PUBLIC_API_URL` must point at CloudFront
(`https://d2b34i3e92al6i.cloudfront.net`) and never at the raw EC2 origin
(`http://15.207.145.174`). Symptom: every request fails instantly, devtools console shows
`Mixed Content: The page at 'https://…' was loaded over HTTPS, but requested an insecure resource`.

### 7.2 Everything 404s / lists are empty

`NEXT_PUBLIC_API_URL` includes `/api` or a trailing slash. See §2.1. Check the Network tab for
`/api/api/` or `//api/`. Fix the variable **and redeploy** (§5) — editing it alone changes nothing.

### 7.3 CORS preflight failure

Console reads `Access to fetch at 'https://…/api/…' from origin 'https://…vercel.app' has been
blocked by CORS policy: Response to preflight request doesn't pass access control check`.

Checklist:
- The exact origin (scheme + host, no trailing slash) is in `BACKEND_CORS_ORIGINS`.
- The backend was **restarted** after the change — the settings object is `@lru_cache`d, so a
  running process never picks up an edited `.env`.
- You are testing the origin you allowlisted. Preview deployments have per-deployment hostnames
  that are *not* covered by the production entry (§2.3).
- The same request from curl works — that confirms it is CORS and not an API error, because curl
  sends no `Origin` header and therefore never triggers the check.
- For S3 upload failures specifically, it is the **bucket's** CORS rule, not the API's (§4.2).

### 7.4 API unreachable from a phone on mobile data, fine on Wi-Fi

Indian mobile networks (Jio/Airtel) are increasingly **IPv6-only**. The EC2 origin is IPv4-only and
has no `AAAA` record, so the request never resolves. CloudFront is dual-stack, which is the reason
the API is fronted by it; S3 media likewise uses the dual-stack endpoint
(`…s3.dualstack.ap-south-1.amazonaws.com`), minted by
`backend/app/services/s3.py::public_url_for_key`. If you ever hardcode a plain
`…s3.ap-south-1.amazonaws.com` URL, media will silently fail to load on those networks.

### 7.5 Build fails with `npm ci can only install packages when … in sync`

`frontend/package-lock.json` is stale relative to `frontend/package.json`. Run `npm install` in
`frontend/`, commit the updated lockfile, and push.

### 7.6 Build fails because the Root Directory is wrong

`No Next.js version detected` or `Couldn't find any pages or app directory` means the Root
Directory is still the repository root. Set it to `frontend` (§1).

### 7.7 Uploads of large files time out

CloudFront's origin response timeout applies to API calls (not to the direct-to-S3 PUTs). Large
media goes straight to S3 via signed URLs precisely to avoid this. If an API call itself times out
at ~30 s, raise the CloudFront distribution's **Origin response timeout** in the AWS console — see
`docs/QA_AUDIT.md`.

### 7.8 Session drops to the login page unexpectedly

`lib/api.ts` clears the stored token and redirects to `/login` on any `401` from an authenticated
request. That is expected when the JWT expires (`JWT_EXPIRES_MINUTES`, default 7 days) or after
`JWT_SECRET` is rotated on the backend — rotating it logs everyone out.

---

## 8. What makes this app Vercel-compatible (and what would break it)

Audited on the current tree:

- **No `app/api/**/route.ts` handlers** — nothing needs a serverless function of ours.
- **No server actions** (`"use server"` appears nowhere).
- **No `fs` / `node:*` imports in application code.** The only match is
  `frontend/scripts/pw-smoke.mjs`, a Playwright smoke script that is never imported by the app and
  never bundled.
- **No `output: "standalone"`.** That setting produces a self-hosted Node bundle and breaks the
  Vercel build — `frontend/next.config.ts` documents why it must stay off. It is only relevant if
  the frontend is ever moved onto the EC2 box or into a container.
- **Fonts are local** (`frontend/fonts/*.woff2` via `next/font/local`), so builds do not depend on
  a network fetch to Google Fonts.
- Every data-fetching page is a client component that calls the API from the browser, so no
  request-time secrets exist. `next build` on the current tree reports 25 of 28 routes as `○`
  (prerendered static). The three exceptions are `ƒ` (server-rendered on demand):
  `/artisans/[id]/edit`, `/products/[id]/edit`, `/tools/[id]/edit` — they have a dynamic segment
  and no `generateStaticParams`, so Vercel deploys a small Node function that renders the client
  shell and hands off to the browser. It fetches nothing and reads no environment variable at
  request time. Adding `generateStaticParams` is not possible here (the id set is unbounded), so
  this is expected, not a defect.

If you add any of the following, revisit this document: a route handler that proxies the API
(then CORS stops mattering but Vercel bandwidth starts to), `next/image` on a host not listed in
`images.remotePatterns` (hard runtime error), or a server component that reads the API at request
time (needs the API reachable from Vercel's servers, not just from the browser).

`frontend/next.config.ts` also sets response security headers on every route:
`Strict-Transport-Security` (production builds only, so a local HTTPS proxy can never pin
`localhost`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Content-Security-Policy: frame-ancestors 'none'`, `Referrer-Policy: strict-origin-when-cross-origin`,
and a `Permissions-Policy` that deliberately **keeps** `geolocation`, `microphone`, `camera` and
`clipboard-write` enabled for our own origin — field capture (GPS tagging, audio recording, camera
file inputs) stops working if those get denied.
