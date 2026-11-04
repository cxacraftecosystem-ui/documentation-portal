# Android data browser and background downloads

The Android **Data** screen in `com.fieldrepository.app` (Field Repository). It is the same Kotlin as
the Design Workshop app: the shared tree is `android/app/src/{main,test,androidTest}/java/<pkg>/explorer/`,
copied from designer-portal by its explorer sync script and pinned by
`shared/android-explorer.sha256`. Only `explorer/portal/ExplorerPortal.kt` differs per app.

## Contents

| | |
|---|---|
| [1. The screen](#1-the-screen) | tree first, search as its toolbar, one place for downloads |
| [2. How a download runs](#2-how-a-download-runs) | WorkManager for zips, DownloadManager for single files |
| [3. Permissions](#3-permissions) | what the manifest asks for and why |
| [4. Play Console declaration](#4-play-console-declaration) | the exact text to paste |
| [5. Server contract](#5-server-contract) | the routes the app calls |
| [6. Tests](#6-tests) | unit and emulator |
| [How this document is kept true](#how-this-document-is-kept-true) | |

## 1. The screen

| Part | What it does |
|---|---|
| Toolbar | record search, then *Organise by* chips and the review-status chips |
| Tree | folders first, then files with a 256 px thumbnail, type and size; *Load more* pages a long folder |
| File row | tap opens the recording page; *Preview* plays video and audio, shows images and renders PDFs; *Transcript* loads that one transcript on demand; *Save*; *All formats* zips every transcript format |
| Long press | starts selection; folders and files from several folders download as one zip |
| Download button | one sheet: *Everything*, *Transcripts, all formats* or *Report (.xlsx)*, with file count and size per type, a mobile-data warning and a free-space check |
| Downloads | the top-bar icon: progress in bytes with time left, Cancel, Retry, Open, Share and the list of files that failed |
| Offline | the saved copy of each folder shows at once with an *Offline copy* note, and download buttons are disabled |

Folder downloads include everything by default and every review status; the status chips change only
what the tree lists.

## 2. How a download runs

| Kind | Runs in | Survives |
|---|---|---|
| Folder, selection, report, transcript zips | `ExplorerWorker`, a WorkManager worker promoted to a `dataSync` foreground service with a progress notification and a Cancel action | leaving the screen, switching apps, locking the phone, a custom tab or another activity on top, process death (WorkManager reschedules) |
| One media file | the system `DownloadManager`, given the signed storage URL the API redirects to | the same, and app updates |
| One media file whose API does not redirect | `ExplorerWorker` as above | the same |

Each file is fetched up to three times and resumed with `Range` from the byte it stopped at. A file that
still fails is left out of the zip and listed. Up to four files are fetched at once; a file of 64 MB or more
is fetched on its own. Audio asked for as `.mp4` comes back either converted or as the original, which is
saved under its real name (`X-Conversion: original`, or its first bytes on an older server). A cancelled or
failed download deletes its scratch folder and its pending entry in Downloads; a sweep on every visit
removes anything a killed process left behind.

## 3. Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | run a long download as a foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | the foreground service type for a user-started transfer (Android 14 and later) |
| `POST_NOTIFICATIONS` | progress and finished notifications; asked for when the first download starts; downloads run without it |
| `ACCESS_NETWORK_STATE` | the mobile-data warning, *Wait for Wi-Fi*, and the offline state |
| `INTERNET` | unchanged |

`DownloadManager` and saving to Downloads through `MediaStore` need no permission on Android 10 and later.

## 4. Play Console declaration

Play Console, *App content*, *Foreground service permissions*: tick **Data sync** and paste the text below.

> **Task:** Download research data the user asked for.
>
> **Description:** When a signed-in researcher taps *Download* on the Data screen, the app saves a zip of the
> chosen folder or records (photos, videos, audio, transcripts and a spreadsheet report) to the phone's
> Downloads folder. Archives can be several gigabytes, so the download runs as a foreground service of type
> `dataSync` with a visible progress notification and a Cancel button, and it keeps going when the user
> switches app or locks the screen. The service starts only from that tap, stops when the file is saved or
> cancelled, and never runs on its own.
>
> **User impact if deferred or interrupted:** The archive would be left incomplete, and the researcher would
> have to start a multi-gigabyte download again from the beginning.

Attach a screen recording that shows: opening *Data*, tapping *Download*, *Start download*, the progress
notification with Cancel, pressing Home, and the finished notification with Open.

## 5. Server contract

`shared/data-explorer-contract.json` is the source. The app calls:

| Route | Use |
|---|---|
| `GET /data/tree?path&slim=1&cursor&status` | one page of a folder |
| `GET /data/media/{id}/transcript` | one transcript, when opened |
| `GET /data/stats`, `POST /data/stats` | count and size before a download |
| `GET /data/manifest?stream=1`, `POST /data/manifest` | the file list of a folder or a selection, streamed |
| `GET /data/media/{id}/download?format=mp4&fallback=original` | audio conversion with a fallback |
| `GET /data/media/{id}/thumbnail?size=256` and `/preview` | listing thumbnails and previews |
| `GET /transcripts/{id}/download?format=zip` | one transcript, every format |
| `GET /data/transcript-bundle?path` | every transcript of a folder, every format |
| `GET /data/transcripts/languages` | the transcript language filter |
| `GET /data/report?format=xlsx&path` | the report; `503` with `Retry-After` shows *Busy, retrying in 60 s* and retries once |

## 6. Tests

| Test | Covers |
|---|---|
| `explorer/ExplorerEngineTest` | zip contents, three tries and a failed-file list, `Range` resume, audio fallback names, space check, cancel clean-up, selection, busy retry, a 20,000-row manifest within 64 MB of heap |
| `explorer/ExplorerStateTest` | paging, one transcript fetch per open, memory and offline copies, landing, selection, locate, restricted |
| `explorer/ExplorerPartsTest` | cache file names, sizes, busy countdown, file names from `Content-Disposition`, job list |
| `explorer/ExplorerScreenUiTest` (emulator) | layout, sizes, multi-select, thumbnails, previews, all-formats zip, transcript on demand, paging |
| `explorer/ExplorerBackgroundDownloadTest` (emulator) | a zip continues after Home, a file continues after `moveTaskToBack`, Cancel leaves nothing behind |

## How this document is kept true

The screen is `explorer/ui/ExplorerScreen.kt`, the download engine `explorer/data/ExplorerEngine.kt`, the
background runner `explorer/data/ExplorerWorker.kt`, and the permissions `android/app/src/main/AndroidManifest.xml`.
`SharedExplorerTreeTest` fails when either app's copy drifts, and the tests in section 6 pin each behaviour
above. Change the declaration text here and in the Play Console together.
