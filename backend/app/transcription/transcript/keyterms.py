from __future__ import annotations

from collections.abc import Iterable

LIMIT = 1000
MAX_CHARS = 50
MAX_WORDS = 5


def _clean(term: object) -> str | None:
    if not isinstance(term, str):
        return None
    value = " ".join(term.split()).strip(" ,.;:-")
    if not value or len(value) > MAX_CHARS or len(value.split()) > MAX_WORDS:
        return None
    return value


def assemble(
    *,
    artisan_names: Iterable[str] = (),
    craft_names: Iterable[str] = (),
    glossary_terms: Iterable[str] = (),
    regional_crafts: Iterable[str] = (),
    vocabulary: Iterable[str] = (),
    master_crafts: Iterable[str] = (),
    limit: int = LIMIT,
) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for tier in (artisan_names, craft_names, glossary_terms, regional_crafts, vocabulary, master_crafts):
        for raw in tier:
            term = _clean(raw)
            if term is None:
                continue
            key = term.casefold()
            if key in seen:
                continue
            seen.add(key)
            out.append(term)
            if len(out) >= limit:
                return out
    return out
