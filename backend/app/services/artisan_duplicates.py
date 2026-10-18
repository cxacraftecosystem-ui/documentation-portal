"""Likely-duplicate artisans: score pairs on the server, store the ones worth a look.

THE ONLY SCORER. Web and Android never score; they ask ``POST /artisan-duplicates/check`` and show
what comes back. An offline-created record is scored when it syncs, as part of its create.

WHAT A PAIR IS SCORED ON (the contract shared with the designer portal; the file there is the same
apart from its ``SAME_FIELD_REPOSITORY_LINK`` reason, which only that portal can see):

* Definitive: the same Aadhaar fingerprint (1.0, ``AADHAAR``) or the same Pehchan card number
  (1.0, ``PEHCHAN``).
* Otherwise a pair is considered only when the names are at least half alike (:func:`name_sim`),
  and the score is ``0.55 x nameSim`` plus the stated state and district (+0.15), a place or village
  at least 60% alike by trigrams (+0.10), the same 10-digit phone (+0.15), the same date of birth
  (+0.10), the same craft (+0.05) and the same local name (+0.05), capped at 1.0. Two different
  fingerprints or Pehchan numbers cap it at 0.30, two different genders at 0.40, and birth years
  more than three apart take 0.25 off.
* Bands: VERY_LIKELY >= 0.90, LIKELY >= 0.75 (the hold at save time), POSSIBLE >= 0.60 (shown).
  Below 0.60 nothing is stored.

``shared/duplicate-score-vectors.json`` pins all of it; ``tests/test_artisan_duplicates_scoring.py``
runs the vectors.

PRIVACY. A reason is a code and a short label ("Same district", "Names 92% alike") and never a
value. Matching across the portals is the designer portal's job and is on exact keys only: it asks
this server's ``/cross-portal/peer/artisans/match``, which answers ids for consenting artisans and
nothing else. Names never leave this server.

NO EXTENSIONS, NO NEW COLUMNS. ``pg_trgm`` and ``fuzzystrmatch`` need a superuser, so the trigram
and phonetic work is done here, in Python, on a pre-filtered set of at most 500 rows at save time,
and on in-memory blocks (district, phone, first-name skeleton) in the nightly scan.
"""

from __future__ import annotations

import json
import logging
import re
import unicodedata
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from typing import Any

from app.services.cross_portal_codes import normalise

logger = logging.getLogger("app.artisan_duplicates")

SHOW_THRESHOLD = 0.60
HOLD_THRESHOLD = 0.75
VERY_LIKELY_THRESHOLD = 0.90
UNDO_DAYS = 30
CHECK_MAX_CANDIDATES = 5
PEER_MATCH_BATCH = 100
PREFILTER_LIMIT = 500
#: A block in the nightly scan larger than this is split by the name's first letter, so one big
#: district cannot turn the scan quadratic.
SCAN_BLOCK_LIMIT = 1500
SCAN_PAGE = 1000

BAND_VERY_LIKELY = "VERY_LIKELY"
BAND_LIKELY = "LIKELY"
BAND_POSSIBLE = "POSSIBLE"
BANDS = (BAND_VERY_LIKELY, BAND_LIKELY, BAND_POSSIBLE)

REASON_AADHAAR = "AADHAAR"
REASON_PEHCHAN = "PEHCHAN"
REASON_NAME = "NAME"
REASON_LOCAL_NAME = "LOCAL_NAME"
REASON_DISTRICT = "DISTRICT"
REASON_PLACE = "PLACE"
REASON_PHONE = "PHONE"
REASON_DOB = "DOB"
REASON_CRAFT = "CRAFT"
REASON_DIFFERENT_AADHAAR = "DIFFERENT_AADHAAR"
REASON_DIFFERENT_PEHCHAN = "DIFFERENT_PEHCHAN"
REASON_DIFFERENT_GENDER = "DIFFERENT_GENDER"
REASON_DOB_FAR_APART = "DOB_FAR_APART"

REASON_CODES = (
    REASON_AADHAAR,
    REASON_PEHCHAN,
    REASON_NAME,
    REASON_LOCAL_NAME,
    REASON_DISTRICT,
    REASON_PLACE,
    REASON_PHONE,
    REASON_DOB,
    REASON_CRAFT,
    REASON_DIFFERENT_AADHAAR,
    REASON_DIFFERENT_PEHCHAN,
    REASON_DIFFERENT_GENDER,
    REASON_DOB_FAR_APART,
)

_LABELS = {
    REASON_AADHAAR: "Same Aadhaar",
    REASON_PEHCHAN: "Same Pehchan card",
    REASON_LOCAL_NAME: "Same local name",
    REASON_DISTRICT: "Same district",
    REASON_PLACE: "Same place",
    REASON_PHONE: "Same phone",
    REASON_DOB: "Same date of birth",
    REASON_CRAFT: "Same craft",
    REASON_DIFFERENT_AADHAAR: "Different Aadhaar",
    REASON_DIFFERENT_PEHCHAN: "Different Pehchan card",
    REASON_DIFFERENT_GENDER: "Different gender",
    REASON_DOB_FAR_APART: "Born years apart",
}

# ── Names ─────────────────────────────────────────────────────────────────────────────────────

HONORIFICS = frozenset(
    {"shri", "sri", "shree", "sh", "smt", "shrimati", "srimati", "kumari", "km", "mr", "mrs", "ms", "dr", "late"}
)
_ALIASES = {"mohd": "mohammad", "md": "mohammad"}

#: Applied in this order, each over the whole token.
_PHONETIC_RULES: tuple[tuple[str, str], ...] = (
    ("aa", "a"),
    ("ee", "i"),
    ("ii", "i"),
    ("oo", "u"),
    ("uu", "u"),
    ("ou", "u"),
    ("ow", "u"),
    ("ai", "e"),
    ("ay", "e"),
    ("ei", "e"),
    ("w", "v"),
    ("ph", "f"),
    ("q", "k"),
    ("ck", "k"),
    ("x", "ks"),
    ("z", "j"),
    ("sh", "s"),
    ("ch", "c"),
    ("th", "t"),
    ("dh", "d"),
    ("kh", "k"),
    ("gh", "g"),
    ("bh", "b"),
    ("jh", "j"),
)
_VOWELS = frozenset("aeiou")


def normalise_name(text: str | None) -> str:
    """Folded, honorific-free, ``mohd``/``md`` spelled ``mohammad``, single-spaced."""
    tokens = []
    for token in normalise(text).split():
        if token in HONORIFICS:
            continue
        tokens.append(_ALIASES.get(token, token))
    return " ".join(tokens)


def skeleton(token: str) -> str:
    """The phonetic skeleton of one normalised token: Lakshmi and Laxmi are both ``lksm``."""
    out = token
    for old, new in _PHONETIC_RULES:
        out = out.replace(old, new)
    if not out:
        return ""
    out = out[0] + out[1:].replace("h", "")
    collapsed = [out[0]]
    for ch in out[1:]:
        if ch != collapsed[-1]:
            collapsed.append(ch)
    return collapsed[0] + "".join(ch for ch in collapsed[1:] if ch not in _VOWELS)


def trigrams(text: str) -> set[str]:
    """pg_trgm's trigrams: each word padded with two spaces in front and one behind."""
    grams: set[str] = set()
    for word in text.split():
        padded = f"  {word} "
        grams.update(padded[i : i + 3] for i in range(len(padded) - 2))
    return grams


def trigram_sim(a: str, b: str) -> float:
    """Jaccard of the two trigram sets (pg_trgm's ``similarity``). 0.0 when either is empty."""
    ga, gb = trigrams(a), trigrams(b)
    if not ga or not gb:
        return 0.0
    return len(ga & gb) / len(ga | gb)


def _token_matches(token: str, others: list[str], other_skeletons: set[str]) -> bool:
    if len(token) == 1:
        return any(other.startswith(token) for other in others)
    if skeleton(token) in other_skeletons:
        return True
    return any(len(other) == 1 and token.startswith(other) for other in others)


def _overlap_from(shorter: list[str], longer: list[str]) -> float:
    if not shorter or not longer:
        return 0.0
    skeletons = {skeleton(t) for t in longer if len(t) > 1}
    hits = sum(1 for token in shorter if _token_matches(token, longer, skeletons))
    return hits / len(shorter)


def phonetic_overlap(a: str, b: str) -> float:
    """Share of the shorter name's tokens whose skeleton is in the other (an initial matches a token
    with its first letter). Symmetric: on a tie in token count both directions are tried."""
    ta, tb = a.split(), b.split()
    if len(ta) < len(tb):
        return _overlap_from(ta, tb)
    if len(tb) < len(ta):
        return _overlap_from(tb, ta)
    return max(_overlap_from(ta, tb), _overlap_from(tb, ta))


def name_sim(a: str | None, b: str | None) -> float:
    """max(trigram Jaccard of the normalised names, 0.9 x phonetic token overlap)."""
    na, nb = normalise_name(a), normalise_name(b)
    if not na or not nb:
        return 0.0
    return max(trigram_sim(na, nb), 0.9 * phonetic_overlap(na, nb))


# ── Profiles ──────────────────────────────────────────────────────────────────────────────────


def _clean(value: Any) -> str:
    return normalise(value if isinstance(value, str) else None)


def phone10(value: Any) -> str | None:
    """The last ten digits of a phone number, or None when it has fewer than ten."""
    digits = re.sub(r"[^0-9]", "", str(value or ""))
    return digits[-10:] if len(digits) >= 10 else None


def _as_date(value: Any) -> date | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    text = str(value).strip()
    try:
        return datetime.fromisoformat(text).date()
    except ValueError:
        try:
            return date.fromisoformat(text[:10])
        except ValueError:
            return None


def _gender(value: Any) -> str | None:
    text = _clean(value)
    return text[0] if text else None


def _local(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    return "".join(unicodedata.normalize("NFKC", value).split()).casefold()


@dataclass
class Profile:
    """What the scorer reads of one artisan, from a stored row or from a form payload."""

    id: str | None = None
    name: str = ""
    localName: str | None = None
    gender: str | None = None
    dateOfBirth: date | None = None
    phone: str | None = None
    state: str | None = None
    district: str | None = None
    place: str | None = None
    village: str | None = None
    craftId: str | None = None
    craftCode: str | None = None
    aadhaarFingerprint: str | None = None
    pehchanCardNumber: str | None = None
    # Display only, never scored.
    display: dict[str, Any] = field(default_factory=dict)

    @property
    def norm_name(self) -> str:
        return normalise_name(self.name)

    @property
    def birth_year(self) -> int | None:
        return self.dateOfBirth.year if self.dateOfBirth else None


def profile_from_dict(raw: dict[str, Any]) -> Profile:
    """A profile from a flat dict (a vector, a raw SQL row, a /check body)."""
    return Profile(
        id=raw.get("id"),
        name=raw.get("name") or "",
        localName=raw.get("localName"),
        gender=raw.get("gender"),
        dateOfBirth=_as_date(raw.get("dateOfBirth")),
        phone=phone10(raw.get("phone")),
        state=raw.get("state"),
        district=raw.get("district"),
        place=raw.get("place"),
        village=raw.get("village"),
        craftId=raw.get("craftId"),
        craftCode=raw.get("craftCode"),
        aadhaarFingerprint=raw.get("aadhaarFingerprint"),
        pehchanCardNumber=(raw.get("pehchanCardNumber") or None),
        display={
            "id": raw.get("id"),
            "name": raw.get("name"),
            "localName": raw.get("localName"),
            "place": raw.get("place"),
            "state": raw.get("state"),
            "district": raw.get("district"),
            "craft": raw.get("craftName"),
            "workshop": raw.get("workshopTitle"),
            "birthYear": (_as_date(raw.get("dateOfBirth")).year if _as_date(raw.get("dateOfBirth")) else None),
            "photoUrl": None,
            "createdAt": raw.get("createdAt"),
        },
    )


# ── Scoring ───────────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Score:
    score: float
    band: str
    reasons: tuple[tuple[str, str], ...]

    def reasons_payload(self) -> list[dict[str, str]]:
        return [{"code": code, "label": label} for code, label in self.reasons]

    @property
    def codes(self) -> list[str]:
        return [code for code, _ in self.reasons]


def band_for(score: float) -> str | None:
    if score >= VERY_LIKELY_THRESHOLD:
        return BAND_VERY_LIKELY
    if score >= HOLD_THRESHOLD:
        return BAND_LIKELY
    if score >= SHOW_THRESHOLD:
        return BAND_POSSIBLE
    return None


def _round(value: float) -> float:
    return round(value + 1e-9, 2)


def _same_text(a: str | None, b: str | None) -> bool:
    ca, cb = _clean(a), _clean(b)
    return bool(ca) and ca == cb


def _place_sim(a: Profile, b: Profile) -> float:
    best = 0.0
    for left in (a.place, a.village):
        for right in (b.place, b.village):
            ca, cb = _clean(left), _clean(right)
            if ca and cb:
                best = max(best, 1.0 if ca == cb else trigram_sim(ca, cb))
    return best


def _side_signals(a: Profile, b: Profile, sim: float) -> tuple[float, list[tuple[str, str]]]:
    total = 0.55 * sim
    reasons: list[tuple[str, str]] = []
    if sim > 0:
        reasons.append((REASON_NAME, f"Names {round(sim * 100)}% alike"))
    if _same_text(a.state, b.state) and _same_text(a.district, b.district):
        total += 0.15
        reasons.append((REASON_DISTRICT, _LABELS[REASON_DISTRICT]))
    if _place_sim(a, b) >= 0.6:
        total += 0.10
        reasons.append((REASON_PLACE, _LABELS[REASON_PLACE]))
    if a.phone and a.phone == b.phone:
        total += 0.15
        reasons.append((REASON_PHONE, _LABELS[REASON_PHONE]))
    if a.dateOfBirth and a.dateOfBirth == b.dateOfBirth:
        total += 0.10
        reasons.append((REASON_DOB, _LABELS[REASON_DOB]))
    if (a.craftId and a.craftId == b.craftId) or (
        a.craftCode and _clean(a.craftCode) == _clean(b.craftCode)
    ):
        total += 0.05
        reasons.append((REASON_CRAFT, _LABELS[REASON_CRAFT]))
    la, lb = _local(a.localName), _local(b.localName)
    if la and la == lb:
        total += 0.05
        reasons.append((REASON_LOCAL_NAME, _LABELS[REASON_LOCAL_NAME]))
    return total, reasons


def score_pair(a: Profile, b: Profile) -> Score | None:
    """The score, band and reasons for two artisans, or None below the show threshold."""
    definitive: list[tuple[float, str]] = []
    if a.aadhaarFingerprint and a.aadhaarFingerprint == b.aadhaarFingerprint:
        definitive.append((1.0, REASON_AADHAAR))
    if a.pehchanCardNumber and a.pehchanCardNumber == b.pehchanCardNumber:
        definitive.append((1.0, REASON_PEHCHAN))
    sim = name_sim(a.name, b.name)
    if definitive:
        best = max(score for score, _ in definitive)
        reasons = [(code, _LABELS[code]) for _, code in definitive]
        _, extra = _side_signals(a, b, sim)
        reasons.extend(extra)
        return Score(_round(best), BAND_VERY_LIKELY, tuple(reasons))
    if sim < 0.5:
        return None
    total, reasons = _side_signals(a, b, sim)
    total = min(total, 1.0)
    if a.aadhaarFingerprint and b.aadhaarFingerprint and a.aadhaarFingerprint != b.aadhaarFingerprint:
        total = min(total, 0.30)
        reasons.append((REASON_DIFFERENT_AADHAAR, _LABELS[REASON_DIFFERENT_AADHAAR]))
    if a.pehchanCardNumber and b.pehchanCardNumber and a.pehchanCardNumber != b.pehchanCardNumber:
        total = min(total, 0.30)
        reasons.append((REASON_DIFFERENT_PEHCHAN, _LABELS[REASON_DIFFERENT_PEHCHAN]))
    ga, gb = _gender(a.gender), _gender(b.gender)
    if ga and gb and ga != gb:
        total = min(total, 0.40)
        reasons.append((REASON_DIFFERENT_GENDER, _LABELS[REASON_DIFFERENT_GENDER]))
    if a.birth_year and b.birth_year and abs(a.birth_year - b.birth_year) > 3:
        total -= 0.25
        reasons.append((REASON_DOB_FAR_APART, _LABELS[REASON_DOB_FAR_APART]))
    total = _round(max(total, 0.0))
    band = band_for(total)
    if band is None:
        return None
    return Score(total, band, tuple(reasons))


def score_or_floor(a: Profile, b: Profile) -> tuple[float, list[dict[str, str]]]:
    """The score with no threshold (the merge preview shows a pair that has since drifted)."""
    result = score_pair(a, b)
    if result is not None:
        return result.score, result.reasons_payload()
    return 0.0, []


# ── Loading profiles (raw SQL: minimal columns, no ORM rows) ──────────────────────────────────

_PROFILE_SELECT = """
SELECT a."id", a."name", a."localName", a."gender", a."phone", a."dateOfBirth", a."place",
       a."craftId", a."aadhaarFingerprint", a."pehchanCardNumber", a."createdAt",
       l."state", l."district", l."village",
       c."craftCode", c."name" AS "craftName",
       w."title" AS "workshopTitle"
FROM "Artisan" a
LEFT JOIN "Location" l ON l."id" = a."locationId"
LEFT JOIN "Craft" c ON c."id" = a."craftId"
LEFT JOIN "Workshop" w ON w."id" = a."workshopId"
"""


def _client(client: Any = None) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def load_profiles(ids: list[str], *, client: Any = None) -> dict[str, Profile]:
    if not ids:
        return {}
    rows = await _client(client).query_raw(
        _PROFILE_SELECT + ' WHERE a."id" = ANY($1::text[])', list(dict.fromkeys(ids))
    )
    return {row["id"]: profile_from_dict(row) for row in rows}


async def load_profile(artisan_id: str, *, client: Any = None) -> Profile | None:
    return (await load_profiles([artisan_id], client=client)).get(artisan_id)


async def prefilter(profile: Profile, *, exclude_id: str | None = None, client: Any = None) -> list[Profile]:
    """At most 500 artisans that could pair with ``profile``: the same stated state, the same
    phone, any name token of three letters or more, or the same fingerprint or Pehchan number."""
    clauses: list[str] = []
    params: list[Any] = []

    def param(value: Any) -> str:
        params.append(value)
        return f"${len(params)}"

    if profile.aadhaarFingerprint:
        clauses.append(f'a."aadhaarFingerprint" = {param(profile.aadhaarFingerprint)}')
    if profile.pehchanCardNumber:
        clauses.append(f'a."pehchanCardNumber" = {param(profile.pehchanCardNumber)}')
    if _clean(profile.state):
        clauses.append(f'lower(btrim(l."state")) = lower(btrim({param(profile.state)}))')
    if profile.phone:
        clauses.append(
            "regexp_replace(coalesce(a.\"phone\", ''), '[^0-9]', '', 'g') LIKE "
            f"'%' || {param(profile.phone)}"
        )
    tokens = [t for t in profile.norm_name.split() if len(t) >= 3][:6]
    for token in tokens:
        clauses.append(f'a."name" ILIKE {param("%" + token + "%")}')
    if not clauses:
        return []
    where = "(" + " OR ".join(clauses) + ")"
    if exclude_id:
        where += f' AND a."id" <> {param(exclude_id)}'
    sql = _PROFILE_SELECT + f" WHERE {where} ORDER BY a.\"createdAt\" DESC LIMIT {PREFILTER_LIMIT}"
    rows = await _client(client).query_raw(sql, *params)
    return [profile_from_dict(row) for row in rows]


async def candidates_for_profile(
    profile: Profile,
    *,
    exclude_id: str | None = None,
    limit: int | None = None,
    client: Any = None,
) -> list[tuple[Profile, Score]]:
    """Every stored artisan scoring at least 0.60 against ``profile``, best first."""
    found: list[tuple[Profile, Score]] = []
    for other in await prefilter(profile, exclude_id=exclude_id, client=client):
        if other.id == profile.id:
            continue
        result = score_pair(profile, other)
        if result is not None:
            found.append((other, result))
    found.sort(key=lambda pair: (-pair[1].score, str(pair[0].display.get("createdAt") or "")))
    return found[:limit] if limit else found


def candidate_payload(other: Profile, result: Score, *, typed_aadhaar: bool) -> dict[str, Any]:
    """One candidate as /check and the 409 hold return it. No identifier value, birth year only.

    The AADHAAR reason is kept only when the caller typed that number themselves."""
    reasons = [
        r for r in result.reasons_payload() if typed_aadhaar or r["code"] != REASON_AADHAAR
    ]
    created = other.display.get("createdAt")
    return {
        "artisan": {**other.display, "createdAt": created.isoformat() if isinstance(created, datetime) else created},
        "score": result.score,
        "band": result.band,
        "reasons": reasons,
    }


# ── Storing pairs ─────────────────────────────────────────────────────────────────────────────

_UPSERT_WITHIN = """
INSERT INTO "ArtisanDuplicateCandidate"
  ("id", "kind", "artisanAId", "artisanBId", "score", "band", "reasons", "status", "source",
   "creatorSaidDifferent", "scannedAt", "createdAt", "updatedAt")
VALUES ($1, 'WITHIN', $2, $3, $4::double precision, $5, $6::jsonb, 'OPEN', $7::"DuplicateSource",
        $8::boolean, (now() AT TIME ZONE 'utc'), (now() AT TIME ZONE 'utc'), (now() AT TIME ZONE 'utc'))
ON CONFLICT ("artisanAId", "artisanBId") WHERE "kind" = 'WITHIN'
DO UPDATE SET "score" = EXCLUDED."score", "band" = EXCLUDED."band", "reasons" = EXCLUDED."reasons",
  "scannedAt" = EXCLUDED."scannedAt", "updatedAt" = EXCLUDED."updatedAt",
  "creatorSaidDifferent" = "ArtisanDuplicateCandidate"."creatorSaidDifferent" OR EXCLUDED."creatorSaidDifferent"
RETURNING "id"
"""

def _new_id() -> str:
    return "c" + uuid.uuid4().hex[:24]


def ordered(a_id: str, b_id: str) -> tuple[str, str]:
    return (a_id, b_id) if a_id < b_id else (b_id, a_id)


async def upsert_within(
    a_id: str,
    b_id: str,
    result: Score,
    *,
    source: str = "SCAN",
    creator_said_different: bool = False,
    client: Any = None,
) -> str | None:
    """Store or refresh one pair. A decided pair keeps its status; only score and reasons move."""
    if a_id == b_id:
        return None
    first, second = ordered(a_id, b_id)
    rows = await _client(client).query_raw(
        _UPSERT_WITHIN,
        _new_id(),
        first,
        second,
        float(result.score),
        result.band,
        json.dumps(result.reasons_payload()),
        source,
        bool(creator_said_different),
    )
    return rows[0]["id"] if rows else None


async def store_candidates(
    artisan_id: str,
    found: list[tuple[Profile, Score]],
    *,
    source: str,
    acknowledged: set[str] | frozenset[str] = frozenset(),
    client: Any = None,
) -> int:
    stored = 0
    for other, result in found:
        if not other.id:
            continue
        await upsert_within(
            artisan_id,
            other.id,
            result,
            source=source,
            creator_said_different=other.id in acknowledged,
            client=client,
        )
        stored += 1
    return stored


async def rescore_artisan(artisan_id: str, *, source: str = "SCAN", client: Any = None) -> int:
    """Re-score one artisan against everyone and refresh its pairs. Drops OPEN pairs it no longer
    makes (a decided pair, or one the creator already called different people, is kept as the
    decision it records). Never raises into a request."""
    try:
        profile = await load_profile(artisan_id, client=client)
        if profile is None:
            return 0
        found = await candidates_for_profile(profile, exclude_id=artisan_id, client=client)
        stored = await store_candidates(artisan_id, found, source=source, client=client)
        keep = [other.id for other, _ in found if other.id]
        await _client(client).execute_raw(
            """DELETE FROM "ArtisanDuplicateCandidate"
               WHERE "kind" = 'WITHIN' AND "status" = 'OPEN' AND "creatorSaidDifferent" = false
                 AND ("artisanAId" = $1 OR "artisanBId" = $1)
                 AND NOT (CASE WHEN "artisanAId" = $1 THEN "artisanBId" ELSE "artisanAId" END = ANY($2::text[]))""",
            artisan_id,
            keep,
        )
        return stored
    except Exception:
        logger.exception("duplicate re-score failed for an artisan")
        return 0


# ── The nightly scan ──────────────────────────────────────────────────────────────────────────


async def _all_profiles(client: Any = None) -> list[Profile]:
    out: list[Profile] = []
    after = ""
    while True:
        rows = await _client(client).query_raw(
            _PROFILE_SELECT + f' WHERE a."id" > $1 ORDER BY a."id" ASC LIMIT {SCAN_PAGE}', after
        )
        if not rows:
            break
        out.extend(profile_from_dict(row) for row in rows)
        after = rows[-1]["id"]
        if len(rows) < SCAN_PAGE:
            break
    return out


def _blocks(profiles: list[Profile]) -> list[list[Profile]]:
    keyed: dict[tuple[str, str], list[Profile]] = defaultdict(list)
    for p in profiles:
        if _clean(p.state) and _clean(p.district):
            keyed[("d", f"{_clean(p.state)}|{_clean(p.district)}")].append(p)
        if p.phone:
            keyed[("p", p.phone)].append(p)
        tokens = p.norm_name.split()
        if tokens:
            keyed[("n", skeleton(tokens[0]))].append(p)
        if p.aadhaarFingerprint:
            keyed[("a", p.aadhaarFingerprint)].append(p)
        if p.pehchanCardNumber:
            keyed[("c", p.pehchanCardNumber)].append(p)
    blocks: list[list[Profile]] = []
    for group in keyed.values():
        if len(group) < 2:
            continue
        if len(group) <= SCAN_BLOCK_LIMIT:
            blocks.append(group)
            continue
        by_letter: dict[str, list[Profile]] = defaultdict(list)
        for p in group:
            by_letter[p.norm_name[:1]].append(p)
        blocks.extend(g for g in by_letter.values() if len(g) > 1)
    return blocks


def scan_pairs(profiles: list[Profile]) -> dict[tuple[str, str], Score]:
    """Every pair at or above 0.60 among ``profiles``, found by blocking (pure; tests call it)."""
    seen: set[tuple[str, str]] = set()
    found: dict[tuple[str, str], Score] = {}
    for block in _blocks(profiles):
        for i, left in enumerate(block):
            for right in block[i + 1 :]:
                if not left.id or not right.id or left.id == right.id:
                    continue
                key = ordered(left.id, right.id)
                if key in seen:
                    continue
                seen.add(key)
                result = score_pair(left, right)
                if result is not None:
                    found[key] = result
    return found


async def run_within_scan(client: Any = None) -> int:
    profiles = await _all_profiles(client)
    found = scan_pairs(profiles)
    for (a_id, b_id), result in found.items():
        await upsert_within(a_id, b_id, result, source="SCAN", client=client)
    return len(found)


_DAY = timedelta(days=1)
_next_run: datetime | None = None


async def run_scan(settings: Any = None) -> dict[str, int]:
    """The nightly within-portal scan. This portal never asks the other for matches."""
    return {"within": await run_within_scan()}


async def maybe_run(settings: Any, *, now: datetime | None = None) -> dict[str, int] | None:
    """At most once a day per process. The worker calls this every loop; cheap when not due."""
    global _next_run
    now = now or datetime.now(UTC)
    if _next_run is not None and now < _next_run:
        return None
    _next_run = now.replace(microsecond=0) + _DAY
    return await run_scan(settings)
