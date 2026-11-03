import logging

from app.core.config import Settings, get_settings

logger = logging.getLogger("app.scale")

_REPORTED: set[str] = set()


def log_once(key: str, message: str, *args: object) -> None:
    if key in _REPORTED:
        return
    _REPORTED.add(key)
    logger.error(message, *args)


def reset_log_once() -> None:
    _REPORTED.clear()


def settings() -> Settings:
    return get_settings()



def cache_enabled() -> bool:
    return settings().scale_cache_enabled


def cache_backend_name() -> str:
    return (settings().scale_cache_backend or "memory").strip().lower()


def redis_url() -> str | None:
    url = settings().scale_redis_url
    return url.strip() if url and url.strip() else None


def rate_limit_enabled() -> bool:
    return settings().scale_rate_limit_enabled


def keyset_enabled() -> bool:
    return settings().scale_keyset_pagination_enabled


def approx_count_enabled() -> bool:
    return settings().scale_approx_count_enabled


def read_replica_url() -> str | None:
    url = settings().database_read_replica_url
    return url.strip() if url and url.strip() else None


def snapshot() -> dict[str, object]:
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
