"""Optional scaling layer: caching, cursor pagination, approximate counts, rate limiting, replica
routing. Every part of it is off until an environment variable turns it on, and nothing in the
request path imports it until a route deliberately adopts one of these helpers.

READ backend/app/scale/README.md FIRST. It has the variable table, the defaults, what each layer
costs when off, and how to verify that turning one on actually took effect.

THE ONE-LINE VERSION OF THE DESIGN. A fresh clone sets none of these variables, so every predicate
in ``flags.py`` returns False, every helper here short-circuits to the code that runs today, and no
optional dependency is imported. Turning something on is meant to be reversible by deleting a line
from ``.env`` and restarting — which is only true if the enabled path WRAPS the existing one instead
of replacing it, and that is the property to preserve when extending this package.

IMPORTING THIS PACKAGE IS FREE. Everything below is standard library, pydantic-core (already loaded)
and starlette (already loaded). ``redis`` is imported inside a function, after the flag check, and
the Prisma client for the replica is constructed inside a function too — so neither an absent
package nor an unconfigured replica can cost anything at import time.
"""

from app.scale.cache import (
    cache_stats,
    cached_response,
    invalidate_all,
    invalidate_families,
    invalidate_record,
    reset_cache,
    shared_window_hit,
)
from app.scale.counts import count_payload, total_for
from app.scale.flags import (
    approx_count_enabled,
    cache_backend_name,
    cache_enabled,
    keyset_enabled,
    rate_limit_enabled,
    read_replica_url,
    snapshot,
)
from app.scale.keys import (
    FAMILY_RECORDS,
    PUBLIC_AUDIENCE,
    audience_for,
    fingerprint,
    role_audience,
)
from app.scale.keyset import (
    Cursor,
    after_where,
    decode_cursor,
    encode_cursor,
    next_cursor,
    order_by,
    with_cursor,
)
from app.scale.rate_limit import install_rate_limit
from app.scale.replica import (
    close_replica,
    read_via_replica,
    reader,
    replica_configured,
    replica_status,
)

__all__ = [
    # flags — ask these before doing anything, and to report configuration
    "cache_enabled",
    "cache_backend_name",
    "keyset_enabled",
    "approx_count_enabled",
    "rate_limit_enabled",
    "read_replica_url",
    "snapshot",
    # cache — read through it, and invalidate it on every write
    "cached_response",
    "invalidate_record",
    "invalidate_families",
    "invalidate_all",
    "cache_stats",
    "reset_cache",
    "shared_window_hit",
    # keys — the audience component is not optional; see keys.py for why
    "audience_for",
    "role_audience",
    "fingerprint",
    "PUBLIC_AUDIENCE",
    "FAMILY_RECORDS",
    # keyset pagination
    "Cursor",
    "encode_cursor",
    "decode_cursor",
    "after_where",
    "order_by",
    "next_cursor",
    "with_cursor",
    # counts
    "total_for",
    "count_payload",
    # rate limiting — install_rate_limit adds nothing when the flag is off
    "install_rate_limit",
    # read replica
    "reader",
    "read_via_replica",
    "replica_configured",
    "replica_status",
    "close_replica",
]
