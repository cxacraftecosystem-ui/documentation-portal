from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.services import naming
from app.services.data_explorer import report_tools

ANSWERS = ("original", "english", "both")
ROWS = ("all", "filtered")
LOCALES = ("en", "hi")
EXTRAS_MAX = 40
EXTRA_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,39}$")
NO_EXTRAS = "none"
SHEETS_MAX = 60
SHEET_NAME_MAX = 120
NAME_MAX = 120

ENGLISH_COLUMNS = frozenset(
    {"English", "English (whole transcript)", "Translation status", "Translation model"}
)
ORIGINAL_TWINS = {"Original": "English"}
LINK_COLUMNS = frozenset({"URL", "Media URLs"})

_NAME_BAD = re.compile(r"[\x00-\x1f\x7f<>:\"|?*]+")


class OptionsInvalid(ValueError):
    pass


@dataclass(frozen=True)
class ReportOptions:
    sheets: tuple[str, ...] | None = None
    answers: str = "both"
    rows: str = "all"
    links: bool = True
    q: str | None = None
    thumbnails: bool = False
    locale: str = "en"
    extras: tuple[str, ...] | None = None

    @property
    def filters_rows(self) -> bool:
        return self.rows == "filtered" and bool(report_tools.words(self.q))

    @property
    def default(self) -> bool:
        return (
            self.sheets is None
            and self.answers == "both"
            and not self.filters_rows
            and self.links
            and not self.thumbnails
            and self.locale == "en"
            and self.extras is None
        )

    def signature(self) -> str:
        return "|".join(
            (
                ",".join(sorted(n.lower() for n in self.sheets)) if self.sheets is not None else "*",
                self.answers,
                self.rows,
                " ".join(report_tools.words(self.q)) if self.filters_rows else "",
                "1" if self.links else "0",
                "1" if self.thumbnails else "0",
                self.locale,
                "*" if self.extras is None else ",".join(sorted(self.extras)) or NO_EXTRAS,
            )
        )

    def stored(self) -> dict[str, Any]:
        return {
            "sheets": list(self.sheets) if self.sheets is not None else None,
            "answers": self.answers,
            "rows": self.rows,
            "links": self.links,
            "thumbnails": self.thumbnails,
            "locale": self.locale,
            "extras": None if self.extras is None else {key: key in self.extras for key in every_extra(self.extras)},
        }


def _flag(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in ("1", "true", "yes", "on"):
        return True
    if text in ("0", "false", "no", "off"):
        return False
    raise OptionsInvalid("links and thumbnails take 1 or 0")


def _sheet_names(value: Any) -> tuple[str, ...] | None:
    if value is None:
        return None
    items = value if isinstance(value, (list, tuple)) else str(value).split(",")
    names: list[str] = []
    for item in items:
        if not isinstance(item, str):
            raise OptionsInvalid("sheets takes sheet names")
        name = item.strip()
        if not name:
            continue
        if len(name) > SHEET_NAME_MAX:
            raise OptionsInvalid(f"A sheet name is at most {SHEET_NAME_MAX} characters.")
        if name.lower() not in (n.lower() for n in names):
            names.append(name)
    if len(names) > SHEETS_MAX:
        raise OptionsInvalid(f"Pick at most {SHEETS_MAX} sheets.")
    return tuple(names) or None


def _choice(value: Any, allowed: tuple[str, ...], label: str, default: str) -> str:
    if value is None or (isinstance(value, str) and not value.strip()):
        return default
    text = str(value).strip().lower()
    if text not in allowed:
        raise OptionsInvalid(f"{label} takes {', '.join(allowed)}")
    return text


def every_extra(picked: tuple[str, ...] = ()) -> list[str]:
    try:
        from app.services.datareport import contract, portal

        known = [option for option, _title in contract.optional_keys(portal.current().key)]
    except Exception:  # noqa: BLE001
        known = []
    return sorted(set(known) | set(picked))


def _extras(value: Any) -> tuple[str, ...] | None:
    if value is None:
        return None
    if isinstance(value, dict):
        if not value:
            return None
        items = [str(k) for k, v in value.items() if v is True or str(v).strip().lower() in ("1", "true", "yes", "on")]
    elif isinstance(value, (list, tuple)):
        items = [str(v) for v in value]
    else:
        items = str(value).split(",")
    if [item.strip().lower() for item in items if item.strip()] == [NO_EXTRAS]:
        return ()
    picked: list[str] = []
    for item in items:
        key = item.strip()
        if not key:
            continue
        if not EXTRA_KEY.match(key):
            raise OptionsInvalid("extras takes option keys")
        if key not in picked:
            picked.append(key)
    if len(picked) > EXTRAS_MAX:
        raise OptionsInvalid(f"Pick at most {EXTRAS_MAX} extras.")
    return tuple(sorted(picked))


def parse(
    *,
    sheets: Any = None,
    answers: Any = None,
    rows: Any = None,
    links: Any = None,
    q: str | None = None,
    thumbnails: Any = None,
    locale: Any = None,
    extras: Any = None,
) -> ReportOptions:
    return ReportOptions(
        sheets=_sheet_names(sheets),
        answers=_choice(answers, ANSWERS, "answers", "both"),
        rows=_choice(rows, ROWS, "rows", "all"),
        links=_flag(links, True),
        q=q,
        thumbnails=_flag(thumbnails, False),
        locale=_choice(locale, LOCALES, "locale", "en"),
        extras=_extras(extras),
    )


def stored_options(body: Any) -> dict[str, Any]:
    if not isinstance(body, dict):
        raise OptionsInvalid("options must be an object")
    picked = parse(
        sheets=body.get("sheets"),
        answers=body.get("answers"),
        rows=body.get("rows"),
        links=body.get("links"),
        thumbnails=body.get("thumbnails"),
        locale=body.get("locale"),
        extras=body.get("extras"),
    )
    return picked.stored()


def file_name(raw: str | None) -> str | None:
    if raw is None:
        return None
    leaf = str(raw).replace("\\", "/").rsplit("/", 1)[-1]
    leaf = _NAME_BAD.sub(" ", leaf)
    leaf = " ".join(leaf.split()).rstrip(" .")
    if leaf.lower().endswith(".xlsx"):
        leaf = leaf[: -len(".xlsx")]
    leaf = leaf.strip(" .")
    leaf = leaf[:NAME_MAX].strip(" .")
    if not leaf:
        return None
    cleaned = naming.ascii_leaf(f"{leaf}.xlsx", fallback="")
    if not cleaned or cleaned.lower() == ".xlsx" or not cleaned.lower().endswith(".xlsx"):
        return None
    return cleaned


def _dropped(columns: list[str], options: ReportOptions) -> set[int]:
    present = set(columns)
    drop: set[int] = set()
    for index, column in enumerate(columns):
        if not options.links and column in LINK_COLUMNS:
            drop.add(index)
        if options.answers == "original" and column in ENGLISH_COLUMNS:
            drop.add(index)
        if options.answers == "english" and ORIGINAL_TWINS.get(column) in present:
            drop.add(index)
    if len(drop) >= len(columns):
        return set()
    return drop


def _trim(sheet: dict[str, Any], options: ReportOptions) -> dict[str, Any]:
    columns = list(sheet.get("columns") or [])
    rows: list[list[Any]] = list(sheet.get("rows") or [])
    note = rows[-1:] if sheet.get("noteRow") and rows else []
    data = rows[:-1] if note else rows
    if options.filters_rows:
        wanted = report_tools.words(options.q)
        data = [row for row in data if report_tools.row_matches(row, wanted)]
    drop = _dropped(columns, options)
    if drop:
        keep = [i for i in range(len(columns)) if i not in drop]
        remap = {old: new for new, old in enumerate(keep)}
        columns = [columns[i] for i in keep]
        data = [[row[i] if i < len(row) else None for i in keep] for row in data]
        note = [[(note[0][0] if note[0] else ""), *[""] * (len(keep) - 1)]] if note else []
        prose = [remap[i] for i in sheet.get("prose") or [] if i in remap]
    else:
        prose = list(sheet.get("prose") or [])
    return {**sheet, "columns": columns, "rows": [*data, *note], "prose": prose}


def apply(sheets: list[dict[str, Any]], options: ReportOptions) -> list[dict[str, Any]]:
    if options.sheets is not None:
        wanted = [name.lower() for name in options.sheets]
        sheets = [s for s in sheets if str(s.get("name") or "").strip().lower() in wanted]
    if options.default:
        return sheets
    return [_trim(sheet, options) for sheet in sheets]
