# Data report (.xlsx)

The Download data > Report workbook is built by `backend/app/services/datareport/`, one package kept byte-identical in both portals (`scripts/sync_data_report.py`, `shared/data-report.sha256`, `backend/tests/test_data_report_parity.py`). Portal differences live in `datareport/portal.py` (name, open routes, FR's review-status filter) and in each portal's generated `schema_<portal>.json`.

## How a report is made

1. `GET /api/data/report?format=xlsx` (and the pack, schedules and live link) call `datareport.build.build_report(user, path, options, status=None)`.
2. The cached report material (`data_browser._material`, one build per folder and data version) supplies the folder name and the legacy design-workshop stage sheets. Inside one report-queue slot the builder loads the folder's records (`data_browser._report_records`), withholds consent-refused transcripts, and adds file facts to every media row.
3. Every registered sheet plugin builds its `Sheet`s from a `ReportContext` (`contract.py`). Core plugins: one generic sheet per record type (`tables.py`: every column of the Prisma model, linked ids shown as names with the id grouped and hidden), Answers, Transcripts, Media, Question bank and the design-workshop stage sheets. Extras are plugins turned on by Report options.
4. `writer.py` streams each sheet with XlsxWriter in constant-memory mode (rows are written in chunks of 2,000 off the event loop; strings go to the shared string table, so repeated values are stored once), then writes the Overview cover (portal, folder, filters, who and when, data version, KPI tiles, contents, bulk link, native charts) and the Data dictionary.
5. The finished file is cached beside the material, keyed by every option (and by user when "What changed" is on). `ctx.delivered` hooks run when the file is actually sent.

## Report options

`sheets` (keys or names, legacy names map to the new sheets), `answers` original|english|both (drops translation rows or original rows of paired sheets, and columns tagged with a language), `rows` all|filtered with `q`, `links` 1|0 (drops link columns and bulk banners), `thumbnails` 1|0 (pictures capped at 150 images and 6 MB), `locale` en|hi, `extras` (comma list of plugin option keys), `name`. `GET /api/data/report/sheets?path=` lists the core sheets with counts and the optional extras.

## Every sheet

A real Excel Table while the sheet is under 10,000 rows and 8 MB of XML, otherwise a plain autofilter range with the same header and a `SUBTOTAL` totals row: brand-purple header, banded rows, frozen header and first column, autofilter, widths from the data, wrapped long text only, date / date-time (IST) / mm:ss / file-size formats, status colours (green Approved, Saved, Ready, Translated; amber Pending, Translating; red Failed, Rejected, Withheld), a totals row for sizes and durations, technical and provenance columns (ids, keys, source, origin, checksums, created/updated by and at, revision, model, structured JSON) moved to the right in one collapsed outline group that one click opens, data bars and sparklines when a sheet asks, landscape A4 print fitted to width with the header row repeated and portal name and page N of M in the header and footer. Fonts are Aptos (family Swiss, so Excel falls back to Calibri).

## Transcripts

Per recording: a whole-transcript row and its English row, then one row per speaker turn (consecutive segments by one speaker make one turn) in the original followed by its translation row (turn, start and end as m:ss text, speaker, language, text). The whole-transcript cell reads as dialogue: one line per turn, the speaker in bold rich text, then the time and the words; the English cell mirrors it turn for turn with the same bold names, and a turn without English says why (Translating N%, Failed with the reason, Not requested). A transcript longer than one cell (32,767 characters) continues on labelled rows, "Whole transcript (part 2 of 3)", each part paired with its English part. Turn rows use the same style: the speaker in bold, then the words. The original whole-transcript row carries the only player link, "Open recording", which plays from the start, and the subtitles (.srt) link; the English row carries the bilingual .docx link. Answers recorded as audio follow the original-then-English pattern on the Answers sheet.

## Workbook budgets

A workbook must open in desktop Excel in seconds, so the builder keeps these limits (`writer.py`, `build.py`):

- At most one set of links per recording or record, never per transcript segment; the Everything sheet has no per-row links (its rows name the record id, the record sheets carry the link).
- At most 5,000 hyperlinks per sheet and 20,000 per workbook (400 kept back for the contents and back links). A sheet that would pass either cap gets a "(web address)" text column after each link column and a one-line banner instead of live links.
- No formulas in data rows: every value is computed when the file is built. The only formulas are `SUBTOTAL` totals.
- Conditional formatting is one range rule per column; styles are shared formats.
- About 40 MB of uncompressed XML per workbook. When the estimated turn rows (from one `count`/`octet_length` query over the folder's current transcript versions) pass 8 MB, the report splits: the main workbook keeps every sheet and, on Transcripts, the whole-transcript original and English rows with their links; every turn row goes to a companion workbook, `<name>_Report-Transcripts.xlsx`, with its own Overview and Data dictionary. The web download asks for `parts=zip` and gets one zip with both workbooks; the report pack always adds the companion beside the main workbook; clients that do not ask (Android, scheduled e-mails) get the main workbook, whose Transcripts banner names the companion.

`backend/tests/test_report_budget.py` asserts each limit; its production-sized case (420 recordings, 20,580 segments, 34,000+ Everything rows) runs in the Checks job "Report workbook budget" with `REPORT_PRODUCTION=1`, times an openpyxl full load and `soffice --headless --convert-to csv`, builds the same fixture with the builder on main for comparison, and uploads PNG renders of sample sheets.

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
