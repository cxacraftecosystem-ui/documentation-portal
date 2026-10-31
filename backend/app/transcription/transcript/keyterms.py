"""The key terms Scribe is biased towards for one recording: at most 100, most specific first.

Scribe v2 takes up to 100 terms and the master craft list alone has 455 names, so ORDER is the
whole design: the terms most likely to be said in THIS recording go first and the general list
fills whatever room is left.

1. the names of the artisans in the session (the people being interviewed);
2. the recording's own craft(s): the workshop's craft, the artisan's craft, the record's craft;
3. glossary terms already heard for those crafts (the living glossary feeds the next recording);
4. master-list crafts practised in the recording's state;
5. the curated vocabulary file (``app/data/craft_vocabulary.txt``);
6. the rest of the master list, in file order.

A term over 50 characters or 5 words is dropped (a provider rejects the whole request over one).
"""

from __future__ import annotations

from collections.abc import Iterable

LIMIT = 100
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
    """The tiers above, deduplicated case-insensitively, cut at ``limit``."""
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
