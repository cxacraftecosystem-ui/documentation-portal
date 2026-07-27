"""The display name of a media file, derived at read time from the row it hangs off.

Every file in this repository was uploaded under a name the capture screen minted, and those names
are codes: ``D_SEC_GIRIRAJ_001046_010720261824.wav``, ``TOOL_blocks_IMG_5_23062026104218.jpg``. They
identify the file to the app that wrote them and to nobody else. A researcher who has extracted a
zip onto their laptop, or who is looking at a row in a list, cannot tell from ``D_SEC_GIRIRAJ`` what
record the clip belongs to, what part of the interview it covers, or which of several artisans
called Giriraj spoke — the folder they came from carried all of that, and a downloaded file has no
folder.

So the name is rebuilt from the data:

    {RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}

    Artisan-Giriraj-Prasad-Chhipa-Interview-Section-D-010720261824.wav
    Artisan-Rashpal-Singh-Billoria-Interview-Section-K-Answer-1-010720261728.m4a
    Product-Bagru-Hand-Block-printing-Video-8-22062026104346.mp4
    Tool-tray-attachment-Grid-Measurement-Height-19062026101703.jpg
    Process-preparation-of-the-Bamboo-Step-4-weaving-Video-4-23062026151955.mp4
    Workshop-Shristi-O-Anusandhan-2nd-Toolkit-Workshop-Photo-1-18062026031534.jpg

The stamp is always twelve digits, ddMMyyyyHHmm. Two thirds of the existing uploads were stamped
ddMMyyyyHHmm**ss** by the capture screen and their seconds are cut here, never padded onto the rest,
so one scheme reads across the whole repository instead of two that differ by a suffix nobody can
predict from the outside. Where cutting them lands two files of the SAME FOLDER on one name, the
later ones take a minimal ``-2``, ``-3`` — see :func:`unique_name`.

Nothing is renamed in storage. ``MediaFile.objectKey`` and the S3 object it points at are untouched,
every existing URL keeps working, and ``MediaFile.originalFilename`` stays exactly as uploaded — the
call sites surface it alongside the display name, because a researcher matching an export against
files already on their own machine still needs the name the app wrote.

Two rules govern what may go into a name.

The first is that it must never state something false. Where a piece of the scheme cannot be
recovered for an old row, this module leaves it out rather than guessing: a section recording that
answers no single question gets ``Interview-Section-D`` and no answer number; a 2026-06 measurement
grid, taken before the capture screen recorded which axis it measured, gets ``Grid-Measurement``
without ``Length-Breadth`` or ``Height``. A name that says less is still useful; a name that says
the wrong thing is worse than the code it replaced.

The second is that the tail must survive. Filesystems cap a name at ~255 BYTES, and a Devanagari
character costs three of them, so a long artisan name written in Devanagari can exhaust the budget
on its own. Only the record NAME is truncated — never the descriptor and never the timestamp, since
those are precisely what tells two files from one artisan apart.
"""

from __future__ import annotations

import os
import re
import unicodedata
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

# ---------------------------------------------------------------------------
# Character safety.
#
# These are lifted verbatim from data_browser._seg, which now delegates here so folder segments and
# file names can never disagree about what a filesystem will take. The reasoning is _seg's: the rule
# is a DENY list, not an allow list, because an allow list of ASCII turned every Devanagari artisan
# name into a row of identical underscores and the names are the data in this repository.
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
RESERVED_NAMES = frozenset(
    {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}
)
# Combining marks are not alphanumeric to Python but they are part of the letter they sit on, so a
# word splitter that treats "not alphanumeric" as a separator would shatter every Indic syllable.
MARK_CATEGORIES = frozenset({"Mn", "Mc", "Me"})

# The whole leaf, in characters and in bytes. 200 bytes matches _seg's per-segment budget and leaves
# room under the 255-byte filesystem limit for the "-2" a duplicate name picks up on the way out.
MAX_NAME_CHARS = 150
MAX_NAME_BYTES = 200
# Enough of a record name to identify it; the rest of the budget belongs to the descriptor.
MAX_RECORD_NAME_CHARS = 60
# Longer than any real extension, and the point past which a dot was never an extension marker at
# all. ``splitext`` splits on the LAST dot wherever it is, so a recording saved as
# "Interview 2026.06 गिरीराज प्रसाद छीपा साक्षात्कार" — no extension, one dot in a date — comes back
# claiming a 268-byte one. Left unchecked that swallows the entire byte budget the descriptor, the
# timestamp and the duplicate suffix are promised out of.
MAX_EXTENSION_BYTES = 24
# A step name is free text a researcher typed and runs to a sentence more often than not.
MAX_STEP_NAME_CHARS = 32

IST = timezone(timedelta(hours=5, minutes=30), name="IST")
DEFAULT_TIMEZONE = "Asia/Kolkata"

# What each kind of file is called in a name. Plain words, never the app's IMG/VID/AUD codes.
_KIND_WORD = {
    "IMAGE": "Photo",
    "VIDEO": "Video",
    "AUDIO": "Audio-Note",
    "PDF": "Document",
    "DOCUMENT": "Document",
    "OTHER": "File",
}

# linkedRecordType tag -> the word that opens the name. A questionnaire clip is deliberately absent:
# the record it is really about is the artisan being interviewed, resolved from the interview below.
_TAG_WORD = {
    "artisan": "Artisan",
    "product": "Product",
    "tool": "Tool",
    "process": "Process",
    "processstep": "Process",
    "workshop": "Workshop",
    "craft": "Craft",
}

# The head token of an uploaded filename -> the same word, for rows whose relation no longer loads
# (a deleted parent nulls the FK but leaves the file). Longest first: PROCESSSTEP before PROCESS.
_FILENAME_HEAD = [
    ("PROCESSSTEP_", "Process"),
    ("QUESTIONNAIRE_", "Interview"),
    ("WORKSHOP_", "Workshop"),
    ("PRODUCT_", "Product"),
    ("ARTISAN_", "Artisan"),
    ("PROCESS_", "Process"),
    ("CRAFT_", "Craft"),
    ("TOOL_", "Tool"),
]

# "Field media for X", "Measurement grid image for X", "Height grid (measurement) for X",
# "Pre-process media for X" — the capture screens all end their caption with the record's name.
_CAPTION_NAME = re.compile(
    r"^(?:field media|pre-process media|measurement grid image|"
    r"(?:length\s*breadth|lengthbreadth|height)\s+grid\s*\(measurement\))\s+for\s+(.+)$",
    re.IGNORECASE,
)

# "Question audio: K1 What types of waste ..." — section letter then question number.
_CAPTION_QUESTION = re.compile(r"^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b", re.IGNORECASE)
# "Section audio: D RAW MATERIALS, PROCUREMENT ..." — a recording covering a whole section.
_CAPTION_SECTION = re.compile(r"^section\s+audio:\s*([A-Za-z]{1,3})\b", re.IGNORECASE)
# "Process step weaving"
_CAPTION_STEP = re.compile(r"^process\s+step\s+(.+)$", re.IGNORECASE)

# The app's own question/section audio names: "K_1_<label>_<duration>_<stamp>.m4a" and
# "D_SEC_<label>_<duration>_<stamp>.wav". Only consulted for rows already known to be questionnaire
# audio, so a "TOOL_..." name can never be read as section T.
_FILE_QUESTION = re.compile(r"^([A-Za-z]{1,3})_(\d+)_")
_FILE_SECTION = re.compile(r"^([A-Za-z]{1,3})_SEC_", re.IGNORECASE)

# "..._IMG_5_...", "..._VID_19_...". The LAST one wins: a part-numbered step video reads
# "_STEP_12S_PART_1_VID_19_" and 19 is the index of the file, 1 is the index of the part.
_FILE_INDEX = re.compile(r"_(?:IMG|VID|AUD|DOC|FILE)_(\d+)(?=_|\.)", re.IGNORECASE)
# "..._STEP_4A_..." — the step's number, followed by a per-step letter this scheme has no use for.
_FILE_STEP = re.compile(r"_STEP_(\d+)", re.IGNORECASE)
# Which axis a measurement grid image measures, as the capture screen tags it.
_FILE_GRID_LB = re.compile(r"_GRID_LENGTH\s*BREADTH", re.IGNORECASE)
_FILE_GRID_H = re.compile(r"_GRID_HEIGHT", re.IGNORECASE)
# The trailing ddMMyyyyHHmm the app stamps on every capture. The seconds most screens append are
# captured separately so :func:`_stamp` can drop them; they are never part of the name it returns.
_FILE_STAMP = re.compile(r"_(\d{12})(\d{2})?(?=\.[^.]*$|$)")

# A run-together token out of an uploaded filename ("DabuHandBlockPrinting"): the only place this
# scheme has to invent word boundaries, and only ever as a fallback for a name the database can no
# longer supply.
_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")


@dataclass(frozen=True)
class NameParts:
    """The pieces of one display name, so a caller can log or test them individually."""

    record_type: str
    record_name: str
    descriptor: str
    stamp: str
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
    """Trim to both limits at once, never mid-character.

    A filesystem counts BYTES while a slice counts characters, and one Devanagari character is three
    bytes, so a name that passes the character check can still be rejected on write.

    The loop stops at the empty string rather than at the budget. A caller subtracts the parts it
    has promised to keep from the total before asking for the rest, so a long enough fixed part
    hands this a budget of zero or less — and on a negative budget "shorter than the budget" is
    never true, so a loop that only watched the byte count would spin on the empty string forever,
    inside the single-worker web process, for one row.
    """
    value = value[:max_chars]
    while value and len(value.encode("utf-8")) > max_bytes:
        value = value[:-1]
    return value


def clip_words(value: str, max_chars: int, max_bytes: int) -> str:
    """:func:`clip`, but cutting back to a whole word when it has to cut at all.

    This trims already-hyphenated text, so a blind slice leaves "Cane-Bamboo-an" or
    "mixing-lime-a" — fragments that read as words the record does not contain. Dropping the partial
    word says less and says nothing false. A single very long word has no boundary to retreat to,
    and there the blind cut stands.
    """
    clipped = clip(value, max_chars, max_bytes)
    if clipped == value:
        return value
    head, sep, _ = clipped.rpartition("-")
    return (head if sep and head else clipped).strip("-")


def hyphenate(value: str | None) -> str:
    """Words joined by hyphens, with every script intact.

    Anything that is not a letter, a digit or a combining mark becomes a boundary — so "Cane, Bamboo
    and Block Printing" reads "Cane-Bamboo-and-Block-Printing" and "गिरीराज प्रसाद छीपा" keeps its
    characters and simply gains hyphens. Marks are kept deliberately: they are not alphanumeric to
    Python but they are half of the syllable they sit on, and dropping them misspells the name.
    """
    out: list[str] = []
    for ch in safe_chars(value):
        if ch.isalnum() or unicodedata.category(ch) in MARK_CATEGORIES or ch in KEEP_FORMAT:
            out.append(ch)
        else:
            out.append("-")
    return re.sub(r"-{2,}", "-", "".join(out)).strip("-")


def split_camel(value: str) -> str:
    """``DabuHandBlockPrinting`` -> ``Dabu Hand Block Printing``, for filename-derived names only.

    The one place this scheme has to invent word boundaries, and only ever for a name the database
    can no longer supply — a row whose parent record was deleted, leaving the upload as the only
    surviving description of what the file is of.
    """
    return _CAMEL_BOUNDARY.sub(" ", value or "")


# ---------------------------------------------------------------------------
# Reading one media row
# ---------------------------------------------------------------------------


def _text(value: Any) -> str:
    return (value or "").strip() if isinstance(value, str) else ""


def _enum(value: Any) -> str:
    return str(getattr(value, "value", value) or "").upper()


def _rel(media: Any, name: str) -> Any:
    return getattr(media, name, None)


def _interview_artisans(interview: Any) -> list[str]:
    names = []
    for link in getattr(interview, "artisans", None) or []:
        name = _text(getattr(getattr(link, "artisan", None), "name", None))
        if name:
            names.append(name)
    return names


def artisan_label(names: list[str]) -> str:
    """The artisans an interview covers, as one record name.

    A third of the interviews in this repository are group sittings — up to five artisans around one
    recorder — and naming such a clip after the first of them would be a plain untruth about who is
    speaking. So every name goes in while they fit, and past that the label says how many were left
    out instead of silently dropping them.
    """
    if not names:
        return ""
    if len(names) == 1:
        return names[0]
    joined = " and ".join(names)
    if len(hyphenate(joined)) <= MAX_RECORD_NAME_CHARS:
        return joined
    return f"{names[0]} and {len(names) - 1} Others"


def interview_record(interview: Any) -> tuple[str, str]:
    """(RecordType, RecordName) for anything hanging off a questionnaire interview.

    An interview is filed under the artisan it is with, not under its own title — the reading
    ``record_fields.interview_label`` already takes for the folder these clips sit in. Exported so a
    caller holding the interview can name its clips without the row re-loading the relation, and so
    a group interview's clips are named identically in every artisan folder that shows them.
    """
    names = artisan_label(_interview_artisans(interview))
    if names:
        return "Artisan", names
    title = _text(getattr(interview, "title", None))
    return ("Interview", title) if title else ("", "")


def _stamp(media: Any) -> str:
    """The ddMMyyyyHHmm the app wrote. Twelve digits, always.

    The uploaded name is preferred over ``recordedAt`` wherever it has a stamp: it is the moment the
    phone captured the file, it is what the researcher already saw in the app, and re-deriving it
    shifts ~1% of rows by a minute because the row is written a beat after the recording stops.

    Two thirds of the existing uploads carry seconds as well, and those seconds are cut here. One
    precision for every file is what makes the scheme predictable — a researcher looking at a name
    should not have to know which capture screen wrote it to know how long the number will be — and
    the alternative, padding the other third out to fourteen digits, would state a second the app
    never recorded. Seconds are therefore dropped but never invented: a derived stamp is
    minute-precision too, since ``recordedAt``'s seconds are the upload beat rather than the capture.

    Cutting them does cost something. Re-recording an artisan's answer four times in one minute is
    the norm in this repository, not an edge case, so a few hundred files now share a name with a
    sibling — which is what :func:`unique_name` numbers, per folder, with a ``-2`` a human can read.
    """
    m = _FILE_STAMP.search(_text(getattr(media, "originalFilename", None)))
    if m:
        return m.group(1)

    recorded = getattr(media, "recordedAt", None) or getattr(media, "createdAt", None)
    if not isinstance(recorded, datetime):
        return ""
    tz_name = _text(getattr(media, "recordedTimezone", None)) or DEFAULT_TIMEZONE
    try:
        tz: Any = ZoneInfo(tz_name)
    except Exception:  # noqa: BLE001 - a bad tz string must not cost the file its timestamp
        tz = IST
    if recorded.tzinfo is None:
        recorded = recorded.replace(tzinfo=timezone.utc)
    return recorded.astimezone(tz).strftime("%d%m%Y%H%M")


def _index(media: Any, position: int | None) -> int:
    """Which photo/clip of its record this is. The app numbers them; otherwise use list order."""
    found = _FILE_INDEX.findall(_text(getattr(media, "originalFilename", None)))
    if found:
        return int(found[-1])
    return position if position and position > 0 else 1


def _kind_word(media: Any) -> str:
    return _KIND_WORD.get(_enum(getattr(media, "mediaType", None)), "File")


def _is_questionnaire(media: Any) -> bool:
    tag = _text(getattr(media, "linkedRecordType", None)).lower()
    return bool(getattr(media, "questionnaireInterviewId", None)) or tag in (
        "questionnaire",
        "questionnaireinterview",
    )


# ---------------------------------------------------------------------------
# Which record, and what the file is
# ---------------------------------------------------------------------------


def _record(media: Any, record_type: str | None, record_name: str | None) -> tuple[str, str]:
    """(RecordType, RecordName) — from the caller, else the relations, else the row's own text.

    Callers inside the tree already hold the record whose folder they are filling and pass it in;
    the flat listers (by media type, by uploader) hold nothing and rely on the relations. The
    filename and caption fallbacks below matter for rows whose parent was deleted: the FK is nulled
    on delete, so the relation is gone but the file is still browsable.
    """
    if record_type or record_name:
        return _text(record_type), _text(record_name)

    interview = _rel(media, "questionnaireInterview")
    if interview is not None:
        kind, name = interview_record(interview)
        if kind:
            return kind, name

    for attr, word, field in (
        ("product", "Product", "productName"),
        ("tool", "Tool", "toolkitName"),
        ("artisan", "Artisan", "name"),
        ("workshop", "Workshop", "title"),
        ("craft", "Craft", "name"),
    ):
        rel = _rel(media, attr)
        if rel is not None and _text(getattr(rel, field, None)):
            return word, _text(getattr(rel, field, None))

    tag = _text(getattr(media, "linkedRecordType", None)).lower()
    word = _TAG_WORD.get(tag, "")

    filename = _text(getattr(media, "originalFilename", None))
    for head, head_word in _FILENAME_HEAD:
        if filename.upper().startswith(head):
            label = filename[len(head) :].split("_")[0]
            return word or head_word, split_camel(label)

    caption_name = _CAPTION_NAME.match(_text(getattr(media, "caption", None)))
    if caption_name:
        return word, caption_name.group(1)

    if word:
        return word, ""

    # Nothing at all identifies the parent: a loose upload with no link and no relation. The stem of
    # what it was uploaded as is the only thing left that means anything to a human, and keeping it
    # says strictly more than replacing it with "Document-1" would.
    stem = os.path.splitext(filename)[0]
    return "", split_camel(stem)


def _grid_axis(media: Any) -> str | None:
    """``Grid-Measurement-...`` when this image is a measurement grid, else None.

    The axis is only stated when the row actually says which one it is. Rows captured before the
    grid screen split length/breadth from height carry a caption that names no axis at all, and
    those get the bare ``Grid-Measurement`` rather than a guess that would be wrong half the time.
    """
    filename = _text(getattr(media, "originalFilename", None))
    caption = _text(getattr(media, "caption", None))
    if _FILE_GRID_LB.search(filename) or re.match(r"^length\s*breadth\s+grid", caption, re.I):
        return "Grid-Measurement-Length-Breadth"
    if _FILE_GRID_H.search(filename) or re.match(r"^height\s+grid", caption, re.I):
        return "Grid-Measurement-Height"
    meta = getattr(media, "extraMetadata", None)
    is_grid = isinstance(meta, dict) and "measurementProcessing" in meta
    if is_grid or re.match(r"^measurement\s+grid", caption, re.I):
        return "Grid-Measurement"
    return None


def _interview_descriptor(media: Any, index: int) -> str:
    """``Interview-Section-K-Answer-1``, or as much of it as the row can prove.

    A per-question clip carries its section and question number in the caption the capture screen
    wrote ("Question audio: K1 ..."), and again in the uploaded filename ("K_1_..."). A recording
    that covers a whole section carries only the section ("Section audio: D ...", "D_SEC_..."), and
    it is not an answer to any one question — so it stops at ``Interview-Section-D`` instead of
    claiming an answer number it does not have.
    """
    caption = _text(getattr(media, "caption", None))
    filename = _text(getattr(media, "originalFilename", None))

    m = _CAPTION_QUESTION.match(caption) or _FILE_QUESTION.match(filename)
    if m:
        return f"Interview-Section-{m.group(1).upper()}-Answer-{int(m.group(2))}"

    m = _CAPTION_SECTION.match(caption) or _FILE_SECTION.match(filename)
    if m:
        return f"Interview-Section-{m.group(1).upper()}"

    # Interview audio that names no section — an upload from the generic media screen, which the
    # tree still files under the interview. Say what it is and stop.
    return f"Interview-{_kind_word(media)}-{index}"


def _descriptor(
    media: Any,
    index: int,
    step_number: int | None,
    step_name: str | None,
) -> str:
    """What this file IS, in words: the second half of the name and the part that disambiguates."""
    if _is_questionnaire(media):
        return _interview_descriptor(media, index)

    grid = _grid_axis(media)
    if grid:
        return grid

    caption = _text(getattr(media, "caption", None))
    filename = _text(getattr(media, "originalFilename", None))
    kind = f"{_kind_word(media)}-{index}"

    tag = _text(getattr(media, "linkedRecordType", None)).lower()
    if tag == "processstep" or step_number is not None or _FILE_STEP.search(filename):
        number = step_number
        if number is None:
            m = _FILE_STEP.search(filename)
            number = int(m.group(1)) if m else None
        name = _text(step_name)
        if not name:
            m = _CAPTION_STEP.match(caption)
            name = m.group(1) if m else ""
        head = "Step" if number is None else f"Step-{number}"
        name_part = clip_words(hyphenate(name), MAX_STEP_NAME_CHARS, MAX_STEP_NAME_CHARS * 3)
        return "-".join(p for p in (head, name_part, kind) if p)

    if re.match(r"^pre-process\s+media", caption, re.I) or re.search(r"_PRE_", filename, re.I):
        return f"Pre-Process-{kind}"

    return kind


# ---------------------------------------------------------------------------
# Assembly
# ---------------------------------------------------------------------------


def _extension(media: Any) -> str:
    """The uploaded name's extension, or nothing when the last dot did not mark one.

    ``splitext`` splits on the last dot regardless of what follows it, so a file saved without an
    extension but with a dot anywhere in its name — a date, an initial — hands back the whole
    remainder as the "extension". Keeping that would be false twice over: it is not an extension,
    and it is long enough to consume the budget the timestamp and the duplicate suffix are promised
    out of, which is the one thing :func:`_assemble` may not let happen.
    """
    ext = safe_chars(os.path.splitext(_text(getattr(media, "originalFilename", None)))[1])
    return ext if len(ext.encode("utf-8")) <= MAX_EXTENSION_BYTES else ""


def name_parts(
    media: Any,
    *,
    record_type: str | None = None,
    record_name: str | None = None,
    step_number: int | None = None,
    step_name: str | None = None,
    position: int | None = None,
) -> NameParts:
    index = _index(media, position)
    kind, name = _record(media, record_type, record_name)
    return NameParts(
        record_type=hyphenate(kind),
        record_name=hyphenate(name),
        descriptor=_descriptor(media, index, step_number, step_name),
        stamp=_stamp(media),
        extension=_extension(media),
    )


def _assemble(parts: NameParts, suffix: str = "") -> str:
    """Join the pieces, spending the byte budget on the record name and nothing else.

    The descriptor, the timestamp and the duplicate suffix are what tell one artisan's forty clips
    apart, so none of them is ever trimmed. The record name absorbs the whole shortfall, and if the
    tail alone has eaten the budget the head goes entirely — leaving a name that says less about
    which record this belongs to but still says, exactly, which file it is.

    The suffix is charged to the budget here rather than glued onto the finished name, because a
    name already at the limit would have it clipped straight back off — putting the two files it
    exists to separate back onto the one name.
    """
    tail = "-".join(p for p in (parts.descriptor, parts.stamp) if p)
    ext = parts.extension
    fixed = len(f"-{tail}{suffix}{ext}".encode("utf-8"))

    budget = MAX_NAME_BYTES - fixed
    head = ""
    if budget > 0:
        name = clip_words(parts.record_name, MAX_RECORD_NAME_CHARS, budget)
        # Re-clip the pair: a long type word plus a short name can still overrun.
        head = clip_words(
            "-".join(p for p in (parts.record_type, name) if p), MAX_NAME_CHARS, budget
        )

    stem = "-".join(p for p in (head, tail) if p)
    stem = re.sub(r"-{2,}", "-", stem).strip("- .")
    if not stem:
        return ""
    # CON, PRN, LPT1 ... are refused by Windows with or without an extension. Checked before the
    # suffix so "AUX" and its duplicate are spelled the same way and still sort together.
    if stem.split(".")[0].upper() in RESERVED_NAMES:
        stem = f"{stem}_"
    room = MAX_NAME_BYTES - len(f"{suffix}{ext}".encode("utf-8"))
    return clip(stem, MAX_NAME_CHARS - len(suffix), room).strip("- .") + suffix + ext


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
) -> str:
    """``{RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}`` for one MediaFile row.

    ``record_type``/``record_name`` let a caller that already holds the parent record name it
    directly, which is both cheaper and more accurate than re-reading it off the row's relations;
    every argument is optional and the row alone produces a usable name. ``position`` is the file's
    1-based place in the list being rendered, used only when the uploaded name carries no index of
    its own. ``suffix`` is which of an identically-named group this is, 1 being the first and
    unnumbered — callers inside a folder go through :func:`unique_display_filename` rather than
    setting it. ``fallback`` is what to return when the row yields nothing nameable at all — pass the
    media id, as the tree does, so a file is never listed with a blank name.
    """
    text = _suffix(suffix)
    name = _assemble(
        name_parts(
            media,
            record_type=record_type,
            record_name=record_name,
            step_number=step_number,
            step_name=step_name,
            position=position,
        ),
        text,
    )
    if name:
        return name
    # Clipped like every other path: this one hands back the uploaded name whole, and an upload is
    # free to be longer than a filesystem will accept. The number is charged to the budget for the
    # same reason it is in _assemble — clipped off, it stops separating the two files it is for.
    original = safe_chars(_text(getattr(media, "originalFilename", None))).strip(" .")
    base = clip(
        original or _text(fallback) or "file",
        MAX_NAME_CHARS - len(text),
        MAX_NAME_BYTES - len(text.encode("utf-8")),
    ).strip(" .") or "file"
    stem, dot, ext = base.rpartition(".")
    return numbered(stem, f".{ext}", suffix) if dot and stem else numbered(base, "", suffix)


def display_stem(media: Any, **kwargs: Any) -> str:
    """The display name without its extension, for the ``.transcript.md`` and converted ``.mp4``
    siblings the export writes next to a file."""
    name = display_filename(media, **kwargs)
    stem, _, _ = name.rpartition(".")
    return stem or name


# ---------------------------------------------------------------------------
# Keeping one folder's names apart
#
# Cutting the seconds off puts a few hundred of the existing files onto a name a sibling already
# holds — four takes of the same answer, recorded inside one minute, are one name at minute
# precision. The tie-break is a plain "-2", and everything below exists to make that suffix mean the
# same thing on every request: the same file must come out under the same name each time it is
# listed or zipped, or a researcher who downloads twice has two names for one recording and any note
# they wrote against a filename points at nothing.
# ---------------------------------------------------------------------------

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def _order_key(media: Any) -> tuple[datetime, str]:
    """What decides which file of a colliding group keeps the plain name.

    ``createdAt`` is the order the tree already lists in, and ``id`` breaks the ties it leaves —
    files saved in one batch share a timestamp to the microsecond, and Postgres may hand tied rows
    back in whatever order it finds them. Both columns are written once and never updated, so the
    file that took the unnumbered name today still takes it next year. Anything derived from the
    request instead (the order rows came back in, a set's iteration order, a dict's) would renumber
    the group between two downloads of the same folder, which is precisely the bug.
    """
    created = getattr(media, "createdAt", None)
    if not isinstance(created, datetime):
        created = _EPOCH
    elif created.tzinfo is None:
        # Comparing an aware datetime against a naive one raises; the column is UTC either way.
        created = created.replace(tzinfo=timezone.utc)
    return created, _text(getattr(media, "id", None))


def folder_order(media: Iterable[Any]) -> list[Any]:
    """One folder's files in the order their names are decided. See :func:`_order_key`."""
    return sorted(media, key=_order_key)


def _suffix(n: int) -> str:
    return f"-{n}" if n > 1 else ""


def numbered(stem: str, extension: str, n: int) -> str:
    """``stem``, the ``-2`` it needs, then the extension — in that order, always.

    Before the extension, because ``recording.m4a-2`` is no longer an m4a to the operating system
    that has to open it; and only from the second file on, so the common case — one file, one name —
    never pays for the rare one.
    """
    return f"{stem}{_suffix(n)}{extension}"


def _first_free(used: set[str], render: Callable[[int], str]) -> str:
    """The first numbering of a name that this folder does not already hold.

    Matching is case-insensitive because extracting a zip on Windows is: two names that differ only
    in case are one file there, and the second would silently overwrite the first.
    """
    n = 1
    while True:
        name = render(n)
        key = name.lower()
        if key not in used:
            used.add(key)
            return name
        n += 1


def unique_name(stem: str, extension: str, used: set[str]) -> str:
    """A name no other file in THIS folder holds, for a caller that has the name but not the row.

    ``used`` is one folder's worth of names and deliberately nothing wider. Two files with the same
    name in different folders are not a collision — nothing ever writes them side by side — and
    numbering globally would put a suffix on hundreds of files to fix a problem none of them have.
    """
    return _first_free(used, lambda n: numbered(stem, extension, n))


def unique_display_filename(media: Any, used: set[str], **kwargs: Any) -> str:
    """:func:`display_filename`, numbered against the other files of the same folder.

    The number is fed back through the assembly rather than appended to the finished name, so the
    byte budget is spent knowing the suffix is coming; see :func:`_assemble`.
    """
    return _first_free(used, lambda n: display_filename(media, suffix=n, **kwargs))


def unique_display_stem(media: Any, used: set[str], *, extension: str, **kwargs: Any) -> str:
    """:func:`display_stem` under a different extension, numbered against the same folder.

    For the ``.transcript.md`` a clip's text is written to: it is a file of its own in the folder it
    lands in, so it needs its own numbering rather than the audio's.
    """
    return _first_free(used, lambda n: display_stem(media, suffix=n, **kwargs) + extension)
