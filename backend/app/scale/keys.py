"""The cache key scheme, and the invalidation families keys are built from.

READ THIS BEFORE CACHING ANYTHING. Two properties of this codebase decide the whole scheme:

1. A LIST RESPONSE IS NOT THE SAME FOR TWO CALLERS. ``records.public_encode`` masks Aadhaar and
   Pehchan numbers PER VIEWER — unmasked for professor-and-above and for the researcher who recorded
   that particular artisan, masked for everybody else — so a key that omits WHO IS ASKING serves one
   person's identity numbers to someone who may not read them. Every key therefore carries an
   audience component, and the default audience is the individual user.

   Which ROWS come back is no longer part of this: reading is open to every signed-in account
   (``records.viewable_where`` returns an empty filter for everyone), so two callers of the same list
   with the same parameters now see the same rows. That makes the per-user audience component
   conservative rather than load-bearing for row scoping — it over-keys, costing cache sharing and
   nothing else. Do NOT relax it to a role or a global audience on that basis: the masking above is
   per-INDIVIDUAL, not per-rank, so a role-wide key would leak the numbers between two researchers of
   equal rank.

2. INVALIDATION MUST NOT ENUMERATE KEYS. There is no cheap "delete every artisan list page" in
   either backend: in Redis it is a SCAN over the keyspace, which is O(keyspace) and races with
   writes. So keys embed a GENERATION counter for the family they belong to, and invalidation is a
   single atomic increment of that counter. Every key minted from the old generation becomes
   unreachable at once and ages out on its own TTL — invalidation is O(1) and cannot half-finish.

The layout::

    fr:v1:artisan.list:7:u:clx9…:1f4c9a2b3d5e6f70
    │  │  │            │ │      └─ fingerprint: sha1 of the canonical filter+page parameters
    │  │  │            │ └──────── audience: u:<userId> | r:<ROLE> | all
    │  │  │            └────────── generation of the family this namespace belongs to
    │  │  └─────────────────────── namespace: what is cached
    │  └────────────────────────── key-format version: bump when the stored ENCODING changes
    └───────────────────────────── product prefix, so a shared Redis stays legible
"""

import hashlib
import json
from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import Any, Mapping

KEY_PREFIX = "fr"

# Bump when the stored VALUE encoding changes (today: UTF-8 JSON of the jsonable-encoded payload).
# A bump orphans every existing entry instead of letting a new reader mis-parse an old one; the
# orphans expire on their own TTL, which is why the TTLs here are deliberately short.
KEY_FORMAT_VERSION = "v1"

PUBLIC_AUDIENCE = "all"

# --- Families ---------------------------------------------------------------------------------
# A family is the unit of invalidation: one counter, incremented when anything in it changes.
# ``records`` is the aggregate family that EVERY record mutation also bumps — the cross-type views
# (dashboard totals, search, the data tree) depend on all of them, and a view that reads six tables
# is not worth six separate dependency counters when one shared counter is exactly as correct.
FAMILY_RECORDS = "records"

FAMILIES: frozenset[str] = frozenset(
    {
        "artisan",
        "craft",
        "process",
        "product",
        "tool",
        "workshop",
        "media",
        "questionnaire",
        "task",
        "user",
        "reference",
        "release",
        FAMILY_RECORDS,
    }
)

# namespace -> the family whose generation the key carries. A namespace missing from this map is a
# programming error, caught loudly in ``key_family`` rather than silently caching under a family
# nothing ever invalidates.
NAMESPACE_FAMILY: dict[str, str] = {
    # Per-type list pages: invalidated by writes to their own table only.
    "artisan.list": "artisan",
    "craft.list": "craft",
    "process.list": "process",
    "product.list": "product",
    "tool.list": "tool",
    "workshop.list": "workshop",
    "media.list": "media",
    "questionnaire.interviews": "questionnaire",
    "questionnaire.questions": "questionnaire",
    "task.list": "task",
    "task.batches": "task",
    "user.directory": "user",
    "reference.address": "reference",
    "workshop.assignments": "workshop",
    # The Android update check. Every installed phone polls it and the answer changes a few times a
    # year, so it is the one namespace here where the cache does almost all the work.
    "app.release.latest": "release",
    # Cross-type views: any record mutation moves them, so they ride the aggregate family.
    "dashboard.stats": FAMILY_RECORDS,
    "search.results": FAMILY_RECORDS,
    "data.tree": FAMILY_RECORDS,
    "data.browser": FAMILY_RECORDS,
    "review.pending": FAMILY_RECORDS,
}

# Per-namespace lifetime overrides, in seconds. A namespace absent from this map uses
# SCALE_CACHE_TTL_SECONDS.
#
# HOW TO CHOOSE ONE. The TTL is NOT how fresh the data is — explicit invalidation on write already
# guarantees that for anything this process does. The TTL answers a narrower question: how long may
# this response lag a change this process never saw? There are exactly three such changes here —
# the separate `fieldrepo-queue` service writing transcripts and measurements onto media rows, a
# second uvicorn worker's private memory cache, and somebody at a psql prompt. So the values below
# are shortest where an unseen writer is most likely (media, review) and longest where the answer is
# effectively static (reference data, the current app release).
NAMESPACE_TTL_SECONDS: dict[str, float] = {
    "media.list": 20.0,  # the queue worker mutates these rows behind our back
    "review.pending": 15.0,  # two reviewers working the same queue must not see a ghost item
    "search.results": 20.0,
    "dashboard.stats": 60.0,  # measured at 10.5s live; a minute of lag on a totals tile is cheap
    "data.tree": 60.0,
    "data.browser": 60.0,
    "user.directory": 120.0,
    "reference.address": 300.0,  # districts and states do not change during a working day
    "app.release.latest": 300.0,
}

# Which families a mutation to a given record type invalidates. Every entry also bumps the
# aggregate family — creating an artisan changes the artisan list AND the dashboard totals AND what
# search returns. Keys are the record-type strings the routes already use.
RECORD_FAMILIES: dict[str, tuple[str, ...]] = {
    "artisan": ("artisan", FAMILY_RECORDS),
    "craft": ("craft", "artisan", FAMILY_RECORDS),  # a renamed craft shows up on artisan rows
    "process": ("process", FAMILY_RECORDS),
    "product": ("product", FAMILY_RECORDS),
    "tool": ("tool", FAMILY_RECORDS),
    "workshop": ("workshop", FAMILY_RECORDS),
    "media": ("media", FAMILY_RECORDS),
    "questionnaire": ("questionnaire", FAMILY_RECORDS),
    "interview": ("questionnaire", FAMILY_RECORDS),
    "task": ("task",),
    "user": ("user",),
    "workshopAssignment": ("workshop",),
    "release": ("release",),
}


def key_family(namespace: str) -> str:
    """The invalidation family a namespace belongs to.

    Raises rather than defaulting: an unregistered namespace would be cached under a counter no
    mutation ever bumps, i.e. data that only ever expires by TTL. That is precisely the "cache with
    no invalidation story" this package is supposed to avoid, so it fails at the call site instead.
    """
    try:
        return NAMESPACE_FAMILY[namespace]
    except KeyError:
        raise KeyError(
            f"Unknown cache namespace {namespace!r}. Register it in "
            "app/scale/keys.py::NAMESPACE_FAMILY together with the family that invalidates it."
        ) from None


def ttl_for(namespace: str, default_seconds: float) -> float:
    """How long an entry in this namespace may live. See NAMESPACE_TTL_SECONDS for how to pick one."""
    return NAMESPACE_TTL_SECONDS.get(namespace, default_seconds)


def families_for_record(record_type: str) -> tuple[str, ...]:
    """Families a write to ``record_type`` invalidates, or () when the type is not registered.

    Returning () rather than raising: this is called from the write path, and a write must never
    fail because of a cache. An unregistered type simply invalidates nothing — which is visible as
    stale reads, not as a lost record. ``invalidate_record`` logs the miss once so it gets fixed.
    """
    return RECORD_FAMILIES.get(record_type, ())


def audience_for(user: Any) -> str:
    """The audience component for a caller: ``u:<userId>``.

    Per-user, not per-role, because visibility below professor is decided by ownership AND by which
    grants that individual holds — two researchers with the same role legitimately see different
    rows. Per-role keys would collapse them onto one entry. The cost is a lower hit rate, and a low
    hit rate is the correct price for not mixing up whose data this is.
    """
    identifier = user.get("id") if isinstance(user, Mapping) else getattr(user, "id", None)
    return f"u:{identifier}" if identifier else PUBLIC_AUDIENCE


def role_audience(user: Any) -> str:
    """Audience for responses that vary ONLY by rank — reference data, option lists, enums.

    Use it deliberately and only where the query carries no owner/grant filter at all: it shares one
    entry across every user of a role, so a response that quietly depends on the individual would
    leak between them.
    """
    role = user.get("role") if isinstance(user, Mapping) else getattr(user, "role", None)
    return f"r:{getattr(role, 'value', role)}"


def _canonical(value: Any) -> Any:
    """Reduce a query parameter to something JSON-canonical, or raise for anything surprising.

    Raising is the point. A parameter this function does not recognise would otherwise land in the
    fingerprint as its ``repr``, and two distinct objects that share a repr would share a cache
    entry. The caller catches this and skips the cache for that request, which is slower but can
    never be wrong.
    """
    if value is None or isinstance(value, (str, bool, int, float)):
        return value
    if isinstance(value, Enum):
        return _canonical(value.value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (list, tuple)):
        return [_canonical(item) for item in value]
    if isinstance(value, Mapping):
        return {str(k): _canonical(v) for k, v in sorted(value.items(), key=lambda kv: str(kv[0]))}
    raise TypeError(f"{type(value).__name__} is not a cacheable query parameter")


def fingerprint(params: Mapping[str, Any]) -> str:
    """A stable short digest of the filter/page parameters that produced a response.

    ``None`` values are dropped so "filter absent" and "filter explicitly null" — the same thing to
    every route here, whose optional query parameters all default to None — cannot mint two keys for
    one answer. Sorted keys make the digest independent of argument order.

    sha1 is not a security boundary here: the digest is compared against keys we minted ourselves,
    and a collision would need an attacker who can choose both parameter sets AND already holds the
    audience component of the key, which is their own user id.
    """
    canonical = {
        key: _canonical(value) for key, value in sorted(params.items()) if value is not None
    }
    encoded = json.dumps(canonical, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha1(encoded.encode("utf-8")).hexdigest()[:16]


def entry_key(namespace: str, generation: int, audience: str, digest: str) -> str:
    """Assemble the full key. See the module docstring for the layout."""
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:{namespace}:{generation}:{audience}:{digest}"


def namespace_prefix(namespace: str) -> str:
    """Key prefix for every entry of one namespace, for the memory backend's eviction sweep."""
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:{namespace}:"


def generation_key(family: str) -> str:
    """Key of the family's generation counter (Redis) / its name in the counter map (memory)."""
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:gen:{family}"


def rate_limit_key(identity: str) -> str:
    """Key of one caller's request window. Shares the product prefix so a Redis holding both the
    cache and the limiter stays readable, but carries no generation — a rate window is not
    invalidated, it expires."""
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:rl:{identity}"


def namespaces_in_family(family: str) -> tuple[str, ...]:
    """Every namespace whose keys carry ``family``'s generation."""
    return tuple(name for name, owner in NAMESPACE_FAMILY.items() if owner == family)
