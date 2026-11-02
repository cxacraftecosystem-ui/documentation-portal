# Transcript pipeline (Scribe v2 → Gemini 3.8 Flash)

Built 31 Oct 2026 from the approved design, `C:/dev/IIT/docs/transcription/DESIGN_2026-10-31_Scribe-Gemini-Pipeline.md`.
The backend is the same in both portals: shared files are listed in `shared/transcription.sha256`
and copied from designer-portal by its transcription sync script.

## Where things are

| Piece | File |
|---|---|
| API contract and UI strings (web and Android build on these) | `shared/transcript-contract.json`, `shared/strings/transcript.json` |
| Turns, key terms, Gemini transport, translation, renderers, citations, budget and status rules | `backend/app/transcription/transcript/` (pure; no FastAPI, no Prisma) |
| Database half | `backend/app/services/transcript_store.py` |
| Orchestration (queue step, view, edits, Ask, auto-draft, quote cards, glossary, budget) | `backend/app/services/transcript_pipeline.py` |
| Routes, `/api/transcripts/...` | `backend/app/api/routes/transcripts.py` |
| What only this portal knows (access, consent, context) | `backend/app/services/transcript_portal.py` (not shared) |
| Tables | migration `20261110090000_transcript_turns` |
| Backfill (dry run by default) | `backend/scripts/backfill_transcripts.py` |
| Old-transcript translation sweep (dry run by default; 0.0.9) | `backend/app/scripts/queue_pending_translations.py` |

## The queue step

1. **Transcribe.** The existing TRANSCRIPTION job. `transcription_jobs.transcribe_recording` asks
   Scribe v2 with the recording's own key terms (artisans, its craft, glossary terms for that craft,
   crafts of its state, the vocabulary file, the master list; at most 100, most specific first), then
   stores the words as a new `TranscriptVersion` of `TranscriptTurn`s.
2. **Translate + insights.** The existing TRANSCRIPT_INSIGHTS job. One Gemini pass (structured JSON,
   keyed by turn id) gives the English of every turn, the summary, the quotes and the craft terms.
   Turns already in English are marked "Already in English" and not sent. Fallback model:
   `gemini-3.7-flash`.

Consent is re-read immediately before each external send (both jobs, and every on-demand call:
re-translate a turn, Ask, auto-draft, quote card).

## Switches

- Everything is behind the existing AI switch (`AI_FEATURES_ENABLED` + `AI_TRANSCRIPT_INSIGHTS_ENABLED`);
  every `/api/transcripts` route answers 404 while it is off.
- Transcription stays paused (`AppSetting.transcriptionPaused`) until the coordinator un-pauses it.
- **Budget:** `AppSetting.transcriptMonthlyBudgetUsd` (default USD 100). At 80 % the admins get one
  notice a month; at 100 % `transcriptionPaused` is set and translation jobs wait too. Spend is
  `SttUsage` (Scribe, USD 0.22 an hour) plus `TranscriptSpend` (Gemini).

## Keys

`ELEVENLABS_API_KEY` (per portal, IP-locked to its server) and `GEMINI_API_KEY` reach the release
`.env` through the deploy pin in `.github/workflows/deploy-backend.yml`. After the switch, the deploy
asks ElevenLabs speech-to-text with no audio (refused by validation, never billed; the keys are
scoped to speech-to-text, so `GET /v1/models` answers 401) and Gemini `models.get gemini-3.8-flash` from the box and fails only
on an auth refusal, printing status codes only. CI never calls either provider: the tests use the
recorded responses in `backend/tests/fixtures/transcript/`.

## Backfill

```
cd backend && python scripts/backfill_transcripts.py                 # counts only
cd backend && python scripts/backfill_transcripts.py --apply --batch 25
```

Oldest first; a batch stops at `--batch` recordings or before its estimate passes what is left of
this month's budget. Recordings whose consent refuses are left out and counted.

## Old transcripts and declined translations (0.0.9, D38)

- **Old transcripts.** A recording transcribed before the turn pipeline gets its turns the first time
  it is opened or exported (`ensure_version`), and that same moment now queues its TRANSCRIPT_INSIGHTS
  job: never while the switch is off, consent refuses, a researcher edited the summary, or a job is
  already queued or running. A one-off sweep catches the rest (counts only; dry run by default):

  ```
  cd backend && python -m app.scripts.queue_pending_translations            # counts only
  cd backend && python -m app.scripts.queue_pending_translations --apply    # queue them
  ```

- **Declined.** When Gemini declines the pass (SAFETY, PROHIBITED_CONTENT and the like) the version is
  marked `DECLINED` and its untranslated turns read "Couldn't translate". The view's `englishNotice` is
  `DECLINED` for whoever may edit, with a way to type or paste the English
  (`POST /api/transcripts/{mediaId}/english`, or a turn's English edit); every other reader sees a
  neutral "English isn't available for this recording." Pasted paragraphs land on the turns when they
  line up; otherwise the text is kept whole (`manualEnglish`, a `TranscriptInsight` with provider
  `manual`) and every copy, download and data export carries it.

## How this document is kept true

| Claim class | Kept true by |
|---|---|
| The API shape and the copy formats | `backend/tests/test_transcript_contract.py` checks every route in `shared/transcript-contract.json` and reproduces its fixtures byte for byte. |
| The two portals run the same pipeline | `backend/tests/test_transcription_parity.py` against `shared/transcription.sha256`. |
| Turns, translation, citations, budget and status rules | `backend/tests/test_transcript_pipeline.py` (recorded responses) and `backend/tests/test_transcript_db.py` (a migrated Postgres in CI). |
