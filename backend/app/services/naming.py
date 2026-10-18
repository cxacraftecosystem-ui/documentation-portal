"""THE one naming convention for generated names (owner correction (a), 18 Oct 2026).

Every generated session title, recording file name, export name and S3 leaf in this portal comes
out of this module, and nowhere else formats one inline. The convention is
``C:/dev/IIT/docs/conventions/NAMING_2026-10-18.md``; the exact rules and the golden examples that
three runtimes must reproduce byte for byte are ``shared/session-title.json``:

* Python: this module (``tests/test_naming.py`` runs every golden row);
* web: ``frontend/lib/naming.ts``;
* Android: ``data/Naming.kt``.

Two grammars, one set of fields:

    title      Entity_Date_Place_Craft_Subject                                     (<= 120 chars)
    file name  FieldRepository_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext
                                                                                   (<= 150 chars, ASCII)

``_`` separates fields and ``-`` joins the words inside a field; nothing else is a separator. A
field that is unknown is LEFT OUT, never written as "Unknown" or guessed: a name that says less is
fine, a name that says something false is not. Titles may keep the original script; file names are
transliterated to ASCII (``ascii_words``).
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta, timezone
from typing import Any

PORTAL = "FieldRepository"
IST = timezone(timedelta(hours=5, minutes=30), name="IST")
TITLE_MAX = 120
FILE_MAX = 150
SUBJECT_MIN = 40
ESTIMATED_SOURCES = frozenset({"SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW"})
RESERVED_STEMS = frozenset(
    {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}
)
_ZW = frozenset({"\u200c", "\u200d"})  # ZWNJ / ZWJ: load-bearing inside Indic words
_DISPLAY_CODE = re.compile(r"^([A-Za-z]+)[\s.-]*0*(\d+)$")

# ── Indic inherent vowel (shared rule, see session-title.json rules.transliteration) ────────────
# anyascii maps every Brahmic consonant WITHOUT its inherent "a" (प्रसाद -> "prsad"). Before handing a
# string to it, an ASCII "a" is inserted after a consonant that is followed, inside the same word, by
# another letter of the same block that is not a virama or a vowel sign. A consonant at the end of a
# word gets nothing (schwa deletion), so गिरिराज प्रसाद becomes "giriraj prasad".
_INDIC_BLOCKS = (0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00)


def _indic(cp: int) -> tuple[int, int] | None:
    for base in _INDIC_BLOCKS:
        if base <= cp < base + 0x80:
            return base, cp - base
    return None


def _is_consonant(off: int) -> bool:
    return 0x15 <= off <= 0x39 or 0x58 <= off <= 0x5F


def _is_vowel_sign_or_virama(off: int) -> bool:
    return 0x3E <= off <= 0x4D or 0x55 <= off <= 0x57 or off in (0x62, 0x63)


def _is_word_letter(off: int) -> bool:
    # consonants, independent vowels, candrabindu / anusvara / visarga
    return _is_consonant(off) or 0x05 <= off <= 0x14 or 0x01 <= off <= 0x03 or off in (0x60, 0x61)


def indic_inherent_vowels(value: str) -> str:
    """Drop every nukta (offset 0x3C), then insert the inherent "a" (see the comment above)."""
    chars = [ch for ch in value if not ((info := _indic(ord(ch))) and info[1] == 0x3C)]
    out: list[str] = []
    for i, ch in enumerate(chars):
        out.append(ch)
        info = _indic(ord(ch))
        if info and _is_consonant(info[1]) and i + 1 < len(chars):
            nxt = _indic(ord(chars[i + 1]))
            if nxt and nxt[0] == info[0] and not _is_vowel_sign_or_virama(nxt[1]) and _is_word_letter(nxt[1]):
                out.append("a")
    return "".join(out)


def to_ascii(value: str | None) -> str:
    """Transliterate to ASCII: the Indic inherent-vowel pass, then ``anyascii``."""
    if not value:
        return ""
    from anyascii import anyascii

    return anyascii(indic_inherent_vowels(unicodedata.normalize("NFC", value)))


# ── words and fields ───────────────────────────────────────────────────────────────────────────
def _is_word_char(ch: str) -> bool:
    if ch in _ZW:
        return True
    cat = unicodedata.category(ch)
    return cat[0] in ("L", "M") or cat == "Nd"


def split_words(value: str | None) -> list[str]:
    """Spaces and punctuation (including ``_``, ``-``, ``:``, ``·``, ``,`` and ``/``) end a word."""
    words: list[str] = []
    current: list[str] = []
    for ch in unicodedata.normalize("NFC", str(value or "")):
        if _is_word_char(ch):
            current.append(ch)
        elif current:
            words.append("".join(current))
            current = []
    if current:
        words.append("".join(current))
    return words


def title_case_word(word: str) -> str:
    """Title-Case one word. A word holding a digit, or an upper-case Latin word of 2-3 letters
    (an acronym or code part: ``DW``, ``RJ``, ``GI``), keeps its own case."""
    if any(ch.isdigit() for ch in word):
        return word
    if 2 <= len(word) <= 3 and word.isascii() and word.isalpha() and word.isupper():
        return word
    first = word[0]
    if first.isalpha() and first.upper() != first.lower():
        return first.upper() + word[1:].lower()
    return word


def field_words(value: str | None, *, ascii_only: bool) -> list[str]:
    text = to_ascii(value) if ascii_only else str(value or "")
    words = split_words(text)
    if ascii_only:
        words = ["".join(ch for ch in w if ch.isascii() and ch.isalnum()) for w in words]
        words = [w for w in words if w]
    return [title_case_word(w) for w in words]


def join_field(words: Sequence[str]) -> str:
    return "-".join(words)


# ── the inputs ─────────────────────────────────────────────────────────────────────────────────
@dataclass
class Person:
    name: str | None = None
    localName: str | None = None
    craft: str | None = None


@dataclass
class TitleInput:
    """What a generated title is computed from. Runtime-neutral: the golden table uses this shape."""

    sessionDate: date | None = None
    place: str | None = None
    workshop: Mapping[str, Any] | None = None  # {locality, district, state, craft}
    participants: list[Person] = field(default_factory=list)
    entity: str | None = None  # override (designer portal: "Workshop"); else derived
    subject: str | None = None  # override (a design workshop's code)


def _parse_date(value: Any) -> date | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value.astimezone(IST).date() if value.tzinfo else value.date()
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value)[:10])


def _parse_instant(value: Any) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    text = str(value).replace("Z", "+00:00")
    parsed = datetime.fromisoformat(text)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def title_input(data: Mapping[str, Any]) -> TitleInput:
    """Build a :class:`TitleInput` from the golden-table JSON shape (also used by the services)."""
    people = [
        p if isinstance(p, Person) else Person(name=p.get("name"), localName=p.get("localName"), craft=p.get("craft"))
        for p in (data.get("participants") or [])
    ]
    return TitleInput(
        sessionDate=_parse_date(data.get("sessionDate")),
        place=data.get("place"),
        workshop=data.get("workshop"),
        participants=people,
        entity=data.get("entity"),
        subject=data.get("subject"),
    )


def _same(a: str | None, b: str | None) -> bool:
    return " ".join(split_words(a)).casefold() == " ".join(split_words(b)).casefold()


def _place_source(inp: TitleInput) -> str | None:
    if (inp.place or "").strip():
        return inp.place
    ws = inp.workshop or {}
    locality, district, state = (ws.get("locality") or "").strip(), (ws.get("district") or "").strip(), (ws.get("state") or "").strip()
    if locality and district and not _same(locality, district):
        return f"{locality} {district}"
    return locality or district or state or None


def _craft_source(inp: TitleInput) -> str | None:
    ws = inp.workshop or {}
    if (ws.get("craft") or "").strip():
        return ws["craft"]
    crafts = [(p.craft or "").strip() for p in inp.participants]
    if crafts and all(crafts) and all(_same(c, crafts[0]) for c in crafts):
        return crafts[0]
    return None


def _entity(inp: TitleInput) -> str:
    if inp.entity:
        return inp.entity
    return "Group-Interview" if len(inp.participants) >= 2 else "Interview"


def _subject_source(inp: TitleInput, *, ascii_only: bool) -> str | None:
    if inp.subject:
        return inp.subject
    n = len(inp.participants)
    if n >= 2:
        return f"{n} Artisans"
    if n == 1:
        person = inp.participants[0]
        if ascii_only:
            # A stored romanised name wins over transliteration.
            for candidate in (person.name, person.localName):
                if candidate and candidate.strip() and candidate.isascii():
                    return candidate
        return person.name or person.localName
    return None


def _fit(render: Any, place: list[str], craft: list[str], subject: list[str], limit: int) -> str:
    """Shorten until ``render`` fits ``limit``: Subject first (to SUBJECT_MIN, at a word boundary),
    then Craft, then Place, then Subject again, then drop Craft, then Place, then cut Subject."""

    def too_long() -> bool:
        return len(render(place, craft, subject)) > limit

    while too_long() and len(subject) > 1 and len(join_field(subject)) > SUBJECT_MIN:
        subject.pop()
    while too_long() and len(craft) > 1:
        craft.pop()
    while too_long() and len(place) > 1:
        place.pop()
    while too_long() and len(subject) > 1:
        subject.pop()
    if too_long() and craft:
        craft.clear()
    if too_long() and place:
        place.clear()
    while too_long() and subject and len(subject[0]) > 1:
        subject[0] = subject[0][:-1]
    if too_long():
        subject.clear()
    return render(place, craft, subject)


def _fields(*parts: str | None) -> str:
    return "_".join(p for p in parts if p)


# ── titles ─────────────────────────────────────────────────────────────────────────────────────
def session_title(inp: TitleInput | Mapping[str, Any]) -> str:
    """``Entity_Date_Place_Craft_Subject``, at most 120 characters, original script allowed."""
    if not isinstance(inp, TitleInput):
        inp = title_input(inp)
    entity = _entity(inp)
    day = inp.sessionDate.isoformat() if inp.sessionDate else None
    place = field_words(_place_source(inp), ascii_only=False)
    craft = field_words(_craft_source(inp), ascii_only=False)
    subject = field_words(_subject_source(inp, ascii_only=False), ascii_only=False)

    def render(p: list[str], c: list[str], s: list[str]) -> str:
        return _fields(entity, day, join_field(p), join_field(c), join_field(s))

    return _fit(render, place, craft, subject, TITLE_MAX)


# ── file names ─────────────────────────────────────────────────────────────────────────────────
def _guard(stem: str) -> str:
    return f"{stem}-File" if stem.upper() in RESERVED_STEMS else stem


def file_name(
    *,
    entity: str,
    what: str,
    ext: str,
    day: date | None = None,
    time: str | None = None,
    place: str | None = None,
    craft: str | None = None,
    subject: str | None = None,
    sequence: str | None = None,
    n: int | None = None,
    portal: str = PORTAL,
) -> str:
    """The NAMING §2.1 grammar, ASCII, at most 150 characters including the extension."""
    ext = re.sub(r"[^A-Za-z0-9.]", "", (ext or "").lstrip(".")).lower() or "bin"
    place_w = field_words(place, ascii_only=True)
    craft_w = field_words(craft, ascii_only=True)
    subject_w = field_words(subject, ascii_only=True)
    entity_f = join_field(field_words(entity, ascii_only=True))
    what_f = join_field(field_words(what, ascii_only=True))
    seq_f = sequence or None
    n_f = str(n) if n and n > 1 else None

    def render(p: list[str], c: list[str], s: list[str]) -> str:
        stem = _fields(
            portal, entity_f, day.isoformat() if day else None, time, join_field(p), join_field(c),
            join_field(s), what_f, seq_f, n_f,
        )
        return f"{_guard(stem)}.{ext}"

    return _fit(render, place_w, craft_w, subject_w, FILE_MAX)


@dataclass
class TargetRef:
    sectionCode: str
    displayCode: str | None = None  # None: the whole section


def _question_number(display_code: str) -> tuple[str, int] | None:
    match = _DISPLAY_CODE.match(display_code.strip())
    return (match.group(1).upper(), int(match.group(2))) if match else None


def recording_sequence(
    targets: Iterable[TargetRef | Mapping[str, Any]], *, part: int | None = None, position: int | None = None,
    roster_count: int | None = None,
) -> str | None:
    """``D-Q03``, ``B-All``, ``F-Q01-Q06``, plus ``-P2`` for a continuation or ``-T2`` for a solo
    repeat. Whole-session takes have no section piece. Group takes never carry ``-T``."""
    by_section: dict[str, dict[str, Any]] = {}
    order: list[str] = []
    for raw in targets:
        t = raw if isinstance(raw, TargetRef) else TargetRef(sectionCode=raw.get("sectionCode") or "", displayCode=raw.get("displayCode"))
        code = join_field(field_words(t.sectionCode, ascii_only=True)).upper() if t.sectionCode else ""
        if not code and t.displayCode:
            parsed = _question_number(t.displayCode)
            code = parsed[0] if parsed else ""
        if not code:
            continue
        if code not in by_section:
            by_section[code] = {"all": False, "numbers": []}
            order.append(code)
        if t.displayCode:
            parsed = _question_number(t.displayCode)
            if parsed:
                by_section[code]["numbers"].append(parsed[1])
        else:
            by_section[code]["all"] = True
    pieces: list[str] = []
    for code in order:
        entry = by_section[code]
        numbers = sorted(set(entry["numbers"]))
        if entry["all"] or not numbers:
            pieces.append(f"{code}-All")
        elif len(numbers) == 1:
            pieces.append(f"{code}-Q{numbers[0]:02d}")
        else:
            pieces.append(f"{code}-Q{numbers[0]:02d}-Q{numbers[-1]:02d}")
    suffix = None
    if part and part >= 2:
        suffix = f"P{part}"
    elif roster_count == 1 and position and position >= 2:
        suffix = f"T{position}"
    sequence = "-".join(pieces) if pieces else None
    if suffix:
        sequence = f"{sequence}-{suffix}" if sequence else suffix
    return sequence


def recording_filename(
    session: TitleInput | Mapping[str, Any],
    *,
    captured_at: Any,
    captured_at_source: str | None,
    targets: Iterable[TargetRef | Mapping[str, Any]] = (),
    ext: str = "m4a",
    what: str = "Recording",
    part: int | None = None,
    position: int | None = None,
    roster_count: int | None = None,
    n: int | None = None,
) -> str:
    """§3.13: the recording, transcript or subtitle file name of one take."""
    inp = session if isinstance(session, TitleInput) else title_input(session)
    instant = _parse_instant(captured_at)
    local = instant.astimezone(IST) if instant else None
    estimated = (captured_at_source or "").upper() in ESTIMATED_SOURCES
    return file_name(
        entity=_entity(inp),
        what=what,
        ext=ext,
        day=local.date() if local else None,
        time=local.strftime("%H%M") if local and not estimated else None,
        place=_place_source(inp),
        craft=_craft_source(inp),
        subject=_subject_source(inp, ascii_only=True),
        sequence=recording_sequence(targets, part=part, position=position, roster_count=roster_count),
        n=n,
    )


def session_filename(session: TitleInput | Mapping[str, Any], *, what: str, ext: str) -> str:
    """A file about a whole session (the zip of its recordings): no time, no sequence."""
    inp = session if isinstance(session, TitleInput) else title_input(session)
    return file_name(
        entity=_entity(inp), what=what, ext=ext, day=inp.sessionDate, place=_place_source(inp),
        craft=_craft_source(inp), subject=_subject_source(inp, ascii_only=True),
    )


def export_filename(
    *, entity: str, what: str, ext: str, day: Any = None, subject: str | None = None,
    place: str | None = None, craft: str | None = None, time: str | None = None,
) -> str:
    """Exports and reports: the generation day (IST) unless ``day`` is given."""
    resolved = _parse_date(day) if day is not None else datetime.now(IST).date()
    return file_name(entity=entity, what=what, ext=ext, day=resolved, subject=subject, place=place, craft=craft, time=time)


# ── leaves, collisions, Content-Disposition ────────────────────────────────────────────────────
def ascii_leaf(filename: str | None, *, fallback: str = "upload.bin") -> str:
    """Any file name as an ASCII S3 leaf: ``[A-Za-z0-9._-]``, at most 150 characters, never empty,
    never a Windows reserved stem. Used for client-named uploads; generated names already comply."""
    raw = str(filename or "").replace("\\", "/").rsplit("/", 1)[-1].strip()
    text = to_ascii(raw)
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", text)
    cleaned = re.sub(r"-{2,}", "-", cleaned).strip("-.")
    if not cleaned:
        return fallback
    stem, dot, ext = cleaned.rpartition(".")
    if not dot:
        stem, ext = cleaned, ""
    stem = _guard(stem.strip("-.") or "upload")
    ext = ext[:16]
    budget = FILE_MAX - (len(ext) + 1 if ext else 0)
    stem = stem[:budget].rstrip("-.") or "upload"
    return f"{stem}.{ext}" if ext else stem


def with_n(name: str, n: int) -> str:
    """``..._Recording_D-Q03.m4a`` -> ``..._Recording_D-Q03_2.m4a`` (``n`` is always last)."""
    if n <= 1:
        return name
    if name.endswith(".sql.gz"):
        return f"{name[:-7]}_{n}.sql.gz"
    stem, dot, ext = name.rpartition(".")
    return f"{stem}_{n}.{ext}" if dot else f"{name}_{n}"


def dedupe_names(names: Sequence[str]) -> list[str]:
    """Give colliding names ``_2``, ``_3`` in the order given. Callers pass rows sorted by
    (createdAt, id), so a repeated download yields the same names."""
    seen: dict[str, int] = {}
    taken: set[str] = set()
    out: list[str] = []
    for name in names:
        key = name.lower()
        count = seen.get(key, 0) + 1
        seen[key] = count
        candidate = with_n(name, count)
        while candidate.lower() in taken:
            count += 1
            seen[key] = count
            candidate = with_n(name, count)
        taken.add(candidate.lower())
        out.append(candidate)
    return out


def content_disposition(filename: str, *, inline: bool = False) -> str:
    """``attachment; filename="<ascii>"; filename*=UTF-8''<percent-encoded>``."""
    from urllib.parse import quote

    ascii_name = ascii_leaf(filename, fallback="download")
    kind = "inline" if inline else "attachment"
    return f"{kind}; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(filename, safe='')}"
