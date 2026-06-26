import asyncio
import logging
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from prisma import Prisma

from app.core.config import get_settings

logger = logging.getLogger(__name__)

_POOLER_HOST_SUFFIX = ".pooler.supabase.com"


def build_runtime_database_url(base_url: str) -> str:
    """Return the URL the *runtime* Prisma client should connect with.

    For a Supabase pooler URL we route runtime queries through the TRANSACTION-mode
    pooler (port 6543, ``pgbouncer=true``) rather than the SESSION pooler (port 5432).

    Why: session mode pins one of only 15 server connections per client for the whole
    life of the connection, so two uvicorn workers (each with a Prisma pool) exhaust the
    pool and everything else — including the keep-alive and ``prisma migrate`` — is
    rejected with ``(EMAXCONNSESSION) max clients reached in session mode``. Transaction
    mode hands the server connection back after each statement, so it multiplexes many
    app connections (up to the pooler's 200 client-connection ceiling) over the same 15
    server connections. ``pgbouncer=true`` tells the query engine not to rely on
    session-pinned named prepared statements, which transaction pooling can't keep.

    Migrations are deliberately NOT affected: ``prisma migrate deploy`` reads
    ``DATABASE_URL`` straight from the environment (still the session pooler :5432),
    which is required for the advisory locks / DDL that transaction mode can't provide.

    Anything that is not a Supabase pooler host (local dev, a direct connection) is
    returned unchanged, so local development and tests keep working untouched.
    """
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

    # Rebuild netloc on port 6543, preserving credentials exactly as they appear in the
    # source (already URL-encoded there — do not re-encode).
    userinfo = ""
    if parts.username:
        userinfo = parts.username
        if parts.password:
            userinfo += f":{parts.password}"
        userinfo += "@"
    netloc = f"{userinfo}{host}:6543"

    # Preserve any existing query params (e.g. sslmode); add/override the pooler ones.
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["pgbouncer"] = "true"
    query["connection_limit"] = str(settings.database_connection_limit)
    if settings.database_pool_timeout is not None:
        query["pool_timeout"] = str(settings.database_pool_timeout)

    return urlunsplit((parts.scheme, netloc, parts.path, urlencode(query), parts.fragment))


db = Prisma(datasource={"url": build_runtime_database_url(get_settings().database_url)})


async def connect_db() -> None:
    """Connect the runtime Prisma client, retrying a transient pooler-full / engine-connect failure.

    If the pooler is momentarily at its client-connection ceiling (e.g. overlapping connections during
    a deploy/restart), connecting raises and — without a retry — uvicorn exits and systemd restarts it,
    which opens YET MORE connection attempts and turns a brief spike into a crash-loop that keeps the
    pooler saturated. Retrying in-process instead waits for connections to drain, breaking the spiral.
    """
    if db.is_connected():
        return
    attempts = 6
    delay = 2.0
    for attempt in range(1, attempts + 1):
        try:
            await db.connect()
            return
        except Exception as exc:  # noqa: BLE001 - any connect failure should back off, not crash-loop
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
