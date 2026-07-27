"""Flag reads, and the fail-soft plumbing every optional layer in this package shares.

WHY THIS MODULE EXISTS AT ALL. Everything under ``app.scale`` is dormant by default, and "dormant"
has to mean something stricter than "returns early". A disabled layer must cost one boolean read on
the hot path, no import of an optional package, and no object built at boot. Concentrating the flag
reads here means there is exactly ONE place to audit that promise, and exactly one place a reviewer
has to look to answer "what does a fresh clone with no new variables do?" — the answer being: every
predicate below returns False, so no other module in this package is ever reached.

``log_once`` lives here rather than in a logging module of its own because a flag and its
degradation notice are the same decision seen from two sides: the flag says "you asked for this",
``log_once`` says "you asked and I could not deliver, here is why, once". A layer that degrades
silently is worse than one that was never enabled, and a layer that logs on every request turns a
missing package into a disk-filling incident of its own.
"""

import logging

from app.core.config import Settings, get_settings

logger = logging.getLogger("app.scale")

# Keys already reported by log_once. Bounded by the number of distinct degradation KINDS in the
# code (a handful), never by traffic — the key is always a literal plus an exception class name.
_REPORTED: set[str] = set()


def log_once(key: str, message: str, *args: object) -> None:
    """Report a degradation exactly once per process, at ERROR level.

    ERROR rather than WARNING on purpose: reaching any of these call sites means an operator
    deliberately turned something on and it is not running. That is a configuration fault someone
    has to see, even though the request itself succeeded on the fallback path.

    ``key`` should name the failure KIND and include the exception class where there is one, so a
    Redis that first times out and later refuses the connection produces two lines rather than one.
    """
    if key in _REPORTED:
        return
    _REPORTED.add(key)
    logger.error(message, *args)


def reset_log_once() -> None:
    """Forget which degradations have been reported. For tests and the self-check only."""
    _REPORTED.clear()


def settings() -> Settings:
    """The process-wide settings object (``get_settings`` is lru_cached, so this is a dict hit)."""
    return get_settings()


# --- The flags -------------------------------------------------------------------------------
# Each returns False / None on a fresh clone. Callers are expected to check the flag BEFORE
# importing anything from the module that implements the feature.


def cache_enabled() -> bool:
    return settings().scale_cache_enabled


def cache_backend_name() -> str:
    """``memory`` (default) or ``redis``. Only consulted once the cache flag is already on."""
    return (settings().scale_cache_backend or "memory").strip().lower()


def redis_url() -> str | None:
    """The Redis connection string, or None. A secret — never log it, log ``bool(...)`` of it."""
    url = settings().scale_redis_url
    return url.strip() if url and url.strip() else None


def rate_limit_enabled() -> bool:
    return settings().scale_rate_limit_enabled


def keyset_enabled() -> bool:
    return settings().scale_keyset_pagination_enabled


def approx_count_enabled() -> bool:
    return settings().scale_approx_count_enabled


def read_replica_url() -> str | None:
    """The read-only database URL, or None. Presence IS the flag — there is no second switch."""
    url = settings().database_read_replica_url
    return url.strip() if url and url.strip() else None


def snapshot() -> dict[str, object]:
    """Every scale flag and its current value, for logging and the self-check.

    Deliberately carries no URL and no secret: the Redis URL may embed a password and the replica
    URL certainly does, so both are reduced to a boolean.
    """
    current = settings()
    return {
        "cache": current.scale_cache_enabled,
        "cacheBackend": cache_backend_name() if current.scale_cache_enabled else None,
        "cacheTtlSeconds": current.scale_cache_ttl_seconds,
        "cacheMaxEntries": current.scale_cache_max_entries,
        "cacheMaxBytes": current.scale_cache_max_bytes,
        "cacheMaxEntryBytes": current.scale_cache_max_entry_bytes,
        "cacheSingleflightTimeoutSeconds": current.scale_cache_singleflight_timeout_seconds,
        "redisUrlConfigured": bool(redis_url()),
        "keysetPagination": current.scale_keyset_pagination_enabled,
        "approxCount": current.scale_approx_count_enabled,
        "approxCountThreshold": current.scale_approx_count_threshold,
        "rateLimit": current.scale_rate_limit_enabled,
        "rateLimitRequests": current.scale_rate_limit_requests,
        "rateLimitWindowSeconds": current.scale_rate_limit_window_seconds,
        "readReplicaConfigured": bool(read_replica_url()),
    }
