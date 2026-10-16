# Android offline audit

**Date:** 2026-10-16. **Branch:** `feature/android-offline-review`, based on `integrate/2fa-nav-downloads`.

**The rule under review:** on every screen, anything a user enters or captures is kept safely on the
device until there is internet, and then it syncs. Nothing may be lost across app kills, reboots,
sign-out and sign-in, or app updates. Read-only screens show the last data they loaded instead of
failing.

This document lists every screen of the Android app, what it writes, where pending data lives, and
how and when it syncs. Each row is classified as it was **before** this review (OK, GAP or RISK),
with the fix next to it. The four items first deferred (F28 drafts, F29 interview edits, F30
idempotency, F31 appearance) were implemented on 2026-10-16 and are no longer open findings.

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
- `android/app/src/main/java/com/fieldrepository/app/data/PreviewCache.kt` (new, 2026-10-17)
- `android/app/src/main/java/com/fieldrepository/app/ui/OfflinePreviews.kt` (new, 2026-10-17)

## Summary

| | Before | After this change |
|---|---|---|
| OK | 10 | 39 |
| GAP | 18 | 0 |
| RISK | 11 | 0 |

There are 39 rows and 29 were fixed. One of those is the administration row, which is now OK by
decision: its reads are cached and its writes stay online-only (see below). The last GAP, media
previews offline, was closed on 2026-10-17 by a device cache of preview images (see its row).

## How offline storage works now

**Writes go to the outbox.** `OfflineOutbox` (`Offline.kt`) keeps a JSON queue at
`filesDir/outbox/queue.json`. Copies of every captured file go to `filesDir/outbox/media/`. Each
queue write is atomic: the file is written to a temp file, flushed, then renamed over the old one.
A queue file that will not parse is set aside and reported, never read as empty. Before this review
an entry could only be a **create**. It now has a `kind` (`OutboxKind`, `Offline.kt:133`):

| Kind | Replay | Used by |
|---|---|---|
| `create` | POST the record, then attach its files | every record form, offline |
| `update` | PATCH/PUT the record, then attach its files | artisan, craft, workshop, product, tool and process edits, offline |
| `attach` | attach files to an existing record | files that did not reach a record saved online; the misc media screen |
| `action` | a small non-record write | comments, app feedback, task progress |
| `sequence` | several requests in order, each ticked off as it lands, stopping at the first refusal; then the files | questionnaire interview edits, offline |

**Entries belong to an account.** Each entry records `ownerUserId` (`Offline.kt:83`). The sync pass
replays only the signed-in account's entries, plus legacy entries with no owner
(`replayableBy`, `FieldRepository.kt:2605`). Signing out keeps the entries. They wait for their
owner to sign in again on this device, and the sign-out shows a count of what is still waiting.

**Sync runs without the app.** `OutboxSyncWorker` (WorkManager, needs a connected network) is
scheduled whenever an entry is queued (`FieldRepository.kt:2517`) and at every sign-in
(`MainActivity.kt:766`). WorkManager keeps the request across process death and reboots. A pass
that is waiting (for a network, or out an exponential backoff after failures) is replaced by a fresh
one each time, so new work never waits for an old backoff; a pass that is running is kept. The
in-app timer and network callback still run while the app is open. Every pass, in the foreground
or in the background, takes one process-wide lock (`outboxSyncMutex`, `FieldRepository.kt:336`), so
two passes never replay the same entry. Replay progress (the created id, each uploaded file) is
written back as it lands, as before, so an interrupted pass resumes rather than repeats.

**Conflicts.** A queued edit replays with last write wins, the same as an online edit. The form
sends the record as it stood when it was opened, plus the researcher's changes. A record deleted
in the meantime answers 404. That, and any other 4xx, marks the entry as refused: it keeps its data
and files, stops blocking the queue, and is listed with the server's reason and a **Try again**
button in the outbox banner (`MediaDownloads.kt`, `OutboxPendingBanner`).

**Creates carry an `Idempotency-Key`.** Every queued create and comment stores a UUID
(`PendingEntry.idempotencyKey`) and sends it as the `Idempotency-Key` header. An online create
sends one too, and if its answer is lost (no signal part-way, a timeout, a 5xx) the record moves
into the outbox under the same key (`createOrKeep`). The backend (`backend/app/core/idempotency.py`,
table `IdempotencyKey`, migration `20261017090000_idempotency_keys`) keeps each key's answer for 30
days per account (a week offline is normal) and returns it for a repeat instead of running the
create again. Only the create routes are covered, by an allow-list: sign-in, two-step, dataset
tokens, secrets and AI keys pass straight through and are never stored. A repeat that arrives while
the first is still running gets `409` with `Idempotency-Status: in-progress`, which the handset
retries rather than treats as a refusal; a first request still unanswered after 15 minutes is
treated as dead, and exactly one repeat may take it over. Requests without the header behave as
before.

**Unsaved forms are drafts on disk.** Every record form (artisan, craft, workshop, product, tool,
process, questionnaire interview) and the miscellaneous media screen binds its fields to a
`FormDraft` (`ui/FormDrafts.kt`). While the form has unsaved changes, it autosaves 0.7 seconds after
the last change to `filesDir/drafts/<account>/<form>__<record>.json` with the same atomic writer as
the queue (`data/DraftStore.kt`). The key is the record id for an edit and `new-<parent>-<uuid>` for a new
record, where the parent is the record the form was opened from (the artisan a product or interview
is being recorded for, else the craft; `none` when nothing was handed over), so a draft started for
one artisan is never offered on another's form. The offer names that parent. Opening the same form again offers "Continue your draft" or "Discard". A successful save,
or a queue to the outbox, deletes the draft. Camera captures are written to
`filesDir/field-captures` (they were in `cacheDir`, which Android may clear), and a file picked from
another app is copied into `filesDir/drafts-media` the first time a draft saves it, because the
other app's permission does not survive a restart. Capture files that no draft refers to are swept
three days after they were made.

**Appearance changes queue too.** A change made without a connection is kept with the time it was
made. At the next reconnect or sign-in it is sent if it is later than the server row's `updatedAt`,
and otherwise the server row is applied.

**Backups.** `res/xml/backup_rules.xml` (Android 11 and older) and
`res/xml/data_extraction_rules.xml` (Android 12 and newer) back up the outbox, drafts, captures and
settings, so a lost or replaced phone keeps unsent work. They exclude the sign-in session
(`field_repository_auth.xml`, which holds the bearer token and cached profile), the staged-object
journal and WorkManager's own database. A restored phone therefore signs in again and is asked for
its two-step code again. Two-step state and secrets are never stored on the device.

**Reads come from a device cache when offline.** `OfflineReadCache` puts an OkHttp disk cache, one
per process created on first use, in `noBackupFilesDir/http-read-cache` (persistent, not reclaimed as `cacheDir` is, excluded from
backups). JSON answers to GET requests are stored with `max-age=0`, so online reads always ask the
server. When a read fails with no connection, the cached answer is returned instead. A read that
was never made online still fails, as before. Sign-in state, two-step state, secrets, AI keys,
Aadhaar lookups, preferences, the update check, downloads, exports and the late-submission check
are never stored. Sign-out clears the cache on the IO dispatcher (`FieldRepository.kt:399`). A line at the foot of every
screen says when the device is offline (`OfflineNotice`, `MainActivity.kt:16064`).

## The matrix

Columns: **Writes**, what the screen sends. **Pending data**, where it waits. **Survives**, whether
it survives process death, reboot, sign-out and update. **Sync**, how and when it is sent.
**Offline**, what the user sees. **Reads**, whether its reads are cached.

### Record capture (the field work)

| Screen | Writes | Pending data | Survives | Sync | Offline | Reads | Before | Now |
|---|---|---|---|---|---|---|---|---|
| Artisan, craft, workshop, product: **new** (`CraftForm` `MainActivity.kt:5981`, `ArtisanForm` `:6090`, `WorkshopForm` `:6592`, `ProductForm` `:6773`) | create + files | outbox | yes | outbox, foreground and background | "Saved on this device…" | pickers cached | OK | OK |
| The same four: **edit** (`trySaveOffline`, `MainActivity.kt:16043`) | update + files | was: form memory only | was: no | now: outbox `update` | was: request error | cached | GAP | **Fixed** |
| The same four: files that fail after an **online** save (`uploadAttachments`, `MainActivity.kt:3624`) | files | was: form memory; the record was saved but the form reported failure | was: no | now: outbox `attach` (`queueFailedAttachments`, `:3702`) | was: "re-attach from Update existing" | — | RISK | **Fixed** |
| Location on offline-captured files (`PendingMedia.location`, `Offline.kt`) | — | was: dropped on the offline path only | — | now: sent with each file | — | — | RISK | **Fixed** |
| Capture time of queued files (`PendingMedia.capturedAt`; `recordedAt`, `FieldRepository.kt:3030`) | — | was: sync time sent as `recordedAt` | — | now: the time the file was queued | — | — | RISK | **Fixed** |
| Tool (`ToolForm`, `MainActivity.kt:7214`): new | create + files + stage captures | outbox | yes | outbox | saved on device | cached | OK | OK |
| Tool: edit (`MainActivity.kt:7842`) | update | was: form memory | was: no | outbox `update` | was: error | cached | GAP | **Fixed** |
| Tool: stage capture fails after an online save | file | was: form memory; save reported failed after the tool existed, so a second tap made a second tool | was: no | outbox `attach` | was: "Unable to save tool" | — | RISK | **Fixed** |
| Process (`ProcessForm`, `MainActivity.kt:8358`): new | create + pre-process and step files | outbox (step files by step index) | yes | outbox | saved on device | cached | OK | OK |
| Process: edit (`MainActivity.kt:8600`) | update | was: form memory | was: no | outbox `update`; step files map to the steps the update returns | was: error | cached | GAP | **Fixed** |
| Process: step file fails after an online save | file | was: form memory; same duplicate risk as tool | was: no | outbox `attach` to the step | — | — | RISK | **Fixed** |
| Questionnaire interview (`QuestionnaireForm`, `MainActivity.kt:12801`): new | create + files | outbox | yes | outbox | saved on device | **sections were not cached**, so an interview could not be started offline at all | GAP (reads) | **Fixed** (read cache) |
| Questionnaire interview: edit (`MainActivity.kt:14047`) | update + answers + clips | was: form memory; now: outbox `sequence` (the PATCH as an ordered step, then the clips and attachments) | yes | in order, each step ticked off on disk, stopped at the first refusal (`FieldRepository.kt:2662`) | "Saved on this device…" | cached | GAP | **Fixed** |
| Any record form **before Save** (all of the above, and miscellaneous media) | every field, captured files, workshop, process steps, interview answers and clips | was: Compose `remember` only; now: `filesDir/drafts/<account>/<form>__<record>.json`, autosaved 0.7 s after a change while the form is dirty (`ui/FormDrafts.kt`, `data/DraftStore.kt`); camera captures now go to `filesDir/field-captures`, and files picked from other apps are copied into `filesDir/drafts-media` | yes: process death, camera, reboot, sign-out (drafts are per account), update | — | the form offers "Continue your draft" or "Discard"; the draft is deleted on save or queue | — | GAP | **Fixed** |
| A create that fails part-way online (no answer, a timeout or a 5xx after the request left) | — | was: form memory; now: the outbox, under the `Idempotency-Key` the attempt sent (`createOrKeep`, `MainActivity.kt:16096`) | yes | outbox; the server answers a repeat of the key from the first record (`backend/app/core/idempotency.py`, 30 days) | "Saved on this device…" | — | RISK | **Fixed** |
| Miscellaneous media upload (`AndroidMediaForm`, `MainActivity.kt:11947`) | files to an existing record | was: kept "staged" in form memory | was: no | now: outbox `attach` | was: "All files failed…"; now "kept on this device" | cached | GAP | **Fixed** |
| Trace panel (`ui/trace/`) | an exported drawing | `filesDir` (`TraceExportFile.kt`), then a form's attachments | yes, until attached | through the form that attaches it | works offline (on-device engine) | — | OK | OK |
| Location reference list (`LocationFields.kt`) | — | `filesDir` cache file | yes | — | last list shown | own cache | OK | OK |

### Reading and smaller writes

| Screen | Writes | Pending data | Survives | Sync | Offline | Reads | Before | Now |
|---|---|---|---|---|---|---|---|---|
| Dashboard (`DashboardScreen`, `MainActivity.kt:2544`) | — | — | — | — | was: stats error | was: none; now: device cache | GAP | **Fixed** |
| Update existing: record picker (`RecordPickerScreen`, `:5374`) | — | — | — | — | was: empty list and error | now: cached | GAP | **Fixed** |
| Edit screen: loading the record (`EditScreen`, `:5665`) | — | — | — | — | was: "Unable to load" | now: cached | GAP | **Fixed** |
| View Data and record detail (`ViewDataScreen` `:10481`, `ViewDataDetail` `:11641`) | — | — | — | — | was: error | now: cached | GAP | **Fixed** |
| Comments on a record (`RecordCollabSection`, `:11856`) | a comment | was: the text box | was: no | now: outbox `action`, queued when offline before sending | "Saved on this device…" | cached | GAP | **Fixed** |
| Search (`SearchScreen.kt`), Map (`MapScreen.kt`), Data Browser (`DataBrowserScreen.kt`), Consolidated questionnaire (`ConsolidatedQuestionnaireScreen.kt`), My Activity (`MyActivityScreen`, `:9081`) | — | — | — | — | was: error | now: cached (Data Browser downloads are not; they need a connection) | GAP | **Fixed** |
| Media previews of saved records | — | — | — | — | was: Coil's own image cache, keyed by a signed URL that expires; now: each photo or video tile that loads online is also kept as a small JPEG keyed by the media id (`PreviewCache`, `noBackupFilesDir/media-previews/<account>/`, least recently shown evicted past 150 MB, every account's folder deleted at sign-out) and drawn when the link fails, in record views and in the Data Browser's image rows; the full-screen viewer falls back to it too. A write is filed under the account signed in when the image loaded and is dropped if a sign-out came first | device preview cache | GAP | **Fixed**. A file never looked at online still has no preview offline |
| My tasks: progress and status (`MyTasksScreen`, `:15491`) | task update | was: nothing | was: no | now: outbox `action`; also on a dropped connection (the update is idempotent) | list shows the change at once, with "Saved on this device…" | cached | GAP | **Fixed** |
| App feedback (`FeedbackScreen`, `:9882`) | feedback upsert | was: form memory | was: no | now: outbox `action`; also on a dropped connection (upsert) | "Saved on this device…" | cached | GAP | **Fixed** |
| Appearance (`AppearanceScreen.kt`) | preferences | device copy, plus a queued-change time (`AppPreferencesStore.pendingSince`) | yes | PUT when online; otherwise at reconnect or the next sign-in, where the later write wins by timestamp (`localPreferencesWin`) | applied locally, "Saved on this device…" | never cached (on purpose) | RISK | **Fixed** |
| Walkthrough (`WalkthroughScreen.kt`) | seen-state | SharedPreferences | yes | none needed | works | — | OK | OK |

### Account and administration

**Online-only by decision.** These screens make decisions about other people's accounts, access or
work, or change security settings. Replaying such a decision hours later, after someone else may
have made a different one, is worse than asking for a connection. Each shows the server's error
offline and loses at most a tap, never captured data. Their reads are cached, except for secrets,
AI keys and two-step state.

| Screen | Before | Now |
|---|---|---|
| Sign-in and two-step challenge (`LoginScreen` `MainActivity.kt:954`, `TwoStepChallengeForm`). An already signed-in user opens the app offline from the cached profile (`MainActivity.kt:649`) | OK | OK |
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
| Queue file atomic and damage-tolerant | `OfflineQueueFile` (`Offline.kt:346`) | OK | OK (moved into a testable object) |
| Auto-backup copied the bearer token to the cloud and onto a restored phone; nothing excluded it | `android:allowBackup="true"` with no rules | RISK | **Fixed**: backup and data-extraction rules exclude the session and keep the outbox and drafts |
| Staged-object journal written with a plain `writeText` (a kill mid-write lost every multipart upload id) | `StagedJournal.kt:72` | RISK | **Fixed**: `writeFileAtomically` |

## How this document is kept true

| What | Check |
|---|---|
| A new screen or form | Add a row. If it captures anything, it must reach the outbox offline (`trySaveOffline`, `queueOfflineEntry`, `queueAttachments` or `queueAction`), or be listed under "Online-only by decision" with the reason. |
| A new GET endpoint that returns secrets or per-request state | Add its path to `NEVER_CACHED` in `OfflineReadCache.kt` and to `OfflineReadCacheTest`. |
| A new field on `PendingEntry` or `PendingMedia` | Give it a default, and extend `OfflineQueueFileTest`'s old-shape decode. An entry that no longer decodes after an update is lost data. |
| A new record form | Bind its state to a `FormDraft` (`.drafted(draft, "key")`, `bindCustom` for anything else), add `DraftOfferCard` and `FormDraftAutosave`, and call `draft.clear()` where it saves or queues. Send its create through `createOrKeep`. |
| A new file the app writes under `filesDir` | Decide whether it belongs in a backup, and say so in both `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`. Anything holding a credential is excluded. |
| The line numbers above | `node docs/tools/check-docs.mjs` fails on a citation past the end of a file. It cannot tell whether a line still says the same thing, so re-check by symbol name when the file moves. |
