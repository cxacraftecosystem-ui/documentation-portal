"""THE one naming convention for generated names (owner correction (a), 18 Oct 2026).

Every generated title, file name, export or download name and S3 leaf in either portal comes out of
this module, and nowhere else formats one inline. The convention is
``C:/dev/IIT/docs/conventions/NAMING_2026-10-18.md`` and the ``naming`` skill. Two golden tables hold
three runtimes (this module, ``frontend/lib/naming.ts``, ``android/.../data/Naming.kt``) to the same
bytes:

* ``shared/session-title.json``: the frozen questionnaire contract (titles, recording names,
  transliteration, exports, collisions). Its rules section is the specification of this module.
* ``shared/naming-golden.json``: the rows for the helpers outside that contract (any file name,
  places, sequences, workshop reports, S3 leaves).

This file is byte-identical in designer-portal and documentation-portal; the portal word comes from
``naming_portal.PORTAL``.

    title      Entity_Date_Place_Craft_Subject                                     (<= 120 chars)
    file name  Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext
                                                                                   (<= 150 chars, ASCII)

``_`` separates fields and ``-`` joins the words inside a field; nothing else is a separator. A
field that is unknown is LEFT OUT, never written as "Unknown" or guessed. Titles may keep the
original script; file names are transliterated to ASCII.

The questionnaire streams call the TitleInput API (``session_title(TitleInput)``,
``recording_filename(session, captured_at=..., captured_at_source=..., targets=[TargetRef])``,
``export_filename(entity=..., what=..., ext=..., day=...)``, ``ascii_leaf(name, fallback=...)``);
the rest of the code base calls the same functions with plain fields
(``recording_filename("Interview", "m4a", captured_at=...)``, ``session_title({"date": ...})``).
Both shapes are one implementation.
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field as dataclass_field
from datetime import UTC, date, datetime, timedelta, timezone
from typing import Any
from urllib.parse import quote

from app.services.naming_portal import PORTAL

IST = timezone(timedelta(hours=5, minutes=30), name="IST")
TITLE_MAX = 120
FILE_MAX = 150
SUBJECT_MIN = 40
# The names the rest of the code base already imports.
MAX_TITLE = TITLE_MAX
MAX_FILE_NAME = FILE_MAX
MIN_SUBJECT = SUBJECT_MIN
ESTIMATED_SOURCES = frozenset({"SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW"})
RESERVED_STEMS = frozenset(
    {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}
)
_ZW = frozenset({"\u200c", "\u200d"})  # ZWNJ / ZWJ: load-bearing inside Indic words
_DISPLAY_CODE = re.compile(r"^([A-Za-z]+)[\s.-]*0*(\d+)$")
_VERSION = re.compile(r"^v\d+(?:\.\d+)*$")
_DOUBLE_EXTENSIONS = (".sql.gz", ".tar.gz")

# ── Indic inherent vowel (session-title.json rules.transliteration) ────────────────────────────
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

    return anyascii(indic_inherent_vowels(unicodedata.normalize("NFC", str(value))))


transliterate = to_ascii


# ── words and fields ───────────────────────────────────────────────────────────────────────────
def _is_word_char(ch: str) -> bool:
    if ch in _ZW:
        return True
    cat = unicodedata.category(ch)
    return cat[0] in ("L", "M") or cat == "Nd"


def split_words(value: Any) -> list[str]:
    """Spaces and punctuation (including ``_``, ``-``, ``:``, ``·``, ``,`` and ``/``) end a word."""
    words: list[str] = []
    current: list[str] = []
    for ch in unicodedata.normalize("NFC", "" if value is None else str(value)):
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


def field_words(value: Any, *, ascii_only: bool) -> list[str]:
    raw = "" if value is None else str(value)
    text = to_ascii(raw) if ascii_only else raw
    words = split_words(text)
    if ascii_only:
        words = ["".join(ch for ch in w if ch.isascii() and ch.isalnum()) for w in words]
        words = [w for w in words if w]
    return [title_case_word(w) for w in words]


def join_field(words: Sequence[str]) -> str:
    return "-".join(words)


def field(value: Any, *, ascii_only: bool = True) -> str:
    """One field: words Title-Cased and joined by ``-``. ASCII unless ``ascii_only`` is False."""
    return join_field(field_words(value, ascii_only=ascii_only))


def _same(a: str | None, b: str | None) -> bool:
    """The same words, ignoring case; also when one is written in another script (Majuli, माजुली)."""
    if " ".join(split_words(a)).casefold() == " ".join(split_words(b)).casefold():
        return True
    return field(a).casefold() == field(b).casefold() != ""


def _place_text(locality: Any, district: Any, state: Any) -> str | None:
    """``locality district`` (district dropped when it equals the locality); else locality; else
    district; else state."""
    loc = str(locality or "").strip()
    dist = str(district or "").strip()
    st = str(state or "").strip()
    if loc and dist and not _same(loc, dist):
        return f"{loc} {dist}"
    return loc or dist or st or None


def place_field(locality: Any = None, district: Any = None, state: Any = None, *, ascii_only: bool = True) -> str:
    """The Place field: ``Locality-District``, else ``Locality``, else ``District``, else ``State``."""
    return field(_place_text(locality, district, state), ascii_only=ascii_only)


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
    participants: list[Person] = dataclass_field(default_factory=list)
    entity: str | None = None  # override (designer portal: "Workshop"); else derived
    subject: str | None = None  # override (a design workshop's code)
    participantCount: int | None = None  # when only the head count is known


def _parse_date(value: Any) -> date | None:
    """A calendar day: a date as it is, a datetime's IST day (naive is UTC), a string's first ten
    characters (``sessionDate`` is a day, never an instant)."""
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(IST).date()
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value).strip()[:10])


def _ist_date(value: Any) -> date | None:
    """The IST day of a moment: a datetime or an ISO instant string; a bare day as it is."""
    instant = _parse_instant(value)
    return instant.astimezone(IST).date() if instant else _parse_date(value)


def _parse_instant(value: Any) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, date):
        return None
    text = str(value).strip().replace("Z", "+00:00")
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", text):
        return None
    parsed = datetime.fromisoformat(text)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _person(p: Any) -> Person | None:
    if isinstance(p, Person):
        return p
    if isinstance(p, Mapping):
        roman = p.get("romanisedName") or p.get("nameEnglish")
        if roman:
            return Person(name=roman, localName=p.get("localName") or p.get("name"), craft=p.get("craft"))
        if not (p.get("name") or p.get("localName")) and not p.get("craft"):
            return None
        return Person(name=p.get("name"), localName=p.get("localName"), craft=p.get("craft"))
    text = str(p or "").strip()
    return Person(name=text) if text else None


def title_input(data: Mapping[str, Any]) -> TitleInput:
    """A :class:`TitleInput` from a mapping: the golden-table shape (``sessionDate``, ``place``,
    ``workshop``, ``participants`` of ``{name, localName, craft}``), plus the plain-field spellings
    (``date``, ``craft``, participant names as strings or ``{name, romanisedName}``,
    ``participantCount``)."""
    people = [person for p in (data.get("participants") or []) if (person := _person(p))]
    workshop = data.get("workshop")
    craft = data.get("craft")
    if craft and not (workshop or {}).get("craft"):
        workshop = {**(workshop or {}), "craft": craft}
    count = data.get("participantCount")
    return TitleInput(
        sessionDate=_parse_date(data.get("sessionDate")) if data.get("sessionDate") else _ist_date(data.get("date")),
        place=data.get("place"),
        workshop=workshop,
        participants=people,
        entity=data.get("entity"),
        subject=data.get("subject"),
        participantCount=int(count) if count else None,
    )


def _as_input(value: Any) -> TitleInput:
    if isinstance(value, TitleInput):
        return value
    if isinstance(value, Mapping):
        return title_input(value)
    keys = ("sessionDate", "date", "place", "workshop", "participants", "entity", "subject", "craft", "participantCount")
    return title_input({k: getattr(value, k, None) for k in keys})


def _place_source(inp: TitleInput) -> str | None:
    if (inp.place or "").strip():
        return inp.place
    ws = inp.workshop or {}
    return _place_text(ws.get("locality"), ws.get("district"), ws.get("state"))


def _craft_source(inp: TitleInput) -> str | None:
    ws = inp.workshop or {}
    if (ws.get("craft") or "").strip():
        return ws["craft"]
    crafts = [(p.craft or "").strip() for p in inp.participants]
    if crafts and all(crafts) and all(_same(c, crafts[0]) for c in crafts):
        return crafts[0]
    return None


def _count(inp: TitleInput) -> int:
    return len(inp.participants) or int(inp.participantCount or 0)


def _entity(inp: TitleInput) -> str:
    if inp.entity:
        return inp.entity
    return "Group-Interview" if _count(inp) >= 2 else "Interview"


def _subject_source(inp: TitleInput, *, ascii_only: bool) -> str | None:
    if inp.subject:
        return inp.subject
    n = _count(inp)
    if n >= 2:
        return f"{n} Artisans"
    if n == 1 and inp.participants:
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
def session_title(inp: Any) -> str:
    """``Entity_Date_Place_Craft_Subject``, at most 120 characters, original script allowed.

    ``inp`` is a :class:`TitleInput`, a mapping (see :func:`title_input`) or a record with those
    attributes."""
    inp = _as_input(inp)
    entity = join_field(field_words(_entity(inp), ascii_only=False))
    day = inp.sessionDate.isoformat() if inp.sessionDate else None
    place = field_words(_place_source(inp), ascii_only=False)
    craft = field_words(_craft_source(inp), ascii_only=False)
    subject = field_words(_subject_source(inp, ascii_only=False), ascii_only=False)

    def render(p: list[str], c: list[str], s: list[str]) -> str:
        return _fields(entity, day, join_field(p), join_field(c), join_field(s))

    return _fit(render, place, craft, subject, TITLE_MAX)


# ── dates (IST) ────────────────────────────────────────────────────────────────────────────────
def as_ist(value: Any) -> datetime | date | None:
    """A datetime in IST, a date as it is, or None. Naive datetimes are UTC (the database's)."""
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(IST)
    if isinstance(value, date):
        return value
    instant = _parse_instant(value)
    return instant.astimezone(IST) if instant else _parse_date(value)


def now_ist() -> datetime:
    return datetime.now(IST)


# ── file names ─────────────────────────────────────────────────────────────────────────────────
def _guard(stem: str) -> str:
    return f"{stem}-File" if stem.upper() in RESERVED_STEMS else stem


def _extension(ext: Any) -> str:
    text = re.sub(r"[^A-Za-z0-9.]", "", str(ext or "").strip().lstrip(".")).lower()
    return re.sub(r"\.{2,}", ".", text).strip(".")


def _sequence(value: Any) -> str | None:
    text = str(value or "").strip()
    if not text:
        return None
    if _VERSION.match(text):
        return text
    return join_field(field_words(text, ascii_only=True)) or None


def file_name(
    *,
    entity: Any = None,
    what: Any = None,
    ext: Any = "",
    day: Any = None,
    time: str | bool | None = None,
    place: Any = None,
    craft: Any = None,
    subject: Any = None,
    sequence: Any = None,
    n: int | None = None,
    portal: str | None = None,
    date: Any = None,
    period: Any = None,
) -> str:
    """The NAMING §2.1 grammar, ASCII, at most 150 characters including the extension.

    The Date field is ``period`` (a field such as ``FY2026-27``), else ``day``, else the IST day of
    ``date``. ``time`` is ``HHMM`` as given, or ``True`` for the IST time of ``date``. An empty
    ``ext`` gives a bare stem."""
    moment = None if period else as_ist(date if date is not None else day)
    if period:
        day_f = join_field(field_words(period, ascii_only=True))
    elif moment is not None:
        day_f = (moment.date() if isinstance(moment, datetime) else moment).isoformat()
    else:
        day_f = None
    if isinstance(time, str):
        time_f = time or None
    elif time and not period and isinstance(moment, datetime):
        time_f = moment.strftime("%H%M")
    else:
        time_f = None
    ext_f = _extension(ext)
    place_w = field_words(place, ascii_only=True)
    craft_w = field_words(craft, ascii_only=True)
    subject_w = field_words(subject, ascii_only=True)
    portal_f = re.sub(r"[^A-Za-z0-9-]", "", portal or PORTAL)
    entity_f = join_field(field_words(entity, ascii_only=True))
    what_f = join_field(field_words(what, ascii_only=True))
    seq_f = _sequence(sequence)
    n_f = str(int(n)) if n and int(n) > 1 else None

    def render(p: list[str], c: list[str], s: list[str]) -> str:
        stem = _fields(
            portal_f, entity_f, day_f, time_f, join_field(p), join_field(c), join_field(s), what_f, seq_f, n_f
        )
        return f"{_guard(stem)}.{ext_f}" if ext_f else _guard(stem)

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


def question_sequence(section: Any, question: Any, *, take: int | None = None, part: int | None = None) -> str:
    """``D-Q03``; ``-P2`` for part 2, else ``-T2`` for a solo take at position 2 or more."""
    sec = join_field(field_words(section, ascii_only=True)).upper()
    if not sec or question is None or str(question).strip() == "":
        return ""
    target = TargetRef(sectionCode=sec, displayCode=f"{sec}{int(question)}")
    return recording_sequence([target], part=part, position=take, roster_count=1) or ""


def section_sequence(section: Any) -> str:
    """``D-All``: a recording that covers a whole section."""
    sec = join_field(field_words(section, ascii_only=True)).upper()
    if not sec:
        return ""
    return recording_sequence([TargetRef(sectionCode=sec)]) or ""


def recording_filename(
    session: Any = None,
    ext: str | None = None,
    *,
    captured_at: Any = None,
    captured_at_source: str | None = None,
    targets: Iterable[TargetRef | Mapping[str, Any]] = (),
    what: str = "Recording",
    part: int | None = None,
    position: int | None = None,
    roster_count: int | None = None,
    n: int | None = None,
    time_known: bool = True,
    place: Any = None,
    craft: Any = None,
    subject: Any = None,
    sequence: Any = None,
    portal: str | None = None,
) -> str:
    """§3.13: the recording, transcript or subtitle file name of one take.

    ``session`` is a :class:`TitleInput` or a mapping (the questionnaire shape: place, craft and
    subject come from it, the sequence from ``targets``), or an Entity word with the fields passed
    directly (``recording_filename("Interview", "m4a", captured_at=..., subject=...)``). A time
    whose ``captured_at_source`` is an estimate, or with ``time_known=False``, is left out."""
    estimated = (captured_at_source or "").upper() in ESTIMATED_SOURCES or not time_known
    instant = _parse_instant(captured_at)
    local = instant.astimezone(IST) if instant else None
    day = local.date() if local else _parse_date(captured_at)
    time = local.strftime("%H%M") if local and not estimated else None
    if session is None or isinstance(session, str):
        return file_name(
            entity=session, what=what, ext=ext if ext is not None else "m4a", day=day, time=time,
            place=place, craft=craft, subject=subject, n=n, portal=portal,
            sequence=sequence or recording_sequence(targets, part=part, position=position, roster_count=roster_count),
        )
    inp = _as_input(session)
    return file_name(
        entity=_entity(inp),
        what=what,
        ext=ext if ext is not None else "m4a",
        day=day,
        time=time,
        place=_place_source(inp),
        craft=_craft_source(inp),
        subject=_subject_source(inp, ascii_only=True),
        sequence=sequence or recording_sequence(targets, part=part, position=position, roster_count=roster_count),
        n=n,
        portal=portal,
    )


def session_filename(session: Any, *, what: str, ext: str) -> str:
    """A file about a whole session (the zip of its recordings): no time, no sequence."""
    inp = _as_input(session)
    return file_name(
        entity=_entity(inp), what=what, ext=ext, day=inp.sessionDate, place=_place_source(inp),
        craft=_craft_source(inp), subject=_subject_source(inp, ascii_only=True),
    )


def export_filename(
    entity: Any,
    what: Any,
    ext: str,
    *,
    day: Any = None,
    at: Any = None,
    time: str | None = None,
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
    """A download, export, report or backup, dated the day it is generated (IST) unless ``day`` or
    ``at`` says otherwise. ``with_time`` adds ``HHMM`` for snapshots that can repeat within a day;
    ``dated=False`` is for templates (pro formas), which carry no date at all."""
    if period or not dated:
        when: Any = None
    elif day is not None:
        when = _parse_date(day)
    else:
        when = at if at is not None else now_ist()
    return file_name(
        portal=portal, entity=entity, what=what, ext=ext, date=when, time=time or bool(with_time),
        period=period, place=place, craft=craft, subject=subject, sequence=sequence, n=n,
    )


def _get(obj: Any, key: str) -> Any:
    return obj.get(key) if isinstance(obj, Mapping) else getattr(obj, key, None)


def workshop_filename(
    workshop: Any, what: Any, ext: str, *, at: Any = None, dated: bool = True, n: int | None = None,
    portal: str | None = None,
) -> str:
    """A design workshop's report, cards or list: ``..._Workshop_Date_Place_Craft_Code_What.ext``.

    ``workshop`` is the record or a mapping with ``workshopCode``, ``title``, ``clusterName``,
    ``district``, ``state`` and ``craftName``. The server, the web's offline report and the handset
    name one report identically through this."""
    return export_filename(
        "Workshop", what, ext, at=at, dated=dated, n=n, portal=portal,
        place=_place_text(_get(workshop, "clusterName"), _get(workshop, "district"), _get(workshop, "state")),
        craft=_get(workshop, "craftName"),
        subject=_get(workshop, "workshopCode") or _get(workshop, "title"),
    )


# ── leaves, collisions, Content-Disposition ────────────────────────────────────────────────────
def split_extension(name: str) -> tuple[str, str]:
    """``("x", ".sql.gz")``, ``("x", ".m4a")`` or ``("x", "")``."""
    lower = name.lower()
    for double in _DOUBLE_EXTENSIONS:
        if lower.endswith(double) and len(name) > len(double):
            return name[: -len(double)], name[-len(double) :]
    stem, dot, ext = name.rpartition(".")
    if dot and stem and 0 < len(ext) <= 10 and ext.isascii() and ext.isalnum():
        return stem, f".{ext}"
    return name, ""


def ascii_leaf(filename: Any, *, fallback: str = "upload.bin") -> str:
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
    ext = ext[:16].lower()
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
    (createdAt, id), so a repeated download yields the same names. Matching ignores case, because a
    zip extracted on Windows does."""
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
    ascii_name = ascii_leaf(filename, fallback="download")
    kind = "inline" if inline else "attachment"
    return f"{kind}; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(str(filename or ascii_name), safe='')}"
