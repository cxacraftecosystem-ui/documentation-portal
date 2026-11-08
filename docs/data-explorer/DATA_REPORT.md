# Data report (.xlsx)

The Download data > Report workbook is built by `backend/app/services/datareport/`, one package kept byte-identical in both portals (`scripts/sync_data_report.py`, `shared/data-report.sha256`, `backend/tests/test_data_report_parity.py`). Portal differences live in `datareport/portal.py` (name, open routes, FR's review-status filter) and in each portal's generated `schema_<portal>.json`.

## How a report is made

1. The API never builds. `GET /api/data/report?format=xlsx` and `GET /api/data/report/pack` call `datareport.jobs.fetch`, which writes a job record keyed by kind, user, `_report_key` (folder, scope, data version) and every option into the report store, then waits up to 55 s (non-blocking polls) for the queue worker to finish it and streams the stored file. If it is not ready the answer is the existing 503 with `Retry-After`, and the retry joins the same job. The worker's report lane (`jobs.run_lane`, one build at a time, beside the media drain) calls `datareport.build.build_report(user, path, options, status=None)` or `pack.response_parts`, writes the file, runs gc and `malloc_trim`, and sends due scheduled reports when no download waits. Details under "Report worker" below.
2. The cached report material (`data_browser._material`, one build per folder and data version) supplies the folder name and the legacy design-workshop stage sheets. Inside one report-queue slot the builder loads the folder's records (`data_browser._report_records`), withholds consent-refused transcripts, and adds file facts to every media row.
3. Every registered sheet plugin builds its `Sheet`s from a `ReportContext` (`contract.py`). Core plugins: one generic sheet per record type (`tables.py`: every column of the Prisma model, linked ids shown as names, the ids on the Provenance sheet), Answers, Transcripts, Media, Question bank and the design-workshop stage sheets. Extras are plugins turned on by Report options.
4. `writer.py` streams each sheet with XlsxWriter in constant-memory mode (rows are written in chunks of 2,000 off the event loop; strings go to the shared string table, so repeated values are stored once), then writes the Provenance sheet, the Overview cover (portal, folder, filters, who and when, data version, KPI tiles, contents, bulk link, native charts) and the Data dictionary. The report is always this one workbook: no companion workbook and no zip, for the web, Android, the report pack and scheduled e-mails alike.
5. The finished file is cached beside the material, keyed by every option (and by user when "What changed" is on). `ctx.delivered` hooks run when the file is actually sent.

## Report options

`sheets` (keys or names, legacy names map to the new sheets), `answers` original|english|both (drops translation rows or original rows of paired sheets, and columns tagged with a language), `rows` all|filtered with `q`, `links` 1|0 (drops link columns and bulk banners), `thumbnails` 1|0 (pictures capped at 150 images and 6 MB), `locale` en|hi, `extras` (comma list of plugin option keys), `name`. `GET /api/data/report/sheets?path=` lists the core sheets with counts (only the sheets the default report writes: a sheet with no rows is left out unless it is one of the always-written ones) and the optional extras, whose titles are the names of the sheets they add (Charts on the cover adds cover charts). Every option round-trips: a ticked sheet or extra is in the file, labelled as in the dialog, with every row its plugin made and every column on the sheet or, for technical columns, on the Provenance sheet; an unticked one is not. A ticked extra is written even when only some sheets are ticked. `backend/tests/test_report_options_matrix.py` proves it for every sheet and extra both portals offer, alone and unticked, all at once, for the switches, and through the worker's stored options.

## Every sheet

Every sheet, however large, is a real Excel Table (Everything, Transcripts and Provenance included; an empty sheet gets a table over its header and one note row; the hidden Chart data sheet holds one table per chart): brand-purple header, banded rows, header filter, frozen header row and first column, widths from the data capped at 60, date / date-time (IST) / mm:ss / file-size formats, status colours (green Approved, Saved, Ready, Translated; amber Pending, Translating; red Failed, Rejected, Withheld), a totals row for sizes and durations, data bars and sparklines when a sheet asks, landscape A4 print fitted to width with the header row repeated and portal name and page N of M in the header and footer. Fonts are Aptos (family Swiss, so Excel falls back to Calibri).

So that Excel opens and scrolls a workbook at once:

- every row has a fixed height (`customHeight`, 16 pt; thumbnail rows 38 pt), so Excel never measures rows;
- no data cell wraps (only the header row and the cover's tile labels do);
- no cell holds more than 1,000 characters: longer text is cut and ends with "… (full text: link)", and the cell links to the full text, the recording's bilingual transcript (.docx) for transcript text and the record in the portal for anything else. When a sheet has no link budget left the address is written in the cell instead.

## Provenance

Record sheets carry the record's own ID (visible, so a row can be found again) and no technical columns. Every other identifier, key, checksum, created/updated/deleted stamp and author, revision, model and structured JSON (`fieldProvenance`, `extraMetadata`) goes to one "Provenance" sheet, a Table of Sheet, Record ID, Field and Value, one row per non-empty value. The Data dictionary lists each of those fields as "Field (Sheet)" under Provenance.

## Transcripts

Per recording: a whole-transcript row and its English row, then one row per speaker turn (one transcript turn each, as in 0.0.14) in the original with its English translation row directly beneath (turn number, start and end as m:ss text, speaker, language, text, status, Record ID). Every turn cell starts with the speaker's name in bold rich text, then the words, in the original and in English alike; a turn without English says why in its status (Translating N%, Failed with the reason, Not requested). The whole-transcript cells preview the dialogue on one line (bold names, time, words) and, once past 1,000 characters, end with "… (full text: link)" to the bilingual .docx. The original whole-transcript row carries the player link, "Open recording", and the subtitles (.srt) link; the English row carries the .docx link. Record ID is the turn's ID on turn rows and the recording's on the whole-transcript rows; turn revisions above 1, corrections, stamps, the version and the translation model are on the Provenance sheet under it. Answers recorded as audio follow the original-then-English pattern on the Answers sheet.

## Workbook budgets

A workbook must open in desktop Excel in seconds, so the builder keeps these limits (`writer.py`, `build.py`):

- At most one set of links per recording or record, never per transcript turn, plus the full-text link of a cut cell; the Everything sheet has no per-row links (its rows name the record id, the record sheets carry the link).
- At most 5,000 hyperlinks per sheet and 20,000 per workbook (400 kept back for the contents and back links). A sheet that would pass either cap gets a "(web address)" text column after each link column and a one-line banner instead of live links.
- No formulas in data rows: every value is computed when the file is built. The only formulas are `SUBTOTAL` totals.
- Conditional formatting is one range rule per column; styles are shared formats.
- Fixed row heights, no wrapping, 1,000 characters per cell, widths capped at 60.
- At most 64 MB of uncompressed XML for the production-sized fixture.

`backend/tests/test_report_budget.py` asserts each limit; its production-sized case (420 recordings, 20,580 turns, so 41,160 turn rows, and 34,000+ Everything rows) runs in the Checks job "Report workbook budget" with `REPORT_PRODUCTION=1`. It checks that the report is one file, that every sheet is a Table whose range openpyxl reads to the last row, that every data row has a fixed height and no data cell wraps, that no cell passes 1,000 characters and long ones link to the full text, that record sheets hold no provenance column and the Provenance sheet exists, that every turn row starts with a bold speaker and has its translation beneath, and that the row counts are at least 0.0.14's (19,716 turn rows, 33,772 Everything rows). It times the build, an openpyxl full load, a read-only pass to the last Transcripts row and `soffice --headless --convert-to csv`, builds the same fixture with the builder on main for comparison, and uploads PNG renders of sample sheets.

## Links

All links are portal web URLs that need sign-in and are permission-checked when opened; none is a signed storage link, so they never expire inside the file.

| Link | Web route | API the page calls |
| --- | --- | --- |
| Download | `/data/go/media/<id>/download` | `GET /api/data/go/media/<id>/download?as=json` → `{url, fileName, mediaId, mediaType}` (without `as=json`: 307 to a fresh signed URL) |
| Open in portal, Open recording | `/data?preview=<id>&t=<seconds>` | the explorer's own state |
| Open this record | `/data/go/open/<kind>/<id>` | `GET /api/data/go/open/<kind>/<id>` → `{kind, id, href}` |
| Transcript .docx / .srt | `/data/go/transcript/<id>/<docx\|srt>` | `GET /api/data/go/transcript/<id>/<fmt>` → `{apiPath, openHref}` |
| Section of a sitting | `/questionnaire/sessions/<id>/view?section=<code>` | |
| Download all N files (sheet banner, Overview) | `/data?bundle=<id>` | `GET /api/data/bundles/<id>` → `{fileCount, bytes, mediaIds, manifestPath, chunk}`; `GET /api/data/bundles/<id>/manifest?stream=1` (NDJSON, same lines as `/data/manifest`) |

Bundles (`DataBundle`) are saved once per owner and file set; any dataset downloader can open one and gets only the files they may see.

## Correctness

`backend/tests/test_report_xlsx.py` checks every package part with a strict structural validator (content types, relationships, unique table names and headings, header cells equal to table columns, ordered rows and cells), round-trips through openpyxl, converts a 20,000-row workbook with headless LibreOffice on CI, and writes 100,000 rows under 160 MB of Python memory. `backend/tests/test_report_coverage.py` fails when any Prisma field is neither exported nor on the reviewed exclusion list, when the schema snapshot or `XLSX_AUDIT.md` is stale, or when a questionnaire definition file is not in the Question bank.

## Keeping it complete

After a schema change run `python scripts/sync_data_report.py --schema` (designer-portal: `--to ../documentation-portal`). New fields of exported models appear automatically; a new model must be added to `tables.py` or to `exclusions.MODELS` with a reason, and secret-looking fields must be excluded or reviewed into `SENSITIVE_ALLOWED`.

## Report worker

- Store: `REPORT_STORE_DIR`, else `~/.cache/data-report-store` of the `ubuntu` user that runs both the API and `fieldrepo-queue`, so they share it on one box. Per job: `<id>.json` (state), `<id>.bin` (file), `<id>.seen` (last poll). The id is a SHA-256 over kind, user id, `_report_key` and options, so a user can only reach their own jobs, and `wait_for` also checks the stored user id.
- Identical requests share one job. A finished file is reused for 15 minutes (the report cache TTL); the key already changes with the data version. Files older than 24 hours are swept.
- Limits: at most 8 queued or running jobs (503 with `Retry-After` past that), a queued job nobody polled for 5 minutes is dropped, a build past 15 minutes fails with 504, and a build cut off by a worker restart fails. A failed job is reported to every request already waiting on it and cleared by the next new request, which starts a fresh one.
- Why the client contract stayed: CloudFront's origin response timeout is 120 s on both distributions, Android's report client reads for 150 s and the web proxy for 300 s; real builds take 23 to 34 s. A 55 s wait fits under all of them, and both clients already retry the 503 with its `Retry-After`.
