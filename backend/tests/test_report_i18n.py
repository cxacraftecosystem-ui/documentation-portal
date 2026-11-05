import ast
import asyncio
import importlib
import re
from pathlib import Path
from typing import Any

import pytest

from app.services.datareport import contract, i18n

PORTALS = ("designer", "fieldRepository")
FORBIDDEN = set("[]:*?/\\")
PLACEHOLDER = re.compile(r"\{[a-zA-Z_]+(?::[^}]*)?\}")
DEVANAGARI = re.compile(r"[ऀ-ॿ]")
LATIN_ONLY_OK = {
    "Design Workshop",
    "Field Repository",
    "Google Maps",
    "URL",
    "SHA-256 चेकसम",
}

WRITER_SHEET_TEXT = {"sheet.banner_download", "sheet.empty", "sheet.back"}

REQUIRED_KEYS = [
    *(
        f"status.{s}"
        for s in (
            "approved",
            "pending",
            "translating",
            "translatingPct",
            "failed",
            "failedReason",
            "ready",
            "saved",
            "rejected",
            "notRequested",
        )
    ),
    *(
        f"cover.kpi.{k}"
        for k in (
            "files",
            "size",
            "records",
            "transcriptsReady",
            "transcriptsTranslating",
            "transcriptsFailed",
            "coverage",
        )
    ),
    "cover.title",
    "cover.portal",
    "cover.folder",
    "cover.filters",
    "cover.generatedBy",
    "cover.generatedOn",
    "cover.dataVersion",
    "cover.contents",
    "cover.downloadAll",
    "cover.chart.mediaByType",
    "cover.chart.recordingsPerDay",
    "cover.chart.completionBySection",
    "cover.chart.transcriptStatus",
    "banner.downloadSheet",
    "banner.downloadReport",
    "banner.capped",
    "dict.title",
    "dict.heading.sheet",
    "dict.heading.column",
    "dict.heading.kind",
    "dict.heading.description",
    "dict.heading.source",
    *(f"dict.kind.{k}" for k in contract.ColumnKind.__args__),
    "print.header",
    "print.footer",
    "link.download",
    "link.openInPortal",
    "link.openRecord",
    "link.playFrom",
    "link.docx",
    "link.srt",
    "row.original",
    "row.translation",
    "row.summary",
    *(
        f"sheet.{k}"
        for k in (
            "overview",
            "dictionary",
            "workshops",
            "crafts",
            "artisans",
            "products",
            "processes",
            "processSteps",
            "tools",
            "questionnaires",
            "answers",
            "transcripts",
            "media",
            "coverage",
            "map",
            "quotes",
            "glossary",
            "profile",
            "changes",
            "bothPortals",
            "audit",
            "everything",
            "designWorkshops",
        )
    ),
]


def test_hindi_has_every_english_key():
    assert i18n.missing_keys("hi") == []
    assert i18n.extra_keys("hi") == []


def test_required_keys_exist_in_both_locales():
    for locale in i18n.LOCALES:
        table = i18n.strings(locale)
        assert [k for k in REQUIRED_KEYS if not table.get(k)] == [], locale


def test_placeholders_match():
    en, hi = i18n.strings("en"), i18n.strings("hi")
    bad = [
        k for k in en if sorted(PLACEHOLDER.findall(en[k])) != sorted(PLACEHOLDER.findall(hi[k]))
    ]
    assert bad == []
    excel_codes = [k for k in en if k.startswith("print.") and en[k].count("&") != hi[k].count("&")]
    assert excel_codes == []


def test_hindi_is_devanagari():
    hi = i18n.strings("hi")
    latin = [
        k
        for k, v in hi.items()
        if not DEVANAGARI.search(v)
        and v not in LATIN_ONLY_OK
        and not v.startswith("&")
        and not re.fullmatch(r"[A-Z0-9 .()\-]+", v)
    ]
    assert latin == []


def test_sheet_names_are_valid_excel_names():
    for locale in i18n.LOCALES:
        table = i18n.strings(locale)
        names = {
            k: v for k, v in table.items() if k.startswith("sheet.") and k not in WRITER_SHEET_TEXT
        }
        for key, name in names.items():
            assert 0 < len(name) <= 31, (locale, key, name)
            assert not FORBIDDEN & set(name), (locale, key, name)
            assert not name.startswith("'") and not name.endswith("'"), (locale, key)
            assert name.lower() != "history", (locale, key)
    en, hi = i18n.strings("en"), i18n.strings("hi")
    by_hindi: dict[str, set[str]] = {}
    for key in en:
        if key.startswith("sheet.") and key not in WRITER_SHEET_TEXT:
            by_hindi.setdefault(hi[key].casefold(), set()).add(en[key].casefold())
    assert {h: e for h, e in by_hindi.items() if len(e) > 1} == {}


def test_generated_descriptions_follow_patterns():
    hi = i18n.translator("hi")
    out = hi("describe.artisans.dateOfBirth", "Date of birth of the artisan (Artisan.dateOfBirth).")
    assert out.endswith("(Artisan.dateOfBirth)।") and DEVANAGARI.search(out)
    assert "Artisan.craftId" in hi("x.y", "Identifier of the linked craft (Artisan.craftId).")
    assert hi("x.y", "Translating 40%") == "अनुवाद जारी 40%"
    assert (
        hi("x.y", "Notes of the made-up thing (X.notes).")
        == "Notes of the made-up thing (X.notes)."
    )


def test_translator_contract():
    en = i18n.translator("en")
    assert en("sheet.artisans", "Artisans") == "Artisans"
    hi = i18n.translator("hi")
    assert hi("sheet.artisans", "Artisans") == i18n.strings("hi")["sheet.artisans"]
    assert hi("col.someNewSheet.district", "District") == i18n.strings("hi")["col.district"]
    assert hi("col.someNewSheet.someNewKey", "Created by") == i18n.strings("hi")["text.Created by"]
    assert hi("sheet.profile.abc123", "Profile") == i18n.strings("hi")["sheet.profile"]
    assert (
        hi("nothing.here", "Fallback text that has no Hindi") == "Fallback text that has no Hindi"
    )
    assert i18n.translator(None)("sheet.artisans", "Artisans") == "Artisans"
    assert i18n.translator("fr")("sheet.artisans", "Artisans") == "Artisans"
    assert i18n.missing("hi", [("sheet.artisans", "Artisans"), ("x.y", "No such words")]) == ["x.y"]
    formatted = hi("banner.downloadSheet", "").format(n=3, size="2 MB")
    assert "3" in formatted and "2 MB" in formatted


class _Empty(dict):
    def __missing__(self, key: Any) -> _Empty:
        return _Empty()

    def __getattr__(self, name: str) -> _Empty:
        if name.startswith("__"):
            raise AttributeError(name)
        return _Empty()

    def __call__(self, *args: Any, **kwargs: Any) -> _Empty:
        return _Empty()

    def __str__(self) -> str:
        return ""

    def __int__(self) -> int:
        return 0


class _Links:
    def download(self, media_id: str) -> str:
        return f"https://example.org/d/{media_id}"

    def open_media(self, media_id: str) -> str:
        return f"https://example.org/m/{media_id}"

    def open_record(self, kind: str, record_id: str) -> str:
        return f"https://example.org/r/{kind}/{record_id}"

    def play_from(self, media_id: str, start_ms: int) -> str:
        return f"https://example.org/m/{media_id}?t={start_ms}"

    def transcript_file(self, media_id: str, fmt: str) -> str:
        return f"https://example.org/t/{media_id}.{fmt}"

    def section(self, interview_id: str, section_code: str) -> str:
        return f"https://example.org/s/{interview_id}/{section_code}"

    def data_folder(self, path: str) -> str:
        return f"https://example.org/f/{path}"

    async def bundle(self, label: str, media_ids: list[str]) -> str:
        return "https://example.org/b/1"


async def _no_cache(key: str, factory: Any) -> Any:
    return _Empty()


def _context(portal: str) -> contract.ReportContext:
    material = _Empty()
    material["records"] = _Empty()
    material["sheets"] = []
    return contract.ReportContext(
        portal=portal,
        user=_Empty(),
        scope=_Empty(),
        path="",
        folder_name="",
        material=material,
        options=contract.ReportOptions(
            locale="hi", extras={k: True for k, _ in contract.optional_keys(portal)}
        ),
        links=_Links(),
        t=i18n.translator("hi"),
        data_version="test",
        generated_at="2026-11-05T00:00:00+05:30",
        cache=_no_cache,
    )


def _plugin_sheets(plugin: Any, portal: str) -> list[tuple[str, str, list[contract.Column]]]:
    catalog = getattr(plugin, "catalog", None)
    if callable(catalog):
        found = []
        for item in catalog():
            if isinstance(item, contract.Sheet):
                found.append((item.key, item.name, list(item.columns)))
            else:
                key, columns = item[0], item[1]
                name = item[2] if len(item) > 2 else plugin.title
                found.append((key, name, list(columns)))
        return found
    built = asyncio.run(plugin.build(_context(portal)))
    return [(s.key, s.name, list(s.columns)) for s in built]


DYNAMIC_SHEET = re.compile(r"[^A-Za-z0-9_]")


def _schema_docs() -> frozenset[str]:
    try:
        schema = importlib.import_module("app.services.datareport.schema")
    except ImportError:
        return frozenset()
    return frozenset(
        f.doc for p in PORTALS for m in schema.load(p).values() for f in m.fields if f.doc
    )


def _pairs(
    portal: str,
) -> tuple[list[tuple[str, str]], dict[str, list[tuple[str, str]]], list[str]]:
    docs = _schema_docs()
    hi = i18n.translator("hi")
    pairs: list[tuple[str, str]] = []
    headings: dict[str, list[tuple[str, str]]] = {}
    broken: list[str] = []
    for plugin in contract.plugins(portal):
        pairs.append((f"sheet.{plugin.key}", plugin.title))
        try:
            sheets = _plugin_sheets(plugin, portal)
        except (AttributeError, LookupError, TypeError, ValueError, RuntimeError, OSError) as error:
            broken.append(f"{plugin.key}: {type(error).__name__}: {error}")
            continue
        for key, name, columns in sheets:
            if not DYNAMIC_SHEET.search(key):
                pairs.append((f"sheet.{key}", name))
            for column in columns:
                pairs.append((f"col.{key}.{column.key}", column.label))
                if column.description and column.description not in docs:
                    pairs.append((f"describe.{key}.{column.key}", column.description))
                headings.setdefault(key, []).append(
                    (column.label, hi(f"col.{key}.{column.key}", column.label))
                )
    return pairs, headings, broken


@pytest.mark.parametrize("portal", PORTALS)
def test_every_registered_sheet_and_column_has_hindi(portal):
    pairs, headings, broken = _pairs(portal)
    assert broken == [], (
        "these report plugins cannot be inspected without a database; give them a catalog() "
        "returning (sheet key, columns) so their Hindi can be checked"
    )
    assert i18n.missing("hi", pairs) == []
    hi = i18n.translator("hi")
    titles = {(f"sheet.{p.key}", p.title) for p in contract.plugins(portal)}
    names = [
        hi(key, default)
        for key, default in pairs
        if key.startswith("sheet.") and (key, default) not in titles
    ]
    assert [n for n in names if len(n) > 31 or FORBIDDEN & set(n)] == []
    clashes = {}
    for key, labels in headings.items():
        by_hindi: dict[str, set[str]] = {}
        for english, hindi in labels:
            by_hindi.setdefault(hindi, set()).add(english)
        found = {h: sorted(e) for h, e in by_hindi.items() if len(e) > 1}
        if found:
            clashes[key] = found
    assert clashes == {}


TRANSLATING_CALLS = {"t", "say", "text", "_t", "translate"}
KEY_SHAPE = re.compile(r"^[a-z][A-Za-z0-9_]*(\.[A-Za-z0-9_-]+)+$")
REPORT_CODE = Path(i18n.__file__).resolve().parent


def _literal_calls() -> list[tuple[str, str, str]]:
    found = []
    for path in sorted(REPORT_CODE.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            name = func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
            if name not in TRANSLATING_CALLS:
                continue
            args = node.args
            for index, arg in enumerate(args):
                if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                    if not KEY_SHAPE.match(arg.value):
                        break
                    nxt = args[index + 1] if index + 1 < len(args) else None
                    default = (
                        nxt.value
                        if isinstance(nxt, ast.Constant) and isinstance(nxt.value, str)
                        else ""
                    )
                    found.append((f"{path.name}:{node.lineno}", arg.value, default))
                    break
    return found


def test_every_literal_key_in_report_code_has_hindi():
    calls = _literal_calls()
    missing = [
        f"{where} {key}" for where, key, default in calls if i18n.lookup("hi", key, default) is None
    ]
    assert missing == []
