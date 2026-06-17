# QA audit — failure modes (Android + web)

Status as of 2026-06-17. Both apps build clean; the web app was exercised end-to-end with
Playwright against the **live** API (admin login + all 11 protected pages → HTTP 200, **0 JS
errors**). Screenshots in `frontend/pw-screens/` (gitignored). Re-run with
`node frontend/scripts/pw-smoke.mjs` (set `PW_PASSWORD`).

## Verified working
- **Web:** login (email/password) against the live cloud API, dashboard, artisans, crafts,
  products, tools, workshops, questionnaire, media, users, review, search — all render, no console
  errors. `apiFetch` handles 204, non-JSON bodies, and surfaces `ApiError(status, detail)`.
- **Android:** compiles (`assembleDebug` green); every network call is wrapped in
  `runCatching {…}.onFailure { onError(...) }`, so transient failures show a message rather than
  crashing. Read-model DTOs are fully nullable, so partial payloads don't break deserialization.
- **Admin edit/delete** (new): backend exempts admins from field/relation ownership guards
  (`assert_can_contribute_fields/relation`), and delete is admin-only (`assert_can_delete`). The
  Android edit screen now shows an admin **Danger zone → Delete** with a confirmation dialog for
  artisan/product/process/tool/workshop/craft/interview.

## Known gaps / things to watch (by severity)

| Area | Mode | Impact | Mitigation |
|------|------|--------|------------|
| Web prod | HTTPS Vercel page → **HTTP** API is blocked (mixed content) | Web app can't call the API in production | Put the API behind **HTTPS** (domain + certbot on the nginx box), then set `NEXT_PUBLIC_API_URL=https://…`. Android (cleartext OK) is unaffected. |
| Google sign-in | Web origin / Android SHA-1 not registered → 403 / invalid token | Google login fails (email/password still works) | Register origin + `com.fieldrepository.app`/SHA-1 in Google Cloud (see RESEARCHER_GUIDE). |
| Vercel env | `NEXT_PUBLIC_API_URL` must be the **origin without** `/api` (the client appends `/api`) | Wrong value → all calls 404 | Set `NEXT_PUBLIC_API_URL=http://15.207.145.174` (not `…/api/`). |
| Android dataset download | On **API < 29** the public-Downloads fallback needs `WRITE_EXTERNAL_STORAGE` (not requested) | Save fails on Android 8–9 | Caught and surfaced via `onError`; most devices are API ≥ 29 (MediaStore path). Add the legacy permission if 8–9 support is needed. |
| Auth token | JWT expires after `JWT_EXPIRES_MINUTES` (7 days); a stale token yields 401 | User sees an error until re-login | Expected; logging out/in re-issues. Pre-login `/api/me` 401 is benign. |
| EC2 reachability | Local **SSH (22) is ISP-blocked**; box managed via GitHub Actions + AWS SSM | No direct `ssh` from this network | Use SSM Session Manager (instance is `Online`) or the Actions deploy. |
| API server | Single t3.micro, 1 GiB; no autoscaling | Heavy concurrent media transcription could be slow | DB + S3 are off-box; uvicorn runs 2 workers; scale up the instance if load grows. |
