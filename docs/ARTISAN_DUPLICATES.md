# Duplicate artisans: finding, holding, merging

One person recorded twice is two half-records: the interviews sit on one, the photographs on the other,
and a count of artisans is wrong by one. This feature finds likely duplicates, asks before a new one is
made, and lets an admin merge a pair on one screen, reversibly for 30 days.

Code: `backend/app/services/artisan_duplicates.py` (scoring, the scan),
`backend/app/services/artisan_merge.py` (merge, undo, redirect, purge),
`backend/app/api/routes/artisan_duplicates.py` (the API), and the peer match in
`backend/app/api/routes/cross_portal.py`. Web: `frontend/components/duplicates/`,
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

**Admin → Duplicates** lists open pairs by score. The pair screen shows both records field by field:
A or B, Both for address, notes, do's and don'ts, Aadhaar and Pehchan locked to the side that has
them. **Blockers** refuse the merge: different fingerprints or Pehchan numbers, an un-backfilled legacy
Aadhaar, and an interview set that would collide (merge the interviews first).

**Sharing consent:** the more recent of the two decisions wins on the survivor. A winning withdrawal
removes the survivor's designer-portal links, as a withdrawal always does.

A merge runs in one transaction: the deleted record's keys are cleared, the survivor is written, every
row in `MERGE_REPOINT` is re-pointed and logged, a revision is written, and the deleted record is
removed. `backend/tests/test_artisan_merge_registry.py` holds `MERGE_REPOINT` to `schema.prisma`.

**Redirect.** `GET /api/artisans/{id}` on a merged id answers `404 {"code":"artisan_merged",
"survivorId":…}`. Products, tools, questionnaires, media and workshops rewrite a merged id to the
survivor, and the designer portal's peer calls follow it.

**Undo** (30 days) recreates the record with its id and moves back the rows still pointing at the
survivor. A field goes back whole or not at all: if any column of a field (the three experience
columns, the four review-status columns, the fingerprint and its last four) was edited since the merge,
the whole field keeps its current value and is named in `keptSurvivorEdits`. An undone pair can be
merged again: only a live merge holds the deleted id (a partial unique index on `undoneAt IS NULL`).

## 4. Across the two portals

Records are never merged across databases. The designer portal's daily scan calls
`POST /api/cross-portal/peer/artisans/match` with exact keys only (fingerprint, Pehchan number):
at most 100 keys a call and 50 calls a day, extra fields refused, consenting artisans only, every match
logged here, ids only in the answer.

## 5. Data protection

- The merge snapshot holds the deleted row without `aadhaarNumber`, and both consent rows. It is
  purged after 30 days ([SECURITY.md](SECURITY.md) §4A.3).
- Both tables have row-level security on and the hosted data-API roles revoked in their migration.
- Nothing merges automatically, at any score.

## How this document is kept true

Tests: `test_artisan_duplicates_scoring.py`, `test_artisan_duplicates_api.py`, `test_artisan_merge.py`,
`test_artisan_merge_registry.py`, `test_cross_portal_match.py`; Android `OutboxPossibleDuplicateTest`.
