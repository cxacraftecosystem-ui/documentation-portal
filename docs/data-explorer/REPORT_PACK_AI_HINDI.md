# Report pack and Hindi report

## Report pack (extra 12)

`GET /api/data/report/pack` streams one zip: the workbook, a branded PDF summary, every transcript as `.docx` and `.srt`, and a `README.txt`. Same route in both portals (the Field Repository also takes `status`).

- **Request**: `path`, plus the `/data/report` options (`q`, `sheets`, `answers`, `rows`, `links`, `name`, `thumbnails`, `locale`, `extras`). `name` also names the zip.
- **Access**: as `/data/report` — dataset download access, the caller's scope, design-workshop data only for those who may export it, consent-withheld transcripts left out (the README says how many).
- **Errors** are JSON with a 4xx/5xx status, never a zip: 422 bad options, 404 no folder or no sheets, 503 with `Retry-After` when the report queue is full. Clients check the status and the zip signature, and never Range-resume a pack.
- **Contents**: the `.xlsx` from the report builder; `…Report-Summary….pdf` (ReportLab, brand `#762cb1`: cover with KPI tiles, charts, coverage table and section grid for workshop folders, up to 40 stored quotes, the pack contents) using the Noto Sans faces bundled in `backend/app/services/datareport/fonts/` (SIL OFL); `Transcripts/*.docx` (bilingual, timed) and `.srt` (original, plus English when translated; none for untimed transcripts); `README.txt` written last. At most 1000 transcripts.
- **Memory**: the workbook queues itself; the pack then holds one report-queue slot while it walks, counts and renders the PDF; the zip streams (`zipstream.StreamingZip`), one transcript at a time.

## Hindi report (extra 15)

Report options → Language. Fixed text only, never AI: sheet names, column headings, cover labels and KPI tiles, banners, status words, data dictionary headings and descriptions, and the print header and footer. Answers and transcripts stay in their original language and English. Strings live in `backend/app/services/datareport/i18n_en.json` (the canonical keys) and `i18n_hi.json`; `i18n.translator(locale)` returns `t(key, english, **values)`, which falls back to English. Lookup order: the exact key, the English text in the `text.*` lexicon, the generic `col.<column>` key, then `pattern.*` templates for generated descriptions. `tests/test_report_i18n.py` fails CI when a plugin's column, description or literal key has no Hindi. The PDF summary and the live page stay English.

## Report extras: Quotes, Glossary, Artisan profiles

Option keys: `quotes` (the Quotes sheet, `sheets/ai_quotes.py`), `glossary` (Glossary, `sheets/ai_glossary.py`) and `profiles` (one "Profile · Name" sheet per artisan, `sheets/ai_profiles.py`). Shared helpers live in `datareport/ai_cache.py`. Every read goes through `ctx.cache`, keyed by portal, data version and a sha of the in-scope ids; links are built after the cache, from `ctx.links`.

- **Quotes**: the stored `TranscriptInsight.quotes`, read through `transcript_export.load` (which carries the consent gate), original beside English; the Original / English / Both option drops one of them. Each quote is matched to the `TranscriptTurn` that holds it for its time and Play from link; an unmatched quote plays from the start. Recordings with a transcript but no insight are queued to the existing `TRANSCRIPT_INSIGHTS` job (never summarised inline), only when `transcript_insights.enabled()` and `transcript_portal.may_send` allow it, skipping recordings whose last job failed, at most 20 per data version; a banner counts them. At most 1,500 quotes.
- **Glossary**: `TranscriptGlossarySighting` joined to `TranscriptGlossaryTerm`, `Craft` and `TranscriptTurn`, for recordings in the report that consent does not withhold. No AI call. One row per sighting with English meaning, counts, recording, artisan, time and Play from. At most 5,000 rows.
- **Profiles**: one sheet per artisan, by name, at most 50 (the first sheet says when capped): photo (only with Thumbnails on), key facts (never Aadhaar), consent per purpose (or Not recorded), tools, products, processes, and up to 200 recordings with Open, Download and Transcript (.docx) links.
