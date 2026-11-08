from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable, Iterable
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

ColumnKind = Literal[
    "text",
    "longtext",
    "int",
    "number",
    "percent",
    "date",
    "datetime",
    "duration_ms",
    "bytes",
    "link",
    "status",
    "id",
    "bool",
    "image",
    "json",
]

RowKind = Literal["original", "translation", "summary", "summary_translation", "plain"]

ROW_KIND = "_row"
ROW_PAIR = "_pair"
ROW_FULL = "_full"

Language = Literal["original", "english", "both"]
Locale = Literal["en", "hi"]


@dataclass(frozen=True)
class Column:
    key: str
    label: str
    kind: ColumnKind = "text"
    description: str = ""
    width: int | None = None
    hidden: bool = False
    group: str | None = None
    total: Literal["sum", "count"] | None = None
    source: str | None = None
    lang: Literal["original", "english"] | None = None
    wrap: bool | None = None
    tech: bool | None = None

    @property
    def sources(self) -> tuple[str, ...]:
        return tuple(part.strip() for part in (self.source or "").split(",") if part.strip())


@dataclass(frozen=True)
class Link:
    text: str
    url: str


@dataclass(frozen=True)
class Rich:
    runs: tuple[tuple[str, bool], ...]

    @property
    def text(self) -> str:
        return "".join(part for part, _bold in self.runs)

    def __str__(self) -> str:
        return self.text

    def __len__(self) -> int:
        return len(self.text)


@dataclass(frozen=True)
class Image:
    media_id: str
    alt: str = ""


@dataclass
class Banner:
    text: str
    url: str | None = None


@dataclass
class ChartSpec:
    title: str
    kind: Literal["column", "bar", "line", "pie", "doughnut"]
    categories: list[str]
    series: list[tuple[str, list[float]]]


@dataclass
class Sheet:
    key: str
    name: str
    columns: list[Column]
    rows: Iterable[dict[str, Any]] | AsyncIterator[dict[str, Any]]
    color: str = "762CB1"
    banners: list[Banner] = field(default_factory=list)
    note: str | None = None
    table: bool = True
    sparkline_columns: list[str] = field(default_factory=list)
    databar_columns: list[str] = field(default_factory=list)
    row_pairs: bool = False
    group: str | None = None
    media_ids: list[str] = field(default_factory=list)
    media_bytes: int = 0
    description: str = ""
    totals: bool = False
    count: int | None = None
    link_bound: int | None = None
    record_key: str | None = None


@dataclass
class ReportOptions:
    sheets: list[str] | None = None
    answers: Language = "both"
    rows: Literal["all", "filtered"] = "all"
    links: bool = True
    thumbnails: bool = False
    locale: Locale = "en"
    extras: dict[str, bool] = field(default_factory=dict)
    q: str | None = None
    name: str | None = None


class LinkBuilder(Protocol):
    def download(self, media_id: str) -> str: ...

    def open_media(self, media_id: str) -> str: ...

    def open_record(self, kind: str, record_id: str) -> str: ...

    def play_from(self, media_id: str, start_ms: int) -> str: ...

    def transcript_file(self, media_id: str, fmt: Literal["docx", "srt"]) -> str: ...

    def section(self, interview_id: str, section_code: str) -> str: ...

    def data_folder(self, path: str) -> str: ...

    async def bundle(self, label: str, media_ids: list[str]) -> str: ...


class Translator(Protocol):
    def __call__(self, key: str, default: str) -> str: ...


@dataclass
class ReportContext:
    portal: Literal["designer", "fieldRepository"]
    user: Any
    scope: Any
    path: str
    folder_name: str
    material: dict[str, Any]
    options: ReportOptions
    links: LinkBuilder
    t: Translator
    data_version: str
    generated_at: str
    cover_charts: list[ChartSpec] = field(default_factory=list)
    cover_kpis: list[tuple[str, str, str]] = field(default_factory=list)
    cache: Callable[[str, Callable[[], Awaitable[Any]]], Awaitable[Any]] | None = None
    portal_name: str = ""
    generated_by: str = ""
    filters: list[tuple[str, str]] = field(default_factory=list)
    sees_spend: bool = False
    thumbnails: dict[str, bytes] = field(default_factory=dict)
    shared: dict[str, Any] = field(default_factory=dict)
    delivered: list[Callable[[], Awaitable[None]]] = field(default_factory=list)

    def records(self, kind: str) -> list[Any]:
        found = list((self.material.get("records") or {}).get(kind) or [])
        if self.options.rows != "filtered":
            return found
        wanted = query_words(self.options.q)
        if not wanted:
            return found
        return [record for record in found if record_matches(record, wanted)]


CatalogItem = tuple[str, list[Column]] | tuple[str, list[Column], str] | Sheet


class SheetPlugin(Protocol):
    key: str
    title: str
    order: int
    option: str | None
    portals: tuple[str, ...]

    async def build(self, ctx: ReportContext) -> list[Sheet]: ...


def catalog_of(plugin: Any) -> list[tuple[str, str, list[Column]]] | None:
    catalog = getattr(plugin, "catalog", None)
    if not callable(catalog):
        return None
    out: list[tuple[str, str, list[Column]]] = []
    for item in catalog():
        if isinstance(item, Sheet):
            out.append((item.key, item.name, list(item.columns)))
        else:
            name = item[2] if len(item) > 2 else plugin.title
            out.append((item[0], name, list(item[1])))
    return out


def query_words(query: str | None) -> list[str]:
    return [w for w in (query or "").lower().split() if w]


def _haystack(value: Any, depth: int = 0) -> str:
    if value is None or depth > 2:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, dict):
        return " ".join(_haystack(v, depth + 1) for v in value.values())
    if isinstance(value, (list, tuple)):
        return " ".join(_haystack(v, depth + 1) for v in value[:200])
    attrs = getattr(value, "__dict__", None)
    if isinstance(attrs, dict):
        return " ".join(_haystack(v, depth + 1) for k, v in attrs.items() if not str(k).startswith("_"))
    return ""


def record_matches(record: Any, wanted: list[str]) -> bool:
    if not wanted:
        return True
    text = _haystack(record).lower()
    return all(w in text for w in wanted)


_PLUGINS: dict[str, SheetPlugin] = {}


def register(plugin: SheetPlugin) -> SheetPlugin:
    if plugin.key in _PLUGINS and _PLUGINS[plugin.key] is not plugin:
        raise ValueError(f"two report sheets share the key {plugin.key!r}")
    _PLUGINS[plugin.key] = plugin
    return plugin


def plugins(portal: str) -> list[SheetPlugin]:
    from app.services.datareport import sheets as _sheets

    _sheets.load()
    return sorted((p for p in _PLUGINS.values() if portal in p.portals), key=lambda p: (p.order, p.key))


def optional_keys(portal: str) -> list[tuple[str, str]]:
    return [(p.option, p.title) for p in plugins(portal) if p.option]
