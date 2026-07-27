# `app.scale` — the dormant scaling layer

Caching, cursor pagination, approximate counts, rate limiting and read-replica routing, all of it
**off by default and unreferenced by any route**. A fresh clone that sets none of the variables
below behaves exactly as this API behaves today, and this document is how you check that claim
rather than take it.

**Nothing here is wired into a route.** Every section carries the exact adoption recipe — where the
call goes, what the cache key looks like, what has to invalidate it — so switching an endpoint over
later is mechanical rather than a design exercise.

---

## The promise, and how it is kept

| Promise | How |
| --- | --- |
| A fresh clone is unchanged | Every flag defaults to off/unset; every helper's first line short-circuits to the code that runs today. |
| Zero cost when off | `import app.main` with no new variables imports **no** `app.scale` module at all — nothing on the request path references the package until a route opts in. |
| No optional dependency at import | `redis` is imported inside `create_redis_backend`, after the flag check. The `cache` extra is not in `dependencies`, so `pip install -e .` is as light as it was. |
| Fail soft | Flag on but the package missing or the service unreachable: one `ERROR` line (once per process, via `flags.log_once`), then the existing behaviour. Never a crash, never a hang, never a silent no-op. |

Verify the first two in one command:

```bash
cd backend
python -c "import sys, app.main; print(sorted(k for k in sys.modules if k.startswith('app.scale')) or 'NONE')"
# NONE
```

And the whole configuration, whatever it happens to be:

```bash
python -m app.scale.selfcheck                           # everything off
SCALE_CACHE_ENABLED=true python -m app.scale.selfcheck  # the memory cache doing its job
```

---

## Every variable

### Response cache

| Variable | What it does | Default | Cost when off | How to verify it took effect |
| --- | --- | --- | --- | --- |
| `SCALE_CACHE_ENABLED` | Master switch. Off: `cached_response` awaits the loader directly. | `false` | One boolean read, inside a helper no route calls yet. | `python -m app.scale.selfcheck` prints `cache [ON - memory]` and "a repeated read is served from the cache". |
| `SCALE_CACHE_BACKEND` | `memory` or `redis`. An unrecognised value logs once and uses `memory`. | `memory` | Not read. | `cache_stats()["backend"]` — the backend actually in use, not the one requested. |
| `SCALE_CACHE_TTL_SECONDS` | Default entry lifetime. Per-namespace overrides in `keys.py`. | `30` | Not read. | Read a page, wait past the TTL, read again — the query runs. |
| `SCALE_CACHE_MAX_ENTRIES` | Entry ceiling for the in-process cache. | `512` | Nothing constructed. | `cache_stats()["evictions"]` climbs once you exceed it. |
| `SCALE_CACHE_MAX_BYTES` | Byte ceiling for the in-process cache. | `33554432` (32 MiB) | Nothing constructed. | `cache_stats()["bytes"]` never exceeds it. |
| `SCALE_CACHE_MAX_ENTRY_BYTES` | Single responses larger than this are refused rather than evicting hundreds of small pages to fit. | `1048576` (1 MiB) | Nothing constructed. | `cache_stats()["rejectedOversize"]`. |
| `SCALE_CACHE_SINGLEFLIGHT_TIMEOUT_SECONDS` | How long a request waits on another request already loading the same key before running the query itself. | `10` | Not read. | The self-check's "8 concurrent misses collapse to one query". |
| `SCALE_REDIS_URL` | Connection string. **Secret** — may embed a password; only ever logged as a boolean. | unset | Not read. | `cache_stats()["backend"] == "redis"`. |
| `SCALE_REDIS_TIMEOUT_SECONDS` | Connect and read deadline on every Redis call. | `0.5` | Not read. | Stop Redis: the first request costs about two of these, then the breaker makes it free. |

### Keyset pagination

| Variable | What it does | Default | Cost when off | How to verify |
| --- | --- | --- | --- | --- |
| `SCALE_KEYSET_PAGINATION_ENABLED` | Off: `decode_cursor` returns `None` for every cursor, so an adopting route falls back to the `skip`/`take` it uses today. | `false` | One boolean read. | Self-check prints `keyset pagination [ON]` and round-trips a cursor. |

### Approximate counts

| Variable | What it does | Default | Cost when off | How to verify |
| --- | --- | --- | --- | --- |
| `SCALE_APPROX_COUNT_ENABLED` | Off: `total_for` is exactly `delegate.count(where=where)`. | `false` | One boolean read. | `total_for` returns `(n, True)` on a table above the threshold. |
| `SCALE_APPROX_COUNT_THRESHOLD` | Exact at or below this many rows; estimated above. | `5000` | Not read. | The largest table here holds 925 rows, so **at the default nothing changes** — set it to `10` against a local table to see the behaviour. |

### Rate limiting

| Variable | What it does | Default | Cost when off | How to verify |
| --- | --- | --- | --- | --- |
| `SCALE_RATE_LIMIT_ENABLED` | Off: **the middleware is never added to the app**. Not added-and-returning-early — absent. | `false` | Nothing. The stack is one layer shorter than if this existed. | `[mw.cls.__name__ for mw in app.user_middleware]` gains `RateLimitMiddleware`. |
| `SCALE_RATE_LIMIT_REQUESTS` | Bucket capacity, and the sustained rate per window. | `120` | Not read. | The `x-ratelimit-limit` header on a 429. |
| `SCALE_RATE_LIMIT_WINDOW_SECONDS` | Window the allowance refills over. | `60` | Not read. | The `retry-after` header on a 429. |

### Read replica

| Variable | What it does | Default | Cost when off | How to verify |
| --- | --- | --- | --- | --- |
| `DATABASE_READ_REPLICA_URL` | Read-only database URL. Presence **is** the flag. **Secret.** Gets the same automatic `sslmode=require` as `DATABASE_URL`. | unset | Nothing constructed, no second engine. | `replica_status()` reports `configured` / `connected` / `demoted`. |

> **The one cost that is easy to miss:** prisma-client-py runs a Rust query engine as a child
> process per client. A replica is a **second engine** — tens of megabytes on a box with 1 GiB
> total, already running uvicorn, the primary engine and (in a separate service) the queue's engine.
> Set this only when a real replica exists, and watch memory after the first deploy that has it on.

---

## The cache: key scheme and invalidation

### The key

```
fr:v1:artisan.list:7:u:clx9…:1f4c9a2b3d5e6f70
│  │  │            │ │       └─ fingerprint: sha1 of the canonical filter + page parameters
│  │  │            │ └───────── audience: u:<userId> | r:<ROLE> | all
│  │  │            └─────────── generation of the family this namespace belongs to
│  │  └──────────────────────── namespace: what is cached
│  └─────────────────────────── key-format version (bump when the stored ENCODING changes)
└────────────────────────────── product prefix, so a shared Redis stays legible
```

**The audience is not optional.** `records.visibility_where` returns different rows per viewer and
`records.public_encode` masks Aadhaar per viewer, so a key without an audience would hand one
researcher's rows — and another person's identity number — to whoever asked next. Use
`audience_for(current_user)`. `role_audience(user)` exists only for responses that provably vary by
rank alone (reference data, option lists) and shares one entry across every user of a role.

### Invalidation

Keys carry the **generation** of their family. Invalidating is one atomic increment of that
counter: every key ever minted under the old generation becomes unreachable at once, in O(1), and
cannot half-finish. This is also why the read/write race is safe — a slow reader that started at
generation 7 and finishes after a writer moved the family to 8 writes its stale answer under a
generation-7 key nothing will ever ask for again. Delete-based invalidation gets that case wrong.

**A create, an edit, a delete and a review approval are the same event to this layer.** All four
change which rows a list returns or what they contain, so all four make the same call:

```python
await invalidate_record("artisan")   # after the write commits, before returning the response
```

Awaited, not scheduled. Save-then-reload is the most common sequence in this app, and a
fire-and-forget invalidation loses that race often enough to look like the save did not work.

| A write to… | bumps families | which retires |
| --- | --- | --- |
| `artisan` | `artisan`, `records` | `artisan.list` + every cross-type view |
| `craft` | `craft`, `artisan`, `records` | craft and artisan lists (a renamed craft shows on artisan rows) + cross-type views |
| `process` / `product` / `tool` / `workshop` / `media` | that family, `records` | that type's list + cross-type views |
| `questionnaire` / `interview` | `questionnaire`, `records` | interviews, questions + cross-type views |
| `task` | `task` | task lists and batches only |
| `user` | `user` | the user directory only |
| `workshopAssignment` | `workshop` | workshop lists and assignments |
| `release` | `release` | the Android update check |

The cross-type views (`dashboard.stats`, `search.results`, `data.tree`, `data.browser`,
`review.pending`) all ride the aggregate `records` family: they read six tables, and six dependency
counters would be exactly as correct as one shared counter and five times the bookkeeping.

**A namespace missing from `NAMESPACE_FAMILY` is a programming error**, not a default — it would be
cached under a counter nothing ever bumps, i.e. data that only expires by TTL. `cached_response`
logs it loudly and serves the request uncached rather than 500-ing on a read path.

### What a TTL is actually for

Not freshness — explicit invalidation covers everything this process does. The TTL bounds how long
a response may lag a change this process **never saw**, and there are exactly three of those:

1. The separate `fieldrepo-queue` service writing transcripts and measurements onto media rows.
2. A second uvicorn web worker's private memory cache (production runs one, so this is currently
   zero — but it is the reason `redis` exists as an option).
3. Somebody at a `psql` prompt.

So `media.list` and `review.pending` have the shortest TTLs and `reference.address` the longest.
Prefer short TTLs plus explicit invalidation over long TTLs; the per-namespace table is in
`keys.py`, with the reasoning next to it.

### Adopting it in a list route

`app/api/routes/artisans.py::list_artisans` as the worked example — the body moves into a closure
and nothing else changes:

```python
from app.scale import audience_for, cached_response

@router.get("")
async def list_artisans(current_user=Depends(get_current_user), search: str | None = None, ...):
    page, page_size, skip = normalize_pagination(page, pageSize)

    async def _load() -> dict[str, Any]:
        ...                                   # exactly the body it has today
        return page_payload(public_encode(items, current_user), total, page, page_size)

    return await cached_response(
        "artisan.list",
        audience=audience_for(current_user),
        params={"search": search, "craft": craft, "craftId": craftId, "workshopId": workshopId,
                "place": place, "statusFilter": statusFilter, "page": page, "pageSize": page_size},
        loader=_load,
    )
```

Rules for `params`: **every** argument that changes the answer goes in, including `page` and
`pageSize`. `None` values are dropped (absent and explicitly-null are the same thing to these
routes). Anything the fingerprint cannot canonicalise raises, is logged once and the request is
served uncached — slower, never wrong.

Then, in `create_artisan`, `update_artisan`, `delete_artisan` and the review approval path:

```python
await invalidate_record("artisan")
```

Two properties of the read path worth knowing:

* **Byte-identical hit and miss.** The payload is encoded with `pydantic_core.to_json` — the
  serializer FastAPI itself uses — and both paths return the decoded form. This is a correctness
  requirement, not tidiness: `fastapi.encoders.jsonable_encoder` renders `Decimal("12.50")` as the
  number `12.5`, while FastAPI's response pipeline renders the string `"12.50"`, and every client
  in this project types those columns as strings. A cache on the wrong encoder would have changed
  the wire format **only when warm**.
* **Single-flight.** When a hot key expires, the first caller to miss runs the query and everyone
  else awaits its result — measured at 8 concurrent misses producing 1 query. Followers that wait
  longer than the timeout run the query themselves, so a slow leader can never hang N requests.

---

## Keyset pagination

Offset paging makes Postgres produce and discard `skip` rows before returning anything, so page 50
costs fifty pages of work. Keyset paging asks "the next 20 rows after this one" — an index seek,
the same cost on page 1 and page 500.

It also fixes something that bites at today's volumes: every list route orders by `createdAt desc`
alone, and rows created in the same millisecond (an import, a batch upload, a phone syncing offline
work) have **no defined order**. Page 2 can repeat a row from page 1 and skip another. Adopting
`order_by()` forces the `(sortField, id)` ordering that makes offset paging correct too — worth
doing on its own, flag or no flag.

### Keeping page numbers in the UI contract

The cursor is an **accelerator, not a replacement**. The response keeps `items`, `total`, `page`,
`pageSize` and `pages` meaning exactly what they mean now, and gains `nextCursor` — present only
when there is a next page, so its presence is the whole test a client needs. A client that has never
heard of cursors keeps working; the web UI keeps rendering numbered pages and can jump to page 7 by
offset while using the cursor for "next".

```python
from app.scale import after_where, decode_cursor, next_cursor, order_by, with_cursor

@router.get("")
async def list_artisans(..., page: int = Query(1, ge=1), cursor: str | None = None):
    page, page_size, skip = normalize_pagination(page, pageSize)
    ...build `where` and `and_filters` as today...

    resume = decode_cursor(cursor, sort_field="createdAt")   # None when the flag is off
    if resume is not None:
        and_filters.append(after_where(resume))              # under AND — never at the top level,
        skip = 0                                             # these routes already use OR

    if and_filters:
        where["AND"] = and_filters
    total, items = await count_and_page(
        db.artisan, where=where, skip=skip, take=page_size,
        order=order_by("createdAt"), relations=RELATIONS,
    )
    payload = page_payload(public_encode(items, current_user), total, page, page_size)
    return with_cursor(payload, next_cursor(items, sort_field="createdAt", page_size=page_size))
```

Cursors are HMAC-signed with `JWT_SECRET`, because a cursor is decoded straight back into a query
filter and the only ones this API should act on are ones it minted. A tampered cursor, or one from a
route with a different ordering, is ignored with one log line and the request is served by offset.

An index on `("createdAt", "id")` per table makes the seek as fast as it can be; without it the
query is still correct, just not yet fast.

---

## Approximate counts

Every paged route runs two queries: the page (`LIMIT 20`, stops early) and `COUNT(*)` over the whole
filtered set, which cannot stop early. The page stays cheap as the table grows; the count does not.

* **Unfiltered** counts have a free answer: `pg_class.reltuples`, the estimate the planner already
  keeps. Used only when it is above the threshold — below it, an exact count is both more accurate
  and no slower.
* **Filtered** counts have no shortcut, so they are **capped**: ask for at most `threshold + 1` rows
  and report "at least threshold" if that many come back. Exact below the cap, honest above it,
  never scanning past it.

`count_and_page` runs the count and the page concurrently, so an adopting route replaces it with the
same two-wait shape:

```python
from app.services.concurrency import gather_reads
from app.scale import count_payload, total_for

(total, approximate), items = await gather_reads(
    total_for(db.artisan, where=where, table="Artisan"),
    db.artisan.find_many(where=where, skip=skip, take=page_size, order=order_by("createdAt")),
)
await hydrate_relations(items, RELATIONS)
payload = page_payload(public_encode(items, current_user), total, page, page_size)
return count_payload(payload, approximate)
```

`count_payload` adds `totalIsApproximate: true` **only when true**, so the response shape is
unchanged wherever the total is exact — which, at the default threshold of 5000 against a largest
table of 925 rows, is everywhere. `table` is the physical table name: this schema declares no
`@@map`, so it is the Prisma model name verbatim and case-sensitively (`"Artisan"`, `"MediaFile"`).

---

## Rate limiting

A courtesy backstop for a 1 GiB box — a phone stuck in a sync loop, a script left running overnight,
an accidental `while true` against `/search`. **Not a security control:** the identity is derived
from headers the origin cannot fully verify. Abuse defence belongs at CloudFront/WAF, where traffic
is dropped before it costs anything.

Callers are identified by a truncated SHA-256 of their bearer token when present (so one user has
one allowance across their phone and laptop, and an office behind one NAT is not one bucket), else
by the left-most `X-Forwarded-For` entry. `/health*` and every `OPTIONS` preflight are exempt — a
rate-limited preflight breaks the web app completely, and the browser reports it as a CORS failure
rather than a 429.

### Where to install it

In `app.main.create_app`, **after** `app.add_middleware(UnhandledErrorMiddleware)` and **before**
`app.add_middleware(CORSMiddleware, ...)`:

```python
app.add_middleware(UnhandledErrorMiddleware)
install_rate_limit(app)          # adds nothing at all when the flag is off
app.add_middleware(CORSMiddleware, ...)
```

That position is load-bearing. Starlette runs the most recently added middleware outermost, so
adding it before CORS puts the limiter *inside* the CORS layer and a 429 picks up
`access-control-allow-origin` on the way out. Installed outside CORS, the same 429 reaches the
browser without that header, the fetch rejects, and the web app says "Failed to fetch" — the exact
confusion `UnhandledErrorMiddleware` was written to end. (Verified: with the limiter in this
position a 429 carries `access-control-allow-origin: http://localhost:3000`.)

With `SCALE_CACHE_BACKEND=redis` the window is shared across processes via `INCR`/`PEXPIRE`;
otherwise each process uses its own token bucket, which is both faster and exactly as correct for a
single process.

---

## Read replica

```python
from app.scale import read_via_replica

rows = await read_via_replica(lambda client: client.artisan.find_many(where=where, take=20))
```

`reader()` returns the replica when one is connected and the primary otherwise — never `None`, never
raising. `read_via_replica` additionally retries on the primary if the replica fails a query, which
is safe **only because the callable must be a read**; a write passed here would execute twice.

**Never use it on a path that reads back what it just wrote.** Replication lag is real and unbounded
during a spike: a create that returns the row it just made, a form that re-reads after save, a
"check completion" that must see a record submitted a second ago — all stay on the primary. The safe
adopters are the wide read-only list and aggregate endpoints, which are also the slow ones.

`close_replica()` belongs in the `finally` of `app.main.lifespan`, next to `disconnect_db()`; it is
a no-op when nothing is configured, so it is safe to call unconditionally.

---

## Failure behaviour, in full

| Situation | What happens |
| --- | --- |
| `SCALE_CACHE_BACKEND=redis`, package not installed | One `ERROR` naming `pip install -e '.[cache]'`; the in-process cache is used. |
| Redis unreachable at startup | One `ERROR`; the in-process cache is used. `cache_stats()` reports `requestedBackend: redis`, `backend: memory`. |
| Redis dies mid-flight | Each failing call is a miss; after 3 consecutive failures the process stops calling Redis entirely and logs once. Measured: one 1.0s request while the breaker trips, then 0.1ms per request. Restart to re-enable. |
| An unregistered cache namespace | Logged once; that response is served uncached. |
| A response that will not encode | Logged once; returned to the client normally, just not stored. |
| A tampered or mismatched cursor | Logged once; the request is served by offset. |
| `pg_class` unreadable | Logged once; totals are counted exactly. |
| Replica unreachable / failing | Logged once; queries retry on the primary, and after 3 consecutive failures the replica is dropped for the life of the process. |

---

## Testing with Redis locally

Redis is **only** ever tested against local Docker — never production.

```bash
docker compose --profile cache up -d redis     # host: redis://localhost:56379/0
cd backend && pip install -e '.[cache]'
SCALE_CACHE_ENABLED=true SCALE_CACHE_BACKEND=redis SCALE_REDIS_URL=redis://localhost:56379/0 \
  python -m app.scale.selfcheck
docker exec field-repository-redis redis-cli --scan --pattern 'fr:*'
```

A Redis hit costs **two** round trips, not one: the family's generation has to be read before the
key can be built. On a co-located Redis that is noise against a ~250ms cross-region query; against a
managed Redis across a WAN it is worth measuring before assuming the cache is a win. The memory
backend has no round trip at all.

Two things to know before writing tests around this:

* **`TestClient` runs every request in a new event loop**, which breaks any persistent async
  connection pool — Redis calls fail with `Event loop is closed` from the second request on (they
  fail *soft*, so the test still passes, just without a shared counter). Test the Redis path from a
  single `asyncio.run`, or with a real server. uvicorn keeps one loop for the process lifetime, so
  this is a harness artifact and not a production concern.
* The compose Redis runs `--maxmemory-policy allkeys-lru`. Generation counters carry no TTL, so
  under that policy they are evictable in principle; with the short TTLs here a lost counter only
  resurrects entries that have already expired. `volatile-lru` removes even that if a shared Redis is
  ever used for anything else.
