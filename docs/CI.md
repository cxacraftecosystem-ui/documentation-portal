# Continuous integration and delivery

Everything that happens automatically when you push to `main`, why it happens in that order, and
every secret it needs. Sister documents:

- [docs/DEPLOYMENT_VERCEL.md](DEPLOYMENT_VERCEL.md) — the Vercel project itself (env vars, domains).
- [backend/DEPLOY_AWS.md](../backend/DEPLOY_AWS.md) — the EC2/S3/CloudFront side.
- [docs/ENVIRONMENT.md](ENVIRONMENT.md) — every environment variable, per service.

---

## 1. The pipeline

Three workflows, chained. One push to `main` walks the whole chain.

```mermaid
flowchart LR
    P([push to main]) --> B["<b>deploy-backend.yml</b><br/>Deploy backend to EC2"]
    B -->|workflow_run:<br/>success only| F["<b>deploy-frontend.yml</b><br/>Deploy frontend to Vercel"]
    F -->|workflow_run:<br/>any outcome| A["<b>android-build.yml</b><br/>Android build"]
    A --> R([app-debug.apk artifact])
```

| # | Workflow | File | Trigger | What it does |
|---|---|---|---|---|
| 1 | Deploy backend to EC2 | `.github/workflows/deploy-backend.yml` | `push` to `main` | rsync → write `.env` → `prisma migrate deploy` → restart `fieldrepo` + `fieldrepo-queue` → poll `/health` |
| 2 | Deploy frontend to Vercel | `.github/workflows/deploy-frontend.yml` | `workflow_run` on **1** completing | `vercel pull` → `vercel build --prod` → `vercel deploy --prebuilt --prod` → smoke-check the alias |
| 3 | Android build | `.github/workflows/android-build.yml` | `workflow_run` on **2** completing, plus `pull_request` | JDK 17 → `compileDebugKotlin` → `testDebugUnitTest` → `lintDebug` (advisory) → `assembleDebug` → upload APK |

There is also `.github/workflows/keep-supabase-active.yml` — an unrelated nightly cron that pings
Postgres so Supabase does not pause the free-tier project.

### Why the order is a dependency, not a preference

**Backend before frontend.** The browser calls the FastAPI box **directly**; there is no Next.js
proxy or rewrite in front of it (DEPLOYMENT_VERCEL.md §0). So the bundle Vercel publishes assumes
every endpoint it calls already exists. A single commit routinely adds a page *and* the API route
that page reads — if the Vercel deploy wins that race, the live site spends the gap calling routes
that answer `404`, which users see as empty lists, failed saves and "Failed to fetch" toasts. The
window is not theoretical: the backend deploy **stops the `fieldrepo` service** before running
`prisma migrate deploy`, so there is a real interval where the API is down and a freshly-shipped
frontend would be pointing straight at it. Backend first, frontend second, always.

**Android last, and unconditional.** Stage 3 builds an APK; it deploys nothing. (Getting a build
onto phones is a separate deliberate act — the in-app OTA updater compares `versionCode` against a
*release-signed* APK uploaded by a master admin. Nothing in CI can reach a device.) It is ordered
last only because it is the cheapest and least urgent stage, and running it first would delay the
deploys. It deliberately has **no success gate**: a build gate's inputs are the source tree, not the
state of the servers, so "does the Android app still compile?" is a question you want answered *more*
urgently when a deploy just failed, not less. Gating it would hide a Kotlin compile break behind an
unrelated infrastructure failure.

### What actually runs, per kind of change

The backend workflow has **no `paths:` filter** — it starts on every push to `main` and decides for
itself whether to touch EC2. That is the fix for the obvious `workflow_run` dead-lock: if stage 1
were filtered to `backend/**`, a frontend-only push would never start it, so stage 2 would never be
triggered and the frontend would never ship. Instead, stage 1's `changes` job diffs the push range,
publishes the result as the `pipeline-changes` artifact, and stages 1 and 2 skip their own work when
their area is untouched.

| Push touches | 1 · backend deploy | 2 · frontend deploy | 3 · Android build |
|---|---|---|---|
| `backend/**` only | **runs** | skipped (nothing to publish) | runs |
| `frontend/**` only | skipped (run still succeeds) | **runs** | runs |
| `android/**` only | skipped | skipped | **runs** |
| several areas | **runs** | **runs**, after 1 is green | runs |
| docs only | skipped | skipped | runs |
| backend deploy **fails** | ❌ red | **refuses to deploy**, says why in the summary | still runs |

Anything the diff cannot be computed for — manual dispatch, the first push of a branch, a force-push
that orphaned the previous head — is treated as "everything changed". The pipeline over-deploys
rather than silently skipping a real change.

---

## 2. Required repository secrets

**Settings → Secrets and variables → Actions → New repository secret.** Names are case-sensitive.

| Secret | Used by | Where to get the value |
|---|---|---|
| `EC2_HOST` | backend | Public/Elastic IP of the API box. `cd infra/terraform && terraform output api_public_ip`, or EC2 console → Instances → the `fieldrepo` instance → Public IPv4. Currently `15.207.145.174`. |
| `EC2_SSH_KEY` | backend | The **entire** private key file for the instance's key pair, `-----BEGIN…` through `-----END…` inclusive, with the trailing newline: `infra/terraform/fieldrepo-deploy.pem`. Paste the file contents, not the path. `*.pem` is gitignored — never commit it. |
| `BACKEND_ENV` | backend | The full contents of the production `backend/.env`: `DATABASE_URL`, `JWT_SECRET`, `AWS_*`, `OPENAI_API_KEY`, `GEMINI_API_KEYS`, `ELEVENLABS_*`, `DEEPGRAM_*`, `BACKEND_CORS_ORIGINS`, … Every key and its meaning is in [ENVIRONMENT.md](ENVIRONMENT.md). Easiest source of truth: `ssh ubuntu@$EC2_HOST cat /home/ubuntu/app/backend/.env`. The workflow pipes it over the SSH tunnel; it is never on a command line. |
| `VERCEL_TOKEN` | frontend | <https://vercel.com/account/tokens> → **Create Token**. Scope it to the **team that owns `field-repository`**, not "Personal Account", or the CLI 403s. Set an expiry you will actually remember — the deploy starts failing with `Error: Not authorized` the day it lapses. This is the only genuinely sensitive value of the three Vercel ones. |
| `VERCEL_ORG_ID` | frontend | `team_pcTf4Alb2DCIwq2IZcdu00dS`. Also at Vercel → Team Settings → General → **Team ID**, or in `frontend/.vercel/project.json` (`orgId`) after a local `vercel link`. An identifier, not a credential. |
| `VERCEL_PROJECT_ID` | frontend | `prj_EzXN8hhGKpMciFBrZRdxpcgUUzN0`. Also at Vercel → Project `field-repository` → Settings → General → **Project ID**, or `frontend/.vercel/project.json` (`projectId`). An identifier, not a credential. |
| `SUPABASE_DATABASE_URL` *or* `DATABASE_URL` | keep-alive cron | The Supabase Postgres connection string (Supabase → Project → Connect). Pre-existing; unrelated to deploys. |

`GITHUB_TOKEN` is **not** something you create — GitHub injects it per run. Stage 2 uses it only to
download stage 1's change-detection artifact (`permissions: actions: read`).

The Android workflow needs **no secrets at all**. It produces a debug-signed APK, and debug signing
uses the auto-generated debug keystore. The release key is deliberately not in CI.

### Not GitHub secrets: the `NEXT_PUBLIC_*` values

`NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID`, `NEXT_PUBLIC_MAPTILER_API_KEY` and friends are
**build-time** variables that live in the Vercel project (Project → Settings → Environment
Variables, DEPLOYMENT_VERCEL.md §2). `vercel pull` fetches them into the runner before
`vercel build`, so the Vercel dashboard stays the single source of truth and you do not maintain the
same value in two places. Change one there and re-run this workflow (or push) to pick it up.

---

## 3. One-time setup

1. **Add the three `VERCEL_*` secrets** above. The other secrets already exist.

2. **Turn off Vercel's own Git auto-deploy for `main`.** This is not optional and it is the easiest
   thing to miss. Vercel's GitHub integration currently deploys production on every push to `main`
   (DEPLOYMENT_VERCEL.md §3). Left on, it races ahead of the backend and re-creates precisely the
   bug this pipeline exists to prevent — the CI deploy would just be a slower second deploy of the
   same commit. Pick one:

   - **Vercel → Project → Settings → Git → Ignored Build Step**, set to `exit 0` — cancels every
     dashboard-triggered build, keeps the Git connection (and therefore commit/PR annotations).
   - or add to `frontend/vercel.json`:

     ```json
     "git": { "deploymentEnabled": { "main": false } }
     ```

     (Not applied in this change: `frontend/vercel.json` is owned by the frontend workstream.)

   Preview deployments for pull requests can stay on — a preview is not the live site and has
   nothing to be ordered against.

3. **Merge these workflow files to `main`.** `workflow_run` only fires for workflow files that exist
   **on the default branch** — on a feature branch, stages 2 and 3 will not trigger no matter what
   stage 1 does. Stage 3 still builds on pull requests, so PR feedback works before the merge.

4. **First run:** push a no-op commit to `main` (or `workflow_dispatch` the backend workflow) and
   watch all three go green in order before trusting the chain.

---

## 4. Running things by hand

| Goal | How |
|---|---|
| Deploy the backend now | Actions → *Deploy backend to EC2* → **Run workflow**. Manual dispatch always deploys (it skips change detection). Stage 2 does **not** chain off a manual dispatch of stage 1 unless the run completes on `main`. |
| Deploy the frontend now | Actions → *Deploy frontend to Vercel* → **Run workflow**. Leave `force` = true to deploy regardless of what changed. This bypasses the backend gate — that is the escape hatch, use it knowing why. |
| Build the APK now | Actions → *Android build* → **Run workflow**, or open a PR touching `android/**`. |
| Re-deploy after changing a Vercel env var | Re-run *Deploy frontend to Vercel*. `NEXT_PUBLIC_*` values are baked at build time; changing them in the dashboard does nothing until something rebuilds. |
| Get the APK | The run's **Artifacts** section → `app-debug-<sha>`. Debug-signed: sideload-only, and Android will refuse to install it over a release-signed build. |

---

## 5. Known limits, and things that are deliberately not gates

- **Android Lint is advisory.** `./gradlew :app:lintDebug` on the current tree reports
  *1 error, 44 warnings* and aborts. The error is pre-existing and unrelated to any code change:
  `AndroidManifest.xml:6 PermissionImpliesUnsupportedChromeOsHardware` — `CAMERA` is requested with
  no matching `<uses-feature android:name="android.hardware.camera" android:required="false"/>`.
  Making lint a hard gate today would fail every run and train everyone to ignore red. The HTML/XML
  report is uploaded on every run. Fix the manifest (or commit a `lint-baseline.xml`), then delete
  `continue-on-error` from the lint step and it becomes a real gate.
- **There are no Android tests.** `android/app/src` contains only `main/` — no `src/test/`, no
  `src/androidTest/`. `:app:testDebugUnitTest` is wired in anyway and currently reports `NO-SOURCE`;
  the step prints a warning so a green tick is never mistaken for "the tests passed". The first unit
  test anyone adds is enforced the moment it lands, with no CI change. Instrumented tests are not
  run at all — they need an emulator; add a separate job with an emulator action if that changes.
- **No web typecheck/lint gate.** `vercel build` runs `next build`, which fails on TypeScript and
  ESLint errors, so a broken frontend cannot reach production — but the failure surfaces as a build
  failure after the backend has already deployed. If you want it caught earlier, add `npm ci &&
  npm run typecheck && npm run lint` as a separate job that stage 2 `needs:`.
- **Don't chain a fourth stage.** GitHub caps how deep `workflow_run` chains can go (documented at
  three levels); this pipeline already uses two hops. A fourth stage should be a job with `needs:`
  inside an existing workflow, not another `workflow_run` link.
- **`concurrency.cancel-in-progress` is off for both deploys.** Cancelling a backend run mid-deploy
  can leave `fieldrepo` stopped between the service stop and the migrate, with no restart step left
  to run. Overlapping pushes queue instead. Only the Android build is cancellable — nothing outside
  the runner is mutated there.

---

## 6. Troubleshooting

**Stage 2 never starts.** `workflow_run` fires only for workflow files on the **default branch**,
and only for runs whose head branch is `main` (the trigger is filtered to `branches: [main]`). Check
that both files are merged. Also check stage 1 actually *ran* — with change detection it may show a
skipped `deploy` job, which is normal and still triggers stage 2.

**Stage 2 says "Backend deploy concluded 'failure' — refusing to publish the frontend".** Working as
designed. Fix the backend deploy, re-run it, and stage 2 will follow automatically. If you must ship
the frontend anyway, dispatch it manually (§4) and know that the site may call endpoints that are
not there yet.

**`Error: Not authorized` / `Forbidden` from the Vercel CLI.** `VERCEL_TOKEN` expired, was revoked,
or is scoped to a personal account instead of the team that owns the project. Re-issue it (§2).

**`Vercel project Root Directory is '', expected 'frontend'`.** Someone cleared Root Directory in
the dashboard. The workflow fails fast on this on purpose, because the alternative is a confusing
`No Next.js version detected` sixty lines into a build. Restore it: Project → Settings → General →
Root Directory = `frontend`. Every Vercel CLI command in the workflow runs from the **repository
root** precisely because that setting is what points the build at `frontend/`; do not "fix" a
root-directory error by adding `working-directory: frontend`, which makes the CLI look for
`frontend/frontend`.

**`npm ci can only install packages when … in sync`.** `frontend/package-lock.json` is stale. Run
`npm install` in `frontend/` and commit the lockfile (DEPLOYMENT_VERCEL.md §7.5).

**Two production deployments per push.** Vercel's Git integration is still enabled for `main` —
see §3.2.

**Android build fails on the SDK.** The workflow installs `platforms;android-35` and
`build-tools;35.0.0` explicitly because runner images drift. If `compileSdk` in
`android/app/build.gradle.kts` moves, update that step and the JDK pin together — the JDK 17 pin
tracks `sourceCompatibility`/`jvmTarget` in the same file.

**A deploy hangs on the health poll.** Stage 1 polls `http://127.0.0.1:8000/health` 40 times at 2 s
and dumps `journalctl -u fieldrepo -n 80` on failure. Read that output first; the usual causes are a
bad `BACKEND_ENV` value and Supabase pooler connection exhaustion — both covered in
[QA_AUDIT.md](QA_AUDIT.md).
