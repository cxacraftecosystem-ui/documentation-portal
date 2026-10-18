"""Generated file names: one convention for both portals (docs/conventions/NAMING_2026-10-18.md).

``Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext``

``_`` separates fields and ``-`` joins the words inside one. A field that is unknown is left out,
never written as ``Unknown``. ASCII only, Title-Case words (codes and acronyms keep their case), at
most 150 characters: Subject is cut first (to 40), then Craft, then Place.

This module is shared byte for byte between designer-portal and documentation-portal. Names are
built here and nowhere else.
"""

from __future__ import annotations

import re
import unicodedata
from datetime import UTC, date, datetime, timedelta, timezone

PORTAL_DESIGN_WORKSHOP = "DesignWorkshop"
PORTAL_FIELD_REPOSITORY = "FieldRepository"

MAX_LENGTH = 150
SUBJECT_MAX = 40

# IST has no daylight saving, and a fixed offset needs no tz database (Windows has none).
_IST = timezone(timedelta(hours=5, minutes=30))
_WORD = re.compile(r"[A-Za-z0-9]+")
_RESERVED = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}


def _title(word: str) -> str:
    # A code or acronym (all capitals, or carrying a digit) keeps its own case.
    if word.isupper() or any(ch.isdigit() for ch in word):
        return word
    return word[:1].upper() + word[1:].lower()


def field(text: str | None) -> str:
    """One field: ASCII words, Title-Case, joined by ``-``. Empty when nothing survives."""
    if not text:
        return ""
    ascii_text = unicodedata.normalize("NFKD", str(text)).encode("ascii", "ignore").decode("ascii")
    return "-".join(_title(word) for word in _WORD.findall(ascii_text))


def ist_date(moment: datetime | date | None = None) -> str:
    """``YYYY-MM-DD`` in IST; now when no moment is given."""
    if isinstance(moment, datetime):
        aware = moment if moment.tzinfo else moment.replace(tzinfo=UTC)
        return aware.astimezone(_IST).date().isoformat()
    if isinstance(moment, date):
        return moment.isoformat()
    return datetime.now(_IST).date().isoformat()


def file_name(
    portal: str,
    entity: str,
    what: str,
    ext: str,
    *,
    day: str | None = None,
    place: str | None = None,
    craft: str | None = None,
    subject: str | None = None,
    sequence: str | None = None,
) -> str:
    """The file name for these fields, in the convention's order, unknown fields left out."""
    fixed_head = [portal, entity, day or ""]
    fixed_tail = [what, field(sequence) if sequence else ""]
    parts = {"place": field(place), "craft": field(craft), "subject": field(subject)[:SUBJECT_MAX].strip("-")}
    suffix = f".{ext.lstrip('.')}"

    def build() -> str:
        fields = [*fixed_head, parts["place"], parts["craft"], parts["subject"], *fixed_tail]
        return "_".join(f for f in fields if f) + suffix

    name = build()
    for key in ("subject", "craft", "place"):
        if len(name) <= MAX_LENGTH:
            break
        over = len(name) - MAX_LENGTH
        parts[key] = parts[key][: max(0, len(parts[key]) - over)].strip("-")
        name = build()
    stem = name.split(".", 1)[0]
    if stem.upper() in _RESERVED:
        name = f"{stem}-File{suffix}"
    return name
