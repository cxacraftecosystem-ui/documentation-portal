"""Virtual data browser: a lazily-explorable file-system view over the repository.

Four endpoints, all gated by the dataset-download permission AND by row visibility (see
:class:`Scope`): the permission decides whether an account may download data at all, the scope
decides whose data that is:

- ``GET /data/tree?path=...``      one level of the virtual tree (folders + files), plus
                                   server-resolved breadcrumbs and, on record folders, an
                                   ``info`` panel of human-labelled fields
- ``GET /data/manifest?path=...``  the flattened subtree below a path, same shape as
                                   ``/export/dataset`` (clients zip client-side), filterable by
                                   an ``include`` CSV of text,images,videos,audios,transcripts
- ``GET /data/report?path=...``    relational report of the subtree (Workshops/Crafts/Artisans/
                                   Products/Processes/Tools/Questionnaires/Transcripts/Media)
                                   as ``format=json`` sheets or a styled ``format=xlsx`` workbook
- ``GET /data/media/{id}/download``  one media file; audio is converted to .mp4 (AAC) on the fly

Tree layout — folder *paths* use record ids so navigation is unambiguous; folder *names* are
always the clean human name (workshop title, artisan name, product name, ...), never an id.
Duplicate display names within one level get a numeric suffix: "Name (2)".

The root is not a folder listing but a TAXONOMY chooser (see ``TAXONOMIES``): the same repository
browsed three ways. ``by-workshop`` is the default one the client opens on.

    ''                     -> by-workshop | by-uploader | by-type

``by-workshop`` — the hierarchy: workshop, craft, artisan, then that artisan's work.

    by-workshop                                 -> one folder per workshop (name = title)
    by-workshop/<wid>                           -> one folder per craft (the workshop's linked
                                                   crafts plus its artisans' crafts; artisans with
                                                   no craft go under 'No craft'), '_misc' (shown
                                                   "Miscellaneous") and details.txt
    by-workshop/<wid>/crafts/<cid>              -> the workshop's artisans having that craft
    .../crafts/<cid>/artisans/<aid>             -> products | tools | questionnaire | misc
    .../products/<pid>                          -> details.txt + media + 'processes' (when any)
    .../products/<pid>/processes/<procid>       -> details.txt + process media + per-step folders
    .../products/<pid>/processes/<procid>/<sid> -> notes.txt + step media
    .../tools/<tid>                             -> details.txt + media
    .../questionnaire/<iid>                     -> answers.txt + per-question audio clips

``by-uploader`` — who recorded what, always scoped to one workshop so "their media" means the
media they put into THAT workshop.

    by-uploader                                 -> one folder per workshop (name = title)
    by-uploader/<wid>                           -> one folder per researcher who uploaded media to,
                                                   or authored a record in, that workshop
    by-uploader/<wid>/<uid>                     -> artisans | products | tools | questionnaire
                                                   | media
    by-uploader/<wid>/<uid>/<branch>            -> one '<record name>.txt' per record of that type
                                                   they created in that workshop
    by-uploader/<wid>/<uid>/media               -> images | videos | audios | transcripts
                                                   | documents | other
    by-uploader/<wid>/<uid>/media/<slug>        -> their files of that kind in that workshop

``by-type`` — every file in the repository grouped purely by what it is.

    by-type                -> images | videos | audios | transcripts | documents | other
    by-type/<slug>         -> the files themselves. 'transcripts' is not a MediaType: it is every
                              media row carrying transcript text, rendered as .transcript.md files
                              so the folder holds transcripts rather than the audio they came from

Pre-taxonomy paths still resolve so links saved before the switcher existed keep working:
``workshops/...`` is the same lister as ``by-workshop/...``, ``media-types/...`` the same as
``by-type/...``, and ``users`` keeps its own older shape (one folder per uploader, then
artisans | products | tools | workshops | questionnaire | misc, repository-wide rather than
per workshop). ``_LEGACY_TAXONOMY`` maps each old root onto the taxonomy that replaced it so the
switcher still highlights the right tab. Inside ``by-workshop`` the pre-craft-level
``<wid>/artisans/<aid>`` path also still resolves.

The query/mapping style mirrors export.py's dataset manifest, but every level is lazy: a /tree
call only runs the queries that level needs (each bounded by ``TAKE``).
"""

import asyncio
import io
import re
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import JSONResponse, RedirectResponse, Response, StreamingResponse

from app.core.db import db
from app.core.deps import require_dataset_downloader
from app.services.records import visibility_where
from app.services.record_fields import (
    MEDIA_COLOR,
    MEDIA_COLUMNS,
    OVERVIEW_COLOR,
    PROVENANCE_COLUMNS,
    SPECS,
    TRANSCRIPT_COLOR,
    artisan_names,
    cell as _cell,
    date_str as _date,
    enum_label as _enum_label,
    ev as _ev,
    human_size as _human_size,
    info_panel,
    info_text as _info_text,
    interview_label as _interview_label,
    media_row,
    provenance_row,
    sheet_columns,
    sheet_row,
)
from app.services.media_naming import (
    RESERVED_NAMES as _RESERVED_NAMES,
    clip as _clip,
    display_filename,
    display_stem,
    folder_order,
    interview_record,
    safe_chars as _safe_chars,
    unique_display_filename,
    unique_display_stem,
    unique_name,
)
from app.services.s3 import get_object_bytes
from app.services.transcript_format import transcript_cell
from app.services.xlsx_report import XLSX_MIME, build_report_workbook

router = APIRouter(
    prefix="/data",
    tags=["data-browser"],
    dependencies=[Depends(require_dataset_downloader)],
)

# Upper bound for any single level's listing query, keeping every /tree call cheap.
TAKE = 500
# Safety valve for /manifest walks so a pathological subtree cannot run away.
MAX_MANIFEST_FILES = 20000
MAX_WALK_DEPTH = 16
# Manifest walks visit sibling subtrees concurrently; this caps in-flight DB queries safely
# below the Prisma connection limit (10 per worker).
_WALK_SEM = asyncio.Semaphore(5)
# Refuse in-process audio conversion beyond this size — decoding a very large WAV would exhaust
# the t3.micro's RAM; the client falls back to the original object URL.
MAX_CONVERT_BYTES = 200 * 1024 * 1024
# Per-sheet row cap for /data/report (a truncation note row is appended when hit).
REPORT_TAKE = 5000
# Pseudo craft-folder id gathering a workshop's artisans that have no craft assigned.
NO_CRAFT = "_none"

# The ASCII reduction of a name, used ONLY for the fallback parameter of a Content-Disposition
# header (see _content_disposition). Folder and file names themselves are NOT reduced to ASCII —
# see _seg.
_ASCII_ONLY = re.compile(r"[^A-Za-z0-9 _.\-]+")

# Name caps. File systems limit a name to ~255 BYTES, not characters, and a Devanagari character
# costs three of them, so both limits are applied. The character rules themselves now live in
# services/media_naming.py, which needs the identical answer for the file names it builds.
_MAX_SEG_CHARS = 80
_MAX_SEG_BYTES = 200

# MediaType -> the `include` CSV token that selects it in /data/manifest.
_TYPE_TOKEN = {
    "IMAGE": "images",
    "VIDEO": "videos",
    "AUDIO": "audios",
    "PDF": "documents",
    "DOCUMENT": "documents",
    "OTHER": "other",
}

# linkedRecordType tags considered "attached to a typed record" (everything else is misc).
_TYPED_TAGS = [
    "artisan",
    "product",
    "process",
    "processstep",
    "tool",
    "workshop",
    "questionnaire",
    "questionnaireinterview",
]

_USER_TYPE_WHERE: dict[str, dict[str, Any]] = {
    "artisans": {"linkedRecordType": "artisan"},
    "products": {"linkedRecordType": {"in": ["product", "process", "processstep"]}},
    "tools": {"linkedRecordType": "tool"},
    "workshops": {"linkedRecordType": "workshop"},
    "questionnaire": {"linkedRecordType": {"in": ["questionnaire", "questionnaireinterview"]}},
    "misc": {
        "OR": [
            {"linkedRecordType": None},
            {"linkedRecordType": {"not_in": _TYPED_TAGS}},
        ]
    },
}

_MEDIA_TYPE_WHERE: dict[str, dict[str, Any]] = {
    "images": {"mediaType": "IMAGE"},
    "videos": {"mediaType": "VIDEO"},
    "audios": {"mediaType": "AUDIO"},
    "documents": {"mediaType": {"in": ["PDF", "DOCUMENT"]}},
    "other": {"mediaType": "OTHER"},
}

# The three ways the same repository can be browsed. The client renders these as a
# switcher at the root; `default` decides which one it opens on.
TAXONOMIES: list[dict[str, Any]] = [
    {
        "id": "by-workshop",
        "name": "By workshop",
        "path": "by-workshop",
        "description": (
            "Workshop, then craft, then artisan, then that artisan's products, tools and "
            "questionnaires. Products open into their processes. A workshop's loose media "
            "sits in Miscellaneous, one level under the workshop."
        ),
        "default": True,
    },
    {
        "id": "by-uploader",
        "name": "By uploader",
        "path": "by-uploader",
        "description": (
            "Workshop, then the researcher who uploaded, then everything they recorded — "
            "their entries with the fields they filled in, and their media by type."
        ),
        "default": False,
    },
    {
        "id": "by-type",
        "name": "By media type",
        "path": "by-type",
        "description": (
            "Every file grouped purely by what it is: audios, videos, images, transcripts, "
            "documents."
        ),
        "default": False,
    },
]

# Display labels for the static (non-record) folder slugs used across the tree.
_CATEGORY_LABEL: dict[str, str] = {
    "by-workshop": "By workshop",
    "by-uploader": "By uploader",
    "by-type": "By media type",
    "transcripts": "Transcripts",
    "media": "Media",
    "workshops": "Workshops",
    "users": "Users",
    "media-types": "Media types",
    "crafts": "Crafts",
    "artisans": "Artisans",
    "products": "Products",
    "tools": "Tools",
    "processes": "Processes",
    "questionnaire": "Questionnaire",
    "misc": "Miscellaneous",
    "_misc": "Miscellaneous",
    "images": "Images",
    "videos": "Videos",
    "audios": "Audios",
    "documents": "Documents",
    "other": "Other",
}


# ---------------------------------------------------------------------------
# Small helpers (same style as export.py; re-implemented locally on purpose —
# export.py is owned elsewhere and must not be touched).
# ---------------------------------------------------------------------------


def _seg(value: str | None, fallback: str) -> str:
    """One path segment of a folder or file name, safe for a filesystem and for a zip.

    This used to be ``_SAFE.sub("_", …)`` against ``[^A-Za-z0-9 _.-]``, which replaced every
    character outside ASCII. For a repository whose subject IS Indian craft that was the wrong
    failure: an artisan named in Devanagari became ``_ _``, and several such artisans collapsed onto
    the same segment, so the tree and the exported zip showed a row of identical ``_`` folders and a
    researcher could not tell whose was whose. Names are the data here, not decoration.

    So the rule is inverted. Instead of allowing a list of characters, it removes the ones that are
    genuinely unusable — the two path separators and the punctuation Windows reserves, plus control
    and format characters by Unicode category — and keeps everything else, in any script. The zero
    width joiner and non joiner are deliberately exempted from the format-character sweep: they are
    invisible, but in Devanagari and other Indic scripts they select conjunct and half forms, so
    dropping them misspells the very names this change exists to preserve.

    That character rule and the two-limit trim now live in services/media_naming.py, which applies
    the identical reasoning to the file names it derives; this stays the folder-segment entry point.
    """
    cleaned = _safe_chars((value or "").strip()).strip(" .")
    cleaned = _clip(cleaned, _MAX_SEG_CHARS, _MAX_SEG_BYTES).strip(" .")

    if not cleaned:
        return fallback
    # CON, PRN, LPT1 … are refused by Windows with or without an extension, so a craft or an artisan
    # legitimately called "Aux" would produce a folder that cannot be written on extraction.
    if cleaned.split(".")[0].upper() in _RESERVED_NAMES:
        return f"{cleaned}_"
    return cleaned


def _content_disposition(name: str) -> str:
    """An attachment header that survives a non-ASCII filename.

    Necessary because :func:`_seg` now keeps Devanagari (and every other script). An HTTP header
    field is latin-1 by definition, so interpolating those bytes straight into `filename="…"`
    either raises on encode or ships mojibake — a download that used to work would start failing
    for exactly the names the Unicode fix set out to preserve, which would be a worse bug than the
    one it replaced.

    RFC 6266 is built for this: `filename=` carries an ASCII reduction for old clients, and
    `filename*=UTF-8''…` carries the real name percent-encoded. Every current browser prefers the
    starred form, so the researcher gets the artisan's actual name and nothing breaks in between.
    """
    ascii_name = _ASCII_ONLY.sub("_", name).strip(" ._") or "download"
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(name, safe='')}"


def _join(parent: str, name: str) -> str:
    return f"{parent}/{name}" if parent else name


def _norm(path: str) -> str:
    return "/".join(s for s in (path or "").split("/") if s)


# Pre-taxonomy roots map onto the taxonomy that replaced them, so an old saved link
# still reports the right active tab in the switcher.
_LEGACY_TAXONOMY = {"workshops": "by-workshop", "users": "by-uploader", "media-types": "by-type"}


def _taxonomy_of(norm: str) -> str | None:
    """Which taxonomy a path sits in, or None at the root (where none is chosen yet)."""
    head = norm.split("/", 1)[0] if norm else ""
    if not head:
        return None
    if head in _LEGACY_TAXONOMY:
        return _LEGACY_TAXONOMY[head]
    return head if any(t["id"] == head for t in TAXONOMIES) else None


# ---------------------------------------------------------------------------
# Row visibility.
#
# The router-level dependency answers "may this account download data at all". It does NOT answer
# "WHOSE data" — and ``canDownloadDataset`` is a GRANTABLE boolean, so a researcher can hold it
# without ranking Professor+. Until this scope existed every /data endpoint handed such a
# researcher the whole repository, while /export/dataset — the sibling endpoint doing the same job
# — filtered by ``visibility_where``. The permission means "download the data you can SEE", so the
# same filter now rides every query behind /tree, /manifest, /report and /media/{id}/download.
#
# Both filters are EMPTY for Professor and above (``visibility_where`` returns ``{}`` for them) and
# every helper below short-circuits on an empty filter back to the exact call it made before, so
# for professors, admins and the master admin this is a no-op down to the query shape.
#
# Crafts are deliberately NOT filtered: they are shared vocabulary (GET /crafts lists them to every
# authenticated user) and ``Craft.createdById`` is nullable, so filtering them would hide the
# taxonomy the tree is built from rather than protect anybody's data.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Scope:
    """The row-visibility filters for one /data request.

    ``records`` filters record tables on their ``createdById`` owner column; ``media`` filters
    MediaFile on ``uploadedById`` — the same split ``/dashboard/stats`` and ``GET /media`` use.
    """

    records: dict[str, Any]
    media: dict[str, Any]

    @property
    def restricted(self) -> bool:
        """False for Professor+/admins, whose filters are empty (they see everything)."""
        return bool(self.records or self.media)


async def _scope_for(user: Any) -> Scope:
    return Scope(
        records=await visibility_where(user),
        media=await visibility_where(user, owner_field="uploadedById"),
    )


def _and(where: dict[str, Any], extra: dict[str, Any]) -> dict[str, Any]:
    """``where`` AND ``extra`` — returning ``where`` untouched when there is nothing to add, which
    is what keeps every query identical for an unrestricted caller."""
    if not extra:
        return where
    if not where:
        return dict(extra)
    return {"AND": [where, extra]}


async def _visible_only(delegate: Any, records: list[Any], scope_where: dict[str, Any]) -> list[Any]:
    """Filter an already-loaded (relation-include-derived) list down to the rows the caller may see.

    Runs NO query at all when the scope is unrestricted; otherwise one id-set query decides the
    whole list and the ORIGINAL objects are returned, so the relations loaded with them survive.
    """
    if not scope_where or not records:
        return records
    rows = await delegate.find_many(
        where={"AND": [{"id": {"in": [r.id for r in records]}}, scope_where]}
    )
    allowed = {r.id for r in rows}
    return [r for r in records if r.id in allowed]


def _uniq(name: str, used: set[str]) -> str:
    """Keep FOLDER names unique within one level: "Name", "Name (2)", "Name (3)", ...

    Dedupes case-insensitively (zip extraction on Windows is case-insensitive) and keeps a trailing
    extension at the end, for the craft or artisan whose name happens to contain a dot. Files no
    longer come through here: they are numbered by ``media_naming.unique_name``, in the same "-2"
    the capture screens would have written, which reads as part of the name rather than as an
    apology for one.
    """
    key = name.lower()
    if key not in used:
        used.add(key)
        return name
    head, slash, leaf = name.rpartition("/")
    stem, dot, ext = leaf.rpartition(".")
    n = 2
    while True:
        if dot:
            candidate = f"{head}{slash}{stem} ({n}).{ext}"
        else:
            candidate = f"{name} ({n})"
        if candidate.lower() not in used:
            used.add(candidate.lower())
            return candidate
        n += 1


# Extensions of more than one part, which a split on the last dot would cut in half.
_COMPOUND_EXTENSIONS = (".transcript.md",)


def _split_leaf(name: str) -> tuple[str, str]:
    """A file name as (stem, extension), so a duplicate can be numbered between the two."""
    for compound in _COMPOUND_EXTENSIONS:
        if name.lower().endswith(compound):
            return name[: -len(compound)], name[-len(compound) :]
    stem, dot, ext = name.rpartition(".")
    return (stem, f".{ext}") if dot and stem else (name, "")


def _folder(name: str, path: str, record_type: str = "category") -> dict[str, Any]:
    return {"name": name, "path": path, "kind": "folder", "recordType": record_type}


def _text(parent: str, name: str, content: str | None) -> dict[str, Any] | None:
    if not (content or "").strip():
        return None
    return {"name": name, "path": _join(parent, name), "kind": "file", "content": content}


def _media_entries(
    parent: str,
    media: list[Any],
    *,
    record_type: str | None = None,
    record_name: str | None = None,
    step_number: int | None = None,
    step_name: str | None = None,
) -> list[dict[str, Any]]:
    """The files in one folder, each shown under a name derived from the record it belongs to.

    The uploaded name is a code the capture screen minted — ``D_SEC_GIRIRAJ_001046_010720261824.wav``
    — and it survives a download into a folder that explains nothing. ``display_filename`` rebuilds
    it as ``Artisan-Giriraj-Prasad-Chhipa-Interview-Section-D-010720261824.wav`` from the row and its
    relations; nothing is renamed in storage, so every URL and every objectKey is untouched. The
    uploaded name rides along in ``originalFilename`` because a researcher reconciling an export
    against files already on their laptop still has to match the two up.

    A caller that already holds the parent record names it here, which is both cheaper than loading
    the relations back off the row and more precise — the step number and step name in particular
    exist nowhere on the media row.

    The names are decided in ``folder_order`` — createdAt, then id — and not in whatever order the
    rows arrived in. That fixes both halves of the answer for good: which of four takes recorded in
    one minute keeps the unnumbered name, and which photo of a batch is "Photo-1" when the uploaded
    name carries no index of its own.
    """
    used: set[str] = set()
    entries: list[dict[str, Any]] = []
    for position, m in enumerate(folder_order(media), start=1):
        name = unique_display_filename(
            m,
            used,
            record_type=record_type,
            record_name=record_name,
            step_number=step_number,
            step_name=step_name,
            position=position,
            fallback=m.id,
        )
        entries.append(
            {
                "name": name,
                "path": _join(parent, name),
                "kind": "file",
                "originalFilename": m.originalFilename,
                "mediaType": str(_ev(m.mediaType)),
                "mediaId": m.id,
                "url": m.url,
                "sizeBytes": int(m.sizeBytes) if m.sizeBytes is not None else None,
                "transcriptAvailable": bool((m.transcriptText or "").strip()),
                # Internal (stripped from /tree responses): lets /manifest emit transcript files
                # without a second query.
                "_transcriptText": m.transcriptText,
            }
        )
    return entries


# The relations a display name is read from when the caller has no parent record to hand: the flat
# listers (by media type, by uploader, a user's media) show files from all over the repository at
# once. Nested for interviews because a questionnaire clip is named after the ARTISAN it is with,
# and that is two hops from the media row.
_NAMING_INCLUDE: dict[str, Any] = {
    "artisan": True,
    "craft": True,
    "workshop": True,
    "product": True,
    "tool": True,
    "questionnaireInterview": {"include": {"artisans": {"include": {"artisan": True}}}},
}


async def _media(where: dict[str, Any], scope: Scope, *, named: bool = False) -> list[Any]:
    """Media rows for one folder. ``named`` loads the relations a display name needs.

    Off by default, and deliberately: the record-level folders pass the record they already hold
    straight to :func:`_media_entries`, so making every level pay for six relation loads — on a
    query the manifest walk repeats for every folder in the subtree — would buy nothing.
    """
    kwargs: dict[str, Any] = {}
    if named:
        kwargs["include"] = _NAMING_INCLUDE
    return await db.mediafile.find_many(
        where=_and(where, scope.media), take=TAKE, order={"createdAt": "asc"}, **kwargs
    )


async def _workshop_misc_media(wid: str, scope: Scope, *, named: bool = False) -> list[Any]:
    """Media that belongs to the WORKSHOP itself: nothing finer-grained claims it."""
    return await _media(
        {
            "AND": [
                {"artisanId": None},
                {"productId": None},
                {"toolId": None},
                {"questionnaireInterviewId": None},
                _record_media_where("workshopId", wid, ["workshop"]),
            ]
        },
        scope,
        named=named,
    )


async def _artisan_own_media(aid: str, scope: Scope) -> list[Any]:
    """Media that belongs to the ARTISAN itself, not to a product, tool or interview of theirs."""
    return await _media(
        {
            "OR": [
                {"AND": [{"linkedRecordType": "artisan"}, {"linkedRecordId": aid}]},
                {"AND": [{"artisanId": aid}, {"linkedRecordType": None}]},
            ]
        },
        scope,
    )


async def _artisan_display_name(aid: str) -> str | None:
    """The artisan's name for the file names in a legacy 'misc' listing, which loads no record.

    Half of an artisan's own media carries the string tag rather than the FK, so the ``artisan``
    relation is not there to read the name off; one lookup keeps those files named after the person
    instead of falling back to whatever the capture screen encoded in the upload.
    """
    artisan = await db.artisan.find_unique(where={"id": aid})
    return (getattr(artisan, "name", None) or "").strip() or None


def _record_media_where(fk_field: str, rec_id: str, tags: list[str]) -> dict[str, Any]:
    """Media attached to one record — via its typed FK column OR the string tag pair."""
    return {
        "OR": [
            {fk_field: rec_id},
            {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": rec_id}]},
        ]
    }


# ---------------------------------------------------------------------------
# Info panels per record type.
#
# Field lists, labels, colours and value coercion all live in
# app/services/record_fields.py so the browser's info card, the browser's in-folder
# table and the .xlsx report sheets can never drift apart. The thin wrappers below
# keep the call sites in this module readable.
# ---------------------------------------------------------------------------


def _workshop_info(ws: Any) -> dict[str, Any]:
    return info_panel("workshop", ws)


def _craft_info(c: Any) -> dict[str, Any]:
    return info_panel("craft", c)


def _artisan_info(a: Any) -> dict[str, Any]:
    return info_panel("artisan", a)


def _product_info(p: Any) -> dict[str, Any]:
    return info_panel("product", p)


def _tool_info(t: Any) -> dict[str, Any]:
    return info_panel("tool", t)


def _process_info(pr: Any) -> dict[str, Any]:
    return info_panel("process", pr)


def _artisan_names(interview: Any) -> list[str]:
    return artisan_names(interview)


def _interview_info(interview: Any) -> dict[str, Any]:
    return info_panel("interview", interview)


def _interview_answers(interview: Any, info: dict[str, Any]) -> str:
    header = _info_text(info)
    responses = sorted(
        interview.responses or [],
        key=lambda r: getattr(getattr(r, "question", None), "sortOrder", 0) or 0,
    )
    answers = []
    for r in responses:
        q = getattr(r, "question", None)
        prompt = getattr(q, "prompt", r.questionId) if q else r.questionId
        code = getattr(q, "sectionCode", "") if q else ""
        answers.append(f"[{code}] {prompt}\n  -> {r.answerText or ''}\n")
    return header + "\n\n" + "".join(answers)


async def _require(
    delegate: Any,
    rec_id: str,
    what: str,
    include: dict[str, Any] | None = None,
    scope_where: dict[str, Any] | None = None,
) -> Any:
    """Load one record by id or 404.

    With ``scope_where`` the row must ALSO satisfy the visibility filter, and one it does not is
    reported as "not found" rather than 403 — a browser path the caller may not open must not
    confirm that the record exists. Unrestricted callers keep the plain ``find_unique``.
    """
    kwargs: dict[str, Any] = {"where": {"id": rec_id}}
    if include:
        kwargs["include"] = include
    if scope_where:
        kwargs["where"] = {"AND": [{"id": rec_id}, scope_where]}
        record = await delegate.find_first(**kwargs)
    else:
        record = await delegate.find_unique(**kwargs)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"{what} not found")
    return record


# ---------------------------------------------------------------------------
# Server-resolved breadcrumbs: clean display names for every ancestor of a path
# (including the path itself). At most one find_unique per id segment.
#
# These lookups are deliberately unscoped: every endpoint resolves crumbs only AFTER the level (or
# report) it was asked for was produced, and that step already 404s on a path the caller may not
# open — so a name resolved here always belongs to a path they were allowed to reach.
# ---------------------------------------------------------------------------


async def _artisan_crumb_names(tail: list[str]) -> list[str]:
    """Crumb names for the segments from an artisan id downwards (products/tools/... subtree).

    ``tail`` starts right AFTER an 'artisans' keyword segment: [<aid>, <sub>, <rid>, ...].
    Shared by the legacy workshops/<wid>/artisans/... shape and the craft-ful
    workshops/<wid>/crafts/<cid>/artisans/... shape.
    """
    names: list[str] = []
    if not tail:
        return names
    artisan = await db.artisan.find_unique(where={"id": tail[0]})
    names.append(_seg(getattr(artisan, "name", None), tail[0]))
    if len(tail) >= 2 and tail[1] in ("products", "tools", "questionnaire", "misc"):
        sub = tail[1]
        names.append(_CATEGORY_LABEL[sub])
        if len(tail) >= 3:
            rid = tail[2]
            if sub == "products":
                p = await db.productdocumentation.find_unique(where={"id": rid})
                names.append(_seg(getattr(p, "productName", None), rid))
                if len(tail) >= 4 and tail[3] == "processes":
                    names.append("Processes")
                    if len(tail) >= 5:
                        pr = await db.process.find_unique(where={"id": tail[4]})
                        names.append(_seg(getattr(pr, "name", None), tail[4]))
                    if len(tail) >= 6:
                        st = await db.processstep.find_unique(where={"id": tail[5]})
                        names.append(_seg(getattr(st, "name", None), tail[5]))
            elif sub == "tools":
                t = await db.tooldocumentation.find_unique(where={"id": rid})
                names.append(_seg(getattr(t, "toolkitName", None), rid))
            elif sub == "questionnaire":
                i = await db.questionnaireinterview.find_unique(
                    where={"id": rid},
                    include={"artisans": {"include": {"artisan": True}}},
                )
                names.append(_seg(_interview_label(i), rid) if i else rid)
    return names


async def _crumb_names(segs: list[str]) -> list[str]:
    """One display name per path segment, resolved from the DB where the segment is an id."""
    names: list[str] = []
    if not segs:
        return names
    head = segs[0]

    if head == "by-uploader":
        names.append(_CATEGORY_LABEL["by-uploader"])
        if len(segs) >= 2:
            ws = await db.workshop.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(ws, "title", None), segs[1]))
        if len(segs) >= 3:
            u = await db.user.find_unique(where={"id": segs[2]})
            names.append(_seg(getattr(u, "name", None), segs[2]))
        if len(segs) >= 4:
            names.append(_UPLOADER_BRANCHES.get(segs[3], _CATEGORY_LABEL.get(segs[3], segs[3])))
        if len(segs) >= 5:
            names.append(_CATEGORY_LABEL.get(segs[4], segs[4]))
    elif head == "by-type":
        names.append(_CATEGORY_LABEL["by-type"])
        if len(segs) >= 2:
            names.append(_CATEGORY_LABEL.get(segs[1], segs[1]))
    elif head in ("workshops", "by-workshop"):
        names.append(_CATEGORY_LABEL["by-workshop"] if head == "by-workshop" else "Workshops")
        if len(segs) >= 2:
            ws = await db.workshop.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(ws, "title", None), segs[1]))
        if len(segs) >= 3:
            if segs[2] == "_misc":
                names.append(_CATEGORY_LABEL["_misc"])
            elif segs[2] == "crafts":
                names.append(_CATEGORY_LABEL["crafts"])
                if len(segs) >= 4:
                    if segs[3] == NO_CRAFT:
                        names.append("No craft")
                    else:
                        craft = await db.craft.find_unique(where={"id": segs[3]})
                        names.append(_seg(getattr(craft, "name", None), segs[3]))
                if len(segs) >= 5 and segs[4] == "artisans":
                    names.append(_CATEGORY_LABEL["artisans"])
                    names.extend(await _artisan_crumb_names(segs[5:]))
            elif segs[2] == "artisans":
                names.append(_CATEGORY_LABEL["artisans"])
                names.extend(await _artisan_crumb_names(segs[3:]))
    elif head == "users":
        names.append("Users")
        if len(segs) >= 2:
            u = await db.user.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(u, "name", None), segs[1]))
        if len(segs) >= 3:
            names.append(_CATEGORY_LABEL.get(segs[2], segs[2]))
    elif head == "media-types":
        names.append("Media types")
        if len(segs) >= 2:
            names.append(_CATEGORY_LABEL.get(segs[1], segs[1]))

    # Any unresolved tail segments (unknown shapes) fall back to the raw segment.
    while len(names) < len(segs):
        names.append(segs[len(names)])
    return names


async def _resolve_crumbs(norm: str) -> list[dict[str, str]]:
    segs = [s for s in norm.split("/") if s]
    crumbs = [{"name": "Repository", "path": ""}]
    names = await _crumb_names(segs)
    path = ""
    for seg, name in zip(segs, names):
        path = _join(path, seg)
        crumbs.append({"name": name, "path": path})
    return crumbs


# ---------------------------------------------------------------------------
# One level of the virtual tree. Each lister returns (entries, info): entries may
# carry _-prefixed internal fields consumed by the manifest walk and stripped from
# /tree; info is the record panel for record-folder levels (None elsewhere).
# ---------------------------------------------------------------------------

Level = tuple[list[dict[str, Any]], dict[str, Any] | None]


async def _list_level(path: str, scope: Scope) -> Level:
    segs = [s for s in path.split("/") if s]
    parent = "/".join(segs)

    if not segs:
        # The root is the taxonomy chooser. The hierarchy taxonomy is listed first and is
        # what the client opens by default.
        return [
            _folder(t["name"], t["path"], "taxonomy") for t in TAXONOMIES
        ], None

    head = segs[0]

    # by-workshop / by-uploader / by-type are the current taxonomy roots; workshops /
    # users / media-types are the pre-taxonomy paths, still resolved so links saved
    # before the switcher existed keep working.
    if head in ("by-workshop", "workshops"):
        return await _list_workshops_level(segs, parent, scope)
    if head == "by-uploader":
        return await _list_uploader_level(segs, parent, scope)
    if head in ("by-type", "media-types"):
        return await _list_media_types_level(segs, parent, scope)
    if head == "users":
        return await _list_users_level(segs, parent, scope)

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _linked_artisans(ws: Any, scope: Scope) -> list[Any]:
    """Every artisan this workshop reaches, by any of the three routes the data actually uses.

    A workshop is joined to its artisans three different ways, because three different features
    wrote the link at three different times:

    1. ``WorkshopArtisan`` — the explicit "linked artisans" multi-select on the workshop form;
    2. ``Artisan.workshopId`` — the workshop dropdown that later went on every record form;
    3. ``WorkshopCraft`` -> ``Artisan.craftId`` — the workshop declares the crafts it covers, and an
       artisan practising one of those crafts was documented at it.

    Only route 1 used to count here, and on the live repository route 1 is EMPTY: the workshop has
    nine linked crafts and sixteen artisans hanging off those crafts, and not one ``WorkshopArtisan``
    row. That is why every craft folder in the browser opened onto nothing. Reaching an artisan
    through the craft is not a fallback — it is the relationship the data was entered with.

    Visibility is applied in the query, so an artisan the caller may not open never appears and
    never conjures a craft folder.
    """
    return await db.artisan.find_many(
        where=_and({"OR": workshop_artisan_reach(ws)}, scope.records),
        include={"craft": True},
        take=TAKE,
        order={"name": "asc"},
    )


def workshop_artisan_reach(ws: Any) -> list[dict[str, Any]]:
    """The three routes of :func:`_linked_artisans`, as Prisma ``OR`` terms on Artisan.

    ``ws`` must carry its ``artisans`` and ``crafts`` link rows. Exported because /export/dataset
    files the same artisans into the same folders and must not answer this question differently:
    the export used route 1 alone, so on the live repository — where route 1 is empty — every
    artisan's details.txt and own media were left out of the ZIP that the browser showed.
    """
    ors: list[dict[str, Any]] = [{"workshopId": ws.id}]
    linked_ids = [link.artisanId for link in ws.artisans or [] if getattr(link, "artisanId", None)]
    if linked_ids:
        ors.append({"id": {"in": linked_ids}})
    craft_ids = [link.craftId for link in ws.crafts or [] if getattr(link, "craftId", None)]
    if craft_ids:
        ors.append({"craftId": {"in": craft_ids}})
    return ors


def workshop_reaches_artisan(ws: Any, artisan: Any) -> bool:
    """Does this workshop reach this artisan? The in-memory twin of :func:`workshop_artisan_reach`,
    for the export, which already holds every artisan row and must not re-query per workshop.

    It EVALUATES the same OR terms rather than restating the three routes, so a fourth route added
    to the query is honoured here too instead of quietly splitting the two answers apart.
    """
    for term in workshop_artisan_reach(ws):
        for field, condition in term.items():
            value = getattr(artisan, field, None)
            if value is None:
                break
            if isinstance(condition, dict):
                if value not in condition["in"]:
                    break
            elif value != condition:
                break
        else:
            return True
    return False


def _craft_folder_entries(ws: Any, base: str, artisans: list[Any]) -> list[dict[str, Any]]:
    """One folder per craft reachable in this workshop: its directly linked crafts unioned with
    its artisans' crafts, plus a 'No craft' folder when any linked artisan has no craft.

    ``ws`` must be loaded with ``crafts->craft`` included; ``artisans`` are the workshop's linked
    artisans the caller may see (already visibility-filtered, each loaded with its ``craft``), so a
    craft folder is never conjured out of an artisan this caller cannot open.
    """
    crafts: dict[str, Any] = {}
    for link in ws.crafts or []:
        craft = getattr(link, "craft", None)
        if craft is not None:
            crafts.setdefault(craft.id, craft)
    no_craft = False
    for artisan in artisans:
        craft = getattr(artisan, "craft", None)
        if craft is not None:
            crafts.setdefault(craft.id, craft)
        elif not artisan.craftId:
            no_craft = True
    used: set[str] = set()
    entries = [
        _folder(_uniq(_seg(c.name, "Craft"), used), f"{base}/{c.id}", "craft")
        for c in crafts.values()
    ]
    if no_craft:
        entries.append(_folder(_uniq("No craft", used), f"{base}/{NO_CRAFT}"))
    return entries


async def _workshop_craft_artisans(wid: str, cid: str, scope: Scope) -> list[Any]:
    """The workshop's visible artisans practising ``cid`` (NO_CRAFT = the ones with no craft).

    Loads with ``_WS_CRAFTS_INCLUDE`` so :func:`_linked_artisans` can see the workshop's crafts and
    therefore reach artisans through them; loading only the ``artisans`` relation (as this used to)
    silently removed route 3 and returned an empty folder.
    """
    ws = await _require(
        db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
    )
    reachable = await _linked_artisans(ws, scope)
    if cid == NO_CRAFT:
        return [a for a in reachable if not a.craftId]
    return [a for a in reachable if a.craftId == cid]


_WS_CRAFTS_INCLUDE = {
    "artisans": {"include": {"artisan": {"include": {"craft": True}}}},
    "crafts": {"include": {"craft": True}},
}


async def _list_workshops_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        workshops = await db.workshop.find_many(
            where=scope.records, take=TAKE, order={"title": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(ws.title, "Workshop"), used), _join(parent, ws.id), "workshop")
            for ws in workshops
        ], None

    wid = segs[1]

    if len(segs) == 2:
        ws = await _require(
            db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
        )
        info = _workshop_info(ws)
        entries = _craft_folder_entries(ws, f"{parent}/crafts", await _linked_artisans(ws, scope))
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        # The workshop OWN media, in the workshop folder - not behind a "Miscellaneous" door.
        # Every level of this tree shows the same three things together: the folders below it, its
        # own fields as a table, and the files that belong to it. A folder whose files are one more
        # click away reads as a folder with no files. (`_misc` still resolves, for saved links.)
        entries.extend(
            _media_entries(
                parent,
                await _workshop_misc_media(wid, scope),
                record_type="Workshop",
                record_name=ws.title,
            )
        )
        return entries, info

    if segs[2] == "crafts":
        if len(segs) == 3:
            # The intermediate 'crafts' path — reachable from breadcrumbs — lists the same craft
            # folders as the workshop level, so every displayed crumb resolves.
            ws = await _require(
                db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
            )
            return _craft_folder_entries(ws, parent, await _linked_artisans(ws, scope)), None

        cid = segs[3]

        if len(segs) == 4:
            info = None
            if cid != NO_CRAFT:
                craft = await _require(db.craft, cid, "Craft")
                info = _craft_info(craft)
            artisans = await _workshop_craft_artisans(wid, cid, scope)
            used = set()
            entries = [
                _folder(_uniq(_seg(a.name, "Artisan"), used), f"{parent}/artisans/{a.id}", "artisan")
                for a in artisans
            ]
            details = _text(parent, "details.txt", _info_text(info))
            if details:
                entries.append(details)
            # Media captured against the craft itself (the craft form has its own capture field).
            if cid != NO_CRAFT:
                entries.extend(
                    _media_entries(
                        parent,
                        await _media(_record_media_where("craftId", cid, ["craft"]), scope),
                        record_type="Craft",
                        record_name=getattr(craft, "name", None),
                    )
                )
            return entries, info

        if len(segs) == 5 and segs[4] == "artisans":
            # Intermediate 'artisans' crumb path under a craft folder.
            artisans = await _workshop_craft_artisans(wid, cid, scope)
            used = set()
            return [
                _folder(_uniq(_seg(a.name, "Artisan"), used), f"{parent}/{a.id}", "artisan")
                for a in artisans
            ], None

        if len(segs) >= 6 and segs[4] == "artisans":
            # Below the artisan everything is craft-agnostic: reuse the artisan lister with the
            # segments remapped to the legacy shape (child paths still keep the craft-ful parent).
            remapped = [segs[0], wid, "artisans", *segs[5:]]
            return await _list_artisan_level(remapped, parent, scope)

        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if len(segs) == 3 and segs[2] == "_misc":
        # No longer listed as a folder (the same files now render in the workshop folder itself),
        # but still resolvable so a link saved or bookmarked before that change does not 404.
        return _media_entries(parent, await _workshop_misc_media(wid, scope, named=True)), None

    if len(segs) == 3 and segs[2] == "artisans":
        # Legacy intermediate 'artisans' path (pre-craft-level tree): lists every linked artisan
        # so links saved before the craft level existed keep resolving.
        ws = await _require(
            db.workshop,
            wid,
            "Workshop",
            include={"artisans": {"include": {"artisan": True}}},
            scope_where=scope.records,
        )
        entries = []
        used = set()
        for artisan in await _linked_artisans(ws, scope):
            name = _uniq(_seg(artisan.name, "Artisan"), used)
            entries.append(_folder(name, f"{parent}/{artisan.id}", "artisan"))
        return entries, None

    if len(segs) >= 4 and segs[2] == "artisans":
        return await _list_artisan_level(segs, parent, scope)

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_artisan_level(segs: list[str], parent: str, scope: Scope) -> Level:
    wid, aid = segs[1], segs[3]

    if len(segs) == 4:
        # `location` is loaded because the info panel below prints the artisan's State and Pincode
        # from it; `_artisan_info` renders the full spec, not just the name shown in the tree.
        artisan = await _require(
            db.artisan,
            aid,
            "Artisan",
            include={"craft": True, "location": True},
            scope_where=scope.records,
        )
        info = _artisan_info(artisan)
        entries = [
            _folder("Products", f"{parent}/products"),
            _folder("Tools", f"{parent}/tools"),
            _folder("Questionnaire", f"{parent}/questionnaire"),
        ]
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        # The artisan own photographs and clips sit here with the sub-folders and the table, the
        # same shape as every other level. ("misc" still resolves for links saved before this.)
        entries.extend(
            _media_entries(
                parent,
                await _artisan_own_media(aid, scope),
                record_type="Artisan",
                record_name=artisan.name,
            )
        )
        return entries, info

    sub = segs[4]

    if sub == "products":
        return await _list_products_level(segs, parent, wid, aid, scope)

    if sub == "tools":
        if len(segs) == 5:
            tools = await db.tooldocumentation.find_many(
                where=_and(
                    {
                        "AND": [
                            {
                                "OR": [
                                    {"artisanId": aid},
                                    {"artisanLinks": {"some": {"artisanId": aid}}},
                                ]
                            },
                            {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                        ]
                    },
                    scope.records,
                ),
                take=TAKE,
                order={"createdAt": "asc"},
            )
            used: set[str] = set()
            return [
                _folder(_uniq(_seg(t.toolkitName, "Tool"), used), f"{parent}/{t.id}", "tool")
                for t in tools
            ], None
        if len(segs) == 6:
            tool = await _require(
                db.tooldocumentation, segs[5], "Tool", scope_where=scope.records
            )
            info = _tool_info(tool)
            entries = []
            details = _text(parent, "details.txt", _info_text(info))
            if details:
                entries.append(details)
            media = await _media(_record_media_where("toolId", tool.id, ["tool"]), scope)
            entries.extend(
                _media_entries(
                    parent, media, record_type="Tool", record_name=tool.toolkitName
                )
            )
            return entries, info

    if sub == "questionnaire":
        if len(segs) == 5:
            interviews = await db.questionnaireinterview.find_many(
                where=_and({"artisans": {"some": {"artisanId": aid}}}, scope.records),
                take=TAKE,
                order={"createdAt": "asc"},
                include={"artisans": {"include": {"artisan": True}}},
            )
            used = set()
            return [
                _folder(
                    _uniq(_seg(_interview_label(i), "Interview"), used),
                    f"{parent}/{i.id}",
                    "interview",
                )
                for i in interviews
            ], None
        if len(segs) == 6:
            interview = await _require(
                db.questionnaireinterview,
                segs[5],
                "Interview",
                include={
                    "responses": {"include": {"question": True}},
                    "artisans": {"include": {"artisan": True}},
                },
                scope_where=scope.records,
            )
            info = _interview_info(interview)
            entries = []
            answers = _text(parent, "answers.txt", _interview_answers(interview, info))
            if answers:
                entries.append(answers)
            # Per-question audio clips (and any other media) recorded for this interview.
            media = await _media(
                _record_media_where(
                    "questionnaireInterviewId",
                    interview.id,
                    ["questionnaire", "questionnaireinterview"],
                ),
                scope,
            )
            # Named after the artisan(s) the interview is WITH, not after the folder it is being
            # listed in: a group sitting shows up under each of its artisans, and a file that
            # changed its name depending on which door you came through would be unmatchable.
            interview_type, interview_name = interview_record(interview)
            entries.extend(
                _media_entries(
                    parent, media, record_type=interview_type, record_name=interview_name
                )
            )
            return entries, info

    if sub == "misc" and len(segs) == 5:
        # Same as the workshop "_misc": kept resolvable for older links, no longer a folder.
        return _media_entries(
            parent,
            await _artisan_own_media(aid, scope),
            record_type="Artisan",
            record_name=await _artisan_display_name(aid),
        ), None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_products_level(
    segs: list[str], parent: str, wid: str, aid: str, scope: Scope
) -> Level:
    if len(segs) == 5:
        products = await db.productdocumentation.find_many(
            where=_and(
                {
                    "AND": [
                        {"artisanId": aid},
                        {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                    ]
                },
                scope.records,
            ),
            take=TAKE,
            order={"createdAt": "asc"},
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(p.productName, "Product"), used), f"{parent}/{p.id}", "product")
            for p in products
        ], None

    pid = segs[5]

    if len(segs) == 6:
        product = await _require(
            db.productdocumentation, pid, "Product", scope_where=scope.records
        )
        info = _product_info(product)
        entries = []
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        media = await _media(_record_media_where("productId", pid, ["product"]), scope)
        entries.extend(
            _media_entries(
                parent, media, record_type="Product", record_name=product.productName
            )
        )
        if await db.process.count(where=_and({"productId": pid}, scope.records)) > 0:
            entries.append(_folder("Processes", f"{parent}/processes"))
        return entries, info

    if len(segs) == 7 and segs[6] == "processes":
        processes = await db.process.find_many(
            where=_and({"productId": pid}, scope.records), take=TAKE, order={"createdAt": "asc"}
        )
        used = set()
        return [
            _folder(_uniq(_seg(pr.name, "Process"), used), f"{parent}/{pr.id}", "process")
            for pr in processes
        ], None

    if len(segs) == 8 and segs[6] == "processes":
        process = await _require(
            db.process, segs[7], "Process", include={"steps": True}, scope_where=scope.records
        )
        info = _process_info(process)
        entries = []
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        media = await _media(
            {"AND": [{"linkedRecordType": "process"}, {"linkedRecordId": process.id}]}, scope
        )
        entries.extend(
            _media_entries(parent, media, record_type="Process", record_name=process.name)
        )
        used = set()
        for step in sorted(process.steps or [], key=lambda s: s.sortOrder):
            entries.append(
                _folder(_uniq(_seg(step.name, "Step"), used), f"{parent}/{step.id}", "process")
            )
        return entries, info

    if len(segs) == 9 and segs[6] == "processes":
        # ProcessStep carries no owner column of its own: its parent process decides who may see
        # it. For a scoped caller that means proving the process is visible AND that this step
        # really hangs off it; an unrestricted caller keeps the original single lookup.
        step_scope: dict[str, Any] | None = None
        if scope.records:
            await _require(db.process, segs[7], "Process", scope_where=scope.records)
            step_scope = {"processId": segs[7]}
        # The parent process rides along because a step's files are named "Process-<process>-Step-N
        # -<step>-..."; the step row carries the number and the name but not the process it is in.
        step = await _require(
            db.processstep,
            segs[8],
            "Process step",
            include={"process": True},
            scope_where=step_scope,
        )
        entries = []
        notes = _text(parent, "notes.txt", step.notes)
        if notes:
            entries.append(notes)
        media = await _media(
            {"AND": [{"linkedRecordType": "processstep"}, {"linkedRecordId": step.id}]}, scope
        )
        entries.extend(
            _media_entries(
                parent,
                media,
                record_type="Process",
                record_name=getattr(getattr(step, "process", None), "name", None),
                step_number=step.sortOrder,
                step_name=step.name,
            )
        )
        return entries, None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_users_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        # Only uploaders who have media THIS caller can see are worth a folder.
        uploaders = await db.user.find_many(
            where={"media": {"some": _and({}, scope.media)}}, take=TAKE, order={"name": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(u.name, "User"), used), _join(parent, u.id), "user")
            for u in uploaders
        ], None

    uid = segs[1]

    if len(segs) == 2:
        # Unscoped on purpose: this only proves the account exists — the same directory-level fact
        # /users/directory serves to every authenticated user — and each folder under it lists
        # nothing but visibility-filtered media.
        await _require(db.user, uid, "User")
        return [
            _folder(_CATEGORY_LABEL[slug], f"{parent}/{slug}")
            for slug in ("artisans", "products", "tools", "workshops", "questionnaire", "misc")
        ], None

    if len(segs) == 3 and segs[2] in _USER_TYPE_WHERE:
        media = await _media(
            {"AND": [{"uploadedById": uid}, _USER_TYPE_WHERE[segs[2]]]}, scope, named=True
        )
        return _media_entries(parent, media), None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


def _transcript_entries(parent: str, media: list[Any]) -> list[dict[str, Any]]:
    """Transcripts rendered as their own text files, so the Transcripts folder actually
    contains transcripts rather than the audio they came from.

    Named off the same stem as the clip, so a transcript and the recording it came from still sort
    next to each other once both have been extracted from a zip."""
    used: set[str] = set()
    entries: list[dict[str, Any]] = []
    for position, m in enumerate(folder_order(media), start=1):
        text = (m.transcriptText or "").strip()
        if not text:
            continue
        name = unique_display_stem(
            m, used, extension=".transcript.md", position=position, fallback=m.id
        )
        entries.append(
            {
                "name": name,
                "path": _join(parent, name),
                "kind": "file",
                "originalFilename": m.originalFilename,
                "content": m.transcriptText,
                "mediaId": m.id,
            }
        )
    return entries


async def _list_media_types_level(segs: list[str], parent: str, scope: Scope) -> Level:
    """The by-type taxonomy: every file grouped purely by what kind of file it is.

    'transcripts' is not a MediaType — it is every media row that has transcript text,
    surfaced as .transcript.md documents in a folder of their own.
    """
    if len(segs) == 1:
        return [
            _folder(_CATEGORY_LABEL[slug], _join(parent, slug))
            for slug in ("images", "videos", "audios", "transcripts", "documents", "other")
        ], None
    if len(segs) == 2 and segs[1] == "transcripts":
        media = await _media({"NOT": {"transcriptText": None}}, scope, named=True)
        return _transcript_entries(parent, media), None
    if len(segs) == 2 and segs[1] in _MEDIA_TYPE_WHERE:
        media = await _media(_MEDIA_TYPE_WHERE[segs[1]], scope, named=True)
        return _media_entries(parent, media), None
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


# ---------------------------------------------------------------------------
# The by-uploader taxonomy: workshop -> the researcher who uploaded -> what they
# recorded there. Each record folder carries the fields they filled in (as a table)
# and their media split by type.
# ---------------------------------------------------------------------------

# Record types a user's contribution is broken down into, with the delegate and the
# workshop-scoping predicate for each.
_UPLOADER_BRANCHES: dict[str, str] = {
    "artisans": "Artisans",
    "products": "Products",
    "tools": "Tools",
    "questionnaire": "Questionnaire",
    "media": "Media",
}


async def _uploader_records(
    branch: str, wid: str, uid: str, scope: Scope
) -> tuple[str, list[Any]]:
    """(record kind, records) this user created of one type within one workshop."""
    if branch == "artisans":
        return "artisan", await db.artisan.find_many(
            where=_and(
                {"AND": [{"createdById": uid}, {"workshops": {"some": {"workshopId": wid}}}]},
                scope.records,
            ),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_ARTISAN_INCLUDE,
        )
    if branch == "products":
        return "product", await db.productdocumentation.find_many(
            where=_and({"AND": [{"createdById": uid}, {"workshopId": wid}]}, scope.records),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_PRODUCT_INCLUDE,
        )
    if branch == "tools":
        return "tool", await db.tooldocumentation.find_many(
            where=_and({"AND": [{"createdById": uid}, {"workshopId": wid}]}, scope.records),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_TOOL_INCLUDE,
        )
    if branch == "questionnaire":
        return "interview", await db.questionnaireinterview.find_many(
            where=_and(
                {
                    "AND": [
                        {"createdById": uid},
                        {
                            "artisans": {
                                "some": {"artisan": {"workshops": {"some": {"workshopId": wid}}}}
                            }
                        },
                    ]
                },
                scope.records,
            ),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_INTERVIEW_INCLUDE,
        )
    return "", []


async def _list_uploader_level(segs: list[str], parent: str, scope: Scope) -> Level:
    # by-uploader -> one folder per workshop
    if len(segs) == 1:
        workshops = await db.workshop.find_many(
            where=scope.records, take=TAKE, order={"title": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(ws.title, "Workshop"), used), _join(parent, ws.id), "workshop")
            for ws in workshops
        ], None

    wid = segs[1]

    # by-uploader/<wid> -> the people who put data into this workshop
    if len(segs) == 2:
        ws = await _require(
            db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
        )
        uploaders = await db.user.find_many(
            where={"media": {"some": _and({"workshopId": wid}, scope.media)}},
            take=TAKE,
            order={"name": "asc"},
        )
        # Someone can author records without ever uploading a file, so union the two sets.
        authors = await db.user.find_many(
            where={
                "OR": [
                    {"products": {"some": _and({"workshopId": wid}, scope.records)}},
                    {"tools": {"some": _and({"workshopId": wid}, scope.records)}},
                ]
            },
            take=TAKE,
            order={"name": "asc"},
        )
        seen: dict[str, Any] = {}
        for u in [*uploaders, *authors]:
            seen.setdefault(u.id, u)
        used: set[str] = set()
        entries = [
            _folder(_uniq(_seg(u.name, "User"), used), _join(parent, u.id), "user")
            for u in sorted(seen.values(), key=lambda u: (u.name or "").lower())
        ]
        return entries, _workshop_info(ws)

    uid = segs[2]

    # by-uploader/<wid>/<uid> -> what this person recorded here. Unscoped like the legacy users
    # level: it resolves an account's directory card, and every branch below it is filtered.
    if len(segs) == 3:
        user = await _require(db.user, uid, "User")
        entries = [
            _folder(label, _join(parent, slug)) for slug, label in _UPLOADER_BRANCHES.items()
        ]
        return entries, {
            "title": (user.name or "").strip() or "User",
            "fields": [
                f
                for f in [
                    {"label": "Email", "value": _cell(user.email)},
                    {"label": "Role", "value": _cell(_enum_label(user.role))},
                ]
                if f["value"]
            ],
        }

    branch = segs[3]

    if branch == "media":
        # .../media -> the same by-type split, scoped to this uploader and workshop
        if len(segs) == 4:
            return [
                _folder(_CATEGORY_LABEL[slug], _join(parent, slug))
                for slug in ("images", "videos", "audios", "transcripts", "documents", "other")
            ], None
        if len(segs) == 5:
            owned = [{"uploadedById": uid}, {"workshopId": wid}]
            if segs[4] == "transcripts":
                media = await _media(
                    {"AND": [*owned, {"NOT": {"transcriptText": None}}]}, scope, named=True
                )
                return _transcript_entries(parent, media), None
            if segs[4] in _MEDIA_TYPE_WHERE:
                media = await _media(
                    {"AND": [*owned, _MEDIA_TYPE_WHERE[segs[4]]]}, scope, named=True
                )
                return _media_entries(parent, media), None
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if branch in _UPLOADER_BRANCHES and len(segs) == 4:
        kind, records = await _uploader_records(branch, wid, uid, scope)
        if not kind:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
        spec = SPECS[kind]
        used: set[str] = set()
        entries: list[dict[str, Any]] = []
        for record in records:
            name = _uniq(_seg(spec.title(record), spec.label), used)
            details = _text(parent, f"{name}.txt", _info_text(info_panel(kind, record)))
            if details:
                entries.append(details)
        # The folder itself carries the tabular view of every record listed in it.
        return entries, None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# Reverse lookup: record -> its folder.
#
# Search finds a record by name; this says where that record LIVES, so a hit can drop the
# reader into the right folder instead of a dead end. The web View Data page ("Show in
# folders") and the Android data screen both call it. Everything below reuses the same
# reachability rule the tree itself walks (:func:`_linked_artisans`), so a path this returns
# is always a path :func:`data_tree` can open.
# ---------------------------------------------------------------------------


async def _workshop_for_artisan(artisan: Any, scope: Scope) -> Any | None:
    """The workshop whose folder holds this artisan, by the same three routes the tree uses."""
    if artisan.workshopId:
        found = await db.workshop.find_first(
            where=_and({"id": artisan.workshopId}, scope.records)
        )
        if found:
            return found
    link = await db.workshopartisan.find_first(
        where={"artisanId": artisan.id}, include={"workshop": True}
    )
    if link is not None and getattr(link, "workshop", None) is not None:
        if not scope.records or await db.workshop.find_first(
            where=_and({"id": link.workshopId}, scope.records)
        ):
            return link.workshop
    if artisan.craftId:
        craft_link = await db.workshopcraft.find_first(
            where={"craftId": artisan.craftId}, include={"workshop": True}
        )
        if craft_link is not None and getattr(craft_link, "workshop", None) is not None:
            if not scope.records or await db.workshop.find_first(
                where=_and({"id": craft_link.workshopId}, scope.records)
            ):
                return craft_link.workshop
    return None


async def _artisan_path(aid: str, scope: Scope) -> str | None:
    artisan = await db.artisan.find_first(where=_and({"id": aid}, scope.records))
    if artisan is None:
        return None
    workshop = await _workshop_for_artisan(artisan, scope)
    if workshop is None:
        return None
    craft = artisan.craftId or NO_CRAFT
    return f"by-workshop/{workshop.id}/crafts/{craft}/artisans/{artisan.id}"


async def _locate_path(record_type: str, record_id: str, scope: Scope) -> str | None:
    kind = (record_type or "").strip().lower()

    if kind == "workshop":
        found = await db.workshop.find_first(where=_and({"id": record_id}, scope.records))
        return f"by-workshop/{found.id}" if found else None

    if kind == "artisan":
        return await _artisan_path(record_id, scope)

    if kind == "craft":
        link = await db.workshopcraft.find_first(where={"craftId": record_id})
        if link is None:
            return None
        if scope.records and not await db.workshop.find_first(
            where=_and({"id": link.workshopId}, scope.records)
        ):
            return None
        return f"by-workshop/{link.workshopId}/crafts/{record_id}"

    if kind in {"product", "tool"}:
        delegate = db.productdocumentation if kind == "product" else db.tooldocumentation
        record = await delegate.find_first(where=_and({"id": record_id}, scope.records))
        if record is None:
            return None
        owner = record.artisanId
        if owner is None and kind == "tool":
            link = await db.toolartisan.find_first(where={"toolId": record_id})
            owner = link.artisanId if link else None
        if owner is None:
            return None
        base = await _artisan_path(owner, scope)
        return f"{base}/{kind}s/{record_id}" if base else None

    if kind == "process":
        process = await db.process.find_first(where=_and({"id": record_id}, scope.records))
        if process is None or not process.productId:
            return None
        product_path = await _locate_path("product", process.productId, scope)
        return f"{product_path}/processes/{record_id}" if product_path else None

    if kind in {"interview", "questionnaire", "questionnaireinterview"}:
        # `questionnaireinterviewartisan` / `interviewId` — the model is
        # QuestionnaireInterviewArtisan and its column is `interviewId` (schema.prisma:836). The
        # shorter names I first wrote do not exist, and because a delegate is resolved by attribute
        # access this failed at RUNTIME with an AttributeError, not at import: every "Show in
        # folders" on a questionnaire recording returned a 500. Nothing caught it because the media
        # branch below tries the interview owner FIRST, so no other owner was ever reached, and my
        # own smoke test only ever asked for an artisan.
        link = await db.questionnaireinterviewartisan.find_first(
            where={"interviewId": record_id}
        )
        if link is None:
            return None
        base = await _artisan_path(link.artisanId, scope)
        return f"{base}/questionnaire/{record_id}" if base else None

    if kind in {"media", "mediafile"}:
        media = await db.mediafile.find_first(where=_and({"id": record_id}, scope.media))
        if media is None:
            return None
        # Deepest owner wins: a clip on a product belongs in the product folder, not the artisan's.
        for owner_kind, owner_id in (
            ("interview", media.questionnaireInterviewId),
            ("product", media.productId),
            ("tool", media.toolId),
            ("artisan", media.artisanId),
            ("craft", media.craftId),
            ("workshop", media.workshopId),
        ):
            if owner_id:
                found = await _locate_path(owner_kind, owner_id, scope)
                if found:
                    return found
        # No owner at all: it can still be reached in the by-type taxonomy.
        bucket = _TYPE_TOKEN.get(str(_ev(media.mediaType)).upper())
        return f"by-type/{bucket}" if bucket else "by-type"

    return None


@router.get("/locate")
async def data_locate(
    type: str = Query(..., description="workshop | craft | artisan | product | tool | process | interview | media"),
    id: str = Query(..., min_length=1),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, str | None]:
    """The tree path that holds this record, or ``{"path": null}`` when nothing files it yet.

    A null is not an error: a product whose artisan has never been attached to a workshop genuinely
    has no folder, and the caller should say so rather than open the wrong one.
    """
    scope = await _scope_for(current_user)
    return {"path": await _locate_path(type, id, scope)}


@router.get("/tree")
async def data_tree(
    path: str = "", current_user: Any = Depends(require_dataset_downloader)
) -> dict[str, Any]:
    """One level of the virtual data tree (lazy: only this level's queries run).

    Response: {path, crumbs:[{name,path}], entries:[...], info:{title,fields}|null, truncated}.
    Crumbs cover every ancestor including the requested path itself, with clean names; info is
    populated on record folders (workshop/artisan/product/tool/process/interview). Everything
    listed is filtered by the caller's row visibility (see :class:`Scope`).
    """
    scope = await _scope_for(current_user)
    norm = _norm(path)
    entries, info = await _list_level(norm, scope)
    crumbs = await _resolve_crumbs(norm)
    public = [{k: v for k, v in e.items() if not k.startswith("_")} for e in entries]
    public.sort(key=lambda e: (0 if e["kind"] == "folder" else 1, e["name"].lower()))
    # A listing that hits the per-level cap likely has more rows than shown.
    return {
        "path": norm,
        "crumbs": crumbs,
        "entries": public,
        "info": info,
        "truncated": len(entries) >= TAKE,
        # The switcher is served with every level so the client always knows which
        # taxonomy it is inside and can offer the other two without a second call.
        "taxonomies": TAXONOMIES,
        "taxonomy": _taxonomy_of(norm),
    }


async def _walk(
    path: str,
    rel: str,
    include: set[str] | None,
    files: list[dict[str, Any]],
    depth: int,
    seen_media: set[str],
    state: dict[str, bool],
    scope: Scope,
) -> None:
    if depth > MAX_WALK_DEPTH or len(files) >= MAX_MANIFEST_FILES:
        state["truncated"] = True
        return
    try:
        # DB access capped by the semaphore (never held across recursion) so sibling subtrees
        # can walk concurrently without exhausting the connection pool.
        async with _WALK_SEM:
            entries, _ = await _list_level(path, scope)
    except HTTPException:
        return  # a record vanished mid-walk; skip that branch rather than failing the manifest
    if len(entries) >= TAKE:
        state["truncated"] = True
    # One folder's leaf names, and only this folder's: the ZIP writes these entries side by side, so
    # two of them may not agree, while the identical name a sibling folder holds is no clash at all.
    used_names: set[str] = set()

    def emit(entry: dict[str, Any], stem: str, extension: str) -> None:
        """Add one file to the manifest under a leaf nothing else in this folder answers to.

        The stem and the extension arrive apart because the number belongs between them, and the
        extension is not always the last dot: a transcript is a ``.transcript.md``, and splitting it
        off blindly would number the file ``…transcript-2.md``.
        """
        if len(files) < MAX_MANIFEST_FILES:
            entry["path"] = _join(rel, unique_name(stem, extension, used_names))
            files.append(entry)
        else:
            state["truncated"] = True

    child_walks = []
    for e in entries:
        if e["kind"] == "folder":
            child_walks.append(
                _walk(
                    e["path"],
                    _join(rel, e["name"]),
                    include,
                    files,
                    depth + 1,
                    seen_media,
                    state,
                    scope,
                )
            )
            continue
        if "content" in e:
            if include is None or "text" in include:
                entry = {"content": e["content"]}
                if e.get("originalFilename"):
                    entry["originalFilename"] = e["originalFilename"]
                emit(entry, *_split_leaf(e["name"]))
            continue
        # The three top-level views (workshops/users/media-types) overlap; each media object is
        # zipped once, at its first occurrence.
        if e["mediaId"] in seen_media:
            continue
        seen_media.add(e["mediaId"])
        media_type = e.get("mediaType") or "OTHER"
        token = _TYPE_TOKEN.get(media_type, "other")
        name = e["name"]
        stem, extension = _split_leaf(name)
        # The zip entry is named for what the file IS; the name it was uploaded under travels with
        # it so a researcher can still line an extracted file up against their own copy.
        original = e.get("originalFilename")
        if include is None or token in include:
            if media_type == "AUDIO":
                # Client fetches the AAC/mp4 conversion via /data/media/{id}/download?format=mp4,
                # falling back to the original object URL when conversion fails.
                emit(
                    {
                        "url": e.get("url"),
                        "originalPath": _join(rel, name),
                        "originalFilename": original,
                        "mediaId": e["mediaId"],
                        "mediaType": media_type,
                        "convertToMp4": True,
                    },
                    stem,
                    ".mp4",
                )
            else:
                emit(
                    {
                        "url": e.get("url"),
                        "originalFilename": original,
                        "mediaId": e["mediaId"],
                        "mediaType": media_type,
                    },
                    stem,
                    extension,
                )
        if e.get("transcriptAvailable") and (include is None or "transcripts" in include):
            emit(
                {
                    "content": e.get("_transcriptText") or "",
                    "originalFilename": original,
                    "mediaId": e["mediaId"],
                },
                stem,
                ".transcript.md",
            )
    if child_walks:
        await asyncio.gather(*child_walks)


@router.get("/manifest")
async def data_manifest(
    path: str = "",
    include: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    """Flattened manifest of the subtree below ``path`` — same shape as /export/dataset
    ({files:[{path,url?,content?,mediaId?,mediaType?}], totalFiles, totalMedia, truncated});
    the client downloads/zips client-side. ``include`` filters entry kinds; omitted = everything.
    The walk reuses the /tree listers, so it carries the same row visibility."""
    include_set: set[str] | None = None
    if include is not None and include.strip():
        include_set = {t.strip().lower() for t in include.split(",") if t.strip()}
    scope = await _scope_for(current_user)
    norm = _norm(path)
    files: list[dict[str, Any]] = []
    state = {"truncated": False}
    await _walk(norm, "", include_set, files, 0, set(), state, scope)
    total_media = sum(1 for f in files if f.get("mediaId") and f.get("content") is None)
    return {
        "files": files,
        "totalFiles": len(files),
        "totalMedia": total_media,
        "truncated": state["truncated"],
    }


# ---------------------------------------------------------------------------
# Relational report (/data/report): the subtree at a path flattened into linked
# sheets — one per record type — served as JSON or a styled .xlsx workbook.
# ---------------------------------------------------------------------------

# Sheet colours now come from the shared field registry (services/record_fields.py),
# so a record type is the same colour in the workbook tab, the web pill and the tree icon.

_ARTISAN_INCLUDE = {
    "craft": True,
    "createdBy": True,
    "workshops": {"include": {"workshop": True}},
    # The artisan spec prints State and Pincode off this relation; without it both cells fall back
    # to the legacy extraMetadata and read blank for every record entered since they became columns.
    "location": True,
}
_PRODUCT_INCLUDE = {"workshop": True, "createdBy": True}
# product->workshop is nested so a process row can resolve its workshop placement for the
# hierarchy columns without a second query.
_PROCESS_INCLUDE = {
    "steps": True,
    "product": {"include": {"workshop": True}},
    "createdBy": True,
}
_TOOL_INCLUDE = {"workshop": True, "createdBy": True}
_INTERVIEW_INCLUDE = {
    "artisans": {"include": {"artisan": True}},
    "responses": {"include": {"question": True}},
    "createdBy": True,
}
_REPORT_MEDIA_INCLUDE = {
    "uploadedBy": True,
    "product": True,
    "tool": True,
    # artisan->craft is nested so a media row attached only to an artisan can still fill
    # its Craft column in the hierarchy sheet.
    "artisan": {"include": {"craft": True}},
    "workshop": True,
    "craft": True,
    "questionnaireInterview": True,
}

_REPORT_KEYS = (
    "workshops",
    "crafts",
    "artisans",
    "products",
    "processes",
    "tools",
    "interviews",
    "media",
)


def _tag_where(tags: list[str], ids: list[str]) -> dict[str, Any]:
    return {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": {"in": ids}}]}


async def _report_media(where: dict[str, Any] | None, scope: Scope) -> list[Any]:
    kwargs: dict[str, Any] = {
        "take": REPORT_TAKE,
        "order": {"createdAt": "desc"},
        "include": _REPORT_MEDIA_INCLUDE,
    }
    scoped = _and(where or {}, scope.media)
    if scoped:
        kwargs["where"] = scoped
    return await db.mediafile.find_many(**kwargs)


async def _report_records(segs: list[str], scope: Scope) -> dict[str, list[Any]]:
    """Load every record reachable under the given tree path, one list per report sheet.

    Root = the whole repository; a workshop path = that workshop's crafts/artisans/records; an
    artisan path = that artisan's records; record paths narrow to the single record (ancestor
    rows are kept so the sheets still interlink). users/media-types paths yield media sheets
    only, mirroring what those tree branches expose. Every query is capped at REPORT_TAKE and
    filtered by ``scope`` — the report must never contain a row the tree would not show.
    """
    data: dict[str, list[Any]] = {key: [] for key in _REPORT_KEYS}

    # Normalise the taxonomy roots onto the shapes the loaders below understand.
    if segs:
        if segs[0] == "by-workshop":
            segs = ["workshops", *segs[1:]]
        elif segs[0] == "by-type":
            segs = ["media-types", *segs[1:]]
        elif segs[0] == "by-uploader":
            # by-uploader/<wid>[/<uid>[/<branch>]] — report on what that person put into
            # that workshop. Without a uid it is the whole workshop, so fall through to
            # the workshop loader below.
            if len(segs) >= 3:
                wid, uid = segs[1], segs[2]
                owned: list[dict[str, Any]] = [{"uploadedById": uid}, {"workshopId": wid}]
                if len(segs) >= 5 and segs[3] == "media" and segs[4] in _MEDIA_TYPE_WHERE:
                    owned.append(_MEDIA_TYPE_WHERE[segs[4]])
                data["media"] = await _report_media({"AND": owned}, scope)
                for branch in ("artisans", "products", "tools", "questionnaire"):
                    if len(segs) >= 4 and segs[3] not in (branch, "media"):
                        continue
                    kind, records = await _uploader_records(branch, wid, uid, scope)
                    if kind == "artisan":
                        data["artisans"] = records
                    elif kind == "product":
                        data["products"] = records
                    elif kind == "tool":
                        data["tools"] = records
                    elif kind == "interview":
                        data["interviews"] = records
                return data
            segs = ["workshops", *segs[1:]]

    if segs and segs[0] == "users":
        where: dict[str, Any] = {"uploadedById": segs[1]} if len(segs) >= 2 else {}
        if len(segs) >= 3 and segs[2] in _USER_TYPE_WHERE:
            where = {"AND": [where, _USER_TYPE_WHERE[segs[2]]]}
        data["media"] = await _report_media(where, scope)
        return data

    if segs and segs[0] == "media-types":
        type_where = _MEDIA_TYPE_WHERE.get(segs[1]) if len(segs) >= 2 else None
        if len(segs) >= 2 and type_where is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
        data["media"] = await _report_media(type_where, scope)
        return data

    if segs and segs[0] != "workshops":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if len(segs) <= 1:
        # Root (or the all-workshops folder): everything visible, each sheet capped at REPORT_TAKE.
        data["workshops"] = await db.workshop.find_many(
            where=scope.records,
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include={"createdBy": True},
        )
        data["crafts"] = await db.craft.find_many(
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include={"workshops": {"include": {"workshop": True}}},
        )
        data["artisans"] = await db.artisan.find_many(
            where=scope.records,
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_ARTISAN_INCLUDE,
        )
        data["products"] = await db.productdocumentation.find_many(
            where=scope.records,
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_PRODUCT_INCLUDE,
        )
        data["processes"] = await db.process.find_many(
            where=scope.records,
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_PROCESS_INCLUDE,
        )
        data["tools"] = await db.tooldocumentation.find_many(
            where=scope.records,
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_TOOL_INCLUDE,
        )
        data["interviews"] = await db.questionnaireinterview.find_many(
            where=scope.records,
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_INTERVIEW_INCLUDE,
        )
        data["media"] = await _report_media(None, scope)
        return data

    wid = segs[1]
    ws = await _require(
        db.workshop,
        wid,
        "Workshop",
        include={
            "createdBy": True,
            "crafts": {"include": {"craft": True}},
            "artisans": {"include": {"artisan": True}},
        },
        scope_where=scope.records,
    )
    data["workshops"] = [ws]
    linked = await _linked_artisans(ws, scope)

    # Parse the (legacy or craft-ful) path below the workshop into craft/artisan/branch parts.
    cid: str | None = None
    aid: str | None = None
    rest: list[str] = []
    if len(segs) >= 3:
        if segs[2] == "_misc":
            data["media"] = await _report_media(
                {
                    "AND": [
                        {"artisanId": None},
                        {"productId": None},
                        {"toolId": None},
                        {"questionnaireInterviewId": None},
                        _record_media_where("workshopId", wid, ["workshop"]),
                    ]
                },
                scope,
            )
            return data
        if segs[2] == "crafts":
            cid = segs[3] if len(segs) >= 4 else None
            if len(segs) >= 6 and segs[4] == "artisans":
                aid = segs[5]
                rest = list(segs[6:])
        elif segs[2] == "artisans":
            aid = segs[3] if len(segs) >= 4 else None
            rest = list(segs[4:]) if len(segs) >= 5 else []
        else:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    # Artisans in scope.
    if aid is not None:
        artisan = await _require(
            db.artisan, aid, "Artisan", include=_ARTISAN_INCLUDE, scope_where=scope.records
        )
        scoped = [artisan]
    else:
        if cid == NO_CRAFT:
            ids = [a.id for a in linked if not a.craftId]
        elif cid:
            ids = [a.id for a in linked if a.craftId == cid]
        else:
            ids = [a.id for a in linked]
        scoped = (
            await db.artisan.find_many(
                where=_and({"id": {"in": ids}}, scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_ARTISAN_INCLUDE,
            )
            if ids
            else []
        )
    data["artisans"] = scoped
    aids = [a.id for a in scoped]

    # Crafts in scope.
    if aid is not None:
        craft_ids = [a.craftId for a in scoped if a.craftId]
    elif cid == NO_CRAFT:
        craft_ids = []
    elif cid:
        craft_ids = [cid]
    else:
        craft_ids = sorted(
            {link.craftId for link in ws.crafts or []} | {a.craftId for a in scoped if a.craftId}
        )
    data["crafts"] = (
        await db.craft.find_many(
            where={"id": {"in": craft_ids}},
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include={"workshops": {"include": {"workshop": True}}},
        )
        if craft_ids
        else []
    )

    branch = rest[0] if rest else None
    if branch is not None and branch not in ("products", "tools", "questionnaire", "misc"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
    rid = rest[1] if len(rest) >= 2 else None
    proc_id = rest[3] if len(rest) >= 4 and rest[2] == "processes" else None

    products: list[Any] = []
    if branch in (None, "products") and aids:
        if branch == "products" and rid:
            products = [
                await _require(
                    db.productdocumentation,
                    rid,
                    "Product",
                    include=_PRODUCT_INCLUDE,
                    scope_where=scope.records,
                )
            ]
        else:
            # Mirrors the tree: an artisan's products, in this workshop or workshop-less.
            products = await db.productdocumentation.find_many(
                where=_and(
                    {
                        "AND": [
                            {"artisanId": {"in": aids}},
                            {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                        ]
                    },
                    scope.records,
                ),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_PRODUCT_INCLUDE,
            )
    data["products"] = products
    pids = [p.id for p in products]

    processes: list[Any] = []
    if pids:
        if proc_id:
            proc = await db.process.find_first(
                where=_and({"id": proc_id}, scope.records), include=_PROCESS_INCLUDE
            )
            processes = [proc] if proc is not None else []
        else:
            processes = await db.process.find_many(
                where=_and({"productId": {"in": pids}}, scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_PROCESS_INCLUDE,
            )
    data["processes"] = processes

    tools: list[Any] = []
    if branch in (None, "tools") and aids:
        if branch == "tools" and rid:
            tools = [
                await _require(
                    db.tooldocumentation,
                    rid,
                    "Tool",
                    include=_TOOL_INCLUDE,
                    scope_where=scope.records,
                )
            ]
        else:
            tools = await db.tooldocumentation.find_many(
                where=_and(
                    {
                        "AND": [
                            {
                                "OR": [
                                    {"artisanId": {"in": aids}},
                                    {"artisanLinks": {"some": {"artisanId": {"in": aids}}}},
                                ]
                            },
                            {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                        ]
                    },
                    scope.records,
                ),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_TOOL_INCLUDE,
            )
    data["tools"] = tools

    interviews: list[Any] = []
    if branch in (None, "questionnaire") and aids:
        if branch == "questionnaire" and rid:
            interviews = [
                await _require(
                    db.questionnaireinterview,
                    rid,
                    "Interview",
                    include=_INTERVIEW_INCLUDE,
                    scope_where=scope.records,
                )
            ]
        else:
            interviews = await db.questionnaireinterview.find_many(
                where=_and({"artisans": {"some": {"artisanId": {"in": aids}}}}, scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_INTERVIEW_INCLUDE,
            )
    data["interviews"] = interviews

    if branch == "misc" and aid is not None:
        # Mirrors the tree's artisan misc listing exactly.
        data["media"] = await _report_media(
            {
                "OR": [
                    {"AND": [{"linkedRecordType": "artisan"}, {"linkedRecordId": aid}]},
                    {"AND": [{"artisanId": aid}, {"linkedRecordType": None}]},
                ]
            },
            scope,
        )
        return data

    ors: list[dict[str, Any]] = []
    if cid is None and aid is None and branch is None:
        # Whole-workshop scope also covers workshop-level (misc) media.
        ors.append(_record_media_where("workshopId", wid, ["workshop"]))
    if aids and branch is None:
        ors.append({"artisanId": {"in": aids}})
        ors.append(_tag_where(["artisan"], aids))
    if pids:
        ors.append({"productId": {"in": pids}})
        ors.append(_tag_where(["product"], pids))
    if processes:
        proc_ids = [p.id for p in processes]
        step_ids = [s.id for p in processes for s in (p.steps or [])]
        ors.append(_tag_where(["process"], proc_ids))
        if step_ids:
            ors.append(_tag_where(["processstep"], step_ids))
    if tools:
        tool_ids = [t.id for t in tools]
        ors.append({"toolId": {"in": tool_ids}})
        ors.append(_tag_where(["tool"], tool_ids))
    if interviews:
        interview_ids = [i.id for i in interviews]
        ors.append({"questionnaireInterviewId": {"in": interview_ids}})
        ors.append(_tag_where(["questionnaire", "questionnaireinterview"], interview_ids))
    data["media"] = await _report_media({"OR": ors}, scope) if ors else []
    return data


def _sheet(
    name: str,
    color: str,
    columns: list[str],
    rows: list[list[Any]],
    truncated: bool = False,
    prose: list[int] | None = None,
) -> dict[str, Any]:
    """One sheet payload.

    An over-long sheet is clipped and flagged twice: as a trailing note row (so the
    downloaded .xlsx carries the warning inline) and as a ``truncated`` flag (so the web
    viewer can render a banner instead of showing the note as a fake data row).

    ``prose`` names the column indexes whose cells hold stored Markdown rather than a label; see
    ``_rendered`` for what the .xlsx does with them.
    """
    capped = truncated or len(rows) > REPORT_TAKE
    if capped:
        rows = rows[:REPORT_TAKE]
        note = f"Note: capped at {REPORT_TAKE} rows — the full data set has more."
        rows = [*rows, [note] + [""] * (len(columns) - 1)]
    return {
        "name": name,
        "color": color,
        "columns": columns,
        "rows": rows,
        "truncated": capped,
        "prose": prose or [],
    }


# ---------------------------------------------------------------------------
# Media indexing: which record does each file belong to, and where does that
# record sit in the workshop -> craft -> artisan hierarchy.
# ---------------------------------------------------------------------------

# Most specific relation first — a photo of a product is filed under the product, not
# under the artisan who also happens to be on the row.
_OWNER_TAGS = ("processstep", "process")
_OWNER_FKS = (
    "productId",
    "toolId",
    "questionnaireInterviewId",
    "artisanId",
    "workshopId",
    "craftId",
)


def _media_owner_id(m: Any) -> str | None:
    """The single record id a media file is filed under, most specific relation first."""
    tag = (getattr(m, "linkedRecordType", None) or "").strip().lower()
    if tag in _OWNER_TAGS and getattr(m, "linkedRecordId", None):
        return m.linkedRecordId
    for fk in _OWNER_FKS:
        value = getattr(m, fk, None)
        if value:
            return value
    # Tag-only attachment (the FK column was never populated).
    return getattr(m, "linkedRecordId", None)


def _index_media_by_record(media: list[Any]) -> dict[str, list[Any]]:
    """record id -> its media files, each file counted exactly once."""
    index: dict[str, list[Any]] = {}
    for m in media:
        owner = _media_owner_id(m)
        if owner:
            index.setdefault(owner, []).append(m)
    return index


def _hierarchy_of(kind: str, record: Any) -> tuple[str, str, str]:
    """(workshop, craft, artisan) display names for a record, for the flat sheets."""
    if kind == "workshop":
        return _cell(record.title), "", ""
    if kind == "craft":
        titles = [
            getattr(getattr(link, "workshop", None), "title", None)
            for link in getattr(record, "workshops", None) or []
        ]
        return _cell(", ".join(t for t in titles if t)), _cell(record.name), ""
    if kind == "artisan":
        titles = [
            getattr(getattr(link, "workshop", None), "title", None)
            for link in getattr(record, "workshops", None) or []
        ]
        return (
            _cell(", ".join(t for t in titles if t)),
            _cell(getattr(getattr(record, "craft", None), "name", None)),
            _cell(record.name),
        )
    if kind in ("product", "tool"):
        return (
            _cell(getattr(getattr(record, "workshop", None), "title", None)),
            _cell(record.craftName),
            _cell(record.artisanName),
        )
    if kind == "process":
        product = getattr(record, "product", None)
        return (
            _cell(getattr(getattr(product, "workshop", None), "title", None)),
            _cell(getattr(product, "craftName", None)),
            _cell(getattr(product, "artisanName", None)),
        )
    if kind == "interview":
        return "", "", _cell(", ".join(artisan_names(record)))
    return "", "", ""


def _media_link_label(m: Any, proc_names: dict[str, str], step_names: dict[str, str]) -> str:
    """Human 'record link' for one media row, most specific relation first."""
    tag = (m.linkedRecordType or "").strip().lower()
    if tag == "process":
        name = proc_names.get(m.linkedRecordId or "")
        return f"Process: {name}" if name else "Process"
    if tag == "processstep":
        name = step_names.get(m.linkedRecordId or "")
        return f"Process step: {name}" if name else "Process step"
    product = getattr(m, "product", None)
    if product is not None:
        return f"Product: {_cell(product.productName) or 'Product'}"
    tool = getattr(m, "tool", None)
    if tool is not None:
        return f"Tool: {_cell(tool.toolkitName) or 'Tool'}"
    interview = getattr(m, "questionnaireInterview", None)
    if interview is not None:
        return f"Interview: {_cell(interview.title) or 'Interview'}"
    artisan = getattr(m, "artisan", None)
    if artisan is not None:
        return f"Artisan: {_cell(artisan.name) or 'Artisan'}"
    workshop = getattr(m, "workshop", None)
    if workshop is not None:
        return f"Workshop: {_cell(workshop.title) or 'Workshop'}"
    craft = getattr(m, "craft", None)
    if craft is not None:
        return f"Craft: {_cell(craft.name) or 'Craft'}"
    return "Miscellaneous"


def _media_context(
    m: Any, proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, str]:
    """Where one media file sits: its workshop/craft/artisan plus what it is attached to.

    Resolved from whichever relations the row actually carries — a product photo gets its
    craft and artisan from the product's denormalised names, a loose workshop upload gets
    only the workshop.
    """
    product = getattr(m, "product", None)
    tool = getattr(m, "tool", None)
    artisan = getattr(m, "artisan", None)
    workshop = getattr(m, "workshop", None)
    craft = getattr(m, "craft", None)

    workshop_name = _cell(getattr(workshop, "title", None))
    craft_name = _cell(getattr(craft, "name", None))
    artisan_name = _cell(getattr(artisan, "name", None))

    if product is not None:
        craft_name = craft_name or _cell(product.craftName)
        artisan_name = artisan_name or _cell(product.artisanName)
    if tool is not None:
        craft_name = craft_name or _cell(tool.craftName)
        artisan_name = artisan_name or _cell(tool.artisanName)
    if artisan is not None and not craft_name:
        craft_name = _cell(getattr(getattr(artisan, "craft", None), "name", None))

    label = _media_link_label(m, proc_names, step_names)
    attached_kind, _, attached_name = label.partition(": ")
    return {
        "workshop": workshop_name,
        "craft": craft_name,
        "artisan": artisan_name,
        "attachedTo": attached_kind,
        "record": attached_name or "",
        "label": label,
    }


def _media_facts(m: Any) -> dict[str, str]:
    """The per-file cells shared by all three media taxonomy sheets."""
    return {
        "file": _cell(m.originalFilename) or _cell(m.id),
        "type": _cell(str(_ev(m.mediaType)).title()),
        "size": _human_size(m.sizeBytes),
        "uploadedBy": _cell(getattr(getattr(m, "uploadedBy", None), "name", None)),
        "uploadedOn": _cell(_date(m.createdAt)),
        "recordedOn": _cell(_date(m.recordedAt or m.createdAt)),
        "transcript": "Yes" if (m.transcriptText or "").strip() else "",
        "url": _cell(m.url),
    }


# ---------------------------------------------------------------------------
# Sheet builders
# ---------------------------------------------------------------------------


def _record_sheet(
    kind: str, records: list[Any], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    """One sheet per record type, columns straight from the shared field registry so the
    sheet, the browser's info card and the in-folder table can never disagree. Each row
    carries its own media inline (count / filenames / URLs)."""
    spec = SPECS[kind]
    rows = [sheet_row(kind, r, media_index.get(r.id, [])) for r in records]
    return _sheet(
        spec.plural, spec.color, sheet_columns(kind), rows, truncated=len(records) >= REPORT_TAKE
    )


def _process_step_sheet(
    processes: list[Any], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    """Steps get their own sheet rather than being interleaved as half-empty rows in the
    Processes sheet, so both stay rectangular and sortable."""
    columns = [
        "Step",
        "Process",
        "Product",
        "Artisan",
        "Step #",
        "Step type",
        "Notes",
        *MEDIA_COLUMNS,
    ]
    rows: list[list[Any]] = []
    for pr in processes:
        product = getattr(pr, "product", None)
        for step in sorted(getattr(pr, "steps", None) or [], key=lambda s: s.sortOrder):
            rows.append(
                [
                    _cell(step.name),
                    _cell(pr.name),
                    _cell(getattr(product, "productName", None)),
                    _cell(getattr(product, "artisanName", None)),
                    step.sortOrder,
                    _cell(_enum_label(step.stepType)),
                    _cell(step.notes),
                    *media_row(media_index.get(step.id, [])),
                ]
            )
    return _sheet("Process steps", SPECS["process"].color, columns, rows)


def _questionnaire_answer_sheet(interviews: list[Any]) -> dict[str, Any]:
    """The per-question answers, one row each, beside the interview-level sheet."""
    columns = ["Interview", "Artisans", "Section", "Question", "Answer", "Notes"]
    rows: list[list[Any]] = []
    for interview in interviews:
        label = _cell(_interview_label(interview))
        names = _cell(", ".join(artisan_names(interview)))
        responses = sorted(
            interview.responses or [],
            key=lambda r: getattr(getattr(r, "question", None), "sortOrder", 0) or 0,
        )
        for r in responses:
            q = getattr(r, "question", None)
            section = (
                (getattr(q, "sectionTitle", None) or getattr(q, "sectionCode", None)) if q else None
            )
            rows.append(
                [
                    label,
                    names,
                    _cell(section),
                    _cell((getattr(q, "prompt", None) if q else None) or r.questionId),
                    _cell(r.answerText),
                    _cell(r.notes),
                ]
            )
    return _sheet("Questionnaire answers", SPECS["interview"].color, columns, rows)


def _all_records_sheet(
    data: dict[str, list[Any]], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    """THE coalesced sheet: every record of every type on one page, each row carrying its
    full workshop -> craft -> artisan placement, its media, and its complete field dump.

    This is the single page to hand someone who wants the whole taxonomy at once without
    hopping between eight tabs.
    """
    columns = [
        "Workshop",
        "Craft",
        "Artisan",
        "Record type",
        "Record",
        *PROVENANCE_COLUMNS,
        *MEDIA_COLUMNS,
        "All fields",
    ]
    order = (
        ("workshop", "workshops"),
        ("craft", "crafts"),
        ("artisan", "artisans"),
        ("product", "products"),
        ("process", "processes"),
        ("tool", "tools"),
        ("interview", "interviews"),
    )
    rows: list[list[Any]] = []
    for kind, key in order:
        spec = SPECS[kind]
        for record in data.get(key) or []:
            workshop, craft, artisan = _hierarchy_of(kind, record)
            rows.append(
                [
                    workshop,
                    craft,
                    artisan,
                    spec.label,
                    _cell(spec.title(record)),
                    *provenance_row(record),
                    *media_row(media_index.get(record.id, [])),
                    _info_text(info_panel(kind, record)),
                ]
            )
    return _sheet("All records", OVERVIEW_COLOR, columns, rows)


def _transcript_sheet(
    media: list[Any], proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, Any]:
    columns = [
        "File",
        "Type",
        "Workshop",
        "Craft",
        "Artisan",
        "Linked record",
        "Uploaded by",
        "Recorded on",
        "Transcript",
    ]
    rows = []
    for m in media:
        if not (m.transcriptText or "").strip():
            continue
        ctx = _media_context(m, proc_names, step_names)
        facts = _media_facts(m)
        rows.append(
            [
                facts["file"],
                facts["type"],
                ctx["workshop"],
                ctx["craft"],
                ctx["artisan"],
                ctx["label"],
                facts["uploadedBy"],
                facts["recordedOn"],
                _cell(m.transcriptText),
            ]
        )
    return _sheet(
        "Transcripts", TRANSCRIPT_COLOR, columns, rows, prose=[columns.index("Transcript")]
    )


def _media_by_hierarchy_sheet(
    media: list[Any], proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, Any]:
    """Media taxonomy 1 — the default browse order: workshop -> craft -> artisan -> record."""
    columns = [
        "Workshop",
        "Craft",
        "Artisan",
        "Attached to",
        "Record",
        "File",
        "Type",
        "Size",
        "Transcript",
        "Uploaded by",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names)
        f = _media_facts(m)
        rows.append(
            [
                ctx["workshop"],
                ctx["craft"],
                ctx["artisan"],
                ctx["attachedTo"],
                ctx["record"],
                f["file"],
                f["type"],
                f["size"],
                f["transcript"],
                f["uploadedBy"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower(), r[2].lower(), r[5].lower()))
    return _sheet("Media by hierarchy", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _media_by_uploader_sheet(
    media: list[Any], proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, Any]:
    """Media taxonomy 2 — who uploaded what, and into which workshop."""
    columns = [
        "Uploaded by",
        "Workshop",
        "Attached to",
        "Record",
        "File",
        "Type",
        "Size",
        "Transcript",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names)
        f = _media_facts(m)
        rows.append(
            [
                f["uploadedBy"],
                ctx["workshop"],
                ctx["attachedTo"],
                ctx["record"],
                f["file"],
                f["type"],
                f["size"],
                f["transcript"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower(), r[4].lower()))
    return _sheet("Media by uploader", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _media_by_type_sheet(
    media: list[Any], proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, Any]:
    """Media taxonomy 3 — grouped by kind of file (audios, videos, images, documents)."""
    columns = [
        "Type",
        "File",
        "Workshop",
        "Artisan",
        "Attached to",
        "Record",
        "Size",
        "Transcript",
        "Uploaded by",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names)
        f = _media_facts(m)
        rows.append(
            [
                f["type"],
                f["file"],
                ctx["workshop"],
                ctx["artisan"],
                ctx["attachedTo"],
                ctx["record"],
                f["size"],
                f["transcript"],
                f["uploadedBy"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower()))
    return _sheet("Media by type", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _report_sheets(data: dict[str, list[Any]]) -> list[dict[str, Any]]:
    """Every sheet in the workbook, in tab order.

    Layout: the coalesced All-records page first (the whole taxonomy at a glance), then
    one page per record type, then the questionnaire answers and process steps, then the
    transcripts, and finally the same media set presented under each of the three
    taxonomies the browser offers.
    """
    media = data["media"]
    proc_names = {p.id: _cell(p.name) for p in data["processes"]}
    step_names = {s.id: _cell(s.name) for p in data["processes"] for s in (p.steps or [])}
    media_index = _index_media_by_record(media)

    return [
        _all_records_sheet(data, media_index),
        _record_sheet("workshop", data["workshops"], media_index),
        _record_sheet("craft", data["crafts"], media_index),
        _record_sheet("artisan", data["artisans"], media_index),
        _record_sheet("product", data["products"], media_index),
        _record_sheet("process", data["processes"], media_index),
        _process_step_sheet(data["processes"], media_index),
        _record_sheet("tool", data["tools"], media_index),
        _record_sheet("interview", data["interviews"], media_index),
        _questionnaire_answer_sheet(data["interviews"]),
        _transcript_sheet(media, proc_names, step_names),
        _media_by_hierarchy_sheet(media, proc_names, step_names),
        _media_by_uploader_sheet(media, proc_names, step_names),
        _media_by_type_sheet(media, proc_names, step_names),
    ]


def _rendered(sheets: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The sheets with every ``prose`` column re-expressed as Excel rich text.

    Only the .xlsx render goes through here. ``format=json`` keeps the stored Markdown, because
    its consumers (the web viewer, the manifest's .md files) render Markdown themselves — Excel
    is the one reader that cannot, and shows the asterisks instead.
    """
    rendered = []
    for sheet in sheets:
        prose = sheet["prose"]
        if not prose:
            rendered.append(sheet)
            continue
        rows = [
            [transcript_cell(value) if idx in prose else value for idx, value in enumerate(row)]
            for row in sheet["rows"]
        ]
        rendered.append({**sheet, "rows": rows})
    return rendered


@router.get("/report")
async def data_report(
    path: str = "",
    format: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    """Relational report of the subtree at ``path``: one sheet per record type, rows carrying
    their parent relations so the sheets interlink. ``format=json`` returns
    {sheets:[{name,color,columns,rows}]}; ``format=xlsx`` (default) streams a styled workbook.
    Every sheet is built from the caller's visible rows only."""
    fmt = (format or "xlsx").strip().lower()
    if fmt not in ("json", "xlsx"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="format must be 'json' or 'xlsx'",
        )
    scope = await _scope_for(current_user)
    norm = _norm(path)
    segs = [s for s in norm.split("/") if s]
    records = await _report_records(segs, scope)
    sheets = _report_sheets(records)
    if fmt == "json":
        return JSONResponse({"sheets": sheets})

    crumbs = await _resolve_crumbs(norm)
    level_name = crumbs[-1]["name"] if crumbs else "Repository"
    # openpyxl is sync; building a big workbook off-loop keeps requests flowing.
    payload = await asyncio.to_thread(
        build_report_workbook, _rendered(sheets), f"{level_name} report"
    )
    slug = re.sub(r"[^A-Za-z0-9]+", "-", level_name).strip("-").lower() or "repository"
    return StreamingResponse(
        io.BytesIO(payload),
        media_type=XLSX_MIME,
        headers={"Content-Disposition": _content_disposition(f"{slug}-report.xlsx")},
    )


def _convert_audio_to_mp4(raw: bytes) -> io.BytesIO:
    """Decode any uploaded audio container and re-encode as .mp4/AAC (sync; run off-loop)."""
    from pydub import AudioSegment

    segment = AudioSegment.from_file(io.BytesIO(raw))
    out = io.BytesIO()
    segment.export(out, format="mp4")  # ffmpeg's mp4 muxer defaults to AAC audio
    out.seek(0)
    return out


@router.get("/media/{media_id}/download")
async def download_media(
    media_id: str,
    format: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    """Download one media file. Audio defaults to an .mp4 (AAC) conversion done server-side;
    anything else redirects to (or streams) the stored object untouched.

    The row must be one the caller can see: this route takes a bare id, so without the visibility
    check the whole media table would be readable by id even though the tree only ever lists the
    caller's own (and granted) uploads. An out-of-scope id reads as 404, never 403.
    """
    scope = await _scope_for(current_user)
    # Relations loaded so the Content-Disposition carries the same derived name the tree showed;
    # a file that arrives on disk under a different name than the one that was clicked is a bug.
    #
    # The name is the unnumbered one. The "-2" a duplicate picks up says "another file in the folder
    # you are extracting already answers to this", and a single download has no such folder — it
    # lands in Downloads, where the browser does its own numbering against whatever is already
    # there. Inventing a suffix here would mean guessing which of three taxonomies the click came
    # from, and each of them shows this file beside a different set of siblings.
    media = (
        await db.mediafile.find_first(
            where={"AND": [{"id": media_id}, scope.media]}, include=_NAMING_INCLUDE
        )
        if scope.media
        else await db.mediafile.find_unique(where={"id": media_id}, include=_NAMING_INCLUDE)
    )
    if media is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Media not found")

    is_audio = str(_ev(media.mediaType)) == "AUDIO"
    fmt = (format or "").strip().lower() or ("mp4" if is_audio else None)

    if is_audio and fmt == "mp4":
        try:
            import pydub  # noqa: F401 — local import: optional runtime dep (needs ffmpeg)
        except Exception as exc:  # pragma: no cover - environment-dependent
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Audio conversion unavailable: pydub is not installed on the server.",
            ) from exc
        size = int(media.sizeBytes or 0)
        if size > MAX_CONVERT_BYTES:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail="This recording is too large to convert in-process; download the original.",
            )
        try:
            # Blocking S3 read off the event loop — this is the single-worker web process.
            raw = await asyncio.to_thread(get_object_bytes, media.objectKey)
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Could not fetch the audio bytes from object storage.",
            ) from exc
        try:
            # ffmpeg decode + AAC encode runs in a worker thread so requests keep flowing.
            out = await asyncio.to_thread(_convert_audio_to_mp4, raw)
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=f"Audio conversion to mp4 failed (is ffmpeg installed?): {exc}",
            ) from exc
        stem = display_stem(media, fallback=media.id)
        return StreamingResponse(
            out,
            media_type="video/mp4",
            headers={"Content-Disposition": _content_disposition(f"{stem}.mp4")},
        )

    # Non-audio (or an explicitly non-mp4 format): hand back the original object.
    if media.url:
        return RedirectResponse(media.url, status_code=status.HTTP_307_TEMPORARY_REDIRECT)
    try:
        raw = await asyncio.to_thread(get_object_bytes, media.objectKey)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Could not fetch the media bytes from object storage.",
        ) from exc
    name = display_filename(media, fallback=media.id)
    return Response(
        content=raw,
        media_type=media.mimeType or "application/octet-stream",
        headers={"Content-Disposition": _content_disposition(name)},
    )
