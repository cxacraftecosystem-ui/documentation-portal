# Search

One search box finds artisans, crafts, workshops, interviews, transcripts, products, tools,
processes and media on the Field Repository, and, for the roles allowed, clusters, design workshops,
reports and the same record types on the designer portal. Each portal indexes only its own
database. The designer portal's results arrive over the cross-portal signed channel (C27,
[ENVIRONMENT.md](ENVIRONMENT.md)), and only for records that portal may share.

**Everything ships OFF.** With `UNIFIED_SEARCH_ENABLED` off, `GET /api/search/unified` answers 404
and both clients use the legacy `GET /api/search` exactly as before (§6).

| | |
|---|---|
| Backend | `backend/app/services/search_index.py` (what a record becomes), `search_indexer.py` (queue drain, embeddings), `search_query.py` (the ranking SQL), `search_embeddings.py` (the AI flag), `unified_search.py` (permissions, peer call, consent filter, merge); `backend/app/api/routes/search.py` (`/search/unified`, `/search/remote/{type}/{id}`), `routes/cross_portal_search.py` (peer routes) |
| Shared with the designer portal, byte for byte | `backend/app/services/cross_portal.py`, `cross_portal_client.py`, `cross_portal_peer.py` |
| Web | `frontend/lib/unifiedSearch.ts`, `frontend/components/search/UnifiedResults.tsx`, `frontend/app/(protected)/search/page.tsx`, `frontend/app/(protected)/remote/[type]/[id]/page.tsx`, `frontend/app/(protected)/media/[id]/page.tsx` |
| Android | `ui/AppSearch.kt`, `ui/AppSearchSources.kt`, `ui/RemoteRecordScreen.kt`, `data/UnifiedSearch.kt` |
| Schema | `20261025090000_search_document` |
| Operator script | `backend/scripts/search_reindex.py` |
| Tests | `backend/tests/test_search_index_builders.py`, `test_search_triggers.py`, `test_search_unified.py`, `test_search_embeddings.py`, `test_cross_portal_search_peer.py`, `test_cross_portal_search_caller.py`, `test_cross_portal_shared_modules.py`; `frontend/e2e/unified-search-unit.spec.ts`, `remote-record-unit.spec.ts`; `AppSearchTest.kt`, `UnifiedSearchParsingTest.kt` |

---

## 1. The index

`SearchDocument` holds one row per record and type: a title, a subtitle, an allowlisted body (at
most 100,000 characters), the place, the craft ids and shared craft codes, the workshop and artisan
ids it names, an `audience`, a `shareable` flag and the web path of its read-only view. `tsv` is a
generated column (`simple` and `english` over the title, `simple` over subtitle and place, both over
the body); a trigram index covers `lower(title)`. Where pgvector is installed the migration also
adds `embedding vector(768)` with an HNSW index; Prisma does not declare that column.

**Every builder is an allowlist** (`search_index.BUILDERS`). Aadhaar, Pehchan, phone, e-mail, date of
birth and the stated address are never named, and free text has e-mail addresses and runs of ten or
more digits removed (`search_index.scrub`). Interview answers to identity questions are skipped.

| Type | What is indexed | Opens at |
|---|---|---|
| artisans | name, local name, craft, place | `/artisans/{id}` |
| crafts | name, local name, category, description, place | `/crafts/{id}` |
| workshops | title, type, place, description, crafts | `/workshops/{id}` |
| interviews | title, language, place, notes, text answers | `/questionnaire/interviews/{id}` |
| transcripts | file name, caption, summary, transcript | `/media/{id}` |
| products | product, local name, craft, artisan, remarks | `/products/{id}` |
| tools | toolkit, English name, craft, artisan, process, material, remarks | `/tools/{id}` |
| processes | name, notes and steps | `/processes/{id}` |
| media | file name, caption, kind | `/media/{id}` |

Clusters, design workshops and reports exist only on the designer portal and are never built here.

**Audience.** Every document here is `ALL`: on this portal every signed-in account reads every
record (`records.viewable_where`).

**Shareable** (may be shown to the designer portal): the record names no artisan, or every artisan
it names has granted sharing consent (`ArtisanSharingConsent`, set by the consent switch on the
artisan's page) and is not rejected. A record naming an artisan only as free text (an `artisanName`
with no id) is never shareable.

### Keeping it current

`search_enqueue()` triggers sit on every source table. An insert or update lists the record in
`SearchIndexQueue`; a delete removes its document in the same statement, so the index never outlives
a record. A process step lists its process, a response or an interview-artisan link its interview,
a tool-artisan link its tool, a workshop-artisan link and a consent row their artisan. The queue
worker (`app/worker.py`) drains 100 items every 15 seconds (`search_indexer.drain`, `FOR UPDATE
SKIP LOCKED`), expands an artisan to its products, processes, tools, interviews and media (whose
`shareable` follows that artisan's consent), and then embeds up to 32 changed documents when
embeddings are on. The migration only lists every record in the queue; the build happens in the
worker after `UNIFIED_SEARCH_ENABLED` is switched on.

`python -m scripts.search_reindex [--type media] [--drain]` (from `backend/`) lists records again
after a builder changes.

## 2. Ranking

One SQL statement per search (`search_query.build_sql`) returns the page of every asked type and
each type's total, so a search holds one of the production pool's connections for one query. Three
candidate lists, each capped at 200 per type and each filtered by the same permission and filter
predicates:

1. **Full text**: `tsv @@ (websearch_to_tsquery('english', q) || websearch_to_tsquery('simple', q))`
   by `ts_rank_cd`. `english` stems ("dyes" finds "dyeing"); `simple` matches any script.
2. **Trigram**: `word_similarity(lower(q), lower(title)) >= 0.45`, for names and typos ("indgo"
   finds "indigo"). The threshold is spelled out because pg_trgm's `<%` default of 0.6 misses
   one-letter typos in short words.
3. **Meaning** (only in hybrid mode): nearest embeddings by cosine distance.

They are fused by reciprocal rank, `score = Σ 1/(60 + rank)`, ties broken by id. Snippets are
`ts_headline` plain text; highlights are character ranges computed in Python, so no HTML travels.

**Embeddings (the AI flag).** `SEARCH_EMBEDDINGS_ENABLED`, a provider key, and the `embedding`
column (probed once per process) must all be present; otherwise the response says `"mode": "text"`
and no provider is called. The query is embedded once with a 1.5 s timeout and a 256-entry cache;
any failure is text mode. Documents are embedded from title, subtitle and the first 8,000
characters of the body. The vectors never leave this portal.

## 3. API

**`GET /api/search/unified`** (signed in). `q` (2 to 200 characters after control characters are
removed), `types` (repeatable or comma-separated), `scope=all|local`, `limit` per group (5, max 20),
`type` + `page` for one group's pages, and the filters `craftId`, `place`, `dateFrom`, `dateTo`,
`workshopIds`.

```json
{"query": "indigo", "mode": "text",
 "groups": [{"type": "artisans", "label": "Artisans", "total": 14, "localTotal": 9, "remoteTotal": 5,
   "items": [{"portal": "fieldRepository", "local": true, "type": "artisans", "id": "fr9",
     "title": "Ramesh Kumar", "subtitle": "Indigo dyeing · Bagru", "snippet": "…",
     "highlights": [[0, 6]], "place": "Bagru", "craftCodes": ["CR-0142"],
     "recordDate": "2026-09-02T00:00:00Z", "href": "/artisans/fr9", "openUrl": null, "score": 0.016393}]}],
 "remote": {"portal": "designer", "label": "Designer portal", "status": "ok"},
 "typesRefused": []}
```

Groups come in the vocabulary order and empty ones are left out. Within a group local and remote
items are interleaved by position (`1/(60 + position)` on each side), local first on a tie. With
`type` and `page`, the page holds that page of each side (up to `2 × limit` rows), so nothing falls
between two pages. `typesRefused` is always empty here: nothing on this portal is refused locally.

`remote.status`:

| Status | When |
|---|---|
| `ok` | The designer portal answered. |
| `off` | `scope=local`, or cross-portal search is not configured. |
| `notPermitted` | The account's role is not in `CROSS_PORTAL_SEARCH_ROLES` (field contributors and crowdsource volunteers). No call is made. |
| `filteredOut` | `workshopIds` was given (workshop ids are local), or the craft has no shared code. No call is made. |
| `unavailable` | The peer timed out (2.5 s), refused the token, or sent an answer not signed for this request. Local results still return. |

Designer items whose `linkedArtisanIds` name one of this portal's artisans without granted consent
are dropped before the merge, and the group total drops with them.

**`GET /api/search/remote/{type}/{id}`** (signed in, a cross-portal role): one designer-portal
record as a read-only card, `{portal, type, id, title, subtitle, fields: [{label, value}], snippet,
recordDate, openUrl}`. 404 for anything missing, refused, not shareable or naming an artisan without
consent here; 503 when the peer cannot be reached (5 s). Logged as `RECORD_VIEW` with this portal's
`userId`.

### Peer routes (the designer portal asking this portal)

`POST /api/cross-portal/peer/search` and `POST /api/cross-portal/peer/search/record`, admitted by the
shared token and a fresh nonce (`cross_portal_peer.require_search_peer`) and answered with a body
signed over the request. POST keeps the query out of URLs and proxy logs.

1. The requester's e-mail is matched to an account here that the allow-list does not refuse. A match
   brings that account's read rules; no match gets the floor (`audience = 'ALL'`). Either way only
   `shareable` documents are considered.
2. Consent is re-checked live in the same SQL: every artisan a record names must still have granted
   consent, so a withdrawal takes effect before the indexer catches up.
3. Each item carries only `PEER_ITEM_KEYS`. Never a media URL, storage key, phone, e-mail, Aadhaar,
   Pehchan, date of birth or address.
4. Every search and record view is written to `CrossPortalAccessLog` (`SEARCH` / `RECORD_VIEW`, the
   asking account, the result count). The query text is never logged.
5. 120 searches per 10 minutes per asking account, then a signed 429.

## 4. Clients

**Web.** The one box is the `/search` page ("Search" in the island). With a query it shows the
unified groups, five per group, with "Show all" paging one group; designer rows carry a portal badge
and open `/remote/{type}/{id}`, a read-only card with an "Open in Designer portal" button (only an
`http(s)` address becomes a link). Without a query the page browses through `GET /api/search` as
before, and `lib/unifiedSearch.ts` falls back to that route whenever `/search/unified` answers 404.
Media and transcript results open `/media/{id}` (the file, its transcript and its details,
read-only); `/questionnaire?interview={id}` opens that interview.

**Android.** The search header (`AppSearchHeader`) asks `search/unified` with five per group and
falls back to the old fan-out on 404. Designer rows carry a portal chip and open
`RemoteRecordScreen`, whose "Open in Designer portal" launches the browser. Offline, the cached
items stay as they are; nothing remote is cached.

## 5. Data protection

- What crosses is a short text projection; opening the full record means signing in on the other
  portal, where that account's own permissions apply.
- Consent is enforced at index time (`shareable`), live in the peer SQL, and again here on designer
  results that name this portal's artisans.
- The answering portal trusts the e-mail the peer server asserts, which rests on the shared token.
  A match can only bring in that local account's own rights; every request is logged and rate
  limited.
- Embeddings are computed from allowlisted text only and stay in this portal.

## 6. Rollout

1. Deploy with every flag off. The migration adds two tables, four nullable log columns, the
   triggers, and a queue listing every record; nothing heavy runs.
2. `UNIFIED_SEARCH_ENABLED=true`; the worker drains the queue (a few thousand rows take minutes).
3. The cross-portal values on both servers (`DESIGNER_PORTAL_API_URL` here), then
   `CROSS_PORTAL_SEARCH_ENABLED=true` on both.
4. Embeddings last. EXPLAIN the ranking query against a production-sized clone first.

## 7. How this document is kept true

| Claim | Kept true by |
|---|---|
| No identity value reaches the index; the shareable rule | `test_search_index_builders.py` |
| Insert/update enqueue, delete removes at once, consent reaches dependents | `test_search_triggers.py` |
| Ranking, grouping, paging, filters, 404 while off | `test_search_unified.py` |
| Text mode without the flag, key or column; no provider call | `test_search_embeddings.py` |
| Peer admission, signing, floor, live consent, projection, log, rate limit | `test_cross_portal_search_peer.py` |
| Remote status values, timeouts, signatures, consent drop, record 404/503 | `test_cross_portal_search_caller.py` |
| The shared modules match the designer portal's | `test_cross_portal_shared_modules.py` with `CROSS_PORTAL_PEER_REPO` set |
| The variables are documented | [ENVIRONMENT.md](ENVIRONMENT.md) |

The database tests run only with `SEARCH_DB_TESTS=1` and a throwaway, migrated `DATABASE_URL`
(pgvector image for the meaning tests); CI has no database service, so there they skip.

**Review triggers:** a new record type or a new identity field on a source table (add it to a builder
or to nothing), a change to `CROSS_PORTAL_SEARCH_ROLES`, and any change to the three shared modules
(make it in both repositories).
