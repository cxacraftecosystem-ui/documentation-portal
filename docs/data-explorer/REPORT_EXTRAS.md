# Report extras: charts, coverage, sparklines, map, what changed, both portals, audit, everything

Eight optional parts of the data report (.xlsx), in both portals. Each is a sheet plugin in `backend/app/services/datareport/sheets/extras_*.py`, registered against `contract.py`, and switched on by its `ReportOptions.extras` key. Shared helpers are in `extras_common.py`.

| Extra | Option | Plugin | Sheets |
|---|---|---|---|
| 1 Cover charts | `charts` | `extras_charts` | none (four charts on the cover) |
| 2 Coverage | `coverage` | `extras_coverage` | `coverage` |
| 3 Sparklines and data bars | `sparklines` | `extras_sparklines` | `completeness` |
| 4 Map | `map` | `extras_map` | `mapDistricts`, `map` |
| 8 What changed | `changes` | `extras_changes` | `changes` |
| 9 Both portals | `peer` | `extras_peer` | `bothPortals` |
| 10 Audit and trust | `audit` | `extras_audit` | `audit`, `auditTrail` |
| 11 Everything | `everything` | `extras_everything` | `everything` |

Every plugin has `catalog()` (its sheets and columns, without a database) for the i18n and field-coverage tests, and each column's `source` names the `Model.field` values it shows.

- **Data**: rows come from `ctx.records(kind)`, which honours "only what's filtered". File facts (checksum, transcript status, length, location) come from the media rows in the core material; expensive results go through `ctx.cache`, keyed by a sha of the ids in scope.
- **Cover charts**: `extras_charts.charts_for(ctx)` returns files by type, recordings per day (per week once the span reaches 62 days), the share of artisans complete per questionnaire section, and transcripts Ready / Translating / Failed / Not transcribed. The shareable live page calls the same function, so both show the same numbers.
- **Coverage and completeness**: reuse `data_explorer/overview.py`. A question counts as answered from `QuestionnaireResponse.answerText` (v1) or the current `QuestionnaireAnswerRevision.text` (v2). Cells read Complete / Partial / Missing and link to the newest sitting that answered that section; least complete first. Completeness adds a sparkline and data bars for share complete, size and talk time.
- **Map**: every artisan, workshop, product, tool, sitting and file with a location or stated place, each linking to Google Maps; `mapDistricts` counts places per district.
- **What changed**: the last report time per user and folder is a `DataFolderVisit` row with the path `report:<path>`; it moves only when a file is actually delivered. Lists records added or changed, review decisions, and transcripts made or translated since then; a first report lists everything.
- **Both portals**: for linked artisans (at most 40 per report), one signed peer search each, keeping only that artisan's items; consent applies on both sides. When cross-portal search is off, not permitted or unreachable, the sheet is empty with a note saying why.
- **Audit and trust**: who recorded, reviewed and approved each record and when, the edit count and last editor, and the SHA-256 of every file; `auditTrail` lists every review decision and edit, with secret fields removed from edit diffs.
- **Everything**: one long table with one row per value (record type, id, name, section, field, source, value, and the value as a number or date), including questionnaire answers and process steps. Aadhaar data, password hashes, join codes, storage keys and tokens are left out. Rows stream.
