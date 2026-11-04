from __future__ import annotations

import os
import re
import unicodedata
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

from app.services import naming

UNSAFE_CHARS = frozenset('<>:"/\\|?*')
UNSAFE_CATEGORIES = frozenset({"Cc", "Cf", "Cs"})
KEEP_FORMAT = frozenset({"‌", "‍"})
RESERVED_NAMES = naming.RESERVED_STEMS
MARK_CATEGORIES = frozenset({"Mn", "Mc", "Me"})

MAX_NAME_CHARS = naming.MAX_FILE_NAME
MAX_NAME_BYTES = 200
MAX_EXTENSION_BYTES = 24

IST = timezone(timedelta(hours=5, minutes=30), name="IST")
DEFAULT_TIMEZONE = "Asia/Kolkata"

_KIND_WORD = {
    "IMAGE": "Photo",
    "VIDEO": "Video",
    "AUDIO": "Recording",
    "PDF": "Document",
    "DOCUMENT": "Document",
    "OTHER": "File",
}

_TAG_WORD = {
    "artisan": "Artisan",
    "product": "Product",
    "tool": "Tool",
    "process": "Process",
    "processstep": "Process",
    "workshop": "Workshop",
    "designworkshop": "Workshop",
}

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
_CAPTION_QUESTION = re.compile(r"^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b", re.IGNORECASE)
_CAPTION_SECTION = re.compile(r"^section\s+audio:\s*([A-Za-z]{1,3})\b", re.IGNORECASE)
_CAPTION_STEP = re.compile(r"^process\s+step\s+(.+)$", re.IGNORECASE)

_FILE_QUESTION = re.compile(r"^([A-Za-z]{1,3})_(\d+)_")
_FILE_SECTION = re.compile(r"^([A-Za-z]{1,3})_SEC_", re.IGNORECASE)
_FILE_INDEX = re.compile(r"_(?:IMG|VID|AUD|DOC|FILE)_(\d+)(?=_|\.)", re.IGNORECASE)
_FILE_STEP = re.compile(r"_STEP_(\d+)", re.IGNORECASE)
_FILE_GRID_LB = re.compile(r"_GRID_LENGTH\s*BREADTH", re.IGNORECASE)
_FILE_GRID_H = re.compile(r"_GRID_HEIGHT", re.IGNORECASE)
_FILE_STAMP = re.compile(r"_(\d{2})(\d{2})(\d{4})(\d{2})(\d{2})(\d{2})?(?=\.[^.]*$|$)")
_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")


@dataclass(frozen=True)
class NameParts:
    entity: str
    date: datetime | None
    time_known: bool
    place: str
    craft: str
    subject: str
    what: str
    sequence: str
    extension: str



def safe_chars(value: str | None) -> str:
    return "".join(
        ch
        for ch in (value or "")
        if ch not in UNSAFE_CHARS
        and (unicodedata.category(ch) not in UNSAFE_CATEGORIES or ch in KEEP_FORMAT)
    )


def clip(value: str, max_chars: int, max_bytes: int) -> str:
    value = value[:max_chars]
    while value and len(value.encode("utf-8")) > max_bytes:
        value = value[:-1]
    return value


def clip_words(value: str, max_chars: int, max_bytes: int) -> str:
    clipped = clip(value, max_chars, max_bytes)
    if clipped == value:
        return value
    head, sep, _ = clipped.rpartition("-")
    return (head if sep and head else clipped).strip("-")


def hyphenate(value: str | None) -> str:
    out: list[str] = []
    for ch in safe_chars(value):
        if ch.isalnum() or unicodedata.category(ch) in MARK_CATEGORIES or ch in KEEP_FORMAT:
            out.append(ch)
        else:
            out.append("-")
    return re.sub(r"-{2,}", "-", "".join(out)).strip("-")


def split_camel(value: str) -> str:
    return _CAMEL_BOUNDARY.sub(" ", value or "")



def _attr(obj: Any, name: str, default: Any = None) -> Any:
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
    if not names:
        return ""
    if len(names) == 1:
        return names[0]
    return f"{len(names)} Artisans"


def interview_record(interview: Any) -> tuple[str, str]:
    names = _interview_artisans(interview)
    if len(names) >= 2:
        return "Group-Interview", artisan_label(names)
    if names:
        return "Interview", names[0]
    title = _text(_attr(interview, "title"))
    return ("Interview", title) if title else ("", "")


def _place_of(obj: Any) -> str:
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
    m = _FILE_STAMP.search(filename)
    if not m:
        return None
    day, month, year, hour, minute = (int(g) for g in m.groups()[:5])
    try:
        return datetime(year, month, day, hour, minute, tzinfo=IST)
    except ValueError:
        return None


def _captured(media: Any) -> tuple[datetime | None, bool]:
    stamped = _parse_stamp(_text(_attr(media, "originalFilename")))
    if stamped is not None:
        return stamped, True
    recorded = _attr(media, "recordedAt")
    if isinstance(recorded, datetime):
        return (recorded if recorded.tzinfo else recorded.replace(tzinfo=UTC)), True
    created = _attr(media, "createdAt")
    if isinstance(created, datetime):
        return created if created.tzinfo else created.replace(tzinfo=UTC), False
    return None, False


def _index(media: Any, position: int | None) -> int:
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



def _entity_for(record_type: str) -> tuple[str, bool]:
    key = record_type.strip().lower()
    if key == "craft":
        return "", True
    return _RECORD_ENTITY.get(key, naming.field(record_type)), False


def _record(media: Any, record_type: str | None, record_name: str | None) -> dict[str, str]:
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
    out["subject"] = split_camel(os.path.splitext(filename)[0])
    return out


def _grid_axis(media: Any) -> str | None:
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



def _extension(media: Any) -> str:
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
    del step_name
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
    name = display_filename(media, **kwargs)
    stem, _ = naming.split_extension(name)
    return stem


def transcript_filename(media: Any, **kwargs: Any) -> str:
    return display_filename(media, what="Transcript", extension=".md", **kwargs)


_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


def _order_key(media: Any) -> tuple[datetime, str]:
    created = _attr(media, "createdAt")
    if not isinstance(created, datetime):
        created = _EPOCH
    elif created.tzinfo is None:
        created = created.replace(tzinfo=UTC)
    return created, _text(_attr(media, "id"))


def folder_order(media: Iterable[Any]) -> list[Any]:
    return sorted(media, key=_order_key)


def numbered(stem: str, extension: str, n: int) -> str:
    return f"{stem}_{n}{extension}" if n > 1 else f"{stem}{extension}"


def _first_free(used: set[str], render: Callable[[int], str]) -> str:
    n = 1
    while True:
        name = render(n)
        key = name.lower()
        if key not in used:
            used.add(key)
            return name
        n += 1


def unique_name(stem: str, extension: str, used: set[str]) -> str:
    return _first_free(used, lambda n: numbered(stem, extension, n))


def unique_display_filename(media: Any, used: set[str], **kwargs: Any) -> str:
    return _first_free(used, lambda n: display_filename(media, suffix=n, **kwargs))


def unique_display_stem(media: Any, used: set[str], *, extension: str, **kwargs: Any) -> str:
    if extension.lower() in (".transcript.md", ".md"):
        return _first_free(
            used,
            lambda n: display_filename(media, suffix=n, what="Transcript", extension=".md", **kwargs),
        )
    return _first_free(
        used, lambda n: display_filename(media, suffix=n, extension=extension, **kwargs)
    )
