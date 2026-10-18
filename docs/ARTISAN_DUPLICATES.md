# Duplicate artisans: finding, holding, merging

One person recorded twice is two half-records: the interviews sit on one, the photographs on the other,
and a count of artisans is wrong by one. This feature finds likely duplicates, asks before a new one is
made, and hands a pair to the one artisan merge (`/admin/artisan-merge`), reversible for 30 days.

Code: `backend/app/services/artisan_duplicates.py` (scoring, the scan),
`backend/app/api/routes/artisan_duplicates.py` (the API), and the peer match in
`backend/app/api/routes/cross_portal.py`. The merge itself is `backend/app/services/artisan_merge.py`
and `backend/app/api/routes/artisan_merge.py` (C24). Web: `frontend/components/duplicates/`,
`frontend/app/(protected)/admin/duplicates/`. Android: `data/PossibleDuplicates.kt`,
`ui/PossibleDuplicateSheet.kt`.

---

## 1. Scoring

The server is the only scorer. Neither client ports it, and an offline create is checked when it syncs.
The score, its weights, caps and bands are the designer portal's, line for line, and both portals'
pytest read the one byte-identical `shared/duplicate-score-vectors.json`.

- **Definitive:** the same Aadhaar fingerprint or the same Pehchan number scores 1.0.
- **Otherwise** a pair is considered only when the names are at least half alike. The name carries
  0.55 of the score, and district, place, phone, date of birth, craft and local name add the rest,
  capped at 1.0.
- **Caps:** different fingerprints or Pehchan numbers cap the score at 0.30, different genders at 0.40,
  and birth years more than three apart take 0.25 off.
- **Bands:** `VERY_LIKELY` from 0.90, `LIKELY` from 0.75 (the hold threshold), `POSSIBLE` from 0.60
  (the show threshold). Below 0.60 nothing is stored.

**Reasons are codes with short labels** ("Names 92% alike", "Same district"). No reason, label or
stored row ever carries an identifier value.

## 2. At save time

`POST /api/artisan-duplicates/check` (`require_record_creator`, 30 a minute per user) takes what the
form knows so far and answers up to five candidates at 0.60 or more, read through
`records.viewable_where` like the artisan list. The Aadhaar travels in the body only and is
fingerprinted, never stored. An `AADHAAR` reason appears only when the caller typed that number.
Candidates carry a birth year, never a date of birth, a masked Aadhaar or a Pehchan number.

`POST /api/artisans` takes `duplicateAcknowledged` (`ArtisanCreate` only, at most 20 ids): the ids the
researcher called "Different person", `[]` when nothing was shown. A create that sends the list and has
an unacknowledged candidate at 0.75 or more is answered `409 artisan_possible_duplicate` with the
candidates, and its idempotency key is released so the answer can be resent. A create without the key
(an older build) is never held. `ARTISAN_DUPLICATE_HOLD=false` switches the hold off
([ENVIRONMENT.md](ENVIRONMENT.md)).

- **Web form:** the "Already recorded?" card and dialog, with Open and "Different person".
- **Android form:** checks before an online create and shows the same sheet, with Cancel and
  "Different person — save" on one line. Open goes to the existing record.
- **Offline, both clients:** the create is queued with `[]`. A held sync gets its own outbox row, with
  **Same person** (the queued files go to that record, nothing is created) and **Different person**
  (sent again, acknowledged). The hold is never read as a clash.

## 3. Review and merge (admin, web)

**Admin → Duplicates** lists open pairs by score, with two actions on each: **Compare** opens
`/admin/artisan-merge` with the pair chosen (the older record kept by default; the page can swap
them), and **Different people** sets the pair aside (`POST /api/artisan-duplicates/{id}/dismiss`;
**Reopen** puts it back). There is ONE merge: the C24 merge's field-by-field pick, its blockers (an
interview set that would collide), its moves and its 30-day undo are described in
`services/artisan_merge.py`. Merging deletes the merged record, and its pairs go with it
(`onDelete: Cascade`); an undo brings the record back and the nightly scan finds the pair again.

**Sharing consent (C27).** The kept record ends with the more recent of the two decisions. The
designer portal's links move to the kept record only while that decision is "granted"; otherwise they
are removed, as a withdrawal on the consent route removes them. The undo puts both back.

**Redirect.** A merged id keeps working: `GET /api/artisans/{id}` answers the record it now lives
on, and products, tools, questionnaires, media and workshops rewrite a queued write's merged id to the
survivor (`redirect_merged_artisans`). The designer portal's peer calls follow it too.

## 4. Across the two portals

Records are never merged across databases. The designer portal's daily scan calls
`POST /api/cross-portal/peer/artisans/match` with exact keys only (fingerprint, Pehchan number):
at most 100 keys a call and 50 calls a day, extra fields refused, consenting artisans only, every match
logged here, ids only in the answer.

## 5. Data protection

- The pair table carries codes and labels only, never an identifier value. Identity numbers in a
  merge's snapshot are scrubbed after the 30-day undo window (`scrub_expired_merge_snapshots`).
- The pair table has row-level security on and the hosted data-API roles revoked in its migration.
- Nothing merges automatically, at any score.

## How this document is kept true

Tests: `test_artisan_duplicates_scoring.py`, `test_artisan_duplicates_api.py`, `test_artisan_merge.py`,
`test_cross_portal_match.py`; Android `OutboxPossibleDuplicateTest`.
