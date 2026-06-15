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

  researcher --> web
  researcher --> android
  admin --> web
  web -->|JWT REST calls| api
  android -->|JWT REST calls| api
  api -->|Prisma ORM| db
  api -->|Presigned URLs| s3
  web -->|Direct PUT upload| s3
  android -->|Direct PUT upload planned| s3
```

## Backend Module Flow

```mermaid
flowchart TD
  routes[FastAPI routes]
  deps[Auth and role dependencies]
  schemas[Pydantic validation schemas]
  services[Services: pagination, S3, CSV, records]
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

## Signed Media Upload Flow

```mermaid
sequenceDiagram
  participant Client as Web / Android
  participant API as FastAPI
  participant S3 as S3-compatible storage
  participant DB as PostgreSQL

  Client->>API: POST /api/media/presign
  API->>S3: Generate presigned PUT URL
  API-->>Client: uploadUrl + objectKey
  Client->>S3: PUT binary file directly
  S3-->>Client: 200 OK
  Client->>API: POST /api/media/complete
  API->>DB: Store metadata and record links
  DB-->>API: MediaFile
  API-->>Client: Media metadata
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
  Craft ||--o{ Artisan : classifies
  Craft ||--o{ ProductDocumentation : links
  Craft ||--o{ ToolDocumentation : links
  Workshop ||--o{ ProductDocumentation : contextualizes
  Workshop ||--o{ ToolDocumentation : contextualizes
  Artisan ||--o{ ProductDocumentation : documents
  Artisan ||--o{ ToolDocumentation : documents
  ProductDocumentation ||--o{ MediaFile : has
  ToolDocumentation ||--o{ MediaFile : has
  Location ||--o{ MediaFile : geotags
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

  web -->|REST + JWT| api
  android -->|REST + JWT| api
  api -->|DATABASE_URL / Prisma| supabase
  api -->|Presign and metadata| storage
```
