# Architecture

This repository is designed around one API-first backend used by both the web app and the Android app. PostgreSQL owns structured records and review state. S3-compatible object storage owns large media binaries.

## System Context

```mermaid
flowchart LR
  researcher[Researcher / Field User]
  admin[Admin Reviewer]
  web[Next.js Web App]
  android[Android Kotlin App]
  api[FastAPI REST API]
  db[(PostgreSQL)]
  s3[(S3-Compatible Storage)]
  queue[(MediaProcessingJob queue)]

  researcher --> web
  researcher --> android
  admin --> web
  web -->|JWT REST calls| api
  android -->|JWT REST calls| api
  api -->|Prisma ORM| db
  api --> queue
  api -->|Presigned URLs| s3
  web -->|Direct PUT upload| s3
  android -->|Direct PUT upload| s3
```

## Backend Module Flow

```mermaid
flowchart TD
  routes[FastAPI routes]
  deps[Auth and role dependencies]
  schemas[Pydantic validation schemas]
  services[Services: pagination, S3, CSV, records, media queue]
  prisma[Prisma Python client]
  postgres[(PostgreSQL)]
  storage[S3-compatible object storage]

  routes --> deps
  routes --> schemas
  routes --> services
  routes --> prisma
  services --> prisma
  services --> storage
  prisma --> postgres
```

## Authentication Flow

```mermaid
sequenceDiagram
  participant Client as Web / Android
  participant API as FastAPI
  participant DB as PostgreSQL

  Client->>API: POST /api/auth/login
  API->>DB: Find user by email
  DB-->>API: User + password hash
  API->>API: Verify password and sign JWT
  API-->>Client: accessToken + user
  Client->>API: GET /api/me with Bearer token
  API->>API: Decode JWT
  API->>DB: Load current user
  DB-->>API: User
  API-->>Client: User profile
```

## Google OAuth Flow

```mermaid
sequenceDiagram
  participant Web as Next.js Web
  participant Android as Android App
  participant Google as Google Identity
  participant API as FastAPI
  participant DB as PostgreSQL / Supabase Postgres

  Web->>Google: Request ID token with web OAuth client ID
  Android->>Google: Credential Manager request with same server client ID
  Google-->>Web: Google ID token
  Google-->>Android: Google ID token
  Web->>API: POST /api/auth/login { googleIdToken }
  Android->>API: POST /api/auth/login { googleIdToken }
  API->>Google: Verify token audience and signature
  Google-->>API: Verified email, name, avatar
  API->>DB: Upsert or update user with GOOGLE provider
  DB-->>API: User record
  API-->>Web: Repository JWT + user
  API-->>Android: Repository JWT + user
```

## Field Capture Flow

```mermaid
flowchart TD
  client[Web / Android capture UI]
  geo[Precise GPS or MapTiler map point]
  media[Images / Video / Audio / Documents]
  split[Android: split long audio/video into PART_1, PART_2 ...]
  grid[Document-using-grid: 1 photo to length+breadth, 1 photo to height]
  api[FastAPI media API]
  queue[Durable MediaProcessingJob queue]
  worker[FastAPI background worker]
  openai[OpenAI Whisper transcription]
  gemini[Gemini grid measurement]
  s3[(S3-compatible object storage)]
  db[(PostgreSQL)]

  client --> geo
  client --> media
  client --> grid
  grid -->|POST /media/analyze-measurement| api
  media --> split
  split -->|each part within processing limits| client
  client -->|POST /media/presign| api
  api -->|signed PUT URL| client
  client -->|streamed PUT uploads, resilient + retried| s3
  client -->|POST /media/complete| api
  api -->|store media metadata| db
  api -->|enqueue transcription / measurement| queue
  queue --> worker
  worker -->|download object| s3
  worker -->|audio jobs| openai
  worker -->|measurement jobs| gemini
  worker -->|patch transcript / dimensions| db
```

Long audio/video captured on Android is split (by re-muxing at sync frames, no re-encoding) into
`PART_1`, `PART_2`, … so each segment stays under the transcription/upload limits and uploads as its own
media file. Uploads stream straight from the content URI (never buffered fully in memory) so multi-hundred-MB
videos never exhaust the device heap. "Document using grid" reads length **and** breadth from a single
top-down photo and height from a separate side-on photo, auto-filling the measurement fields.

## Permission Model

```mermaid
flowchart LR
  master[MASTER_ADMIN from MASTER_ADMIN_EMAIL]
  admin[ADMIN junior admin]
  researcher[RESEARCHER]
  read[Read all records]
  own[Edit own repository records]
  qedit[Edit questionnaire interviews]
  delete[Delete records]
  users[Manage users]

  master --> read
  master --> own
  master --> qedit
  master --> delete
  master --> users
  admin --> read
  admin --> own
  admin --> qedit
  admin --> delete
  admin --> users
  researcher --> read
  researcher --> own
  researcher --> qedit
```

## Questionnaire Model

```mermaid
erDiagram
  QuestionnaireQuestion ||--o{ QuestionnaireResponse : asks
  QuestionnaireInterview ||--o{ QuestionnaireResponse : contains
  QuestionnaireInterview ||--o{ QuestionnaireInterviewArtisan : links
  Artisan ||--o{ QuestionnaireInterviewArtisan : participates
  User ||--o{ QuestionnaireInterview : creates
  User ||--o{ QuestionnaireResponse : answers
  QuestionnaireInterview ||--o{ MediaFile : has
```

## Signed Media Upload Flow

```mermaid
sequenceDiagram
  participant Client as Web / Android
  participant API as FastAPI
  participant S3 as S3-compatible storage
  participant DB as PostgreSQL
  participant Queue as MediaProcessingJob queue
  participant Worker as Backend worker
  participant AI as Whisper / Gemini

  Client->>Client: Split long audio/video into PART_n (Android)
  Client->>API: POST /api/media/presign (per file / per part)
  API->>S3: Generate presigned PUT URL
  API-->>Client: uploadUrl + objectKey
  Client->>S3: Streamed PUT (64 KB chunks, retried on transient errors)
  S3-->>Client: 200 OK
  Client->>API: POST /api/media/complete
  API->>DB: Store metadata and record links
  API->>Queue: Create durable processing job when requested
  DB-->>API: MediaFile
  API-->>Client: Media metadata
  Worker->>Queue: Claim queued job
  Worker->>S3: Download stored object
  Worker->>AI: Process audio or grid image
  Worker->>DB: Patch transcript / measurement state
  Worker->>Queue: Complete or schedule retry
```

## Record Lifecycle

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> PENDING: submit
  PENDING --> APPROVED: admin approve
  PENDING --> REJECTED: admin reject
  REJECTED --> PENDING: edit and resubmit
  APPROVED --> [*]
```

## Core Data Relationships

```mermaid
erDiagram
  User ||--o{ Artisan : creates
  User ||--o{ ProductDocumentation : creates
  User ||--o{ ToolDocumentation : creates
  User ||--o{ MediaFile : uploads
  User ||--o{ MediaProcessingJob : requests
  MediaFile ||--o{ MediaProcessingJob : queues
  Craft ||--o{ Artisan : classifies
  Craft ||--o{ ProductDocumentation : links
  Craft ||--o{ ToolDocumentation : links
  Workshop ||--o{ ProductDocumentation : contextualizes
  Workshop ||--o{ ToolDocumentation : contextualizes
  Artisan ||--o{ ProductDocumentation : documents
  Artisan ||--o{ ToolDocumentation : documents
  ProductDocumentation ||--o{ Process : "is made by"
  Process ||--o{ ProcessStep : "has ordered"
  Process ||--o{ MediaFile : "pre-process clips"
  ProcessStep ||--o{ MediaFile : "step media"
  ToolDocumentation ||--o{ ToolArtisan : "assigned via"
  Artisan ||--o{ ToolArtisan : "uses (many-to-many)"
  ProductDocumentation ||--o{ MediaFile : has
  ToolDocumentation ||--o{ MediaFile : has
  Location ||--o{ MediaFile : geotags
```

`ProcessStep` carries an optional `notes` field ("record additional information"). `ToolArtisan` is a join
table that lets one documented tool be assigned to many artisans across the same or different crafts
(so the tool is entered once, not re-entered per craft). Decimal columns (measurements, costs) are
serialized to JSON **strings** by the API; clients parse them as strings to avoid type-mismatch failures.

## Process Documentation

A process records how a product is made, step by step. Each process is tied to one product (chosen via an
artisan → product cascade), can carry pre-process media, and has ordered steps (sequential or grouped). Each
step can attach media and optional free-text "additional information" notes.

```mermaid
erDiagram
  ProductDocumentation ||--o{ Process : "is made by"
  Process ||--o{ ProcessStep : "ordered steps"
  Process ||--o{ MediaFile : "pre-process clips"
  ProcessStep ||--o{ MediaFile : "step media (PART_n for long clips)"
  ProcessStep {
    string name
    enum   stepType "SEQUENTIAL | GROUP"
    int    sortOrder
    string notes "optional: record additional information"
  }
```

## Tool to Artisan Assignment (many-to-many)

The same documented tool often recurs across crafts. Instead of re-entering it per craft, the Tools page can
assign one tool to many artisans (across the same or different crafts) through the `ToolArtisan` join table.

```mermaid
flowchart LR
  toolPick[Pick a documented tool]
  crafts[Multi-select crafts]
  artisans[Multi-select artisans of those crafts]
  assign[POST /api/tools/:id/artisans]
  join[(ToolArtisan join rows)]

  toolPick --> assign
  crafts --> artisans
  artisans --> assign
  assign --> join
  join -->|GET /api/tools/:id/artisans| toolPick
```

## Deployment Shape

```mermaid
flowchart TB
  subgraph Local["Local development"]
    compose[Docker Compose]
    pg[PostgreSQL host :55432 / container :5432]
    minio[MinIO :9000/:9001]
    compose --> pg
    compose --> minio
  end

  subgraph Apps["Application processes"]
    next[Next.js :3000]
    fastapi[FastAPI :8000]
    kotlin[Android emulator/device]
  end

  next --> fastapi
  kotlin --> fastapi
  fastapi --> pg
  fastapi --> minio
```

## Supabase Postgres Shape

```mermaid
flowchart LR
  web[Next.js Web]
  android[Android App]
  api[FastAPI REST API]
  supabase[(Supabase Postgres)]
  storage[(S3-compatible media storage)]
  queue[(MediaProcessingJob)]

  web -->|REST + JWT| api
  android -->|REST + JWT| api
  api -->|DATABASE_URL / Prisma| supabase
  api -->|Presign and metadata| storage
  api --> queue
  queue --> supabase
```
