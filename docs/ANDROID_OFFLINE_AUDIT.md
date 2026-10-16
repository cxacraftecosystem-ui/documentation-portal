# Android offline audit

**Date:** 2026-10-16. **Branch:** `feature/android-offline-review`, based on `integrate/2fa-nav-downloads`.

**The rule under review:** on every screen, anything a user enters or captures is kept safely on the
device until there is internet, and then it syncs. Nothing may be lost across app kills, reboots,
sign-out and sign-in, or app updates. Read-only screens show the last data they loaded instead of
failing.

This document lists every screen of the Android app, what it writes, where pending data lives, and
how and when it syncs. Each row is classified as it was **before** this review (OK, GAP or RISK),
with the fix or the deferral next to it. Deferred items are recorded in `docs/OPEN_FINDINGS.md`
(F28 to F31).

## Files cited

Line citations below use the short file name. The full paths are:

- `android/app/src/main/java/com/fieldrepository/app/MainActivity.kt`
- `android/app/src/main/java/com/fieldrepository/app/data/Offline.kt`
- `android/app/src/main/java/com/fieldrepository/app/data/FieldRepository.kt`
- `android/app/src/main/java/com/fieldrepository/app/data/ApiClient.kt`
- `android/app/src/main/java/com/fieldrepository/app/data/OfflineReadCache.kt` (new)
- `android/app/src/main/java/com/fieldrepository/app/data/OutboxSyncWorker.kt` (new)
- `android/app/src/main/java/com/fieldrepository/app/data/AtomicFiles.kt` (new)
- `android/app/src/main/java/com/fieldrepository/app/data/StagedJournal.kt`
- `android/app/src/main/java/com/fieldrepository/app/ui/MediaDownloads.kt`
- `android/app/src/main/java/com/fieldrepository/app/ui/AppearanceScreen.kt`

## Summary

| | Before | After this change |
|---|---|---|
| OK | 10 | 34 |
| GAP | 18 | 3 (deferred: F28, F29, and offline media previews) |
| RISK | 11 | 2 (deferred: F30, F31) |

There are 39 rows. 24 were fixed in this change. One of those is the administration row, which is
now OK by decision: its reads are cached and its writes stay online-only (see below). One of the
three remaining GAPs, media previews, is a limitation of signed media URLs rather than missing code.
It is described in its row and is not a finding.

## How offline storage works now

**Writes go to the outbox.** `OfflineOutbox` (`Offline.kt`) keeps a JSON queue at
`filesDir/outbox/queue.json`. Copies of every captured file go to `filesDir/outbox/media/`. Each
queue write is atomic: the file is written to a temp file, flushed, then renamed over the old one.
A queue file that will not parse is set aside and reported, never read as empty. Before this review
an entry could only be a **create**. It now has a `kind` (`OutboxKind`, `Offline.kt:111`):

| Kind | Replay | Used by |
|---|---|---|
| `create` | POST the record, then attach its files | every record form, offline |
| `update` | PATCH/PUT the record, then attach its files | artisan, craft, workshop, product, tool and process edits, offline |
| `attach` | attach files to an existing record | files that did not reach a record saved online; the misc media screen |
| `action` | a small non-record write | comments, app feedback, task progress |

**Entries belong to an account.** Each entry records `ownerUserId` (`Offline.kt:83`). The sync pass
replays only the signed-in account's entries, plus legacy entries with no owner
(`replayableBy`, `FieldRepository.kt:2575`). Signing out keeps the entries. They wait for their
owner to sign in again on this device, and the sign-out shows a count of what is still waiting.

**Sync runs without the app.** `OutboxSyncWorker` (WorkManager, needs a connected network) is
scheduled whenever an entry is queued (`FieldRepository.kt:2496`) and at every sign-in
(`MainActivity.kt:756`). WorkManager keeps the request across process death and reboots. The
in-app timer and network callback still run while the app is open. Every pass, in the foreground
or in the background, takes one process-wide lock (`outboxSyncMutex`, `FieldRepository.kt:336`), so
two passes never replay the same entry. Replay progress (the created id, each uploaded file) is
written back as it lands, as before, so an interrupted pass resumes rather than repeats.

**Conflicts.** A queued edit replays with last write wins, the same as an online edit. The form
sends the record as it stood when it was opened, plus the researcher's changes. A record deleted
in the meantime answers 404. That, and any other 4xx, marks the entry as refused: it keeps its data
and files, stops blocking the queue, and is listed with the server's reason and a **Try again**
button in the outbox banner (`MediaDownloads.kt`, `OutboxPendingBanner`).

**Reads come from a device cache when offline.** `OfflineReadCache` puts an OkHttp disk cache in
`noBackupFilesDir/http-read-cache` (persistent, not reclaimed as `cacheDir` is, excluded from
backups). JSON answers to GET requests are stored with `max-age=0`, so online reads always ask the
server. When a read fails with no connection, the cached answer is returned instead. A read that
was never made online still fails, as before. Sign-in state, two-step state, secrets, AI keys,
Aadhaar lookups, preferences, the update check, downloads, exports and the late-submission check
are never stored. Sign-out clears the cache (`FieldRepository.kt:399`). A line at the foot of every
screen says when the device is offline (`OfflineNotice`, `MainActivity.kt:15917`).

## The matrix

Columns: **Writes**, what the screen sends. **Pending data**, where it waits. **Survives**, whether
it survives process death, reboot, sign-out and update. **Sync**, how and when it is sent.
**Offline**, what the user sees. **Reads**, whether its reads are cached.

### Record capture (the field work)

| Screen | Writes | Pending data | Survives | Sync | Offline | Reads | Before | Now |
|---|---|---|---|---|---|---|---|---|
| Artisan, craft, workshop, product: **new** (`CraftForm` `MainActivity.kt:5964`, `ArtisanForm` `:6066`, `WorkshopForm` `:6561`, `ProductForm` `:6736`) | create + files | outbox | yes | outbox, foreground and background | "Saved on this device…" | pickers cached | OK | OK |
| The same four: **edit** (`trySaveOffline`, `MainActivity.kt:15896`) | update + files | was: form memory only | was: no | now: outbox `update` | was: request error | cached | GAP | **Fixed** |
| The same four: files that fail after an **online** save (`uploadAttachments`, `MainActivity.kt:3607`) | files | was: form memory; the record was saved but the form reported failure | was: no | now: outbox `attach` (`queueFailedAttachments`, `:3685`) | was: "re-attach from Update existing" | — | RISK | **Fixed** |
| Location on offline-captured files (`PendingMedia.location`, `Offline.kt`) | — | was: dropped on the offline path only | — | now: sent with each file | — | — | RISK | **Fixed** |
| Capture time of queued files (`PendingMedia.capturedAt`; `recordedAt`, `FieldRepository.kt:2954`) | — | was: sync time sent as `recordedAt` | — | now: the time the file was queued | — | — | RISK | **Fixed** |
| Tool (`ToolForm`, `MainActivity.kt:7170`): new | create + files + stage captures | outbox | yes | outbox | saved on device | cached | OK | OK |
| Tool: edit (`MainActivity.kt:7790`) | update | was: form memory | was: no | outbox `update` | was: error | cached | GAP | **Fixed** |
| Tool: stage capture fails after an online save | file | was: form memory; save reported failed after the tool existed, so a second tap made a second tool | was: no | outbox `attach` | was: "Unable to save tool" | — | RISK | **Fixed** |
| Process (`ProcessForm`, `MainActivity.kt:8304`): new | create + pre-process and step files | outbox (step files by step index) | yes | outbox | saved on device | cached | OK | OK |
| Process: edit (`MainActivity.kt:8524`) | update | was: form memory | was: no | outbox `update`; step files map to the steps the update returns | was: error | cached | GAP | **Fixed** |
| Process: step file fails after an online save | file | was: form memory; same duplicate risk as tool | was: no | outbox `attach` to the step | — | — | RISK | **Fixed** |
| Questionnaire interview (`QuestionnaireForm`, `MainActivity.kt:12704`): new | create + files | outbox | yes | outbox | saved on device | **sections were not cached**, so an interview could not be started offline at all | GAP (reads) | **Fixed** (read cache) |
| Questionnaire interview: edit (`MainActivity.kt:13977`) | update + answers | form memory | no | needs a connection | request error | cached | GAP | Deferred, **F29** |
| Any record form **before Save** (all of the above) | — | Compose `remember` only | no: a kill while the camera is open loses the form | — | — | — | GAP | Deferred, **F28** |
| A create that fails part-way online (`IOException` after the request left) | — | form memory | no | not queued, because replaying might duplicate | request error | — | RISK | Deferred, **F30** (needs a server idempotency key) |
| Miscellaneous media upload (`AndroidMediaForm`, `MainActivity.kt:11881`) | files to an existing record | was: kept "staged" in form memory | was: no | now: outbox `attach` | was: "All files failed…"; now "kept on this device" | cached | GAP | **Fixed** |
| Trace panel (`ui/trace/`) | an exported drawing | `filesDir` (`TraceExportFile.kt`), then a form's attachments | yes, until attached | through the form that attaches it | works offline (on-device engine) | — | OK | OK |
| Location reference list (`LocationFields.kt`) | — | `filesDir` cache file | yes | — | last list shown | own cache | OK | OK |

### Reading and smaller writes

| Screen | Writes | Pending data | Survives | Sync | Offline | Reads | Before | Now |
|---|---|---|---|---|---|---|---|---|
| Dashboard (`DashboardScreen`, `MainActivity.kt:2527`) | — | — | — | — | was: stats error | was: none; now: device cache | GAP | **Fixed** |
| Update existing: record picker (`RecordPickerScreen`, `:5357`) | — | — | — | — | was: empty list and error | now: cached | GAP | **Fixed** |
| Edit screen: loading the record (`EditScreen`, `:5648`) | — | — | — | — | was: "Unable to load" | now: cached | GAP | **Fixed** |
| View Data and record detail (`ViewDataScreen` `:10415`, `ViewDataDetail` `:11575`) | — | — | — | — | was: error | now: cached | GAP | **Fixed** |
| Comments on a record (`RecordCollabSection`, `:11790`) | a comment | was: the text box | was: no | now: outbox `action`, queued when offline before sending | "Saved on this device…" | cached | GAP | **Fixed** |
| Search (`SearchScreen.kt`), Map (`MapScreen.kt`), Data Browser (`DataBrowserScreen.kt`), Consolidated questionnaire (`ConsolidatedQuestionnaireScreen.kt`), My Activity (`MyActivityScreen`, `:9015`) | — | — | — | — | was: error | now: cached (Data Browser downloads are not; they need a connection) | GAP | **Fixed** |
| Media previews of saved records | — | — | — | — | Coil's own image cache. A signed URL that has expired, or an image never viewed, cannot load offline | Coil disk cache | GAP | Remains. A limitation of signed media URLs, not a finding |
| My tasks: progress and status (`MyTasksScreen`, `:15344`) | task update | was: nothing | was: no | now: outbox `action`; also on a dropped connection (the update is idempotent) | list shows the change at once, with "Saved on this device…" | cached | GAP | **Fixed** |
| App feedback (`FeedbackScreen`, `:9816`) | feedback upsert | was: form memory | was: no | now: outbox `action`; also on a dropped connection (upsert) | "Saved on this device…" | cached | GAP | **Fixed** |
| Appearance (`AppearanceScreen.kt`) | preferences | device copy (SharedPreferences) | yes, on this device | PUT when online; the server's row wins at the next sign-in | applied locally | never cached (on purpose) | RISK | Deferred, **F31** |
| Walkthrough (`WalkthroughScreen.kt`) | seen-state | SharedPreferences | yes | none needed | works | — | OK | OK |

### Account and administration

**Online-only by decision.** These screens make decisions about other people's accounts, access or
work, or change security settings. Replaying such a decision hours later, after someone else may
have made a different one, is worse than asking for a connection. Each shows the server's error
offline and loses at most a tap, never captured data. Their reads are cached, except for secrets,
AI keys and two-step state.

| Screen | Before | Now |
|---|---|---|
| Sign-in and two-step challenge (`LoginScreen` `MainActivity.kt:937`, `TwoStepChallengeForm`). An already signed-in user opens the app offline from the cached profile (`MainActivity.kt:641`) | OK | OK |
| Two-step verification settings, My AI keys, API keys (secrets): never cached | OK | OK |
| Reviews and approvals, Recovered recordings, User feedback (admin), Tool assignments (`ToolAssignScreen`), Tasks admin (`TaskAdminScreen.kt`), Workshop assignments and access requests, Access roster (`AccessRosterScreen.kt`), Settings, Users (`UserManagementForm`), Sharing (`SharingForm`), Workshop access (`WorkshopAccessScreen`), Questionnaire builder (`QuestionnaireBuilder`), Delete record (`DeleteRecordSection`). Reads were not cached | GAP (reads) | OK by decision: reads now cached, writes online-only |

### Across every screen

| Concern | Evidence | Before | Now |
|---|---|---|---|
| Sync only while the app is open: a swiped-away app or a reboot left the queue unsent | `RepositoryApp`'s timer and network callback were the only triggers | GAP | **Fixed**: `OutboxSyncWorker` (WorkManager, needs network) |
| Sign-out then sign-in as **another** account replayed the first account's queue under the second account's name | `syncOutbox` replayed every entry | RISK | **Fixed**: `ownerUserId`, `replayableBy` |
| Two sync passes at once (the lock was per repository instance) | `syncMutex` was an instance field | RISK | **Fixed**: process-wide `outboxSyncMutex` |
| Entries the server refused were invisible after the toast, and counted as "uploading" | `outboxFailures` had no caller | RISK | **Fixed**: listed with the reason and **Try again** in the banner |
| App update: a queue written by the previous build must still decode | every new field is defaulted; `OfflineQueueFileTest` decodes the old shape | OK | OK |
| Queue file atomic and damage-tolerant | `OfflineQueueFile` (`Offline.kt:318`) | OK | OK (moved into a testable object) |
| Staged-object journal written with a plain `writeText` (a kill mid-write lost every multipart upload id) | `StagedJournal.kt:72` | RISK | **Fixed**: `writeFileAtomically` |

## How this document is kept true

| What | Check |
|---|---|
| A new screen or form | Add a row. If it captures anything, it must reach the outbox offline (`trySaveOffline`, `queueOfflineEntry`, `queueAttachments` or `queueAction`), or be listed under "Online-only by decision" with the reason. |
| A new GET endpoint that returns secrets or per-request state | Add its path to `NEVER_CACHED` in `OfflineReadCache.kt` and to `OfflineReadCacheTest`. |
| A new field on `PendingEntry` or `PendingMedia` | Give it a default, and extend `OfflineQueueFileTest`'s old-shape decode. An entry that no longer decodes after an update is lost data. |
| A deferred row is closed | Update its row here and delete the matching entry in `docs/OPEN_FINDINGS.md` in the same change. |
| The line numbers above | `node docs/tools/check-docs.mjs` fails on a citation past the end of a file. It cannot tell whether a line still says the same thing, so re-check by symbol name when the file moves. |
