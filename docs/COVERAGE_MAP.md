# Coverage map

`/map?view=coverage` — the second reading of the map. **Places** plots records where they are;
**Coverage** colours each district by how much documented work it holds and lists what is missing:
shared crafts nobody has documented, and districts and states with no workshop. Any signed-in
account may open it, the same rule as `/map`; every count is folded from the records that account
may read.

## What is drawn

- **India's outline** is the verified national outline in `frontend/components/map/indiaGeometry.ts`
  (the official depiction: all of Jammu & Kashmir and Ladakh, including Gilgit-Baltistan, Muzaffarabad
  and Aksai Chin). It is drawn first as a neutral base fill, and every district fill is clipped to it
  with an SVG `clipPath`, so an area with no district polygon still reads as India, in the "none" tone.
- **District polygons** come from `frontend/public/boundaries/district-shapes.json`, written by
  `scripts/build_boundaries.py --shapes` (same polyline encoding as the outline, clipped to it at build
  time, simplified at 0.01°). The file is fetched only when Coverage opens. The manifest records
  `shapesVersion` and the SHA-256 of the source data in `shapesSourceSha256`.
- **State borders** on top, as on the Places map.
- A fixed caption under the map: *External boundaries of India as depicted by Survey of India.
  District boundaries are indicative.*
- No tile server, no Google Maps, no MapTiler and no mapping library. `e2e/coverage-unit.spec.ts`
  fails if the coverage code names one.

The 43 districts without a polygon of their own (`services/district_lineage`) are coloured inside the
parent they were carved from; that parent gets a dashed outline and the unit panel says
"Shown inside <parent> (parent)". The three Delhi districts with no parent appear in the table only.

## Districts and LGD codes

Every unit is keyed by its Local Government Directory code. `backend/app/data/lgd_register.json`
(built by `scripts/build_lgd_register.py`, byte-identical in both portals) codes the 36 states and
union territories and the 795 districts of `services/address.py`, names equal. A district whose code
cannot be confirmed sits in `pending` with a reason; it resolves to its state.

Codes are resolved **when the map is read** (`services/lgd.resolve`); no record table stores one and
nothing is backfilled. The order is fixed and nothing is fuzzy:

1. `address.canonical_state` / `canonical_district` (the register and its aliases — "Orissa", "Kutch").
2. A `GeoAlias` row an administrator saved for that exact spelling.
3. `place_atlas` over the free-text place (a workshop's `place`).

A real state with a district from another state resolves to the state only. Anything else is
**unmatched** and is counted under the map.

## Unmatched places (administrators)

In admin view the right-hand column lists every unmatched spelling with its record count. Pick a
state (and a district, or "State only") and press **Map**: a `GeoAlias` row maps that spelling for
every record written that way. The only suggestion offered is a district that exists, by exactly that
name, in one other state. Each mapping writes a `RecordRevision` (`recordType` `geoalias`) and can be
removed with `DELETE /api/map/coverage/aliases/{id}`.

## Filters

Crafts (the 50 shared craft codes), workshop type (`DPD` = Design & Prototype Development, or
`OTHER`), financial year (1 April – 31 March, IST, by start year: `2025` is FY 2025-26), state, and
what the map is coloured by (Workshops, Artisans or All records). Detail is District or State. Every
filter is in the URL. A workshop's year is its start date (or its date); a record's is when it was
created. Craft and type reach artisans, products, tools and media through their own craft and their
workshop.

## API

All on the `/map` router (`backend/app/api/routes/map_coverage.py`, `services/coverage.py`).

| Route | Who | What |
|---|---|---|
| `GET /map/coverage` | signed in | Units (sparse), workshop markers (500 cap), gaps, unmatched count; admins also get the first 20 unmatched spellings. |
| `GET /map/coverage/units/{stateLgd}/{districtLgd}/records` | signed in | One unit's records per bucket, 40 a page with `cursor`. `districtLgd` 0 is the state-level match. Names and links only. |
| `GET /map/coverage/unmatched` | admin | Every unmatched spelling, grouped, with row counts. |
| `POST /map/coverage/aliases` | admin | Map a spelling. 409 if it is mapped, 422 if the district is not in the state. |
| `DELETE /map/coverage/aliases/{id}` | admin | Remove a mapping. |
| `GET /cross-portal/peer/coverage` | designer portal's server | Aggregate counts per district for its ministry map. Off by default (`CROSS_PORTAL_COVERAGE_ENABLED`, see `docs/ENVIRONMENT.md`). |

Every row is read through `record_filters.build_record_wheres`, exactly as `/map/points` reads it;
the coverage code writes no `where` of its own and runs no raw SQL. A bucket reads at most 20,000 rows
(`truncated` says so on screen), and one caller's fold is reused for 60 seconds. No Aadhaar, Pehchan,
phone or address text leaves either route; the tests scan the JSON for those keys.

## Android

The Android map has no coverage view in this release.

## How this document is kept true

| Claim class | Kept true by |
|---|---|
| The depiction, the outline tolerance and the centroids | `backend/tests/test_coverage_shapes.py` runs `verify_shapes` from `scripts/build_boundaries.py` over the committed `district-shapes.json`; the script refuses to write a file that fails it. |
| LGD codes and names | `backend/tests/test_lgd_register.py` (every district coded once or pending, names equal `address.py`, the sibling portal's copy identical). |
| Resolution order | `backend/tests/test_lgd_resolve.py`. |
| Counts, filters, gaps, caps, no identity fields | `backend/tests/test_map_coverage.py` and `backend/tests/test_cross_portal_coverage.py`. |
| No mapping library, the caption, the switch | `frontend/e2e/coverage-unit.spec.ts`; the screen itself in `frontend/e2e/map-coverage.spec.ts`. |
