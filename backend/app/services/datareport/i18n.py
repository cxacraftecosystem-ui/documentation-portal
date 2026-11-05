from __future__ import annotations

import functools
import json
import re
from collections.abc import Iterable
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
LOCALES = ("en", "hi")
DEFAULT_LOCALE = "en"
_SAFE = re.compile(r"^[a-z]{2}$")


@functools.cache
def strings(locale: str) -> dict[str, str]:
    if not _SAFE.match(locale or ""):
        return {}
    path = HERE / f"i18n_{locale}.json"
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return {}
    if not isinstance(data, dict):
        return {}
    return {
        str(k): v
        for k, v in data.items()
        if isinstance(v, str) and v.strip() and not str(k).startswith("_")
    }


def _generic(key: str) -> str | None:
    parts = key.split(".")
    if parts[0] == "column":
        parts = ["col", *parts[1:]]
    if parts[0] == "col" and len(parts) >= 2:
        return f"col.{parts[-1]}"
    if parts[0] in ("describe", "dict") and len(parts) >= 3:
        return f"describe.{parts[-1]}"
    if parts[0] == "sheet" and len(parts) >= 3:
        return f"sheet.{parts[1]}"
    return None


_KEEP = frozenset(
    {"source", "n", "pct", "reason", "name", "count", "size", "total", "date", "time"}
)
_SLOT = re.compile(r"\{([a-z_]+)\}")


@functools.cache
def _patterns(locale: str) -> tuple[tuple[re.Pattern[str], str], ...]:
    english, local = strings(DEFAULT_LOCALE), strings(locale)
    out = []
    for key, template in english.items():
        if not key.startswith("pattern.") or not local.get(key):
            continue
        parts = _SLOT.split(template)
        regex = "".join(
            f"(?P<{part}>.+?)" if index % 2 else re.escape(part) for index, part in enumerate(parts)
        )
        out.append((re.compile(f"^{regex}$", re.DOTALL), local[key]))
    return tuple(sorted(out, key=lambda item: -len(item[0].pattern)))


def _word(locale: str, text: str) -> str | None:
    table = strings(locale)
    upper = text[:1].upper() + text[1:]
    forms = [text, upper]
    if upper.endswith("sse"):
        forms.append(upper[:-1])
    forms.append(f"{upper}s")
    for form in forms:
        found = table.get(f"text.{form}")
        if found:
            return found
    return None


def _fill(template: str, values: dict[str, str]) -> str:
    return _SLOT.sub(lambda m: values.get(m.group(1), m.group(0)), template)


def _from_pattern(locale: str, default: str) -> str | None:
    for regex, local in _patterns(locale):
        match = regex.match(default)
        if not match:
            continue
        values = {}
        for slot, value in match.groupdict().items():
            if slot in _KEEP:
                values[slot] = value
                continue
            found = _word(locale, value)
            if found is None:
                break
            values[slot] = found
        else:
            return _fill(local, values)
    return None


def lookup(locale: str, key: str, default: str) -> str | None:
    table = strings(locale)
    found = table.get(key)
    if found:
        return found
    if default:
        found = table.get(f"text.{default}")
        if found:
            return found
    generic = _generic(key)
    if generic and generic != key:
        english = strings(DEFAULT_LOCALE).get(generic)
        if not default or english == default or key.startswith("sheet."):
            found = table.get(generic)
            if found:
                return found
    if default and locale != DEFAULT_LOCALE:
        return _from_pattern(locale, default)
    return None


class Translator:
    def __init__(self, locale: str = DEFAULT_LOCALE) -> None:
        self.locale = locale if locale in LOCALES else DEFAULT_LOCALE

    def text(self, key: str, default: str) -> str:
        if self.locale == DEFAULT_LOCALE:
            return default
        return lookup(self.locale, key, default) or default

    def __call__(self, key: str, default: str, **values: Any) -> str:
        text = self.text(key, default)
        if not values:
            return text
        try:
            return text.format(**values)
        except KeyError, IndexError, ValueError:
            return default.format(**values)


def translator(locale: str | None) -> Translator:
    return Translator(locale or DEFAULT_LOCALE)


t = translator


def missing(locale: str, pairs: Iterable[tuple[str, str]]) -> list[str]:
    return sorted({key for key, default in pairs if lookup(locale, key, default) is None})


def missing_keys(locale: str) -> list[str]:
    table = strings(locale)
    return sorted(key for key in strings(DEFAULT_LOCALE) if not table.get(key))


def extra_keys(locale: str) -> list[str]:
    canon = strings(DEFAULT_LOCALE)
    return sorted(key for key in strings(locale) if key not in canon)
