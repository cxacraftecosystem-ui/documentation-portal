# Data explorer: the server side (0.0.12)

The `/data` page and the Android data screen read the repository through one API, the same in both
portals. Its routes, fields and limits are written once, in
[shared/data-explorer-contract.json](../shared/data-explorer-contract.json), and the web and Android
clients build against that file. This page explains what each part does and why.

## What changed in 0.0.12

| Item | What the server does now |
|---|---|
| A1 | Removed recordings (`MediaFile.removedAt` set) never appear in the tree, a manifest, a zip, a report, a dataset, a CSV or a transcript download in any format |
| A2 | Folder listings load media without the three transcript columns, through a slim Prisma model (`backend/app/services/data_explorer/slim.py`), and mark "has a transcript" with one cheap query per folder |
| A3 | `GET /data/tree?slim=1` leaves transcript text out. A transcript is fetched when it is opened, from `GET /data/media/{media_id}/transcript` |
| A4 | One zip walk loads each workshop, its linked artisans and its design-workshop stages once (`walk.memo`) |
| A5 | The transcript list checks visibility with ids only and keeps filling a page until it is full |
| A6 | The JSON table preview cuts transcript cells to 2,000 characters. The `.xlsx` and `.csv` stay complete |
| A7 | "Has a transcript" is `"transcriptText" IS NOT NULL AND "transcriptText" <> ''`. Saved transcripts were confirmed trimmed and non-blank in both production databases on 4 Nov 2026 |
| A8 | The root report and the dataset export run their record lookups together, and the export sorts every list by creation time then id, so the 5,000-row cut is the same each time |
| A10 | Audio converts to `.mp4` by streaming through ffmpeg. AAC is copied without re-encoding, one conversion runs at a time, and every outcome is counted. A failed conversion sends the original under its real name (`X-Conversion: original`) unless the request says `fallback=none`. `format=original` always sends the stored file |
| A11 | nginx logs `$request_time` and `$upstream_response_time` for every request to `/var/log/nginx/response-times.log` (path only, never the query string) |
| A11 | Every API request's route template, method, status, duration and client (`web`, `android` or `api`) is written to `UsageEvent` in batches every 15 seconds and kept 30 days (`backend/app/services/usage_log.py`). No user id, path or query string is stored |
| A13 | A signed download link is reused for half its life, so a browser can cache a repeat play or download (`backend/app/services/data_explorer/signing.py`) |
| A14 | `backend/tests/test_data_explorer_db.py` fails if a folder listing, a zip walk or a stats call makes more statements as a folder grows, or if a 300-file manifest uses more than a fixed amount of memory |

## Routes

All routes sit under `/api/data` and need dataset-download access, like the rest of the page. Downloads, stats, the multi-select manifest and Browse by type include every review status (owner decision of 4 Nov 2026, W6 dropped).

| Route | Purpose |
|---|---|
| `GET /data/tree` | One folder, still filtered by `status` for the tree. New: `slim=1`, `cursor`, `limit`, and `total` / `nextCursor` in the answer. File entries carry `thumbnailPath`, `previewPath`, `downloadPath` and `transcriptPath` |
| `GET /data/stats`, `POST /data/stats` | File count and bytes, in total and per type, before a download |
| `GET /data/manifest`, `POST /data/manifest` | The files of a folder, or of several folders and files picked together. Each file carries `sizeBytes` and `typeToken`; the answer carries `totalBytes` and `byType` |
| `GET /data/media/{media_id}/download` | The file itself: a `307` to a signed link, or a streamed `.mp4` for audio |
| `GET /data/media/{media_id}/preview` | A signed inline link for video, audio, image and PDF |
| `GET /data/media/{media_id}/thumbnail` | A 256 or 512 pixel WebP, made once and kept in storage under `thumbnails/` |
| `GET /data/media/{media_id}/transcript` | One transcript, on demand |
| `GET /data/transcript-bundle` | Every transcript in a folder, each in every format, as one streamed zip |
| `GET /transcripts/{media_id}/download?format=zip` | Every format of one transcript in one zip |
| `GET /data/transcripts/languages` | The languages the transcripts this user may read are in, with counts |
| `GET /data/browse`, `GET /data/browse/{type}/{record_id}/media` | Browse by type, paged, searched on the server, with a `truncated` flag |
| `GET /data/report` | New: `q` searches every row of every sheet on the server, `sheet` picks one sheet, and `format=csv` streams one sheet |

## Access

- Every route reads the same scope as the tree: a record or file is reachable by id only when the
  tree would list it for that user, and removed files never are.
- `GET /data/media/{media_id}/transcript` asks the consent ledger first, for every recording, so a
  refused consent answers `404` even when the transcript exists only as pipeline turns.
- Previews and thumbnails compare the MIME type without its parameters, so `image/svg+xml;
  charset=utf-8` is never signed inline.
- Signed links are cached by object, file name, type and disposition, never by user; the redirect is
  `Cache-Control: private`.
- On Android, signing out cancels running downloads and clears the download list, the saved folder
  copies, the scratch folder and the PDF previews.

## Memory

The box is a t3.small with 1.9 GB. Nothing on these routes holds a whole archive, a whole folder of transcripts
or a whole recording in memory:

- zips are written by `zipstream.StreamingZip`, which hands each file's bytes on as soon as they are
  compressed, with zip64 for anything large;
- the folder transcript zip renders one transcript at a time and reads legacy transcript text one row
  at a time;
- the NDJSON manifest fills transcript text 200 entries at a time while it streams;
- audio is downloaded to a temporary file and converted by ffmpeg, which writes to the response as it
  goes;
- thumbnails are made one at a time and refuse sources over 64 MB or 50 megapixels.

## How this document is kept true

The routes table follows `shared/data-explorer-contract.json`, and
`backend/tests/test_data_explorer_contract.py` fails when a contract route is not mounted or a
contract limit differs from the server. Re-read this page whenever that file changes. The contract,
the `data_explorer` package and its tests are byte-identical in both portals through
`shared/transcription.sha256`.
