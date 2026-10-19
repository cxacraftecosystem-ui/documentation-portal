# Questionnaire v2 API

The backend half of the questionnaire redesign (`C:/dev/IIT/docs/questionnaire/DESIGN_2026-10-18.md`,
build plan `BUILD_PLAN_2026-10-18.md` beside it). The **frozen contract** is
`shared/questionnaire-contract.json`: every example there validates against
`backend/app/schemas/questionnaire_v2.py`, and every route it lists exists
(`backend/tests/test_questionnaire_contract.py`). This page says how the pieces fit; the contract file
is the authority on shapes.

## Switches

| Switch | Shipped | Effect |
|---|---|---|
| `QUESTIONNAIRE_V2` (env) | `false` | Off: every v2 route answers 404, the old routes behave exactly as before. On: the routes below, and the old-build shims. |
| `AppSetting.transcriptionPaused` (database) | `true` | Owner correction (d): no audio leaves the server. Jobs wait as QUEUED; Retry answers 202 `{"status":"QUEUED","paused":true}`. |
| `QUESTIONNAIRE_MIN_APP_VERSION_FOR_CREATE` (env) | unset | Decision 5: older builds' creates get 503 + `Retry-After: 86400`. Clips are always accepted. |

`GET /api/questionnaire/config` reports all three to both clients.

## Routes

Base `/api/questionnaire` unless the path starts with `/api/`. Errors are always
`{"detail": {"code", "message", ...}}` with a code from the contract's closed list.

| Area | Routes | Service |
|---|---|---|
| Config | `GET /config` | `backend/app/api/routes/questionnaire_v2.py` |
| Sessions | `GET /sessions/open-match`, `POST /sessions` (201, or 200 for a repeated `clientKey`), `GET /sessions`, `GET`/`PATCH`/`DELETE /sessions/{id}`, participants, researchers, `POST /sessions/join`, `/finish`, `/merge-into/{targetId}`, `POST /sessions/continue`, `GET /sessions/{id}/insights` | `backend/app/services/questionnaire_v2/sessions.py` |
| Coverage | `POST /coverage/check`, `GET /coverage`, `GET /coverage/snapshot` | `backend/app/services/questionnaire_v2/coverage.py` |
| Recordings | `PATCH`/`DELETE /recordings/{id}`, `/restore`, `/decide`, `/make-primary`, `/answer-proposals`, `/transcribe`, `GET /recordings/{id}/download` | `backend/app/services/questionnaire_v2/recordings.py` |
| Media | `POST /api/media/presign` and `/api/media/multipart/create` with `linkedRecordType: "questionnaire_session"`, `GET /api/media/uploads/{clientClipKey}`, `POST /api/media/complete` | `backend/app/api/routes/media.py` dispatches to the recordings service |
| Answers | `PUT /answers` | `backend/app/services/questionnaire_v2/answers.py` |
| Inbox | `GET /inbox` | `backend/app/services/questionnaire_v2/inbox.py` |
| Artisan | `GET`/`POST /api/artisans/{id}/consent` (always on), `GET /api/artisans/{id}/record` and `.csv` | `backend/app/services/consent_gate.py`, `backend/app/services/questionnaire_v2/record.py` |
| Corpus | `GET /corpus.jsonl` and `/corpus.csv` with `questionnaireId`, `status` (default `APPROVED`) and `kind` = `answers`, `recordings` or `meta` | `backend/app/services/questionnaire_v2/corpus.py` |

The two halves of the corpus are two downloads of one route (`kind`), each with its NAMING file name
in `Content-Disposition`; `kind=meta` answers the contract's `CorpusMeta`.

## What holds the rules

- **Titles and file names:** `backend/app/services/naming.py`, against the golden table in
  `shared/session-title.json` (`backend/tests/test_naming.py`). The server mints every new S3 leaf at
  presign; old keys and `originalFilename` are never renamed, and downloads carry the readable name.
- **Coverage:** the view `questionnaire_take_v` (migration `20261025090000_questionnaire_v2`). Nothing
  is stored: positions, grids and snapshots are read from it.
- **The consent gate:** `backend/app/services/consent_gate.py`. It is asked before every outside send
  (transcription, refinement, AI summaries and translation, answer drafting, search embeddings) and
  never by recording, upload, filing or review. Summaries are asked when queued and again when the
  job runs; a draft from pasted text asks for the interview's participants; a transcript whose
  recording may not leave is not embedded, and is asked again whenever new consent is recorded.
- **Transcription:** `backend/app/transcription/__init__.py`. `enqueue` is the only door; it checks the
  pause, the gate and a verified twin, and calls no provider.
- **Old builds:** `backend/app/services/questionnaire_v2/shims.py` and the one legacy resolver,
  `backend/app/services/questionnaire_v2/legacy_resolver.py`.

## Data scripts (dry run by default, never against production from a test)

| Script | Design | Writes on `--apply` |
|---|---|---|
| `backend/scripts/questionnaire_v2_backfill.py` | 7.3 | Legacy rows become LEGACY_RECONSTRUCTED sessions; clips become recordings; copies are soft-removed. `--undo --run-id` reverts a run. `--sha-only` counts the clips without a checksum; `--sha-only --apply` hashes them, and must run before the dry run (apply refuses while any clip is unhashed). |
| `backend/scripts/backfill_consent_owner_attestation.py` | 7.6 | One RECORDING consent row per audio or video file made on or before 18 Oct 2026, source "owner attestation". No job, status or transcript changes. |
| `backend/scripts/questionnaire_purge.py` | 7.5 | Verified duplicates removed more than 30 days ago, and orphan objects unclaimed 30 days after their report. Never a distinct take. |

All three print the target database host, refuse a host not named in `--allow-host`, run the dry run
in a verified READ ONLY transaction, and refuse `--apply` unless `--approved-report` is the current dry
run's report carrying `Approved-by: <owner email> <YYYY-MM-DD>`.

## Migrations

- `20261022090000_consent_and_pause` (M0): the consent ledger, `consent_current_v` and the pause. The
  pause column is added `IF NOT EXISTS`, so M0 still applies after a transcription hotfix that added it
  first. The hotfix branch's own migration must not add the column or a second consent table; the two
  branches agree on M0 before either merges.
- `20261025090000_questionnaire_v2` (M1): additive, plus the dropped (questionnaireId, artisanSetKey)
  unique. Lineage and display codes are backfilled in it; `QuestionnaireResponse` rows become answers.
  While `QUESTIONNAIRE_V2` is off the legacy create and artisan-set edit keep the one-interview-per-set
  rule with an advisory lock on (questionnaire, set) instead of the unique.
- `backend/prisma/migrations_pending/20261102090000_questionnaire_v2_restrict` (M3): written, NOT in
  `prisma/migrations`, so a deploy cannot apply it. Rollout step 6 moves it in.
- M4 (cleanup) is not written.

## Tests that need Postgres

The v2 modules marked `needs_db` and the three script tests skip unless `QV2_TEST_DATABASE_URL` and
`QV2_BACKFILL_DATABASE_URL` name migrated throwaway databases. CI has no database by design
(`.github/workflows/checks.yml`), so they are run that way before every merge of this branch.

## How this document is kept true

The contract test fails when a route listed in `shared/questionnaire-contract.json` is not served or an
example stops validating; the docs check (`node docs/tools/check-docs.mjs`) fails when a path named here
stops existing. When a route is added or moved, change the contract file (with a `contractVersion`
bump), the table above and the test in one commit.
