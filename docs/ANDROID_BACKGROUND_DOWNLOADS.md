# Android data browser and background downloads

The Android **Data** screen in `com.fieldrepository.app` (Field Repository). It is the same Kotlin as
the Design Workshop app: the shared tree is `android/app/src/{main,test,androidTest}/java/<pkg>/explorer/`,
kept byte-identical in both repositories (package name aside) and pinned by
`shared/android-explorer.sha256`. Only `explorer/portal/ExplorerPortal.kt` differs per app: the product name,
the download folder (`Downloads/FieldRepository/`), the web origin and a 150 s read timeout for reports that
wait in the server's build queue.

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
still fails is tried once more after every other file (the screen says *1 file failed and will retry at the
end*); only a file that fails that too is left out of the zip and listed. A report or transcript bundle is
built fresh by the server each time, so it is never resumed with `Range`: it starts again from byte 0, a busy
reply (`503`/`429`) waits for `Retry-After` up to five times, and the body must start with the zip signature
(`PK`) before it is saved, so a JSON or HTML error never lands in Downloads as a `.xlsx`. Up to four files are fetched at once; a file of 64 MB or more
is fetched on its own. Audio asked for as `.mp4` comes back either converted or as the original, which is
saved under its real name (`X-Conversion: original`, or its first bytes on an older server). A cancelled or
failed download deletes its scratch folder and its pending entry in Downloads; a sweep on every visit
removes anything a killed process left behind.

Progress is reported per job: bytes and percent, files *N of M*, speed over the last five seconds,
time left from that speed (or the average when there is no speed yet), the current file with its own bar,
and the six most recent files with their state (saving, saved, retrying, failed). Background jobs can be
paused and resumed; a paused single file keeps its partial bytes and resumes with `Range`, a paused zip
starts again. The progress notification carries *Title · 62%*, the progress line, the current file and
speed, and *Pause*, *Cancel* and *Open*; a finished one says *Saved <title>* with the file count and size and
*Open* and *Share*; a failed one names the file and offers *Retry* and *Details*. Tapping any of them opens
the Downloads sheet on the Data screen (`ExplorerNotifications.EXTRA_OPEN_DOWNLOADS`, read by
`ExplorerDownloadsTap` in `MainActivity`).

Before a download over 500 MB, on mobile data, or without room, a sheet shows the size and time at the speed
last seen on this kind of network, the free space after, the save location and a smaller download without
videos (`ExplorerPreflight`); *Download on Wi-Fi* sets the WorkManager `UNMETERED` constraint.

Audio previews play through `ExplorerAudioService`, a Media3 `MediaSessionService` of type `mediaPlayback`:
playback continues while browsing and with the screen off, headphone and lock-screen buttons work, and the
notification shows the line being spoken.

## 3. Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | run a long download as a foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | the foreground service type for a user-started transfer (Android 14 and later) |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | audio previews that keep playing in the background, with lock-screen controls |
| `POST_NOTIFICATIONS` | progress and finished notifications; asked for when the first download starts; downloads run without it |
| `ACCESS_NETWORK_STATE` | the mobile-data warning, *Wait for Wi-Fi*, and the offline state |
| `INTERNET` | unchanged |

`DownloadManager` and saving to Downloads through `MediaStore` need no permission on Android 10 and later.

## 4. Play Console declaration

Play Console, *App content*, *Foreground service permissions*: tick **Data sync** and paste the text below.
Also tick **Media playback**: the user starts an audio recording from the Data screen and it keeps playing,
with a media notification, while they browse or lock the phone.

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
| `GET /data/report?format=xlsx&path` | the report; requests queue on the server, and `503` with `Retry-After` waits and retries up to five times; never `Range`-resumed |
| `GET /search/unified` | the data screen's search, *This app* (`scope=local`) or *Both portals* (`scope=all`), one type at a time with `type` and `page`; a `404` falls back to `GET /search` |

## 6. Tests

| Test | Covers |
|---|---|
| `explorer/ExplorerEngineTest` | zip contents, three tries, the retry at the end and a failed-file list, `Range` resume, a report never resumed and never saved from an error body, audio fallback names, space check, cancel clean-up, selection, busy retry, a 20,000-row manifest within 64 MB of heap |
| `explorer/ExplorerProgressTest` | speed, time left, the progress line, recent files, pause rules, the current file's bar, notification text, zip-signature checks |
| `explorer/ExplorerPreflightTest` | when the sheet shows, free space after, the smaller download, remembered speed, the spoken line and player clock |
| `ui/DataSearchModelTest` | type pills and counts, Play from, other-portal results, one result for an artisan in both portals, legacy fallback |
| `explorer/ExplorerStateTest` | paging, one transcript fetch per open, memory and offline copies, landing, selection, locate, restricted |
| `explorer/ExplorerPartsTest` | cache file names, sizes, busy countdown, file names from `Content-Disposition`, job list |
| `explorer/ExplorerScreenUiTest` (emulator) | layout, sizes, multi-select, thumbnails, previews, all-formats zip, transcript on demand, paging |
| `explorer/ExplorerBackgroundDownloadTest` (emulator) | a zip continues after Home, a file continues after `moveTaskToBack`, Cancel leaves nothing behind |

## How this document is kept true

The screen is `explorer/ui/ExplorerScreen.kt`, the download engine `explorer/data/ExplorerEngine.kt`, the
background runner `explorer/data/ExplorerWorker.kt`, and the permissions `android/app/src/main/AndroidManifest.xml`.
`SharedExplorerTreeTest` fails when either app's copy drifts, and the tests in section 6 pin each behaviour
above. Change the declaration text here and in the Play Console together.
