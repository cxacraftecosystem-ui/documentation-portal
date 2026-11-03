import asyncio
import logging
from contextlib import suppress
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from prisma import Prisma

from app.core.config import get_settings

logger = logging.getLogger(__name__)

_POOLER_HOST_SUFFIX = ".pooler.supabase.com"


def build_runtime_database_url(base_url: str) -> str:
    settings = get_settings()
    if not settings.database_use_transaction_pooler:
        return base_url
    try:
        parts = urlsplit(base_url)
    except ValueError:
        return base_url
    host = parts.hostname or ""
    if not host.endswith(_POOLER_HOST_SUFFIX):
        return base_url

    userinfo = ""
    if parts.username:
        userinfo = parts.username
        if parts.password:
            userinfo += f":{parts.password}"
        userinfo += "@"
    netloc = f"{userinfo}{host}:6543"

    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["pgbouncer"] = "true"
    query["connection_limit"] = str(settings.database_connection_limit)
    if settings.database_pool_timeout is not None:
        query["pool_timeout"] = str(settings.database_pool_timeout)

    return urlunsplit((parts.scheme, netloc, parts.path, urlencode(query), parts.fragment))


db = Prisma(datasource={"url": build_runtime_database_url(get_settings().database_url)})


async def connect_db() -> None:
    if db.is_connected():
        return
    attempts = 6
    delay = 2.0
    for attempt in range(1, attempts + 1):
        try:
            await db.connect()
            return
        except Exception as exc:  # noqa: BLE001
            if attempt == attempts:
                raise
            logger.warning(
                "Database connect failed (attempt %s/%s): %s — retrying in %.0fs",
                attempt, attempts, exc, delay,
            )
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)


async def disconnect_db() -> None:
    if db.is_connected():
        await db.disconnect()


async def ensure_db_connected() -> None:
    try:
        if db.is_connected():
            await db.query_raw("SELECT 1")
            return
    except Exception:  # noqa: BLE001
        pass
    with suppress(Exception):
        await db.disconnect()
    await connect_db()
    await db.query_raw("SELECT 1")
