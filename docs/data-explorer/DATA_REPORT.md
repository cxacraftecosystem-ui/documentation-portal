# Data report (.xlsx)

The Download data > Report workbook is built by `backend/app/services/datareport/`, one package kept byte-identical in both portals (`scripts/sync_data_report.py`, `shared/data-report.sha256`, `backend/tests/test_data_report_parity.py`). Portal differences live in `datareport/portal.py` (name, open routes, FR's review-status filter) and in each portal's generated `schema_<portal>.json`.

## How a report is made

1. `GET /api/data/report?format=xlsx` (and the pack, schedules and live link) call `datareport.build.build_report(user, path, options, status=None)`.
2. The cached report material (`data_browser._material`, one build per folder and data version) supplies the folder name and the legacy design-workshop stage sheets. Inside one report-queue slot the builder loads the folder's records (`data_browser._report_records`), withholds consent-refused transcripts, and adds file facts to every media row.
3. Every registered sheet plugin builds its `Sheet`s from a `ReportContext` (`contract.py`). Core plugins: one generic sheet per record type (`tables.py`: every column of the Prisma model, linked ids shown as names with the id grouped and hidden), Answers, Transcripts, Media, Question bank and the design-workshop stage sheets. Extras are plugins turned on by Report options.
4. `writer.py` streams each sheet with XlsxWriter in constant-memory mode (rows are written in chunks of 2,000 off the event loop), then writes the Overview cover (portal, folder, filters, who and when, data version, KPI tiles, contents, bulk link, native charts) and the Data dictionary.
5. The finished file is cached beside the material, keyed by every option (and by user when "What changed" is on). `ctx.delivered` hooks run when the file is actually sent.

## Report options

`sheets` (keys or names, legacy names map to the new sheets), `answers` original|english|both (drops translation rows or original rows of paired sheets, and columns tagged with a language), `rows` all|filtered with `q`, `links` 1|0 (drops link columns and bulk banners), `thumbnails` 1|0 (pictures capped at 150 images and 6 MB), `locale` en|hi, `extras` (comma list of plugin option keys), `name`. `GET /api/data/report/sheets?path=` lists the core sheets with counts and the optional extras.

## Every sheet

A real Excel Table: brand-purple header, banded rows, frozen header and first column, autofilter, widths from the data, wrapped long text, date / date-time (IST) / mm:ss / file-size formats, status colours (green Approved, Saved, Ready, Translated; amber Pending, Translating; red Failed, Rejected, Withheld), a totals row for sizes and durations, hidden grouped id columns, data bars and sparklines when a sheet asks, landscape A4 print fitted to width with the header row repeated and portal name and page N of M in the header and footer. Fonts are Aptos (family Swiss, so Excel falls back to Calibri).

## Transcripts

Per recording: a whole-transcript row and its English row, then every segment's original row followed by its translation row (start, end, speaker, language, text, Play from link). A translation row is always there; without English it says Translating N% (from the turn progress), Failed with the reason, Already English or Not requested. Answers recorded as audio follow the same pattern on the Answers sheet.

## Links

All links are portal web URLs that need sign-in and are permission-checked when opened; none is a signed storage link, so they never expire inside the file.

| Link | Web route | API the page calls |
| --- | --- | --- |
| Download | `/data/go/media/<id>/download` | `GET /api/data/go/media/<id>/download?as=json` → `{url, fileName, mediaId, mediaType}` (without `as=json`: 307 to a fresh signed URL) |
| Open in portal, Play from | `/data?preview=<id>&t=<seconds>` | the explorer's own state |
| Open this record | `/data/go/open/<kind>/<id>` | `GET /api/data/go/open/<kind>/<id>` → `{kind, id, href}` |
| Transcript .docx / .srt | `/data/go/transcript/<id>/<docx\|srt>` | `GET /api/data/go/transcript/<id>/<fmt>` → `{apiPath, openHref}` |
| Section of a sitting | `/questionnaire/sessions/<id>/view?section=<code>` | |
| Download all N files (sheet banner, Overview) | `/data?bundle=<id>` | `GET /api/data/bundles/<id>` → `{fileCount, bytes, mediaIds, manifestPath, chunk}`; `GET /api/data/bundles/<id>/manifest?stream=1` (NDJSON, same lines as `/data/manifest`) |

Bundles (`DataBundle`) are saved once per owner and file set; any dataset downloader can open one and gets only the files they may see.

## Correctness

`backend/tests/test_report_xlsx.py` checks every package part with a strict structural validator (content types, relationships, unique table names and headings, header cells equal to table columns, ordered rows and cells), round-trips through openpyxl, converts a 20,000-row workbook with headless LibreOffice on CI, and writes 100,000 rows under 160 MB of Python memory. `backend/tests/test_report_coverage.py` fails when any Prisma field is neither exported nor on the reviewed exclusion list, when the schema snapshot or `XLSX_AUDIT.md` is stale, or when a questionnaire definition file is not in the Question bank.

## Keeping it complete

After a schema change run `python scripts/sync_data_report.py --schema` (designer-portal: `--to ../documentation-portal`). New fields of exported models appear automatically; a new model must be added to `tables.py` or to `exclusions.MODELS` with a reason, and secret-looking fields must be excluded or reviewed into `SENSITIVE_ALLOWED`.
