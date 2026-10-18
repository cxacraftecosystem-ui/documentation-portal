"""One naming convention for every name either portal generates (owner correction, 18 Oct 2026).

The convention is ``docs/conventions/NAMING_2026-10-18.md`` in the IIT docs tree, and the ``naming``
skill puts it into practice. This module is the Python half of it. ``frontend/lib/naming.ts`` and
``android/.../data/Naming.kt`` are the other two, and all three pass the same golden table,
``shared/naming-golden.json``. This file is byte-identical in designer-portal and
documentation-portal; the portal word comes from ``naming_portal.PORTAL``.

File names::

    Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext

Display titles (generated defaults only, never a title the user typed)::

    Entity_Date_Place_Craft_Subject

* ``_`` separates fields and ``-`` joins the words inside one. Nothing else is a separator.
* A field that is unknown is left out. Never ``Unknown``, ``NA`` or a guess.
* File names are ASCII. Titles may keep the original script.
* File names are at most 150 characters, titles 120. Subject is cut first (to 40), then Craft,
  then Place; Portal, Entity, Date, Time, What, Sequence, ``n`` and the extension never are.

Public API (kept stable: the questionnaire stream builds on it):
``session_title``, ``recording_filename``, ``export_filename``, ``file_name``, ``ascii_leaf``,
``dedupe_names``, ``field``, ``place_field``, ``question_sequence``, ``section_sequence``,
``workshop_filename``, ``transliterate``, ``content_disposition``.
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable, Mapping
from datetime import UTC, date, datetime, timedelta, timezone
from typing import Any
from urllib.parse import quote

from anyascii import anyascii

from app.services.naming_portal import PORTAL

IST = timezone(timedelta(hours=5, minutes=30), name="IST")

MAX_FILE_NAME = 150
MAX_TITLE = 120
MIN_SUBJECT = 40

RESERVED_STEMS = frozenset(
    {"CON", "PRN", "AUX", "NUL"}
    | {f"COM{i}" for i in range(1, 10)}
    | {f"LPT{i}" for i in range(1, 10)}
)

# Extensions that are two parts on purpose.
_DOUBLE_EXTENSIONS = (".sql.gz", ".tar.gz")
_VERSION = re.compile(r"^v\d+(?:\.\d+)*$")
_APOSTROPHES = "'\u2019\u02bc`"
_KEEP_FORMAT = frozenset({"\u200c", "\u200d"})
_MARKS = frozenset({"Mn", "Mc", "Me"})

# ---------------------------------------------------------------------------
# Transliteration
#
# anyascii maps one code point at a time, and for the Brahmic scripts it maps a consonant to its bare
# letter: प्रसाद comes out "prsad". The scripts carry an inherent "a" after every consonant that has
# no vowel sign or virama, so it is put back here, and dropped at the end of a word as Hindi, Bengali
# and their neighbours speak it: गिरिराज प्रसाद -> "giriraj prasad". The same rule is written out in
# naming.ts and Naming.kt, and the golden table's transliteration rows hold all three to it.
# ---------------------------------------------------------------------------

_INDIC_FIRST = 0x0900
_INDIC_LAST = 0x0D7F
# Devanagari, Bengali, Gurmukhi and Gujarati drop the inherent vowel at the end of a word; the
# southern scripts and Odia keep it (ಕನ್ನಡ is "kannada").
_FINAL_SCHWA_DROPS_BELOW = 0x0B00
# Assamese ra and wa sit outside the shared consonant range; Gurmukhi tippi is a nasal.
_EXTRA_CONSONANTS = frozenset({0x09F0, 0x09F1})
_NASALS = frozenset({0x0A70})


def _indic_offset(ch: str) -> int | None:
    cp = ord(ch)
    if _INDIC_FIRST <= cp <= _INDIC_LAST:
        return (cp - _INDIC_FIRST) % 0x80
    return None


def _is_consonant(ch: str | None) -> bool:
    if ch is None:
        return False
    off = _indic_offset(ch)
    return off is not None and (
        0x15 <= off <= 0x39 or 0x58 <= off <= 0x5F or ord(ch) in _EXTRA_CONSONANTS
    )


def _is_nasal(ch: str) -> bool:
    off = _indic_offset(ch)
    return off in (0x01, 0x02) or ord(ch) in _NASALS


def _is_sign(ch: str | None) -> bool:
    """Vowel signs and the virama: a consonant followed by one has no inherent vowel."""
    off = _indic_offset(ch) if ch is not None else None
    return off is not None and (
        0x3A <= off <= 0x3B or 0x3E <= off <= 0x4D or 0x55 <= off <= 0x57 or 0x62 <= off <= 0x63
    )


def _is_indic_letter(ch: str | None) -> bool:
    if ch is None:
        return False
    off = _indic_offset(ch)
    return off is not None and (
        0x01 <= off <= 0x63 or ord(ch) in _EXTRA_CONSONANTS or ord(ch) in _NASALS
    )


# Consonant + nukta pairs that NFC leaves apart (they are composition exclusions), put back together
# so a dda with a nukta reads "r" and not "d" followed by a stray mark.
_NUKTA_FORMS = {
    0x0915: 0x0958, 0x0916: 0x0959, 0x0917: 0x095A, 0x091C: 0x095B, 0x0921: 0x095C,
    0x0922: 0x095D, 0x092B: 0x095E, 0x092F: 0x095F, 0x09A1: 0x09DC, 0x09A2: 0x09DD,
    0x09AF: 0x09DF, 0x0A16: 0x0A59, 0x0A17: 0x0A5A, 0x0A1C: 0x0A5B, 0x0A2B: 0x0A5E,
    0x0A32: 0x0A33, 0x0A38: 0x0A36, 0x0B21: 0x0B5C, 0x0B22: 0x0B5D,
}


def _compose_nukta(chars: list[str]) -> list[str]:
    out: list[str] = []
    for ch in chars:
        if out and _indic_offset(ch) == 0x3C and ord(out[-1]) in _NUKTA_FORMS:
            out[-1] = chr(_NUKTA_FORMS[ord(out[-1])])
        else:
            out.append(ch)
    return out


def transliterate(text: str | None) -> str:
    """ASCII for any script, with the Brahmic inherent vowel kept where it is spoken."""
    chars = _compose_nukta(list(unicodedata.normalize("NFC", text or "")))
    out: list[str] = []
    word_has_vowel = False
    for i, ch in enumerate(chars):
        # Candrabindu, anusvara and tippi are a nasal: "n" reads right far more often than "m".
        if _indic_offset(ch) in (0x3C, 0x3D):  # a nukta left over, or an avagraha: no sound
            out.append("")
        else:
            out.append("n" if _is_nasal(ch) else anyascii(ch))
        if not _is_indic_letter(ch):
            word_has_vowel = False
            continue
        if not _is_consonant(ch):
            if _indic_offset(ch) != 0x4D and not _is_nasal(ch):  # virama and nasals are not vowels
                word_has_vowel = True
            continue
        j = i + 1
        while j < len(chars) and _indic_offset(chars[j]) == 0x3C:  # nukta
            j += 1
        nxt = chars[j] if j < len(chars) else None
        if _is_sign(nxt):
            if _indic_offset(nxt) != 0x4D:
                word_has_vowel = True
            continue
        word_end = not _is_indic_letter(nxt) or (_is_nasal(nxt) and not _is_indic_letter(
            chars[j + 1] if j + 1 < len(chars) else None
        ))
        drops = ord(ch) < _FINAL_SCHWA_DROPS_BELOW and word_has_vowel
        if not (word_end and drops):
            out.append("a")
            word_has_vowel = True
    return "".join(out)


# ---------------------------------------------------------------------------
# Fields
# ---------------------------------------------------------------------------


def _word(word: str) -> str:
    """Title-Case one word. Codes, acronyms and versions keep their own case."""
    if not word or any(ch.isdigit() for ch in word):
        return word
    if word.isupper():
        return word if len(word) <= 3 else word[0] + word[1:].lower()
    return word[0].upper() + word[1:]


def _words(text: str, *, ascii_only: bool) -> list[str]:
    for ch in _APOSTROPHES:
        text = text.replace(ch, "")
    current: list[str] = []
    words: list[str] = []
    for ch in text:
        keep = (
            (ch.isascii() and ch.isalnum())
            if ascii_only
            else (ch.isalnum() or unicodedata.category(ch) in _MARKS or ch in _KEEP_FORMAT)
        )
        if keep:
            current.append(ch)
        elif current:
            words.append("".join(current))
            current = []
    if current:
        words.append("".join(current))
    return [_word(w) for w in words]


def field(value: Any, *, ascii_only: bool = True) -> str:
    """One field: words Title-Cased and joined by ``-``. ASCII unless ``ascii_only`` is False."""
    if value is None:
        return ""
    text = unicodedata.normalize("NFC", str(value)).strip()
    if not text:
        return ""
    if ascii_only:
        text = transliterate(text)
    return "-".join(_words(text, ascii_only=ascii_only))


def place_field(
    locality: Any = None,
    district: Any = None,
    state: Any = None,
    *,
    ascii_only: bool = True,
) -> str:
    """``Locality-District``, else ``District``, else ``State``. A locality alone is not a place."""
    dist = field(district, ascii_only=ascii_only)
    loc = field(locality, ascii_only=ascii_only)
    if dist:
        if loc and loc.lower() != dist.lower():
            return f"{loc}-{dist}"
        return dist
    return field(state, ascii_only=ascii_only)


def _two(n: int) -> str:
    return f"{int(n):02d}"


def question_sequence(
    section: Any, question: Any, *, take: int | None = None, part: int | None = None
) -> str:
    """``D-Q03``, ``D-Q03-T2`` (a solo take at position 2 or more), ``D-Q03-P2`` (part 2)."""
    sec = field(section).upper()
    if not sec or question is None or str(question).strip() == "":
        return ""
    out = f"{sec}-Q{_two(int(question))}"
    if part and int(part) >= 2:
        out += f"-P{int(part)}"
    if take and int(take) >= 2:
        out += f"-T{int(take)}"
    return out


def section_sequence(section: Any) -> str:
    """``D-All``: a recording that covers a whole section."""
    sec = field(section).upper()
    return f"{sec}-All" if sec else ""


def _sequence(value: Any) -> str:
    text = str(value or "").strip()
    if _VERSION.match(text):
        return text
    return field(text)


# ---------------------------------------------------------------------------
# Dates (IST)
# ---------------------------------------------------------------------------


def as_ist(value: Any) -> datetime | date | None:
    """A datetime in IST, a date as it is, or None. Naive datetimes are UTC (the database's)."""
    if value is None or value == "":
        return None
    if isinstance(value, str):
        text = value.strip()
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", text):
            return date.fromisoformat(text)
        value = datetime.fromisoformat(text)
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(IST)
    if isinstance(value, date):
        return value
    return None


def _date_text(value: Any) -> str:
    moment = as_ist(value)
    return moment.strftime("%Y-%m-%d") if moment is not None else ""


def _time_text(value: Any) -> str:
    moment = as_ist(value)
    return moment.strftime("%H%M") if isinstance(moment, datetime) else ""


def now_ist() -> datetime:
    return datetime.now(IST)


# ---------------------------------------------------------------------------
# Assembly and truncation
# ---------------------------------------------------------------------------


def _drop_last_word(text: str) -> str:
    head, sep, _ = text.rpartition("-")
    return head if sep else ""


def _fit(parts: dict[str, str], render: Any, limit: int) -> str:
    """Cut Subject (to 40), then Craft, then Place, then the rest of Subject, word by word."""
    name = render(parts)
    for key, floor in (("subject", MIN_SUBJECT), ("craft", 0), ("place", 0), ("subject", 0)):
        while len(name) > limit and len(parts[key]) > floor:
            shorter = _drop_last_word(parts[key])
            if floor and not shorter:  # one long word: no boundary to retreat to
                shorter = parts[key][:floor]
            parts[key] = shorter
            name = render(parts)
    return name


def _extension(ext: str) -> str:
    text = str(ext or "").strip().lower().lstrip(".")
    text = re.sub(r"[^a-z0-9.]", "", text)
    text = re.sub(r"\.{2,}", ".", text).strip(".")
    return f".{text}" if text else ""


def file_name(
    *,
    entity: Any = None,
    what: Any = None,
    ext: str = "",
    portal: str | None = None,
    date: Any = None,
    time: bool = False,
    period: Any = None,
    place: Any = None,
    craft: Any = None,
    subject: Any = None,
    sequence: Any = None,
    n: int | None = None,
) -> str:
    """``Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext``, ASCII, <= 150."""
    fixed = {
        "portal": field(portal or PORTAL),
        "entity": field(entity),
        "date": field(period) if period else _date_text(date),
        "time": _time_text(date) if time and not period else "",
        "what": field(what),
        "sequence": _sequence(sequence),
        "n": str(int(n)) if n and int(n) >= 2 else "",
    }
    extension = _extension(ext)
    parts = {"place": field(place), "craft": field(craft), "subject": field(subject)}

    def render(p: dict[str, str]) -> str:
        order = (
            fixed["portal"],
            fixed["entity"],
            fixed["date"],
            fixed["time"],
            p["place"],
            p["craft"],
            p["subject"],
            fixed["what"],
            fixed["sequence"],
            fixed["n"],
        )
        return "_".join(x for x in order if x) + extension

    return _fit(parts, render, MAX_FILE_NAME)


def export_filename(
    entity: Any,
    what: Any,
    ext: str,
    *,
    at: Any = None,
    with_time: bool = False,
    dated: bool = True,
    period: Any = None,
    place: Any = None,
    craft: Any = None,
    subject: Any = None,
    sequence: Any = None,
    n: int | None = None,
    portal: str | None = None,
) -> str:
    """A download, export, report or backup, dated the day it is generated (IST).

    ``with_time`` adds ``HHMM`` for snapshots that can repeat within a day. ``dated=False`` is for
    templates (pro formas), which carry no date at all.
    """
    when = (at if at is not None else now_ist()) if dated and not period else None
    return file_name(
        portal=portal,
        entity=entity,
        date=when,
        time=with_time,
        period=period,
        place=place,
        craft=craft,
        subject=subject,
        what=what,
        sequence=sequence,
        n=n,
        ext=ext,
    )


def recording_filename(
    entity: Any,
    ext: str,
    *,
    captured_at: Any = None,
    time_known: bool = True,
    place: Any = None,
    craft: Any = None,
    subject: Any = None,
    sequence: Any = None,
    what: Any = "Recording",
    n: int | None = None,
    portal: str | None = None,
) -> str:
    """A capture, dated and timed when it was captured. An estimated time is left out."""
    return file_name(
        portal=portal,
        entity=entity,
        date=captured_at,
        time=bool(time_known),
        place=place,
        craft=craft,
        subject=subject,
        what=what,
        sequence=sequence,
        n=n,
        ext=ext,
    )


def _get(obj: Any, key: str) -> Any:
    return obj.get(key) if isinstance(obj, Mapping) else getattr(obj, key, None)


def workshop_filename(
    workshop: Any,
    what: Any,
    ext: str,
    *,
    at: Any = None,
    dated: bool = True,
    n: int | None = None,
    portal: str | None = None,
) -> str:
    """A design workshop's report, cards or list: ``..._Workshop_Date_Place_Craft_Code_What.ext``.

    ``workshop`` is the record or a mapping with ``workshopCode``, ``title``, ``clusterName``,
    ``district``, ``state`` and ``craftName``. The server, the web's offline report and the handset
    name one report identically through this.
    """
    code = _get(workshop, "workshopCode") or _get(workshop, "title")
    return export_filename(
        "Workshop",
        what,
        ext,
        at=at,
        dated=dated,
        place=place_field(
            _get(workshop, "clusterName"), _get(workshop, "district"), _get(workshop, "state")
        ),
        craft=_get(workshop, "craftName"),
        subject=code,
        n=n,
        portal=portal,
    )


def _participant_name(person: Any) -> str:
    if isinstance(person, Mapping):
        roman = person.get("romanisedName") or person.get("nameEnglish")
        return str(roman or person.get("name") or "")
    return str(person or "")


def session_title(fields: Mapping[str, Any]) -> str:
    """``Entity_Date_Place_Craft_Subject`` for a generated interview, session or workshop title.

    ``fields``: ``participants`` (names, or ``{"name", "romanisedName"}``), ``participantCount``
    (when only the head count is known), ``date``, ``place`` (already chosen), ``craft``, and
    optionally ``entity`` (``Workshop``) and ``subject`` (a workshop code) to override the
    participant-derived ones. Original script is kept. At most 120 characters.
    """
    people = [p for p in (fields.get("participants") or []) if _participant_name(p)]
    count = len(people) or int(fields.get("participantCount") or 0)
    entity = fields.get("entity") or ("Group-Interview" if count >= 2 else "Interview")
    subject = fields.get("subject")
    if not subject:
        if count >= 2:
            subject = f"{count} Artisans"
        elif people:
            subject = _participant_name(people[0])
    fixed_entity = field(entity)
    fixed_date = _date_text(fields.get("date"))
    parts = {
        "place": field(fields.get("place"), ascii_only=False),
        "craft": field(fields.get("craft"), ascii_only=False),
        "subject": field(subject, ascii_only=False),
    }

    def render(p: dict[str, str]) -> str:
        order = (fixed_entity, fixed_date, p["place"], p["craft"], p["subject"])
        return "_".join(x for x in order if x)

    return _fit(parts, render, MAX_TITLE)


# ---------------------------------------------------------------------------
# Leaves, collisions, Content-Disposition
# ---------------------------------------------------------------------------


def split_extension(name: str) -> tuple[str, str]:
    lower = name.lower()
    for double in _DOUBLE_EXTENSIONS:
        if lower.endswith(double) and len(name) > len(double):
            return name[: -len(double)], name[-len(double) :]
    stem, dot, ext = name.rpartition(".")
    if dot and stem and 0 < len(ext) <= 10 and ext.isascii() and ext.isalnum():
        return stem, f".{ext}"
    return name, ""


def ascii_leaf(name: Any) -> str:
    """Any name made safe as an S3 leaf or a download name: ASCII ``[A-Za-z0-9._-]``, <= 150."""
    text = unicodedata.normalize("NFC", str(name or "")).strip()
    text = text.replace("\\", "/").rsplit("/", 1)[-1]
    stem, ext = split_extension(text)
    ext = _extension(ext)
    stem = transliterate(stem)
    for ch in _APOSTROPHES:
        stem = stem.replace(ch, "")
    stem = re.sub(r"[^A-Za-z0-9._-]+", "-", stem)
    stem = re.sub(r"-{2,}", "-", stem)
    stem = re.sub(r"_{2,}", "_", stem).strip("-._")
    if not stem:
        stem = "File"
    if stem.split(".")[0].upper() in RESERVED_STEMS:
        stem = f"{stem}-File"
    room = MAX_FILE_NAME - len(ext)
    if len(stem) > room:
        stem = stem[:room].rstrip("-._") or "File"
    return stem + ext


def dedupe_names(names: Iterable[str]) -> list[str]:
    """Give the second and later copies of a name ``_2``, ``_3``, in the order given.

    Pass the names in a fixed order (createdAt, then id) so a repeat download names every file the
    same way. Matching ignores case, because a zip extracted on Windows does.
    """
    used: set[str] = set()
    out: list[str] = []
    for name in names:
        stem, ext = split_extension(name)
        candidate, n = name, 1
        while candidate.lower() in used:
            n += 1
            candidate = f"{stem}_{n}{ext}"
        used.add(candidate.lower())
        out.append(candidate)
    return out


def content_disposition(filename: str, *, inline: bool = False) -> str:
    """``attachment; filename="<ascii>"; filename*=UTF-8''<percent-encoded>``."""
    kind = "inline" if inline else "attachment"
    plain = ascii_leaf(filename)
    encoded = quote(str(filename or plain), safe="")
    return f"{kind}; filename=\"{plain}\"; filename*=UTF-8''{encoded}"

