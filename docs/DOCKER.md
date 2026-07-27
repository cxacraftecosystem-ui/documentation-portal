# Docker

Containers for local development: the two application images, and the profile-gated Compose
stack that runs them next to PostgreSQL, MinIO and (optionally) Redis.

Production does **not** use any of this. The API runs from a systemd unit on a single EC2
t3.micro behind nginx behind CloudFront (`docs/ARCHITECTURE.md`), and the web app is built by
Vercel's own pipeline (`docs/DEPLOYMENT_VERCEL.md`). These images exist so the whole stack can be
brought up on one machine, and so there is a tested answer ready the day either of those two
hosts has to change.

---

## The one rule

**`backend/.env` is the production environment. It must never reach a container.**

It is not a template — it is the live configuration, holding the Supabase pooler URL, the real JWT
signing secret, and the AWS keys for the bucket with every researcher's uploaded media. A
container started on those values would be reading and writing real records from a sandbox nobody
is monitoring.

```bash
docker compose run --rm --env-file backend/.env api      # never
docker compose --env-file backend/.env up                # never
```

Three things stand in the way. Know all three, because a guard you cannot name is one you will
eventually work around:

| # | Guard | Catches |
|---|-------|---------|
| 1 | `.dockerignore` excludes `**/.env` from the build context | A `COPY` baking credentials into an image layer, where `docker history` would show them |
| 2 | `docker/backend/entrypoint.sh` parses `DATABASE_URL` before `uvicorn` is exec'd and refuses a remote host | Exactly the two commands above |
| 3 | Compose passes only the variables named in `docker-compose.yml` | A stray value in an unintended file silently joining the environment |

Guard 2 is the one that actually stops the accident, and it refuses rather than warns — a warning
scrolls past. Its escape hatch (`ALLOW_REMOTE_DATABASE=yes-i-mean-it`) is deliberately something
you have to type out, and it exists for one case: a read-only look at a remote *staging* database.
If you ever use it, leave `MEDIA_QUEUE_WORKER_ENABLED=false`, or a second worker starts draining
the live media queue in parallel with the real one.

Local values live in `.env.docker.example`.

---

## Profiles

The default is a contract. `docker compose up` starts what it has always started, and it does not
build an image or run a Redis you did not ask for.

| Command | Services |
|---|---|
| `docker compose up` | `postgres`, `minio`, `create-bucket` |
| `docker compose --profile api up` | ... + `api` |
| `docker compose --profile web up` | ... + `api`, `web` |
| `docker compose --profile cache up` | ... + `redis` |
| `docker compose --profile full up` | everything that runs |
| `docker compose --profile migrate run --rm migrate` | one-shot; see [Running it](#running-it) |

Profiles compose, so `--profile api --profile cache` is the API next to a Redis.

`api` belongs to the `web` profile as well as its own. That is not a convenience: Compose treats a
`depends_on` pointing at a service no active profile enabled as a hard configuration error, so
`--profile web` on its own would refuse to start at all. Sharing the profile makes it mean "the
web app, and the thing it talks to".

To confirm the default has not drifted:

```bash
docker compose config --services            # postgres, minio, create-bucket. Nothing else.
```

### Startup order

`depends_on` conditions, so the stack settles instead of racing:

```
postgres  (healthy: pg_isready)  ─┐
minio     (healthy: mc ready)    ─┼─→ api (healthy: /health) ──→ web
create-bucket (exited 0)         ─┘
```

`create-bucket` is waited on with `service_completed_successfully`, not `service_started` — MinIO
being healthy says nothing about whether the bucket exists, and the first media write would 404
against a bucket that does not.

`web` waits for the API to be **healthy**, not merely started: server components render on the
first request, and an API still spawning its Prisma engine turns that into an error page.

Nothing waits on `redis`. That is deliberate, and it is the subject of the next section.

---

## Redis is inert

`--profile cache` starts a Redis. On its own, that changes nothing.

The optional scale layer under `backend/app/scale` is off by default. With no new variables set,
the API never opens a socket to Redis, never imports a Redis client, and declares no dependency on
the container. A `depends_on: redis` would have made the API's startup contingent on an optional
component — the exact coupling the flag design exists to prevent — so there isn't one.

To actually use it, set all three together (they are commented out in `.env.docker.example`):

```bash
SCALE_CACHE_ENABLED=true
SCALE_CACHE_BACKEND=redis
SCALE_REDIS_URL=redis://redis:6379/0
```

A backend of `redis` with no URL, or a URL with the cache switched off, is a configuration that
looks enabled and is not. If the flags are on and Redis is unreachable, the API logs it once and
serves from its in-process fallback rather than erroring.

The URL depends on where you are standing: `redis://redis:6379/0` from inside the compose network,
`redis://localhost:56379/0` from a shell on your machine. The published port follows the same
convention as PostgreSQL's `55432` — offset so it cannot collide with a Redis you already run.

The container holds no volume. A cache with a volume invites being treated as a store of record.
It is capped at 128 MB with `allkeys-lru`, and RDB/AOF persistence is switched off: the
fork-to-snapshot briefly doubles the resident set, which is the one behaviour a 1 GiB production
box cannot absorb, so it is not rehearsed here either.

---

## The images

Both build **from the repository root**, not from their own directory — the backend image needs
`docker/backend/entrypoint.sh` as well as `backend/`, and Docker only reads the `.dockerignore` at
the root of the context. There is one, at the root, governing both builds.

```bash
docker build -f backend/Dockerfile  -t field-repository-backend:local  .
docker build -f frontend/Dockerfile -t field-repository-frontend:local .
```

Both are multi-stage and both run as a non-root user. Neither final image contains a compiler,
a package manager's build toolchain, or the source of the stage that produced it.

### Backend

Three stages: `builder` (pip, a C toolchain), `prisma` (adds Node.js), `runtime` (neither).

The `prisma` stage is what makes the image work at all, and it is the part worth understanding
before changing anything. `prisma-client-py` ships **no generated code** — the `prisma` package on
PyPI is a generator, and `from prisma import Prisma` raises *"Client hasn't been generated yet"*
until `prisma generate` has written the models into `site-packages/prisma/`. That generator is a
Node program. So Node has to exist somewhere, and this stage is the somewhere; it is discarded
afterwards. At run time the client talks to a standalone **Rust** query engine, not to Node, which
is why the runtime image needs the engine binary and not a JavaScript runtime.

Two details in that stage exist to stop the image being subtly broken rather than obviously so:

- `PRISMA_BINARY_CACHE_DIR` and `PRISMA_HOME_DIR` are pinned **in the build stage**. Left at their
  defaults the engine lands under `$HOME/.cache`, and `$HOME` differs between the root user that
  builds and the unprivileged user that runs — so the engine would be re-downloaded on first
  request, inside a container with no compiler, no guaranteed network and no write access to that
  path.
- The stage ends with `python -c "from prisma import Prisma"`. A generation failure becomes a
  build failure there, rather than a container that starts and 500s on its first query.
- Only the query engine crosses into the runtime stage. `prisma py fetch` leaves an **81 MB npm
  tree** behind — the CLI, its JavaScript build output, the schema engine, and the same 18 MB Rust
  binary stored twice under two package names. Copying that wholesale would ship Node's payload
  into an image with no Node to run it. The build locates the one binary (by search, not by a
  literal path — the filename carries the platform triple, so a base-image bump to a different
  OpenSSL would silently break a hard-coded one), proves it executes standalone with `--version`,
  and the runtime points `PRISMA_QUERY_ENGINE_BINARY` at it. That variable is checked *first* by
  `prisma-client-py`, ahead of the working directory, the generated client's embedded paths and
  the binary cache — and a wrong path raises `BinaryNotFoundError` naming it, rather than
  attempting the silent re-download an unpinned cache directory invites.

Dependencies are installed from `[project.dependencies]` only, read out of `pyproject.toml` with
`tomllib`. Optional extras are deliberately not installed: the image is meant to weigh what the
t3.micro weighs. It installs the *dependencies* rather than the project, because `pip install .`
would also copy an `app` package into site-packages without
`app/data/questionnaire_questions.json` — which is not declared as package data — and the
questionnaire seed would fail at run time. The source is copied in as a plain directory instead,
which cannot lose a file.

`ffmpeg` is **not** installed by default. It costs ~180 MB and nothing on the request path needs
it: `pydub` is imported lazily inside the two functions that convert audio, and both degrade with
a clear message when it is absent. For local transcription work:

```bash
docker compose build --build-arg INSTALL_FFMPEG=true api
```

The `HEALTHCHECK` probes **`/health`**, not `/api/health`. The health routes are registered
directly on the FastAPI app, outside the router carrying the `/api` prefix, and nginx proxies `/`
straight through in production — `/api/health` has never existed and returns 404. Probing it would
mark a perfectly healthy container unhealthy forever. `/health` is also the right choice over
`/health/ready`: it answers "is this process serving?" without touching the database, whereas
`/health/ready` 503s whenever the DB is unreachable. A container is not broken because PostgreSQL
is still starting, and marking it unhealthy would take the API down instead of letting the
built-in watchdog reconnect. Point an uptime monitor at `/health/ready`; point Docker at
`/health`. The probe uses `urllib` rather than `curl`, which would be 4 MB of image existing
solely to answer it.

The command is `--workers 1`, always. `--workers 2` puts a supervisor in front of uvicorn that
SIGKILLs a worker failing to answer its ping; on a CPU-starved box a slow request starved that
ping, the worker died mid-flight, and its Prisma query engine was orphaned rather than reaped.
Enough orphans exhausted the connection pooler and every database call started returning 500 while
`/health` stayed green. Scale with replicas, never with `--workers`.

There is no `restart:` policy on the service. The entrypoint's job is to refuse loudly, and a
restart loop would scroll that refusal off the screen every few seconds. A container that stays
dead is one whose logs you read.

### Frontend

Three stages: `deps` (`npm ci` against the lockfile alone), `builder`, `runtime`.

`output: "standalone"` is switched on **in the Dockerfile**, not in `next.config.ts`. Vercel's
build errors out when it is set, and Vercel is the real deployment target — so the container turns
it on for the container build only, by rewriting `next.config.ts` into a wrapper that re-exports
the committed config with the one field added. Every redirect, header and image-host rule stays in
force and there is exactly one place they are maintained; a second full config would drift the
moment somebody added a security header to the real one.

Standalone output is what keeps the runtime stage thin: `.next/standalone` contains the server plus
a traced copy of only the `node_modules` actually reached at run time, so the runtime never runs
`npm install` and never carries the build's full dependency tree. It runs `node server.js` —
`next start` is not available, because the Next CLI is not part of the traced bundle.

`NEXT_PUBLIC_*` values are **inlined into the JavaScript bundle at build time**. They are not read
from the container's environment at run time, so putting them in Compose's `environment:` block
would do nothing at all. They arrive as build args, which means changing the API URL requires
`--build`, not a restart. They default to `localhost` rather than to a service name because the
code that reads them executes in the **browser**, which is outside the compose network and cannot
resolve `api`.

Compose interpolates those build args from the same `API_HOST_PORT` / `WEB_HOST_PORT` variables
the `ports:` mappings use, so the bundle always points at the port actually published.

---

## Configuration

Everything is `${VAR:-default}` interpolation, and every default is a working local one. **The
stack runs correctly with no `.env` file at all.**

```bash
cp .env.docker.example .env     # only if you need to override something
```

Compose reads `.env` from the repository root automatically. It is gitignored.

Two defaults intentionally disagree with the application's own:

- `MEDIA_QUEUE_WORKER_ENABLED=false` (the app defaults to `true`). The queue drains real
  transcription and media jobs; opt in when you are testing it.
- `SECURITY_HSTS_ENABLED=false`. Over plain `http://`, HSTS would teach your browser to refuse
  `http://localhost` for two years — for every project on the machine, not just this one.

`AWS_S3_SSE_ALGORITHM` is hard-coded empty rather than made configurable: MinIO has no KMS backend
and rejects the SSE header outright, so a non-empty value fails every API-initiated multipart
upload and there is no correct alternative for this stack.

Note that `pydantic-settings` also reads a `.env` from the process working directory. In the
container that is `/app`, and no `.env` is ever there — but if you bind-mount `backend/` over it,
one would be. Environment variables take precedence over the file, and the entrypoint requires
`DATABASE_URL` to be set in the real environment before it will start at all, so a mounted file
cannot quietly win. Do not rely on that; do not mount it.

### Ports

| Service | Host | Container | Why not the obvious number |
|---|---|---|---|
| api | 8000 | 8000 | Matches `NEXT_PUBLIC_API_URL` everywhere in the repo |
| web | 3000 | 3000 | |
| postgres | **55432** | 5432 | Dodges a locally installed PostgreSQL |
| minio | 9000 / 9001 | 9000 / 9001 | |
| redis | **56379** | 6379 | Same reasoning as PostgreSQL |

`API_HOST_PORT` and `WEB_HOST_PORT` override the first two. Because they also feed the web image's
build args, changing `API_HOST_PORT` needs `--build`.

---

## Running it

```bash
docker compose --profile full up --build

docker compose ps
docker compose logs -f api
curl -s http://localhost:8000/health          # {"status":"ok"}
curl -s http://localhost:8000/health/ready    # adds latencyMs; 503 if the DB is not answering
```

The database starts **empty**. Migrations are not run automatically, and nothing in `up` will run
them: a container that silently migrates is a container that will one day silently migrate the
wrong thing.

```bash
docker compose --profile migrate run --rm migrate       # prisma migrate deploy
docker compose exec -e ADMIN_PASSWORD='<choose one>' api python scripts/seed_admin.py
docker compose exec api python scripts/seed_questionnaire.py
```

`seed_admin.py` refuses to run without `ADMIN_PASSWORD`; it seeds `MASTER_ADMIN_EMAIL` as the
master admin, plus `ADMIN_EMAIL` as a plain admin when the two differ.

Note that the migration runs in its **own** container, not via `exec api`. `prisma migrate` is a
Node program, and the runtime image has no Node — that is the whole point of the three-stage
build. The `migrate` service is built from the Dockerfile's `prisma` stage, which has the CLI, the
schema, and the same entrypoint guard as the API.

That stage weighs **1.49 GB**, against 408 MB for the API. This is the trade, stated plainly: the
build toolchain, Node, npm and the full engine cache are what a migration needs and what a serving
container must not carry. Compose only builds it when you name the profile, so it never
materialises for anyone who does not migrate — and `docker image rm field-repository-migrate:local`
reclaims it afterwards.

`--profile migrate` is deliberately **not** part of `--profile full`. `full` brings up the running
stack; folding a one-shot migration job into it would re-run migrations on every `up`.

The seeds are plain Python and do run under `exec api`.

### Teardown

```bash
docker compose --profile full down     # stop; keep volumes
docker compose --profile full down -v  # also delete the database and the MinIO bucket
```

`down` without a profile flag leaves profile-gated containers running. Use the same profile you
brought the stack up with, or `--profile full`, which stops everything.

### Matching the production memory ceiling

Production is a 1 GiB box. Nothing here enforces that, because an OOM kill mid-debug is a bad
trade for a dev machine — but when you want to reproduce one:

```bash
docker compose --profile api run --rm --memory=512m --memory-swap=512m api
```

---

## Troubleshooting

**`REFUSING TO START ... DATABASE_URL points at a REMOTE host`** — working as designed. You passed
production configuration to a container. Read the first section.

**`ports are not available: ... bind: address already in use` on 3000 or 8000** — you already have
`npm run dev` or `uvicorn` running on the host. That is the common case, not an edge case:

```bash
WEB_HOST_PORT=3100 docker compose --profile full up -d --build
```

`--build` is needed for `API_HOST_PORT`, which is inlined into the web bundle; `WEB_HOST_PORT`
alone also feeds the bundle's `NEXT_PUBLIC_APP_URL`, which nothing reads, so a rebuild there is
optional. Put the value in `.env` to stop retyping it.

**`service "web" depends on undefined service "api"`** — a profile was removed from `api` in
`docker-compose.yml`. It has to remain a member of every profile that enables something depending
on it.

**`ModuleNotFoundError: No module named 'app'` running a script** — the image sets
`PYTHONPATH=/app` for exactly this. If you see it, you are on an image built before that was added;
rebuild, or prefix the command with `-e PYTHONPATH=/app`.

**Web app loads, every API call fails CORS** — `BACKEND_CORS_ORIGINS` must list the exact origin
the browser used, including the port. If you changed `WEB_HOST_PORT`, change this too.

**Uploads succeed, media never renders** — `AWS_S3_ENDPOINT` and `AWS_S3_PUBLIC_BASE_URL` are the
same value. The first is signed from inside the network (`http://minio:9000`); the second is what
a browser fetches (`http://localhost:9000/...`), and a browser cannot resolve `minio`.

**`Client hasn't been generated yet`** — the `prisma` build stage did not run or its output was
not copied. It should be impossible: that stage ends with an import check that fails the build.
Rebuild without cache.

**The API container is `unhealthy` but the app responds** — check the probe path is `/health`.
`/api/health` returns 404 and would keep it unhealthy forever.
