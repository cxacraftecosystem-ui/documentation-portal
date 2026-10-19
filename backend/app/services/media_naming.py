"""The download name of a media file, derived at read time from the row it hangs off.

Every file in this repository was uploaded under a name the capture screen minted, and those names
are codes: ``D_SEC_GIRIRAJ_001046_010720261824.wav``, ``TOOL_blocks_IMG_5_23062026104218.jpg``. They
identify the file to the app that wrote them and to nobody else. So the name a file is DOWNLOADED
under is rebuilt from the data, in the one convention both portals use (``services/naming.py``,
docs/conventions/NAMING_2026-10-18.md)::

    Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext

    DesignWorkshop_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03.m4a
    DesignWorkshop_Product_2026-06-22_1043_Indigo-Dabu-Saree_Photo_03.jpg
    DesignWorkshop_Tool_2026-06-19_1017_Tray-Attachment_Grid-Measurement-Height_01.jpg

Nothing is renamed in storage. ``MediaFile.objectKey`` and the S3 object it points at are untouched,
every existing URL keeps working, and ``MediaFile.originalFilename`` stays exactly as uploaded — the
call sites surface it alongside the display name, because a researcher matching an export against
files already on their own machine still needs the name the app wrote. The old capture codes are
still PARSED here, for the section, the question and the capture time they carry.

Two rules govern what may go into a name. It must never state something false: a field the row
cannot prove is left out (a section recording gets ``D-All`` and no question; a time derived from
the upload moment rather than the capture is dropped). And the tail survives: only Subject, Craft
and Place are ever shortened, never the date, the time, what the file is or its sequence.

This module is identical in designer-portal and documentation-portal.
"""

from __future__ import annotations

import os
import re
import unicodedata
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

from app.services import naming

# ---------------------------------------------------------------------------
# Character safety, for FOLDER segments (data_browser._seg delegates here). Folder names inside a
# zip keep their script; file names do not, and are built by ``naming``.
# ---------------------------------------------------------------------------

# The two path separators plus the punctuation Windows reserves.
UNSAFE_CHARS = frozenset('<>:"/\\|?*')
# Cc control characters; Cf invisible format characters (which include the bidi overrides that can
# render a filename back-to-front); Cs lone surrogates, which no encoder will take.
UNSAFE_CATEGORIES = frozenset({"Cc", "Cf", "Cs"})
# ...except the zero-width joiner and non-joiner, which are Cf but LOAD-BEARING in Devanagari and
# other Indic scripts, where they select conjunct and half forms. Dropping them misspells names.
KEEP_FORMAT = frozenset({"‌", "‍"})
# Windows refuses these device names in any case, with or without an extension.
RESERVED_NAMES = naming.RESERVED_STEMS
MARK_CATEGORIES = frozenset({"Mn", "Mc", "Me"})

# A whole download name, in characters (NAMING 2.2) and in bytes (well under the 255-byte limit).
MAX_NAME_CHARS = naming.MAX_FILE_NAME
MAX_NAME_BYTES = 200
# Longer than any real extension, and the point past which a dot was never an extension marker.
MAX_EXTENSION_BYTES = 24

IST = timezone(timedelta(hours=5, minutes=30), name="IST")
DEFAULT_TIMEZONE = "Asia/Kolkata"

# What each kind of file is, as the What field.
_KIND_WORD = {
    "IMAGE": "Photo",
    "VIDEO": "Video",
    "AUDIO": "Recording",
    "PDF": "Document",
    "DOCUMENT": "Document",
    "OTHER": "File",
}

# linkedRecordType tag -> Entity. A questionnaire clip is absent: its entity is the interview.
_TAG_WORD = {
    "artisan": "Artisan",
    "product": "Product",
    "tool": "Tool",
    "process": "Process",
    "processstep": "Process",
    "workshop": "Workshop",
    "designworkshop": "Workshop",
}

# What a caller's record type means as an Entity. "Craft" is no entity: its name is the Craft field.
_RECORD_ENTITY = {
    "artisan": "Artisan",
    "product": "Product",
    "tool": "Tool",
    "process": "Process",
    "workshop": "Workshop",
    "design workshop": "Workshop",
    "stage": "Workshop",
    "interview": "Interview",
    "group-interview": "Group-Interview",
    "group interview": "Group-Interview",
}

# The head token of an uploaded filename -> the entity, for rows whose relation no longer loads.
_FILENAME_HEAD = [
    ("PROCESSSTEP_", "Process"),
    ("QUESTIONNAIRE_", "Interview"),
    ("WORKSHOP_", "Workshop"),
    ("PRODUCT_", "Product"),
    ("ARTISAN_", "Artisan"),
    ("PROCESS_", "Process"),
    ("CRAFT_", ""),
    ("TOOL_", "Tool"),
]

_CAPTION_NAME = re.compile(
    r"^(?:field media|pre-process media|measurement grid image|"
    r"(?:length\s*breadth|lengthbreadth|height)\s+grid\s*\(measurement\))\s+for\s+(.+)$",
    re.IGNORECASE,
)
# "Question audio: K1 What types of waste ..." — section letter then question number.
_CAPTION_QUESTION = re.compile(r"^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b", re.IGNORECASE)
# "Section audio: D RAW MATERIALS, PROCUREMENT ..." — a recording covering a whole section.
_CAPTION_SECTION = re.compile(r"^section\s+audio:\s*([A-Za-z]{1,3})\b", re.IGNORECASE)
_CAPTION_STEP = re.compile(r"^process\s+step\s+(.+)$", re.IGNORECASE)

# The app's own question/section audio names: "K_1_<label>_<duration>_<stamp>.m4a" and
# "D_SEC_<label>_<duration>_<stamp>.wav". Only consulted for rows already known to be questionnaire
# audio, so a "TOOL_..." name can never be read as section T.
#
# A SECTION CODE ALONE IS AMBIGUOUS SINCE 2026-09-13, AND THAT IS ACCEPTED HERE ON PURPOSE: two
# instruments run sections coded A..V, and these patterns are only the FALLBACK for a clip that
# arrived without ``extraMetadata.sectionId``. A caller that needs the section for a clip whose
# interview is known resolves (interview.questionnaireId, code) through
# ``services/questionnaire_consolidation``.
_FILE_QUESTION = re.compile(r"^([A-Za-z]{1,3})_(\d+)_")
_FILE_SECTION = re.compile(r"^([A-Za-z]{1,3})_SEC_", re.IGNORECASE)
# "..._IMG_5_...", "..._VID_19_...". The LAST one wins: a part-numbered step video reads
# "_STEP_12S_PART_1_VID_19_" and 19 is the index of the file, 1 is the index of the part.
_FILE_INDEX = re.compile(r"_(?:IMG|VID|AUD|DOC|FILE)_(\d+)(?=_|\.)", re.IGNORECASE)
_FILE_STEP = re.compile(r"_STEP_(\d+)", re.IGNORECASE)
_FILE_GRID_LB = re.compile(r"_GRID_LENGTH\s*BREADTH", re.IGNORECASE)
_FILE_GRID_H = re.compile(r"_GRID_HEIGHT", re.IGNORECASE)
# The trailing ddMMyyyyHHmm[ss] the app stamped on every capture before 18 Oct 2026.
_FILE_STAMP = re.compile(r"_(\d{2})(\d{2})(\d{4})(\d{2})(\d{2})(\d{2})?(?=\.[^.]*$|$)")
_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")


@dataclass(frozen=True)
class NameParts:
    """The fields of one download name, so a caller can log or test them individually."""

    entity: str
    date: datetime | None
    time_known: bool
    place: str
    craft: str
    subject: str
    what: str
    sequence: str
    extension: str


# ---------------------------------------------------------------------------
# Primitives (shared with data_browser._seg)
# ---------------------------------------------------------------------------


def safe_chars(value: str | None) -> str:
    """Drop only the characters a filesystem or a zip genuinely cannot carry, in any script."""
    return "".join(
        ch
        for ch in (value or "")
        if ch not in UNSAFE_CHARS
        and (unicodedata.category(ch) not in UNSAFE_CATEGORIES or ch in KEEP_FORMAT)
    )


def clip(value: str, max_chars: int, max_bytes: int) -> str:
    """Trim to both limits at once, never mid-character. Stops at the empty string on a budget of
    zero or less instead of spinning on it."""
    value = value[:max_chars]
    while value and len(value.encode("utf-8")) > max_bytes:
        value = value[:-1]
    return value


def clip_words(value: str, max_chars: int, max_bytes: int) -> str:
    """:func:`clip`, but cutting back to a whole word when it has to cut at all."""
    clipped = clip(value, max_chars, max_bytes)
    if clipped == value:
        return value
    head, sep, _ = clipped.rpartition("-")
    return (head if sep and head else clipped).strip("-")


def hyphenate(value: str | None) -> str:
    """Words joined by hyphens, with every script intact (for folder segments)."""
    out: list[str] = []
    for ch in safe_chars(value):
        if ch.isalnum() or unicodedata.category(ch) in MARK_CATEGORIES or ch in KEEP_FORMAT:
            out.append(ch)
        else:
            out.append("-")
    return re.sub(r"-{2,}", "-", "".join(out)).strip("-")


def split_camel(value: str) -> str:
    """``DabuHandBlockPrinting`` -> ``Dabu Hand Block Printing``, for filename-derived names only."""
    return _CAMEL_BOUNDARY.sub(" ", value or "")


# ---------------------------------------------------------------------------
# Reading one media row
# ---------------------------------------------------------------------------


def _attr(obj: Any, name: str, default: Any = None) -> Any:
    """A column off a Prisma model or a plain dict row (``datasets`` hands either)."""
    if isinstance(obj, Mapping):
        return obj.get(name, default)
    return getattr(obj, name, default)


def _text(value: Any) -> str:
    return (value or "").strip() if isinstance(value, str) else ""


def _enum(value: Any) -> str:
    return str(getattr(value, "value", value) or "").upper()


def _rel(obj: Any, name: str) -> Any:
    return _attr(obj, name)


def _interview_artisans(interview: Any) -> list[str]:
    names = []
    for link in _attr(interview, "artisans") or []:
        artisan = _attr(link, "artisan")
        name = _text(_attr(artisan, "nameEnglish")) or _text(_attr(artisan, "name"))
        if name:
            names.append(name)
    return names


def artisan_label(names: list[str]) -> str:
    """The Subject for the artisans an interview covers: the one name, or ``N Artisans``."""
    if not names:
        return ""
    if len(names) == 1:
        return names[0]
    return f"{len(names)} Artisans"


def interview_record(interview: Any) -> tuple[str, str]:
    """(Entity, Subject) for anything hanging off a questionnaire interview.

    ``Interview`` and the artisan's name for one artisan; ``Group-Interview`` and ``N Artisans`` for
    two or more, so a group sitting's clips are named identically in every artisan folder that
    shows them. With no artisan linked, the interview's own title is the Subject.
    """
    names = _interview_artisans(interview)
    if len(names) >= 2:
        return "Group-Interview", artisan_label(names)
    if names:
        return "Interview", names[0]
    title = _text(_attr(interview, "title"))
    return ("Interview", title) if title else ("", "")


def _place_of(obj: Any) -> str:
    """``Locality-District`` from a loaded ``location`` relation; nothing when it is not loaded."""
    loc = _rel(obj, "location")
    if loc is None:
        return ""
    return naming.place_field(
        _text(_attr(loc, "village")),
        _text(_attr(loc, "district")),
        _text(_attr(loc, "state")),
    )


def _craft_of(obj: Any) -> str:
    craft = _rel(obj, "craft")
    return _text(_attr(craft, "name")) if craft is not None else ""


def _parse_stamp(filename: str) -> datetime | None:
    """The capture moment an old capture code carries (ddMMyyyyHHmm[ss], IST), to the minute."""
    m = _FILE_STAMP.search(filename)
    if not m:
        return None
    day, month, year, hour, minute = (int(g) for g in m.groups()[:5])
    try:
        return datetime(year, month, day, hour, minute, tzinfo=IST)
    except ValueError:
        return None


def _captured(media: Any) -> tuple[datetime | None, bool]:
    """(When it was captured, whether the time of day is known).

    The uploaded name's stamp is preferred: it is the moment the phone captured the file.
    ``recordedAt`` is the capture too. ``createdAt`` is only the upload, so it gives the day and
    never a time, because a time read off it would state a false one.
    """
    stamped = _parse_stamp(_text(_attr(media, "originalFilename")))
    if stamped is not None:
        return stamped, True
    recorded = _attr(media, "recordedAt")
    if isinstance(recorded, datetime):
        # The convention's clock is IST whatever zone the phone was set to (recordedTimezone).
        return (recorded if recorded.tzinfo else recorded.replace(tzinfo=UTC)), True
    created = _attr(media, "createdAt")
    if isinstance(created, datetime):
        return created if created.tzinfo else created.replace(tzinfo=UTC), False
    return None, False


def _index(media: Any, position: int | None) -> int:
    """Which photo/clip of its record this is. The app numbers them; otherwise use list order."""
    found = _FILE_INDEX.findall(_text(_attr(media, "originalFilename")))
    if found:
        return int(found[-1])
    return position if position and position > 0 else 1


def _kind_word(media: Any) -> str:
    return _KIND_WORD.get(_enum(_attr(media, "mediaType")), "File")


def _is_questionnaire(media: Any) -> bool:
    tag = _text(_attr(media, "linkedRecordType")).lower()
    return bool(_attr(media, "questionnaireInterviewId")) or tag in (
        "questionnaire",
        "questionnaireinterview",
    )


# ---------------------------------------------------------------------------
# Which record, and what the file is
# ---------------------------------------------------------------------------


def _entity_for(record_type: str) -> tuple[str, bool]:
    """(Entity, whether the record name is really the Craft field)."""
    key = record_type.strip().lower()
    if key == "craft":
        return "", True
    return _RECORD_ENTITY.get(key, naming.field(record_type)), False


def _record(media: Any, record_type: str | None, record_name: str | None) -> dict[str, str]:
    """Entity, Subject, Place and Craft — from the caller, else the relations, else the row's text."""
    out = {"entity": "", "subject": "", "place": _place_of(media), "craft": _craft_of(media)}
    if record_type or record_name:
        entity, is_craft = _entity_for(_text(record_type))
        out["entity"] = entity
        if is_craft:
            out["craft"] = _text(record_name) or out["craft"]
        else:
            out["subject"] = _text(record_name)
        return out

    interview = _rel(media, "questionnaireInterview")
    if interview is not None:
        entity, subject = interview_record(interview)
        if entity:
            out.update(entity=entity, subject=subject)
            out["place"] = out["place"] or _place_of(interview) or _text(_attr(interview, "place"))
            return out

    for attr, word, name_field in (
        ("product", "Product", "productName"),
        ("tool", "Tool", "toolkitName"),
        ("artisan", "Artisan", "name"),
        ("workshop", "Workshop", "title"),
    ):
        rel = _rel(media, attr)
        if rel is not None and _text(_attr(rel, name_field)):
            out.update(entity=word, subject=_text(_attr(rel, name_field)))
            out["place"] = out["place"] or _place_of(rel)
            out["craft"] = out["craft"] or _craft_of(rel)
            return out
    if out["craft"]:
        return out

    tag = _text(_attr(media, "linkedRecordType")).lower()
    word = _TAG_WORD.get(tag, "")

    filename = _text(_attr(media, "originalFilename"))
    for head, head_word in _FILENAME_HEAD:
        if filename.upper().startswith(head):
            label = split_camel(filename[len(head):].split("_")[0])
            if head == "CRAFT_":
                out["craft"] = label
            else:
                out.update(entity=word or head_word, subject=label)
            return out

    caption_name = _CAPTION_NAME.match(_text(_attr(media, "caption")))
    if caption_name:
        out.update(entity=word, subject=caption_name.group(1))
        return out
    if word:
        out["entity"] = word
        return out
    # A loose upload with no link and no relation: the stem it was uploaded as is the only thing
    # left that means anything to a human.
    out["subject"] = split_camel(os.path.splitext(filename)[0])
    return out


def _grid_axis(media: Any) -> str | None:
    """``Grid-Measurement-...`` when this image is a measurement grid, else None. The axis is only
    stated when the row actually says which one it is."""
    filename = _text(_attr(media, "originalFilename"))
    caption = _text(_attr(media, "caption"))
    if _FILE_GRID_LB.search(filename) or re.match(
        r"^length\s*breadth\s+grid", caption, re.IGNORECASE
    ):
        return "Grid-Measurement-Length-Breadth"
    if _FILE_GRID_H.search(filename) or re.match(r"^height\s+grid", caption, re.IGNORECASE):
        return "Grid-Measurement-Height"
    meta = _attr(media, "extraMetadata")
    is_grid = isinstance(meta, dict) and "measurementProcessing" in meta
    if is_grid or re.match(r"^measurement\s+grid", caption, re.IGNORECASE):
        return "Grid-Measurement"
    return None


def _interview_what(media: Any, index: int) -> tuple[str, str]:
    """(What, Sequence) for interview media: ``Recording`` + ``K-Q01`` / ``D-All``, as far as the
    row can prove it."""
    caption = _text(_attr(media, "caption"))
    filename = _text(_attr(media, "originalFilename"))
    kind = _kind_word(media)

    m = _CAPTION_QUESTION.match(caption) or _FILE_QUESTION.match(filename)
    if m:
        return kind, naming.question_sequence(m.group(1), int(m.group(2)))
    m = _CAPTION_SECTION.match(caption) or _FILE_SECTION.match(filename)
    if m:
        return kind, naming.section_sequence(m.group(1))
    if kind == "Recording":
        return kind, ""
    return kind, f"{index:02d}"


def _what(media: Any, index: int, step_number: int | None) -> tuple[str, str]:
    """(What, Sequence): what this file IS, and which of its kind."""
    if _is_questionnaire(media):
        return _interview_what(media, index)
    seq = f"{index:02d}"
    grid = _grid_axis(media)
    if grid:
        return grid, seq

    caption = _text(_attr(media, "caption"))
    filename = _text(_attr(media, "originalFilename"))
    kind = _kind_word(media)

    tag = _text(_attr(media, "linkedRecordType")).lower()
    if tag == "processstep" or step_number is not None or _FILE_STEP.search(filename):
        number = step_number
        if number is None:
            m = _FILE_STEP.search(filename)
            number = int(m.group(1)) if m else None
        return ("Step" if number is None else f"Step-{number}") + f"-{kind}", seq

    if re.match(r"^pre-process\s+media", caption, re.IGNORECASE) or re.search(
        r"_PRE_", filename, re.IGNORECASE
    ):
        return f"Pre-Process-{kind}", seq
    return kind, seq


# ---------------------------------------------------------------------------
# Assembly
# ---------------------------------------------------------------------------


def _extension(media: Any) -> str:
    """The uploaded name's extension, or nothing when the last dot did not mark one."""
    ext = safe_chars(os.path.splitext(_text(_attr(media, "originalFilename")))[1])
    return ext if len(ext.encode("utf-8")) <= MAX_EXTENSION_BYTES and ext.isascii() else ""


def name_parts(
    media: Any,
    *,
    record_type: str | None = None,
    record_name: str | None = None,
    step_number: int | None = None,
    step_name: str | None = None,
    position: int | None = None,
    what: str | None = None,
    extension: str | None = None,
) -> NameParts:
    del step_name  # the step's free-text name is not a field of the convention
    index = _index(media, position)
    rec = _record(media, record_type, record_name)
    kind, sequence = _what(media, index, step_number)
    captured, time_known = _captured(media)
    return NameParts(
        entity=rec["entity"],
        date=captured,
        time_known=time_known,
        place=rec["place"],
        craft=rec["craft"],
        subject=rec["subject"],
        what=what or kind,
        sequence=sequence,
        extension=_extension(media) if extension is None else extension,
    )


def _assemble(parts: NameParts, n: int = 1) -> str:
    return naming.recording_filename(
        parts.entity,
        parts.extension,
        captured_at=parts.date,
        time_known=parts.time_known,
        place=parts.place,
        craft=parts.craft,
        subject=parts.subject,
        sequence=parts.sequence,
        what=parts.what,
        n=n,
    )


# A name minted at capture under the convention (18 Oct 2026 on) already says everything the row
# can; it is kept rather than rebuilt from relations that may not be loaded.
_CONFORMANT = re.compile(r"^(?:DesignWorkshop|FieldRepository)_[A-Za-z0-9._-]+$")


def _conformant_name(
    media: Any, what: str | None, extension: str | None, n: int
) -> str | None:
    original = _text(_attr(media, "originalFilename"))
    if not _CONFORMANT.match(original) or len(original) > naming.MAX_FILE_NAME:
        return None
    stem, ext = naming.split_extension(original)
    if what == "Transcript":
        stem, swapped = re.subn(r"_(?:Recording|Video)(?=_|$)", "_Transcript", stem, count=1)
        if not swapped:
            return None
    elif what:
        return None
    if extension is not None:
        ext = extension
    if n > 1:
        stem = f"{stem}_{n}"
    return naming.ascii_leaf(stem + ext)


def display_filename(
    media: Any,
    *,
    record_type: str | None = None,
    record_name: str | None = None,
    step_number: int | None = None,
    step_name: str | None = None,
    position: int | None = None,
    suffix: int = 1,
    fallback: str | None = None,
    what: str | None = None,
    extension: str | None = None,
) -> str:
    """The NAMING 2.1 download name for one MediaFile row.

    ``record_type``/``record_name`` let a caller that already holds the parent record name it
    directly. ``position`` is the file's 1-based place in the list being rendered, used only when the
    uploaded name carries no index of its own. ``suffix`` is which of an identically-named group this
    is (1 = unnumbered); callers inside a folder go through :func:`unique_display_filename`.
    ``what``/``extension`` name a sibling file (``Transcript`` + ``.md``, a converted ``.mp4``).
    ``fallback`` is unused by the convention (a name always has at least Portal and What) and kept
    for callers.
    """
    del fallback
    kept = _conformant_name(media, what, extension, suffix)
    if kept:
        return kept
    return _assemble(
        name_parts(
            media,
            record_type=record_type,
            record_name=record_name,
            step_number=step_number,
            step_name=step_name,
            position=position,
            what=what,
            extension=extension,
        ),
        suffix,
    )


def display_stem(media: Any, **kwargs: Any) -> str:
    """The display name without its extension."""
    name = display_filename(media, **kwargs)
    stem, _ = naming.split_extension(name)
    return stem


def transcript_filename(media: Any, **kwargs: Any) -> str:
    """``..._Transcript_D-Q03.md``: the clip's own name with What ``Transcript``."""
    return display_filename(media, what="Transcript", extension=".md", **kwargs)


# ---------------------------------------------------------------------------
# Keeping one folder's names apart
#
# Re-recording an answer several times inside one minute is ordinary, so takes collide at minute
# precision. The tie-break is ``_2``, ``_3`` (NAMING 2.1 ``n``), and it must mean the same thing on
# every request: names are decided in createdAt-then-id order, never in arrival order.
# ---------------------------------------------------------------------------

_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


def _order_key(media: Any) -> tuple[datetime, str]:
    created = _attr(media, "createdAt")
    if not isinstance(created, datetime):
        created = _EPOCH
    elif created.tzinfo is None:
        created = created.replace(tzinfo=UTC)
    return created, _text(_attr(media, "id"))


def folder_order(media: Iterable[Any]) -> list[Any]:
    """One folder's files in the order their names are decided: createdAt, then id."""
    return sorted(media, key=_order_key)


def numbered(stem: str, extension: str, n: int) -> str:
    """``stem``, the ``_2`` it needs, then the extension — only from the second file on."""
    return f"{stem}_{n}{extension}" if n > 1 else f"{stem}{extension}"


def _first_free(used: set[str], render: Callable[[int], str]) -> str:
    """The first numbering of a name this folder does not already hold (case-insensitive, because
    extracting a zip on Windows is)."""
    n = 1
    while True:
        name = render(n)
        key = name.lower()
        if key not in used:
            used.add(key)
            return name
        n += 1


def unique_name(stem: str, extension: str, used: set[str]) -> str:
    """A name no other file in THIS folder holds, for a caller that has the name but not the row."""
    return _first_free(used, lambda n: numbered(stem, extension, n))


def unique_display_filename(media: Any, used: set[str], **kwargs: Any) -> str:
    """:func:`display_filename`, numbered against the other files of the same folder."""
    return _first_free(used, lambda n: display_filename(media, suffix=n, **kwargs))


def unique_display_stem(media: Any, used: set[str], *, extension: str, **kwargs: Any) -> str:
    """A sibling of the clip under another extension, numbered against the same folder.

    ``.transcript.md`` (the old spelling) and ``.md`` both mean the clip's transcript, which the
    convention names with What ``Transcript``.
    """
    if extension.lower() in (".transcript.md", ".md"):
        return _first_free(
            used,
            lambda n: display_filename(media, suffix=n, what="Transcript", extension=".md", **kwargs),
        )
    return _first_free(
        used, lambda n: display_filename(media, suffix=n, extension=extension, **kwargs)
    )
