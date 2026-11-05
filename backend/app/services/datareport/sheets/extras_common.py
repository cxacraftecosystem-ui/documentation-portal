from __future__ import annotations

import hashlib
import json
from collections.abc import Awaitable, Callable, Iterable, Iterator
from dataclasses import dataclass
from datetime import UTC, date, datetime
from decimal import Decimal
from enum import Enum
from typing import Any

from app.core.db import db
from app.services.datareport.contract import Column, Link, ReportContext, Sheet

BOTH: tuple[str, ...] = ("designer", "fieldRepository")
CHUNK = 500
CELL_MAX = 32000

KINDS: tuple[str, ...] = (
    "workshops",
    "crafts",
    "artisans",
    "products",
    "processes",
    "tools",
    "interviews",
    "media",
    "designWorkshops",
)

SINGULAR: dict[str, str] = {
    "workshops": "workshop",
    "crafts": "craft",
    "artisans": "artisan",
    "products": "product",
    "processes": "process",
    "tools": "tool",
    "interviews": "interview",
    "media": "media",
    "designWorkshops": "designWorkshop",
}

MODELS: dict[str, str] = {
    "workshops": "Workshop",
    "crafts": "Craft",
    "artisans": "Artisan",
    "products": "ProductDocumentation",
    "processes": "Process",
    "tools": "ToolDocumentation",
    "interviews": "QuestionnaireInterview",
    "media": "MediaFile",
    "designWorkshops": "DesignWorkshop",
}

KIND_LABELS: dict[str, str] = {
    "workshops": "Workshop",
    "crafts": "Craft",
    "artisans": "Artisan",
    "products": "Product",
    "processes": "Process",
    "tools": "Tool",
    "interviews": "Questionnaire sitting",
    "media": "File",
    "designWorkshops": "Design workshop",
}

REVIEW_KINDS: dict[str, str] = {
    "ARTISAN": "artisans",
    "WORKSHOP": "workshops",
    "PRODUCT": "products",
    "TOOL": "tools",
    "PROCESS": "processes",
    "QUESTIONNAIRE": "interviews",
    "MEDIA": "media",
    "DESIGN_WORKSHOP": "designWorkshops",
}

_LABEL_FIELDS = ("name", "title", "productName", "toolkitName", "originalFilename")

_MEDIA_FACTS_SQL = (
    'SELECT m."id", m."mediaType"::text AS "mediaType", m."status"::text AS "status", '
    'm."transcriptStatus", m."contentSha256", m."checksum", m."shaVerifiedAt", m."locationId", '
    'm."reviewedById", m."reviewedAt", m."uploadedById", m."mimeType", m."updatedAt", '
    '(m."transcriptText" IS NOT NULL AND m."transcriptText" <> \'\') AS "hasText", '
    'EXISTS (SELECT 1 FROM "TranscriptVersion" v WHERE v."mediaFileId" = m."id" '
    'AND v."supersededAt" IS NULL AND v."translationStatus" = \'PENDING\') AS "translating", '
    '(SELECT coalesce(sum(greatest(t."endMs" - t."startMs", 0)), 0) FROM "TranscriptTurn" t '
    'JOIN "TranscriptVersion" tv ON tv."id" = t."versionId" '
    'WHERE tv."mediaFileId" = m."id" AND tv."supersededAt" IS NULL) AS "talkMs" '
    'FROM "MediaFile" m WHERE m."id" = ANY($1::text[])'
)
_HEX64 = frozenset("0123456789abcdef")

SECRET_FIELDS: frozenset[str] = frozenset(
    {
        "aadhaarFingerprint",
        "aadhaarNumber",
        "aadhaarVerifyTxn",
        "aadhaarVerifyDob",
        "aadhaarVerifySignedAt",
        "passwordHash",
        "joinCode",
        "clientKey",
        "objectKey",
        "cleanedObjectKey",
        "bucket",
        "url",
    }
)
_SECRET_WORDS = ("password", "secret", "token", "totp", "otp", "twofactor", "recovery", "aadhaarxml")


def is_secret(name: str) -> bool:
    low = name.lower()
    return name in SECRET_FIELDS or any(word in low for word in _SECRET_WORDS)


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: redact(v) for k, v in value.items() if not is_secret(str(k))}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


@dataclass(frozen=True)
class Plugin:
    key: str
    title: str
    order: int
    option: str | None
    builder: Callable[[ReportContext], Awaitable[list[Sheet]]]
    sheets: Callable[[], list[tuple[str, list[Column], str]]] = list
    portals: tuple[str, ...] = BOTH

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        return await self.builder(ctx)

    def catalog(self) -> list[tuple[str, list[Column], str]]:
        return self.sheets()


def say(ctx: ReportContext, key: str, default: str, **values: Any) -> str:
    text = ctx.t(key, default) if ctx.t is not None else default
    try:
        return text.format(**values)
    except (KeyError, IndexError, ValueError):
        return default.format(**values)


def g(obj: Any, key: str, default: Any = None) -> Any:
    if obj is None:
        return default
    if isinstance(obj, dict):
        return obj.get(key, default)
    return getattr(obj, key, default)


def ident(obj: Any) -> str:
    value = g(obj, "id")
    return str(value) if value is not None else ""


def scalar(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, datetime):
        return value
    if isinstance(value, date):
        return datetime(value.year, value.month, value.day, tzinfo=UTC)
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, Enum):
        return str(value.value)
    return None


def when(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, str) and value:
        try:
            parsed = datetime.fromisoformat(value)
        except ValueError:
            return None
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)
    return None


def status_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, Enum):
        value = value.value
    return str(value).split(".")[-1].upper()


def clip(value: Any) -> Any:
    if isinstance(value, str) and len(value) > CELL_MAX:
        return value[:CELL_MAX] + "…"
    return value


def label(kind: str, rec: Any) -> str:
    for name in _LABEL_FIELDS:
        value = g(rec, name)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return KIND_LABELS.get(kind, kind) + " " + ident(rec)[:8]


def _text_values(rec: Any) -> Iterator[str]:
    fields = rec if isinstance(rec, dict) else getattr(rec, "__dict__", {})
    for value in fields.values():
        if isinstance(value, str):
            yield value


def matches(rec: Any, q: str) -> bool:
    needle = q.casefold()
    return any(needle in value.casefold() for value in _text_values(rec))


def material(ctx: ReportContext) -> dict[str, Any]:
    mat = ctx.material or {}
    inner = mat.get("records")
    return inner if isinstance(inner, dict) else mat


def records(ctx: ReportContext, kind: str, *, filtered: bool = True) -> list[Any]:
    pick = getattr(ctx, "records", None)
    if filtered and callable(pick):
        return [r for r in pick(kind) or [] if r is not None]
    rows = material(ctx).get(kind)
    out = [r for r in rows if r is not None] if isinstance(rows, list) else []
    q = (ctx.options.q or "").strip()
    if filtered and ctx.options.rows == "filtered" and q:
        out = [r for r in out if matches(r, q)]
    return out


def open_record(ctx: ReportContext, kind: str, rec: Any) -> Link | str:
    rid = ident(rec)
    text = label(kind, rec)
    if not rid or not ctx.options.links:
        return text
    if kind == "media":
        return Link(text, ctx.links.open_media(rid))
    return Link(text, ctx.links.open_record(SINGULAR.get(kind, kind), rid))


def chunks(items: list[str], size: int = CHUNK) -> Iterator[list[str]]:
    for start in range(0, len(items), size):
        yield items[start : start + size]


def fingerprint(*parts: Iterable[str]) -> str:
    digest = hashlib.sha256()
    for part in parts:
        for item in sorted(part):
            digest.update(item.encode("utf-8"))
            digest.update(b"\0")
        digest.update(b"\1")
    return digest.hexdigest()[:24]


async def cached(ctx: ReportContext, key: str, factory: Callable[[], Awaitable[Any]]) -> Any:
    if ctx.cache is None:
        return await factory()
    return await ctx.cache(key, factory)


FACT_FIELDS: tuple[str, ...] = (
    "mediaType",
    "status",
    "transcriptStatus",
    "contentSha256",
    "checksum",
    "shaVerifiedAt",
    "locationId",
    "reviewedById",
    "reviewedAt",
    "uploadedById",
    "mimeType",
    "updatedAt",
)


def _carried(media: list[Any]) -> bool:
    def has(row: Any, key: str) -> bool:
        return key in row if isinstance(row, dict) else hasattr(row, key)

    return bool(media) and all(has(m, "contentSha256") and has(m, "transcriptStatus") for m in media)


def _fact_of(row: Any) -> dict[str, Any]:
    fact = {key: g(row, key) for key in FACT_FIELDS}
    fact["id"] = ident(row)
    fact["hasText"] = bool(g(row, "hasTranscript") or g(row, "hasText"))
    fact["translating"] = bool(g(row, "translating"))
    fact["talkMs"] = int(g(row, "durationMs") or 0)
    return fact


async def media_facts(ctx: ReportContext) -> dict[str, dict[str, Any]]:
    media = records(ctx, "media", filtered=False)
    if _carried(media):
        return {ident(m): _fact_of(m) for m in media if ident(m)}
    ids = sorted({ident(m) for m in media} - {""})

    async def load() -> dict[str, dict[str, Any]]:
        out: dict[str, dict[str, Any]] = {}
        for part in chunks(ids):
            for row in await db.query_raw(_MEDIA_FACTS_SQL, part):
                out[str(row["id"])] = dict(row)
        return out

    if not ids:
        return {}
    return await cached(ctx, "extras.media." + fingerprint(ids), load)


def sha256_of(fact: dict[str, Any] | None) -> str | None:
    if not fact:
        return None
    for key in ("contentSha256", "checksum"):
        value = str(fact.get(key) or "").strip().lower()
        if len(value) == 64 and set(value) <= _HEX64:
            return value
    return None


def transcript_state(fact: dict[str, Any] | None, media_type: str) -> str:
    if media_type not in ("AUDIO", "VIDEO"):
        return ""
    if not fact:
        return "Not transcribed"
    state = str(fact.get("transcriptStatus") or "").upper()
    if state == "FAILED":
        return "Failed"
    if state in ("QUEUED", "PROCESSING", "PENDING", "RUNNING") or fact.get("translating"):
        return "Translating"
    if fact.get("hasText") or state == "COMPLETED":
        return "Ready"
    return "Not transcribed"


async def user_names(ids: set[str]) -> dict[str, str]:
    wanted = sorted(i for i in ids if i)
    out: dict[str, str] = {}
    for part in chunks(wanted):
        for user in await db.user.find_many(where={"id": {"in": part}}):
            out[str(user.id)] = str(g(user, "name") or g(user, "email") or user.id)
    return out


def json_text(value: Any) -> str:
    try:
        return json.dumps(value, ensure_ascii=False, default=str, sort_keys=True)
    except (TypeError, ValueError):
        return str(value)


def media_owner_artisans(ctx: ReportContext) -> dict[str, set[str]]:
    product_artisan = {ident(p): str(g(p, "artisanId") or "") for p in records(ctx, "products", filtered=False)}
    tool_artisan = {ident(t): str(g(t, "artisanId") or "") for t in records(ctx, "tools", filtered=False)}
    interview_artisans: dict[str, set[str]] = {}
    for interview in records(ctx, "interviews", filtered=False):
        for link in g(interview, "artisans") or []:
            aid = str(g(link, "artisanId") or ident(g(link, "artisan")) or "")
            if aid:
                interview_artisans.setdefault(ident(interview), set()).add(aid)
    out: dict[str, set[str]] = {}
    for media in records(ctx, "media", filtered=False):
        owners: set[str] = set()
        if g(media, "artisanId"):
            owners.add(str(g(media, "artisanId")))
        tag = str(g(media, "linkedRecordType") or "").lower()
        linked = str(g(media, "linkedRecordId") or "")
        if tag == "artisan" and linked:
            owners.add(linked)
        product = str(g(media, "productId") or (linked if tag == "product" else ""))
        if product_artisan.get(product):
            owners.add(product_artisan[product])
        tool = str(g(media, "toolId") or (linked if tag == "tool" else ""))
        if tool_artisan.get(tool):
            owners.add(tool_artisan[tool])
        interview = str(g(media, "questionnaireInterviewId") or "")
        if not interview and tag in ("questionnaire", "questionnaireinterview"):
            interview = linked
        owners |= interview_artisans.get(interview, set())
        out[ident(media)] = owners
    return out
