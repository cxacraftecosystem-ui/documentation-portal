# Architecture

One API-first backend, used by both the web app and the Android app. PostgreSQL owns structured
records and review state. S3 owns the media binaries, and the clients push bytes to it **directly** —
they never travel through the API.

This document is the map. The territory is split across:

| For | Read |
|---|---|
| What is stored and how it relates | [DATA_MODEL.md](DATA_MODEL.md) |
| Who may do what, and the review state machine | [PERMISSIONS.md](PERMISSIONS.md) |
| How bytes get from a phone into the bucket | [MEDIA_PIPELINE.md](MEDIA_PIPELINE.md) |
| Transport, secrets, PII, open risks | [SECURITY.md](SECURITY.md) |
| Where the latency actually is | [SCALABILITY.md](SCALABILITY.md) |
| Caching and the CloudFront timeout | [CDN.md](CDN.md) |
| Counts: models, endpoints, code volume | [REPO_FACTS.md](REPO_FACTS.md) |

---

## 1. System context

```mermaid
flowchart LR
  researcher([Researcher / field user])
  admin([Reviewer / admin])
  web[Next.js web app<br/>Vercel]
  android[Android app<br/>Kotlin + Compose]
  cf{{CloudFront<br/>dual-stack TLS}}
  api[FastAPI on EC2<br/>behind nginx]
  db[(PostgreSQL<br/>Supabase, other region)]
  s3[(S3 · ap-south-1<br/>dual-stack endpoint)]
  worker[fieldrepo-queue<br/>separate systemd unit]
  stt{{ElevenLabs → Deepgram → Whisper}}
  gem{{Gemini · grid measurement}}

  researcher --> web
  researcher --> android
  admin --> web
  web -->|JWT REST| cf
  android -->|JWT REST| cf
  cf --> api
  api -->|Prisma| db
  web -.->|presigned PUT/GET,<br/>bytes never touch the API| s3
  android -.->|presigned PUT/GET| s3
  api -->|MediaProcessingJob rows| db
  worker -->|claims jobs| db
  worker -->|downloads object| s3
  worker --> stt
  worker --> gem
  worker -->|writes transcript / dimensions| db
```

The dotted lines are the point of the design: a 400 MB video costs the API one presign and one
metadata row, not 400 MB of bandwidth.

---

## 2. The production request path

Worth drawing in full, because the two facts that dominate this system's behaviour are both
invisible in the logical diagram above: **the database is in a different AWS region from the web
box**, and **the box is a single burstable t3.micro**.

```mermaid
flowchart TB
  subgraph client["Client"]
    B[Browser or Android app]
  end
  subgraph edge["AWS edge"]
    CF["CloudFront distribution<br/>d2b34i3e92al6i.cloudfront.net<br/>TLS 1.2+ · IPv4 and IPv6"]
  end
  subgraph ec2["EC2 t3.micro · ap-south-1 · 2 burstable vCPU · 1 GiB"]
    NX["nginx :80<br/>client_max_body_size 200M<br/>proxy_read_timeout 300s"]
    UV["uvicorn :8000 on 127.0.0.1<br/><b>--workers 1</b>"]
    QW["fieldrepo-queue<br/>python -m app.worker"]
    PE[Prisma query engine]
  end
  subgraph remote["Elsewhere"]
    PG[("Supabase PostgreSQL<br/>transaction pooler :6543<br/><b>different region</b>")]
    S3[(S3 bucket)]
  end

  B -->|"HTTPS"| CF
  CF -->|"plaintext HTTP inside AWS — risk P1"| NX
  NX -->|loopback| UV
  UV --> PE
  QW --> PE
  PE -->|"TLS, sslmode=require<br/><b>~200–400 ms per round trip</b>"| PG
  B -.->|presigned, direct| S3
  QW --> S3

  style PG fill:#fff6e0,stroke:#d89a2a,color:#222
  style UV fill:#e8f2ff,stroke:#4a7fd6,color:#222
```

### 2.1 What each hop costs

Measured against live production on 2026-07-27, median of five, from a developer machine in India.
These are **end-to-end** figures including the client's own network, not server timings.

| Endpoint | Median | What it is measuring |
|---|---:|---|
| `GET /health` | **129 ms** | the floor: client network + TLS + CloudFront + nginx + uvicorn, and **no database at all** |
| `GET /health/ready` | 828 ms | the floor **plus one `SELECT 1`** — so a single cross-region round trip is most of 700 ms as seen from here |
| `GET /api/data/tree` | 1,029 ms | |
| `GET /api/media` | 3,074 ms | |
| `GET /api/artisans` | 3,310 ms | 20 rows out of a 16-artisan table |
| `GET /api/tools` | 4,633 ms | 20 rows out of a 74-row table |
| `GET /api/search` | 8,920 ms | |
| `GET /api/dashboard/stats` | 10,567 ms | |

> **Do not quote "154 ms at `/api/health`".** That path does not exist and returns 404 — verified
> again while writing this. The health endpoints are `/health` and `/health/ready`, declared on the
> app rather than on the API router, and therefore **not** under the `/api` prefix.

### 2.2 The finding that explains the table

This is the interesting result, and it is not the one anybody expects.

**There was no classic N+1 in the read routes.** Prisma's query engine already batches a relation
across a page into a single statement — `include={"craft": True}` over twenty artisans issues one
`SELECT … FROM "Craft" WHERE id IN ($1,…,$20)`, not twenty selects.

**The defect was that relations resolved *sequentially*.** Each `include` cost its own round trip,
one awaiting the last. So the cost of a page tracked **the number of relations, not the number of
rows** — which is why twenty rows out of a seventy-four-row table took 3.3 seconds, and why making
the table smaller would have changed nothing.

```mermaid
flowchart LR
  subgraph before["Before — sequential"]
    direction TB
    b1[count] --> b2[page] --> b3[Craft] --> b4[Location] --> b5[MediaFile] --> b6[User] --> b7[ToolArtisan]
  end
  subgraph after["After — waved"]
    direction TB
    a1[count + page<br/>together] --> a2["Craft · Location · MediaFile<br/>· User · ToolArtisan<br/><b>all issued together</b>"]
  end
  before -.->|"same statements,<br/>fewer waits"| after
```

**Statement counts are identical before and after.** Only the waiting changed.

| Endpoint | Sequential waves | After |
|---|---:|---:|
| artisans list | 6 | 3–4 |
| tools list | 8 | 4–5 |
| questionnaire interviews | 12 | 5–6 |
| questionnaire completion | 10 | 5 |

**The real N+1s were in the writes.** A twenty-answer questionnaire PATCH issued **69 statements** —
three round trips per answer — which at production latency is a ~28-second save. It now issues 14.

Measured on an isolated database clone behind a 200 ms-per-statement proxy, **not** on production.
The full analysis, the ranked inventory and the reproduction commands are in
[SCALABILITY.md](SCALABILITY.md).

### 2.3 Why one uvicorn worker

`--workers 1`, and it is not a default nobody changed. With more than one worker uvicorn runs a
supervisor that will `SIGKILL` a busy child; the child's Prisma query engine survives as an orphan
holding pooler connections, and enough orphans exhaust Supabase's client limit and turn every request
into a 500. The queue therefore runs as a **separate systemd unit** (`fieldrepo-queue`,
`python -m app.worker`) rather than as a second web worker, so ffmpeg and transcription never block
HTTP and never share the supervisor.

`MEDIA_QUEUE_WORKER_ENABLED` must be `false` on the web process for exactly that reason. See
[ENVIRONMENT.md](ENVIRONMENT.md).

---

## 3. Backend module flow

```mermaid
flowchart TD
  routes["backend/app/api/routes/*.py<br/>one module per resource"]
  deps["core/deps.py<br/>auth + the six-tier ladder<br/>+ the identity cache"]
  schemas["schemas/*.py<br/>Pydantic, extra=forbid"]
  svc["services/*.py"]
  prisma[Prisma Python client]
  pg[(PostgreSQL)]
  s3[(S3)]

  routes --> deps
  routes --> schemas
  routes --> svc
  routes --> prisma
  svc --> prisma
  svc --> s3
  prisma --> pg

  subgraph services["services/ — what lives where"]
    direction LR
    s1["records.py<br/>relation waves, status policy"]
    s2["access.py · workshop_access.py<br/>grants, rosters, the late gate"]
    s3b["ai.py<br/>the STT chain + failover"]
    s4["media_queue.py<br/>claim, retry, cooldown"]
    s5["s3.py<br/>presign, multipart"]
    s6["managed_secrets.py<br/>Fernet-encrypted keys"]
    s7["record_fields.py<br/>the export field registry"]
    s8["xlsx_report.py · csv_export.py"]
    s9["artisan_identity.py<br/>Aadhaar/Pehchan validate + mask"]
  end
  svc -.-> services
```

Two module-level things worth knowing before reading any route:

- **`APIModel` is `extra="forbid"`.** An unknown key in a payload is a 422, not a 500 from Prisma.
- **Decimal columns are serialised as JSON strings.** Clients must type measurements and costs as
  strings, not numbers. This has emptied a dropdown twice.

---

## 4. Authentication

```mermaid
sequenceDiagram
  autonumber
  participant C as Web / Android
  participant API as FastAPI
  participant Cache as identity cache (in-process)
  participant DB as PostgreSQL

  C->>API: POST /api/auth/login { email, password } or { googleIdToken }
  API->>DB: find user by email
  API->>API: verify bcrypt hash, or verify the Google ID token's audience + signature
  API->>API: sign JWT (HS256, exp = JWT_EXPIRES_MINUTES, default 7 days)
  API-->>C: { accessToken, user }

  C->>API: any request, Authorization: Bearer …
  API->>API: decode + verify, algorithm PINNED, exp REQUIRED
  API->>Cache: resolve_user(sub)
  alt warm (< 5 s)
    Cache-->>API: user row
  else cold
    Cache->>DB: find_unique — ONE query however many requests wait
    DB-->>Cache: user row
  end
  API->>API: rank and capability checks read THIS row, never the token's claims
  API-->>C: response
```

The step that matters for security is the last one. The token carries `email` and `role`, and
**neither is trusted for authorisation**. The database row is re-read because that read *is* the
revocation check: tokens live seven days and are never revoked, so a role claim minted before a
demotion would otherwise stay valid for a week.

The identity cache shortens that revocation window rather than removing it — five seconds by default,
sized to collapse the burst of parallel requests one page load makes and nothing more, with explicit
invalidation on every write that changes a user's authority, and a miss that is never cached so a
deleted account 401s every time. `AUTH_USER_CACHE_ENABLED=false` restores one-query-per-request with
a restart and no deploy.

### 4.1 Google sign-in

```mermaid
sequenceDiagram
  participant W as Web (GSI)
  participant A as Android (Credential Manager)
  participant G as Google Identity
  participant API as FastAPI
  participant DB as PostgreSQL

  W->>G: request ID token with the WEB client id
  A->>G: request ID token with the SAME web client id as server client id
  G-->>W: Google ID token
  G-->>A: Google ID token
  W->>API: POST /api/auth/login { googleIdToken }
  A->>API: POST /api/auth/login { googleIdToken }
  API->>G: verify signature — audience must be GOOGLE_CLIENT_ID or GOOGLE_ANDROID_CLIENT_ID
  G-->>API: verified email, name, avatar
  API->>DB: upsert user, provider GOOGLE, role = DEFAULT_SIGNUP_ROLE
  API-->>W: repository JWT + user
  API-->>A: repository JWT + user
```

A brand-new self-registered Google account lands on `DEFAULT_SIGNUP_ROLE`, which defaults to the
**lowest** tier (`CROWDSOURCE_VOLUNTEER`). An unknown Google account therefore cannot read or write
as a researcher until an admin elevates it.

---

## 5. Field capture

```mermaid
flowchart TD
  ui[Capture UI · web or Android]
  geo["Location: GPS fix, or a MapTiler map pin<br/>PROVENANCE ≠ the subject's stated address"]
  grid["Document using grid:<br/>one top-down photo → length + breadth<br/>one side-on photo → height"]
  files[Images · video · audio · documents]
  split["Android: split long A/V into PART_1, PART_2 …<br/>re-mux at sync frames, no re-encode"]
  eager["Eager pre-upload — bytes start moving<br/>the moment a file is attached"]
  presign[POST /api/media/presign]
  put[Streamed PUT direct to S3]
  complete["POST /api/media/complete<br/>idempotent on objectKey"]
  queue[(MediaProcessingJob)]
  outbox[["No connection?<br/>the save goes to the offline outbox"]]

  ui --> geo
  ui --> grid
  ui --> files
  files --> split --> eager
  grid -->|POST /media/analyze-measurement<br/>synchronous, Gemini| ui
  eager --> presign --> put --> complete
  complete --> queue
  ui --> outbox
  outbox -.->|network returns| complete

  style outbox fill:#fff6e0,stroke:#d89a2a,color:#222
```

Long audio and video captured on Android is split by **re-muxing at sync frames — no re-encoding** —
into `PART_1`, `PART_2`, … so each segment stays inside the transcription and upload limits and
uploads as its own media file. Uploads stream straight from the content URI and are never buffered
whole, so a multi-hundred-megabyte video cannot exhaust the device heap.

Every tactic in that diagram, and the several the web has that Android does not, is documented in
[MEDIA_PIPELINE.md](MEDIA_PIPELINE.md).

---

## 6. Transcription: three providers, with failover

Not Whisper. Whisper is the **third** provider, and on a deployment with an ElevenLabs key it is
usually never called.

```mermaid
flowchart TD
  job["MediaProcessingJob<br/>TRANSCRIPTION, QUEUED"] --> claim{"worker claims it<br/>single elected worker"}
  claim --> chain["transcription_provider_chain()<br/>master admin's ranking,<br/>minus every provider with no key"]
  chain --> p1["1 · ElevenLabs Scribe<br/>≤ ~1 GB · auto language · scribe_v2"]

  p1 -->|text| done[["write transcript<br/>+ formatted dialogue"]]
  p1 -->|"429 / 503"| rl1[[remember: throttled]]
  p1 -->|"401 / 403"| hf1[[remember: key rejected]]
  p1 -->|"5xx, size, transport"| hf1
  p1 -->|empty| e1[[remember: empty]]

  rl1 --> p2
  hf1 --> p2
  e1 --> p2
  p2["2 · Deepgram Nova-3<br/>≤ 2 GB · language=multi<br/>code-switched Hindi + English"]

  p2 -->|text| done
  p2 -->|fails / throttled / empty| p3
  p3["3 · Whisper (OpenAI)<br/>chunks above 24 MB, stitches"]
  p3 -->|text| done
  p3 -->|nothing| resolve{"nothing returned text —<br/>what happened?"}

  resolve -->|"a definitive EMPTY"| empty["EMPTY · the clip is silent · done"]
  resolve -->|"PURE throttle,<br/>no hard failures"| rate["RATE_LIMITED · requeue<br/><b>without spending an attempt</b><br/>behind a growing cooldown"]
  resolve -->|"throttle + hard failures"| failed["FAILED · normal retry/backoff,<br/>terminates after maxAttempts"]

  style rate fill:#fff6e0,stroke:#d89a2a,color:#222
  style failed fill:#fdecec,stroke:#c33,color:#222
  style done fill:#eaf7ee,stroke:#3a9a5c,color:#222
```

Three distinctions in that diagram are the whole design, and they are genuinely different failures:

| Response | Read as | Queue behaviour |
|---|---|---|
| `401` / `403` | the key is wrong or revoked — every retry will be rejected identically | **hard failure**, but the message names the key an admin must fix, not an HTTP status |
| `429` / `503` | "come back later", in the two ways a provider says it | **`RATE_LIMITED`** — requeued **without consuming an attempt**, behind a growing cooldown, so a throttled clip is still transcribed eventually |
| `5xx` | the provider broke on *this* request | hard failure; the normal attempt budget applies, so a permanently broken clip terminates |

An **empty** result is kept as a fallback but the next provider still gets a chance, because a codec
or language one engine cannot decode is sometimes fine on another.

Ordering is a master-admin setting (`AppSetting.sttProviderOrder`, edited in the Settings hub and on
Android). Ranking expresses a **preference, not a requirement**: a provider whose key is unset is
dropped wherever it sits, so promoting Deepgram on a deployment with no Deepgram key does not stop
transcription. Keys resolve through the managed-secret layer, so adding a key in the UI extends the
chain immediately, with no restart.

Transcription also runs on **idle time**: outside the off-peak window the worker still transcribes
when the one-minute load average is below a fraction of the CPU count, so spare daytime capacity on a
burstable instance is used rather than wasted. Measurement jobs keep flowing during a transcription
cooldown — they are lighter and hit a different provider.

**Refinement is separate from transcription.** `OPENAI_API_KEY`'s primary job is rewriting a raw
transcript into clean interviewer/interviewee dialogue and translating it, per the master-admin
`transcriptionMode` setting (`RAW` / `REFINED` / `REFINED_TRANSLATED`). It only *transcribes* when it
is reached as the third link in the chain.

---

## 7. The offline outbox

Both clients can complete a save with no connection at all. They do it differently, and the
difference is deliberate.

```mermaid
stateDiagram-v2
  direction LR
  [*] --> Attempt: user presses Save
  Attempt --> Online: connection validated
  Attempt --> Queued: no connection
  Online --> [*]: saved normally

  Queued --> Replaying: network returns (online event / Sync now)
  Replaying --> Created: create succeeded — <b>written back immediately</b>
  Created --> Uploading: upload each media batch
  Uploading --> Uploading: each batch marked done as it lands
  Uploading --> [*]: entry deleted, local copies dropped

  Replaying --> Queued: transient (offline, 5xx, 408, 429)
  Uploading --> Created: transient — resume at the media, <b>never re-create</b>
  Replaying --> Permanent: 4xx validation or permission
  Replaying --> Conflict: <b>409</b>
  Permanent --> [*]: shown with the server's reason, user discards
  Conflict --> [*]: shown as a conflict — <b>nothing is deleted</b>

  note right of Created
    "created / createdId / uploadedBatches"
    are written back per step. Without this,
    a pass that died during the media upload
    re-created the record on every retry —
    once per sync pass, for as long as the
    signal stayed bad.
  end note
```

**The two clients triage failures differently, on purpose.** Android's outbox stops at the first
failure, which is right for a connection that dropped again. The web's does not, because one 422 at
the head of the queue would block every entry behind it forever with nothing on screen to say why —
so a permanent failure marks *that* entry and the pass carries on.

**A 409 is never treated as "already saved."** It used to be, and the entry and its photographs were
deleted as sent. No endpoint in this API means that by 409: from `/artisans` it is a clashing Aadhaar
number, from `/crafts` a craft of that name, from `/questionnaire/interviews` an interview that
already exists for that exact artisan set. So the one answer meaning "someone else's record collides
with yours" was destroying the record *and* the attachments *and* reporting success. The lost-response
case it was aiming at is now covered properly by `created`, which knows rather than guesses.

Storage: IndexedDB on the web (`File` objects survive by structured clone), a file plus a `Mutex` on
Android. Media is stored as a **list of batches**, not one lump, because a product queues its two
measurement-grid photos each with the caption naming its dimension, and for a grid photo the caption
is the only thing recording which dimension it measures.

---

## 8. The workshop hierarchy

Everything a researcher records is scoped to a workshop, and that scoping is what the Data Browser,
the export and the permission checks all read.

```mermaid
flowchart TD
  W["<b>Workshop</b><br/>title · place · start/end dates<br/><i>the container everything drops into</i>"]

  W --> WA["WorkshopAssignment<br/><i>who may work here</i><br/>VIEW &lt; CONTRIBUTE &lt; EDIT"]
  W --> WC["Crafts covered<br/><i>WorkshopCraft join + direct FK</i>"]
  W --> WR["Artisan roster<br/><i>WorkshopArtisan join + direct FK</i>"]

  WC --> C["<b>Craft</b><br/>shared vocabulary · name UNIQUE<br/><i>no status — never reviewed</i>"]
  WR --> A
  C --> A["<b>Artisan</b><br/>the anchor of the dataset<br/>Aadhaar UNIQUE = dedup key<br/>Do's and Don'ts REQUIRED"]

  A --> P["<b>Product</b><br/>dimensions · cost · demand"]
  A --> T["<b>Tool</b><br/>material · maker · replacement cost"]
  A --> Q["<b>Questionnaire interview</b><br/>one per EXACT artisan set"]
  P --> PR["<b>Process</b><br/>ordered ProcessSteps<br/>SEQUENTIAL or GROUP"]
  T -.->|ToolArtisan join| A

  P --> M[(Media)]
  T --> M
  PR --> M
  Q --> M
  A --> M
  MM["Miscellaneous Media<br/><i>polymorphic link to anything</i>"] --> M

  W --> TK["AssignedTask<br/><i>what someone was asked to record</i>"]

  style W fill:#e2ecff,stroke:#3a6fd0,color:#222
  style A fill:#eaf7ee,stroke:#3a9a5c,color:#222
```

Two consequences of that shape a newcomer will hit:

- **A record created outside its workshop's dates is flagged and pinned** to `PENDING`, and only an
  admin can approve it. The full gate, including its three bypasses, is in
  [PERMISSIONS.md §3.3](PERMISSIONS.md).
- **`Craft` has no status column**, because it is vocabulary rather than a submission. It is
  therefore never reviewed, never pinned, and managed by rank alone (Professor and above).

---

## 9. Record lifecycle

Summarised here; the authoritative version, with who may make each transition, is
[PERMISSIONS.md §3](PERMISSIONS.md).

```mermaid
stateDiagram-v2
  direction LR
  [*] --> DRAFT: Professor+ chooses Draft
  [*] --> PENDING: everyone below Professor<br/>(status chip locked)
  DRAFT --> PENDING: submit
  PENDING --> APPROVED: approve
  PENDING --> REJECTED: reject
  PENDING --> NEEDS_REVISION: send back, comments mandatory
  NEEDS_REVISION --> PENDING: the creator's edit IS the resubmission
  REJECTED --> PENDING: edit and resubmit
  APPROVED --> [*]
```

`NEEDS_REVISION` is a real state and has been for some time; a diagram without it is wrong, and
earlier versions of this file were.

---

## 10. Deployment shapes

### 10.1 Production, today

```mermaid
flowchart TB
  subgraph vercel["Vercel"]
    NX2["Next.js 16 App Router<br/>static prerender · no route handlers<br/>no server actions · no fs"]
  end
  subgraph aws["AWS ap-south-1"]
    CF2[CloudFront]
    E["EC2 t3.micro<br/>nginx + uvicorn(1) + fieldrepo-queue"]
    S3B[(S3 media bucket)]
  end
  subgraph sb["Supabase — different region"]
    PG2[(PostgreSQL<br/>session pooler :5432 for migrations<br/>transaction pooler :6543 at runtime)]
  end
  GH["GitHub Actions<br/>backend → frontend → Android"]

  NX2 --> CF2 --> E --> PG2
  NX2 -.-> S3B
  E --> S3B
  GH -->|rsync + migrate + restart| E
  GH -->|vercel deploy --prebuilt| NX2
```

Alternative shapes are documented and, in the case of Kubernetes, partially validated:
[DOCKER.md](DOCKER.md) for containers, [KUBERNETES.md](KUBERNETES.md) for a cluster,
[SCALABILITY.md](SCALABILITY.md) for what would break first at each size.

### 10.2 Local development

```mermaid
flowchart LR
  subgraph compose["docker compose up -d"]
    pg[PostgreSQL<br/>host :55432 → container :5432]
    minio[MinIO<br/>:9000 API · :9001 console]
    bucket[[one-shot: create bucket<br/>field-repository]]
  end
  next["Next.js :3000"]
  fastapi["uvicorn :8000<br/>MEDIA_QUEUE_WORKER_ENABLED=true"]
  kotlin["Android emulator<br/>apiBaseUrl=http://10.0.2.2:8000/api/"]

  next --> fastapi
  kotlin --> fastapi
  fastapi --> pg
  fastapi --> minio
```

Two local-only gotchas: set `AWS_S3_SSE_ALGORITHM=` (empty) or MinIO rejects multipart creates with
`NotImplemented`, and leave the queue worker **on** locally — the split into a separate service is a
production concern.

---

## How this document is kept true

| Claim class | Kept true by |
|---|---|
| Every path and file reference | `node docs/tools/check-docs.mjs` resolves them; the run fails on a path that no longer exists. |
| Counts (models, endpoints, code volume) | Not stated here. Generated into [REPO_FACTS.md](REPO_FACTS.md). |
| §2.1 latency table | Re-measure, do not trust. `for u in /health /health/ready /api/artisans; do curl -s -o /dev/null -w "$u %{time_total}\n" https://d2b34i3e92al6i.cloudfront.net$u; done` — take a median of five and **re-date the table**. Numbers with no date are the ones that mislead. |
| §2.2 the sequential-waves finding | [SCALABILITY.md](SCALABILITY.md) §1 and §3, and the long comment above `load_relations` in `backend/app/services/records.py`. Re-derive wave counts with the proxy method described in SCALABILITY.md §13. |
| §2.3 one uvicorn worker | `infra/terraform/user_data.sh` — the `ExecStart` line and the comment above it. |
| §4 authentication | `backend/app/core/deps.py` (`get_current_user`, `resolve_user`) and `backend/app/core/security.py`. |
| §6 the provider chain | `backend/app/services/ai.py` (`transcription_provider_chain`, `_transcribe_sync`, `_AUTH_STATUSES`, `_DEFER_STATUSES`) and `backend/app/services/media_queue.py`. `backend/tests/test_stt_providers.py` covers the failover cases. Default order is generated into [REPO_FACTS.md](REPO_FACTS.md). |
| §7 the offline outbox | `frontend/lib/offline.ts` (module docstring) and `android/app/src/main/java/com/fieldrepository/app/data/Offline.kt`. |
| §8–§9 hierarchy and lifecycle | `backend/prisma/schema.prisma`; the authority for transitions is [PERMISSIONS.md](PERMISSIONS.md). |
| §10 deployment | `infra/terraform/user_data.sh`, `.github/workflows/*.yml`, `docker-compose.yml`. |

**Review triggers:** a new migration, a change to `backend/app/services/ai.py` or `records.py`, a
change to `infra/terraform/user_data.sh`, or any new top-level `backend/app/` package.

**Known unverified:** the CloudFront distribution's cache policy and origin timeout are console
settings this repository cannot read. §2 draws them as [CDN.md](CDN.md) describes them; confirm in
the console rather than from this diagram.
