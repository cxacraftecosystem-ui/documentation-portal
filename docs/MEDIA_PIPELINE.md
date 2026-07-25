# Media pipeline — how a photo, clip or video gets from the field into the repository

Field capture happens on bad networks. A researcher standing in a workshop in a village has a
2G-ish uplink, a phone full of 12 MP photos and 4K video, and no patience for a save button that
spins for four minutes. Every tactic in this document exists to make that situation work, and to
make sure that when it *doesn't* work nothing is silently lost or silently left behind.

Both clients — the Android app and the web app — talk to the **same** API and push bytes **straight
to object storage**, never through the API. This document is the single description of that pipeline
and of every trick either client plays.

- Android: `android/app/src/main/java/com/fieldrepository/app/`
- Web: `frontend/lib/media.ts`, `frontend/lib/uploads.tsx`, `frontend/components/forms/MediaCaptureField.tsx`
- API: `backend/app/api/routes/media.py`, `backend/app/services/s3.py`

---

## 1. The server contract

```
POST   /api/media/presign                  -> { uploadUrl, objectKey, bucket, headers, publicUrl }
POST   /api/media/multipart/create         -> { objectKey, uploadId, bucket, partSize, partCount }
POST   /api/media/multipart/presign-parts  -> { urls: { "1": …, "2": … } }
POST   /api/media/multipart/complete       -> { objectKey, bucket, publicUrl }
POST   /api/media/multipart/abort          -> { aborted: true }
POST   /api/media/complete                 -> the created MediaFile row
DELETE /api/media/object?objectKey=…       -> 204 (staged object that was never linked)
```

Four properties of that contract the clients lean on hard:

| Property | Where | Why it matters |
| --- | --- | --- |
| **`/complete` is idempotent on `objectKey`** | `media.py:198-264` | The key embeds the uploader id + a per-upload uuid, so a row already present for a key *is* this upload. A retried finish returns the existing row instead of a 500 `UniqueViolationError` — this is what makes retrying `/complete` safe rather than duplicating records. |
| **Every object lives under `media/<user_id>/`** | `s3.py:make_object_key`, `media.py:96-99` | Ownership is a prefix check, so `/media/object` can delete *your* staged uploads and nothing else. |
| **`originalFilename` is independent of `objectKey`** | `media.py:224-227` | The displayed name is applied at `/complete`. A file can therefore be uploaded under a provisional key long before its final, nomenclature-correct name is known. This is the whole basis of eager uploading. |
| **`/media/object` refuses attached objects (409)** | `media.py:504-519` | An orphan sweeper can never delete an object that a record now points at. |

`MediaCompleteRequest` also accepts a free-form `checksum` (`backend/app/schemas/media.py:107`).

---

## 2. Android — the tactics already in place

### 2.1 Eager pre-upload

The single biggest win. The bytes start moving the moment a file is attached, not when the form is
saved, so the transfer overlaps the minutes spent typing.

| Piece | Location |
| --- | --- |
| `preuploadObject` — presign + PUT under a provisional key, no record needed | `data/FieldRepository.kt:1094` |
| `startEagerUpload` — one uri, progress + failure bookkeeping, runs on a process-lifetime scope | `MainActivity.kt:2126` |
| `MediaCaptureState.stagedDeferred / staged / stagedProgress / stagedFailed` | `MainActivity.kt:2087-2113` |
| Fired for every newly attached uri | `MainActivity.kt:3509` (and `MediaStagingEffect`, `MainActivity.kt:3751`) |
| `completeStaged` — applies the final filename and links the object at save | `FieldRepository.kt:1270` |
| Save prefers the staged object, awaiting one still in flight | `MainActivity.kt:2238-2240` |
| `AppScope.io` — a process-lifetime scope so a transfer survives recomposition | `data/AppScope.kt:11` |

### 2.2 Cleanup of staged-but-unsaved objects

| Situation | Handling |
| --- | --- |
| One attachment removed (`✕`) | await the in-flight transfer, then `deleteStaged` — `MainActivity.kt:3711-3719` |
| "Clear attachments" | same, for the whole batch — `MainActivity.kt:3727-3737` |
| Capture screen dismissed without saving | `DisposableEffect { onDispose { … deleteStaged … } }` — `MainActivity.kt:3517`, `:3761` |
| A grid-measurement photo re-captured or discarded | `MainActivity.kt:2332-2339`, `:2378-2386` |
| `deleteStaged` itself | `FieldRepository.kt:1316` -> `DELETE /media/object` |

### 2.3 Multipart above a size threshold

`MULTIPART_THRESHOLD = 64 MiB` (`FieldRepository.kt:46`). At or under it, one streamed PUT; above it,
`create` -> `presign-parts` -> per-part PUT -> `complete`, and S3 stitches the parts into a single
object so the stored file is still whole (`uploadBytesToS3`, `FieldRepository.kt:1140`;
`uploadMultipart`, `:1169`).

- Per-part retry with backoff, 3 attempts: `putPart`, `FieldRepository.kt:1240`.
- `Content-Type` is deliberately unset on a part — the part presign does not sign it (`:1245`).
- **Abort on any failure** so half-written parts never linger: `FieldRepository.kt:1222`.
- Bytes are streamed from the content Uri, never held on the heap: `uploadResolved`, `:1053-1056`.

### 2.4 Safe-request retry with backoff

An OkHttp interceptor retries **only** requests that are safe to repeat (`ApiClient.kt:27-36`):
GETs, plus `/media/presign`, `/media/multipart/create`, `/media/multipart/presign-parts`,
`/media/multipart/abort`. Record-creating calls are deliberately excluded so a 504 can never create a
duplicate. Retriable codes: 502/503/504 (`ApiClient.kt:17`), up to 4 attempts, backoff
`min(4 s, 600 ms × attempt)` (`ApiClient.kt:39`). Generous transport timeouts and
`retryOnConnectionFailure(true)` for mobile data (`ApiClient.kt:60-63`).

### 2.5 Offline outbox

With no validated internet, a create is written to disk instead of the network and replayed later
(`data/Offline.kt`).

- `ConnectivityObserver.isOnline` — validated internet, not just an attached interface (`Offline.kt:71`).
- `OfflineOutbox.stageMedia` — copies the captured content Uri into app storage so it survives (`Offline.kt:114`).
- `queueOfflineEntry` — serialised create request + its media specs (`FieldRepository.kt:1349`).
- `syncOutbox` — create the record, upload its media, *then* drop the local copy; stops at the first
  failure so the rest stay queued (`FieldRepository.kt:1389`).
- Process records fan their media out to the right freshly-created step (`syncProcessEntry`, `:1437`).
- A legacy entry that would 422 forever is repaired rather than allowed to block the queue (`:1416`).

### 2.6 Idempotent completion

Android never auto-retries `/complete` at the transport layer, but the server-side idempotency
(§1) is what makes the app's own save/back-guard retry flow safe.

---

## 3. Web — what it does now

Before this change the web uploaded **only after the record was saved**, strictly one file at a time,
with no multipart, no orphan cleanup, and a flat 5-minute `xhr.timeout`. All of the Android tactics
that make sense in a browser are now in place, plus several that Android does not have.

Everything lives behind the unchanged `uploadMediaFile` / `uploadMediaBatch` signatures, so **no call
site had to change**.

### 3.1 Eager pre-upload (`frontend/lib/media.ts`, `frontend/lib/uploads.tsx`)

```
attach file ──► stageFiles(files, ownerId)
                   └─ presign ──► PUT to S3 ──► StagedObject { objectKey, bucket, checksum, … }
                                                        │
save record ──► uploadMediaBatch(files)                 │
                   └─ takeStagedFor(files) ─────────────┘  (synchronous, before any await)
                        └─ POST /media/complete   ← the only call save has to make
```

- `MediaCaptureField` calls `useEagerStaging(files, title)`; every media form in the app already uses
  that component, so every form gets eager upload for free.
- The capture tiles show per-file byte progress, `Uploaded ✓`, or `Upload failed — …` with a **Retry**
  button; the card header shows Android's wording, *"All uploaded ✓ — ready to save"*.
- The eager transfer is published into the page-level `<UploadTray>` as its own section. A file
  claimed by a save leaves the store in the same tick the batch row appears, so the two never
  double-count.

**Matching a staged object back to its file.** The store is keyed by `File` object identity. Three
call sites rename a file just before saving (`app/(protected)/media/page.tsx:246`,
`components/forms/ProcessForm.tsx:116`, `components/forms/ToolForm.tsx:211`) — `new File([file], …)`
keeps the bytes but destroys identity. The fallback is a content signature
(`size:lastModified:type`), honoured **only when it is unambiguous**: if two staged files share a
signature, neither is matched and both simply upload again. Attaching the wrong photo to a record is
far worse than re-uploading one.

### 3.2 Correctness — every way a staged object can be abandoned

| Situation | What happens |
| --- | --- |
| File removed from the attach list (`Discard`) | `discardStagedFile` aborts the XHR and `DELETE /media/object`s whatever reached storage |
| Eager upload failed | the record is marked `error`, the tile offers **Retry**, and the save path re-uploads from scratch after binning the failed attempt's object |
| Save never happens; user navigates away in the SPA | the owning component unmounts -> `releaseStagedOwner` -> after a 2 s grace (React StrictMode re-runs effects on mount) any object nobody else owns is aborted and deleted |
| Tab closed mid-form | `pagehide` (skipped when `event.persisted`, i.e. bfcache) fires a `keepalive` DELETE per staged object |
| Tab closed **while bytes are still moving** | `beforeunload` warns the user first; if they leave anyway the object is reclaimed by the sweep below |
| Browser crash, power loss, keepalive lost | the **journal sweep**: every presigned key is written to `localStorage["field_repo_staged_objects"]` with a timestamp, refreshed by a 60 s heartbeat while the tab still owns it. A key not heart-beaten for 5 minutes is deleted on the next page load and every 5 minutes thereafter. Because a live form keeps heart-beating, the sweeper can never delete an object out from under an open form; because `/media/object` 409s on attached objects, it can never delete a linked one either. |
| `/complete` succeeded but the response was lost | the retry returns the same row (server idempotency); a later sweep gets a 409 and simply drops the journal entry |

### 3.3 Parallel uploads with a concurrency cap

`uploadMediaBatch` used to be a strict `for` loop. It now runs `UPLOAD_CONCURRENCY = 3` files at a
time through a pool that preserves the caller's ordering of `uploaded[]`.

*Why 3:* one connection cannot saturate even a poor link (TCP slow-start plus per-request latency
dominates), while a high fan-out on a 200 kbit uplink starves every individual transfer and pushes
them all towards the stall watchdog. Three is the usual sweet spot and matches the per-part
concurrency used for multipart.

### 3.4 Stall watchdog instead of a flat timeout

The old `xhr.timeout = 5 * 60 * 1000` is exactly wrong for field conditions: it kills a large video
that is uploading perfectly well but slowly, and then burns all three retries doing it again.

`putBlob` sets `xhr.timeout = 0` and instead arms a watchdog that is **reset on every progress
event**:

- `STALL_TIMEOUT_MS = 60 s` — no bytes moved at all: the socket is dead, abort now.
- `FINALIZE_TIMEOUT_MS = 5 min` — armed once the last byte is handed to the socket, because S3
  finalising a large object produces no further progress events.

A slow-but-alive upload can now run for as long as it needs; a genuinely dead one fails in a minute
instead of five.

### 3.5 Multipart for large files

Mirrors Android: over `MULTIPART_THRESHOLD = 64 MiB`, `create` -> `presign-parts` -> per-part PUT
(3 in parallel, 3 attempts each) -> `complete`, with `abort` on any failure. Two things it does that
Android does not:

- **ETag capability probe.** S3 identifies parts by the ETag it returns, and a browser can only read
  that header when the bucket CORS rule lists `ETag` under `ExposeHeaders`. Part 1 is uploaded alone;
  if its ETag is unreadable the upload is aborted, the session flips to single PUTs, and the file is
  retried whole. A misconfigured bucket therefore costs 16 MiB, not every large upload.
- **Per-part re-presigning.** A part that 403s because its (1 hour) signature expired mid-transfer is
  re-signed individually rather than failing a 400 MB upload.

### 3.6 Safe-request retry, and a retriable `/complete`

`apiRetry` reproduces Android's interceptor policy — 4 attempts, `min(4 s, 600 ms × attempt)`, on
502/503/504 and on transport-level `TypeError` — and is applied only to presign, the multipart setup
calls, the multipart abort, **and `/media/complete`**.

Retrying `/complete` is the meaningful addition. Previously a `/complete` that timed out threw away a
completed upload and re-did the whole presign + PUT on the next attempt. Because the endpoint
de-duplicates on `objectKey`, retrying just the finish is both safe and free.

### 3.7 SHA-256 checksum

Computed with WebCrypto *concurrently with the transfer* (so it never delays the bytes) and sent as
`checksum: "sha256:<hex>"` on `/complete`. Skipped above 32 MiB, because `crypto.subtle.digest` needs
the whole file in one allocation and a 300 MB video is not worth that; skipped on an insecure origin
where `crypto.subtle` is unavailable. Nothing verifies it server-side yet — it is stored so that a
later integrity sweep *can*, and so identical bytes are recognisable.

### 3.8 Clearer failure of impossible uploads

A zero-byte file used to reach `/media/presign` and come back as an opaque 422 (`sizeBytes` must be
`> 0`). It now fails immediately with *"…is empty (0 bytes) — there is nothing to upload."*

---

## 3.1 The offline outbox — `frontend/lib/offline.ts`

The last Android tactic the web was missing. A save made with no connection is written to an
IndexedDB queue instead of failing, and sent when the network returns.

**What is stored.** One entry per attempted save: the record request (endpoint, method, JSON body)
and the attached files as `File` objects. IndexedDB stores those by structured clone, so the bytes,
the name and the MIME type survive a browser restart — a blob: URL or an in-memory array would not.
The attachments are the part that cannot be recreated: by the time signal returns the artisan has
gone home.

**Media is a LIST of batches, not one lump.** A product queues its two measurement-grid photos —
each with the caption naming its dimension — beside the general field media; a tool adds its numbered
process-stage captures on top; an interview adds one batch per question carrying that question's
`questionId` metadata. Flattening them would put every file under one caption, and for a grid photo
the caption is the only thing that says which dimension it measures.

**Server-created children.** The process form's step captures link to `processstep` rows that do not
exist until the server makes them, so those batches carry a `stepIndex` and the replay resolves the
real id from the create response's `steps[]`.

**How a failure is triaged** — the one place this deliberately differs from Android, whose outbox
stops at the first failure:

| Failure | Verdict | What happens |
| --- | --- | --- |
| No connection, 5xx, 408, 429 | transient | Stop the pass, keep everything queued, retry on the next `online` event. |
| 4xx (validation, permission) | permanent | Mark **that** entry with the server's reason, leave it for the user to read and discard, carry on to the next. |
| 409 on replay | already saved | Drop the entry. The create landed on an earlier pass whose response was lost; re-queueing would duplicate the record forever. |

Stopping at the first failure is right for a connection that dropped again and wrong for a request
the server will never accept: one 422 at the head of the queue would block every entry behind it
indefinitely with nothing on screen to say why.

**Where it shows.** `components/OutboxBanner.tsx`, mounted once in the protected layout above the
page. An outbox nobody can see is worse than no outbox — the researcher believes the record is filed
when it is sitting in one laptop's browser storage — so the banner names every entry, says plainly
that they live in this browser, drains automatically on `online`, and offers "Sync now" for captive
portals that report `navigator.onLine === true` while nothing routes.

**Wired into:** artisan, product, tool, process, craft, workshop and questionnaire saves — every form
that creates a record in the field. `saveOrQueue` deliberately does not upload the media when online;
the caller keeps its own `uploadMediaBatch` call so progress, per-file retry and the eager-staging
claim all behave exactly as before, and the files are handed over only if the save is queued.

---

## 4. Tactic matrix

| Tactic | Android | Web |
| --- | --- | --- |
| Eager pre-upload on capture/attach | yes | **yes (new)** |
| Per-file progress, retry, remove | yes | **yes (new)** |
| Delete staged object on discard / leave | yes | **yes (new)** |
| Journal + sweep of orphaned staged objects | no | **yes (new)** |
| `beforeunload` guard while bytes are moving | n/a | **yes (new)** |
| Multipart over 64 MiB, per-part retry, abort | yes | **yes (new)** |
| ETag capability probe + fallback | no | **yes (new)** |
| Per-part re-presigning on expiry | no | **yes (new)** |
| Parallel files with a concurrency cap | no (sequential) | **yes (new, 3)** |
| Stall watchdog instead of a flat timeout | n/a (OkHttp) | **yes (new)** |
| Safe-request retry on 502/503/504 | yes | **yes (new)** |
| Retriable, idempotent `/complete` | server-side | **yes (new, client too)** |
| Content checksum | no | **yes (new)** |
| Offline outbox for whole records | yes | **yes (new)** — see §3.1 |
| Streams from disk, never buffers the file | yes | yes (XHR streams a `File`/`Blob`) |

---

## 5. Considered and deliberately not done

**Client-side image downscaling before upload.** Tempting — field photos are 6–12 MB and the uplink
is bad — but wrong for this product. This is a heritage documentation archive: the original file *is*
the artifact, and re-encoding through a canvas destroys full resolution and strips the EXIF the app
deliberately preserves (`collectExifMetadata`, and the on-screen promise that "captured files go up
unchanged"). The bandwidth problem is better solved by not making the user *wait* for the bytes
(eager upload), by not restarting them (multipart + per-part retry), and by not killing a slow
transfer (stall watchdog). If it is ever wanted, it should be an explicit, off-by-default
"low-bandwidth mode" that transplants the original EXIF onto the resized JPEG and records
`extraMetadata.downscaledFrom` — never a silent default.

**Resumable uploads across a page reload.** S3 multipart *is* resumable in principle (the `uploadId`
and completed part ETags could be journalled and the transfer picked up later), but a browser cannot
re-open the user's file after a reload without them re-picking it, so "resume" would still start with
a file dialog. Not worth the machinery; the eager upload already means a reload rarely lands
mid-transfer.

**Client-side dedupe on checksum.** Now cheap to add (the hash already exists) but it needs a server
lookup endpoint and a policy for what "the same file twice" means for two different records. Noted,
not built.

**Retrying the record-creating calls.** Deliberately never done, on either client: a 504 on a create
may or may not have landed, and a duplicate artisan is worse than an error message.

---

## 6. Operational notes

- **S3 bucket CORS must expose ETag** or multipart from the browser can never complete:
  `"ExposeHeaders": ["ETag"]` (already documented in `backend/DEPLOY_AWS.md:143` and
  `docs/DEPLOYMENT_VERCEL.md:178`; the Terraform variable is `cors_allowed_origins`). Local MinIO
  exposes it by default. The client degrades to single PUTs if it is missing, so the symptom is
  "large uploads are slower and less resilient", not "large uploads fail".
- **Local MinIO and SSE.** `AWS_S3_SSE_ALGORITHM` defaults to `AES256`
  (`backend/app/core/config.py:139`), and MinIO without a KMS backend rejects
  `CreateMultipartUpload` with `NotImplemented: Server side encryption specified but KMS is not
  configured`. For local development set `AWS_S3_SSE_ALGORITHM=` (empty) in `backend/.env`, as
  `.env.example` already advises. Real S3 is unaffected.
- **Presign lifetimes**: whole-object PUT 15 min (`s3.py:159`), multipart part 1 hour (`s3.py:191`).
  The web client re-presigns per attempt for whole objects and per part on a 403.
- **The staged-object journal** is `localStorage["field_repo_staged_objects"]`, a
  `{ objectKey: lastSeenEpochMs }` map. Clearing it is harmless: the objects simply stop being
  tracked, and the bucket lifecycle rule (if configured) is the final backstop.

---

## 7. How to verify it end to end

With the local stack up (`docker compose up -d`, API on `:8000`, web on `:3000`, MinIO on `:9000`):

1. Open **Crafts**, attach two files, and *do not save*. The network panel should show one
   `POST /api/media/presign` and one `PUT` to `:9000` per file, **no** `/api/media/complete`, and the
   card should read *"All uploaded ✓ — ready to save"*.
2. Save the craft. There should be exactly one `/api/media/complete` per file, carrying the
   `objectKey`s from step 1 and a `sha256:` checksum — and **no** new presign or PUT.
3. Attach a file, wait for *Uploaded ✓*, press **Discard**: a `DELETE /api/media/object` fires and
   `GET http://127.0.0.1:9000/field-repository/<objectKey>` returns 404.
4. Attach a file and navigate away without saving, or close the tab: same 404.
5. On **Miscellaneous Media** (which renames files before saving), repeat step 2 — `/complete` must
   still carry the staged `objectKey` while `originalFilename` is the nomenclature name.
