# Bulk Dataset API

Mass download of, and programmatic access to, everything in the repository — for a **machine**,
behind **admin credentials**. Mounted at `/api/datasets`.

Everything under `/api/export` and `/api/data` answers a *browser*: one artefact per click, bounded
by what a page can hold, buffered in the web process before the first byte, authenticated as a
person with a session. This API is the other half — a scheduled mirror, an archival snapshot, a
statistics pipeline: a caller with no browser, no session and no upper bound on how much it wants.

---

## 1. Authenticate

Two credentials work. Both must belong to an **ADMIN** or **MASTER_ADMIN** account.

### a. An ordinary admin session token

Whatever `POST /api/auth/login` already gives you. Convenient for a one-off `curl`.

### b. A `dataset:read` token — the one a cron job should hold

```bash
curl -sX POST https://api.example.org/api/datasets/token \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.org","password":"…"}'
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs…",
  "tokenType": "bearer",
  "scope": "dataset:read",
  "expiresInMinutes": 43200,
  "account": { "id": "…", "email": "admin@example.org", "role": "ADMIN" },
  "usage": "Send as: Authorization: Bearer <accessToken>"
}
```

**This token can reach `/api/datasets` and nothing else in the API.** The containment is enforced in
`deps._user_from_bearer`, the one function every authenticated route funnels through — a token
carrying a `scope` claim is refused by default and admitted only where a dependency names that
scope, so a route added next year is closed to it without its author having to know scoped tokens
exist. Using one elsewhere returns `403` with a message saying so.

Notes:

- **Admin rank is read from the live user row, never from the token's `role` claim.** A dataset token
  can outlive its holder's tenure.
- **Revocation is by account.** There is no token store; demoting or deleting the account invalidates
  every token it minted, within the identity cache's 5-second TTL.
- Lifetime is `DATASET_TOKEN_EXPIRES_MINUTES` (default 30 days). Longer than a session token
  *because* it is narrower.
- A non-admin is refused at **issue** time, not at first use.
- Google ID tokens are refused: that flow needs a browser, and this credential is for a process.

All requests below carry `-H "Authorization: Bearer $TOKEN"`.

---

## 2. Find out what there is

```bash
curl -s "$API/api/datasets" -H "Authorization: Bearer $TOKEN"
```

Returns a self-describing download plan — a client can mirror the whole repository from this one
response without reading this document:

```json
{
  "datasets": [
    { "name": "artisans", "label": "Artisans", "rows": 16,
      "json": "/api/datasets/artisans",
      "ndjson": "/api/datasets/artisans.ndjson",
      "csv": "/api/datasets/artisans.csv",
      "columns": ["ID", "Artisan", "Craft", "Place", "…"] },
    { "name": "media", "rows": 925, "csv": null, "columns": null }
  ],
  "totalRows": 1071,
  "filters": { "…": "…" },
  "applied": { "workshopIds": null, "createdBy": null }
}
```

The row counts are computed under **the same filters you passed**, and every generated URL carries
them, so following a link from the plan cannot silently widen the scope the plan was counted under.
URLs are root-relative (the API sits behind CloudFront; an absolute URL built from the origin's view
of the request would name a host the client cannot reach).

### The datasets

| name | contents | `.csv`? |
|---|---|---|
| `workshops` | workshops, their location, linked artisans and crafts | yes |
| `crafts` | the craft vocabulary | yes |
| `artisans` | artisans, craft, workshops, stated address | yes |
| `products` | product documentation | yes |
| `tools` | tool / toolkit documentation | yes |
| `processes` | making processes with their ordered steps | yes |
| `interviews` | questionnaire interviews with answers | yes |
| `media` | every uploaded file, with the record it hangs off | **no** |

`media` has no CSV form: the shared field registry describes *records*, and a media file is not one.
Use `media.ndjson`, whose rows carry every column.

**One thing to know about the media columns.** For every dataset except `processes` they come from the
record's own `media` relation, which this API loads.

`Process` has no `media` back-relation in the schema at all: a process's clips and its per-step
captures are attached by the `MediaFile` **tag pair** (`linkedRecordType` / `linkedRecordId`) with no
FK column to join on. So `processes` resolves its media by tag instead — one extra query per batch —
and reports the same files for a process that the `.xlsx` report does.

**Per-step captures are deliberately not on the process row.** The report files them on its separate
*Process steps* sheet, keyed by step id, so folding them into the parent here would make the two
disagree in the other direction. Find them in the `media` dataset, filtering
`linkedRecordType == "processstep"`.

---

## 3. Take the data

### `GET /api/datasets/{dataset}.ndjson` — the bulk primitive

Newline-delimited JSON, one complete record per line, **no row cap**, streamed in constant memory.

```bash
curl -s "$API/api/datasets/artisans.ndjson" -H "Authorization: Bearer $TOKEN" > artisans.ndjson
```

NDJSON rather than a JSON array because an array has to be *closed*: a consumer cannot begin parsing
until the last byte arrives, and a truncated array is invalid JSON that yields nothing — whereas a
truncated NDJSON file is every line that did arrive.

### `GET /api/datasets/{dataset}.csv`

The same rows as CSV, streamed, no row cap. Columns come from the shared field registry
(`app/services/record_fields.py`) — the same one the `.xlsx` report, the data browser's cards and
`/api/export/*.csv` are built from, so this download cannot describe a record differently from the
surfaces a researcher checks it against.

### `GET /api/datasets/{dataset}` — paged JSON

The usual `{items, total, page, pageSize, pages}` envelope, `pageSize` capped at 100. For browsing
and sampling. **Do not walk a large table with it** — it is OFFSET-paged like every paged route
here, which is quadratic at depth and unstable against concurrent writes. Use `.ndjson`.

### Proving a download is complete

Every stream sends **`X-Dataset-Total`** before the body. Compare it with what arrived:

```bash
total=$(curl -sD- -o out.ndjson "$API/api/datasets/tools.ndjson" -H "Authorization: Bearer $TOKEN" \
        | grep -i '^x-dataset-total:' | tr -d '\r' | cut -d' ' -f2)
[ "$(wc -l < out.ndjson)" = "$total" ] && echo complete
```

A bulk download that stops early and looks complete is the failure mode this repository has hit most
often. Here the client can check rather than assume.

Read the header precisely: it is the row count **at the moment the export began**, taken by a
separate query from the walk that follows — a tripwire, not a checksum. A mismatch means either a
truncated transfer or a concurrent write, and those are worth telling apart: the keyset walk picks up
rows inserted mid-export and misses rows deleted ahead of it, so on a live repository ±a few is
normal and a large shortfall is not. Nothing takes a snapshot, because that would mean holding a
transaction open for the length of a multi-gigabyte download against a connection pooler this
deployment has already exhausted twice.

---

## 4. Filters

Shared by every route above, and by the catalogue.

| parameter | meaning |
|---|---|
| `workshopIds` | Repeated (`?workshopIds=a&workshopIds=b`) or comma-joined (`?workshopIds=a,b`). **Absent means every workshop.** The reserved word `none` means "not linked to any workshop". |
| `createdBy` | A user id. Narrows to rows that account created — *uploaded*, for media. |
| `createdSince` | ISO-8601. Rows created at or after it. |
| `updatedSince` | ISO-8601. Rows changed at or after it — **for incremental mirrors**. |
| `presign` | `media` only. `true` swaps in time-limited direct download URLs. |
| `includeIdentityNumbers` | Master admin only — see §5. |

`workshopIds` is the repository's one shared scoping vocabulary, so this API narrows identically to
the search page, the map, the completion matrix and the consolidated questionnaire.

Two tables do **not** answer it with a plain column test, because on the live repository the readings
disagree and a column-only predicate returns nothing:

- **artisans** reach a workshop three ways — `Artisan.workshopId`, the `WorkshopArtisan` roster, or
  having sat in an interview taken at that workshop;
- **crafts** reach one two ways — `Craft.workshopId` or the `WorkshopCraft` join.

Both defer to the shared clause builders in `app/services/record_filters.py`.

### Incremental mirror

```bash
since=$(cat .last-run 2>/dev/null || echo 1970-01-01T00:00:00Z)
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for d in workshops crafts artisans products tools processes interviews media; do
  curl -s "$API/api/datasets/$d.ndjson?updatedSince=$since" \
       -H "Authorization: Bearer $TOKEN" >> "$d.ndjson"
done
echo "$now" > .last-run
```

---

## 5. Regulated identity numbers

`aadhaarNumber` and `pehchanCardNumber` are **masked by default** (`XXXX XXXX 9012`), exactly as they
are on every other exported surface. An endpoint that hands over every artisan in one request is the
easiest possible way to take a copy of several thousand identity numbers, so it must not be the one
place in the codebase where they come unmasked as a side effect of being convenient.

To get the raw values you must be a **MASTER_ADMIN** *and* ask by name:

```
GET /api/datasets/artisans.ndjson?includeIdentityNumbers=true
```

An ordinary admin asking gets a `403` that says why — a loud refusal rather than a quiet masked
value, so a pipeline that needs the real numbers cannot silently store the wrong thing.

The `.csv` routes have **no override at all**. A CSV is the format that ends up in a shared drive.

---

## 6. Media bytes

`media.ndjson` rows carry the stored `url` and `objectKey`. Add `presign=true` for a signed,
time-limited direct download URL per row:

```
GET /api/datasets/media.ndjson?presign=true
```

adds to each row:

```json
{ "downloadUrl": "https://…s3…?X-Amz-Signature=…", "downloadUrlExpiresIn": 21600 }
```

Valid for 6 hours — long enough for a mirror job to work through a large media table after reading
the index, short enough that a leaked line out of an `.ndjson` file is not a permanent public link to
a recording. Re-read the index rather than storing these. A row whose object cannot be signed simply
has no `downloadUrl`, which says plainly that this one cannot be fetched.

Bytes come **straight from S3**, never through the API — the web box is a single-worker `t3.micro`.

---

## 7. Operational notes

- **Streaming, always.** `.ndjson` and `.csv` hold one batch (`STREAM_BATCH = 200` rows) in memory at
  a time and start emitting immediately, so a large export cannot exhaust the box or trip
  CloudFront's origin read timeout waiting for a buffered answer.
- **Keyset paging, never OFFSET.** The streams walk `id > last-seen` on an ordered unique column:
  each batch is an index seek regardless of depth, and a concurrent write can only ever be included
  or missed whole — never counted twice. OFFSET paging would make an archival snapshot that silently
  duplicates or drops rows look like a clean one.
- **No `owned_or_granted_where`.** Every caller here is already an admin, for whom that filter
  resolves to the empty filter anyway; spelling it out would suggest a narrowing that is not
  happening. Row selection is the caller's explicit filters and nothing implicit.
- **Adding a dataset** is one entry in `DATASETS` in `app/api/routes/datasets.py`. It gains a paged
  route, an `.ndjson` stream, a `.csv` stream and a catalogue line at once.
- **Route order is load-bearing** in that module: `/{dataset_name}` must stay last, and the
  `.ndjson` / `.csv` routes above it, or `/datasets/token` is read as a dataset named `token`.

## 8. Errors

| status | when |
|---|---|
| `401` | no bearer token, or an expired/invalid one, or bad credentials at `/token` |
| `403` | valid token, wrong scope; or not an admin; or `includeIdentityNumbers` without master admin |
| `404` | unknown dataset name — the message lists the real ones |
| `422` | `.csv` on a dataset the field registry does not describe (`media`) |

Tests: `backend/tests/test_dataset_api.py`.
