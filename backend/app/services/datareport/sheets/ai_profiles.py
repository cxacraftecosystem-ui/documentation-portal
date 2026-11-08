from __future__ import annotations

import logging
import re
from datetime import datetime
from typing import Any

from app.services.datareport import ai_cache, exclusions
from app.services.datareport.ai_cache import value
from app.services.datareport.contract import Column, Image, Link, ReportContext, Sheet, register

logger = logging.getLogger(__name__)

PROFILES_CAP = 50
RECORDINGS_CAP = 200
QUERY_BATCH = 500

CONSENT_SQL = (
    'SELECT "subjectId", "purpose"::text AS "purpose", "state"::text AS "state", "basis"::text AS "basis", '
    '"recordedAt", "note" FROM consent_current_v '
    """WHERE "subjectType"::text = 'ARTISAN' AND "subjectId" = ANY($1::text[])"""
)

PURPOSES = (
    ("EXTERNAL_PROCESSING", "Transcription and AI"),
    ("CROSS_PORTAL_SHARING", "Sharing with the other portal"),
)
STATES = {"GRANTED": "Granted", "REFUSED": "Refused", "NOT_RECORDED": "Not recorded"}
BASES = {
    "VERBAL_RECORDED": "Verbal, recorded",
    "VERBAL_WITNESSED": "Verbal, witnessed",
    "SIGNED_FORM": "Signed form",
    "OWNER_ATTESTATION": "Owner attestation",
}
MEDIA_TYPES = {
    "IMAGE": "Photo",
    "VIDEO": "Video",
    "AUDIO": "Audio",
    "PDF": "PDF",
    "DOCUMENT": "Document",
    "OTHER": "File",
}


def _label(raw: Any) -> str:
    text = str(getattr(raw, "value", raw) or "")
    return text.replace("_", " ").capitalize() if text else ""


def _when(raw: Any) -> datetime | None:
    if isinstance(raw, datetime):
        return raw
    if isinstance(raw, str) and raw:
        try:
            return datetime.fromisoformat(raw)
        except ValueError:
            return None
    return None


def _experience(artisan: Any) -> str:
    from app.services.records import derive_experience_years

    start = value(artisan, "craftStartDate")
    years = derive_experience_years(start) if start else value(artisan, "experienceYears")
    months = None if start else value(artisan, "experienceMonths")
    parts = []
    if years is not None:
        parts.append(f"{years} year{'' if years == 1 else 's'}")
    if months:
        parts.append(f"{months} month{'' if months == 1 else 's'}")
    return " ".join(parts)


def _place(location: Any) -> str:
    bits = [value(location, k) for k in ("village", "placeName", "district", "state", "pincode")]
    return ", ".join(dict.fromkeys(str(b) for b in bits if b))


def _maps(location: Any) -> Link | None:
    lat, lng = value(location, "latitude"), value(location, "longitude")
    if lat is None or lng is None:
        return None
    return Link("Map", f"https://www.google.com/maps?q={float(lat):.6f},{float(lng):.6f}")


def _tool_name(tool: Any) -> str:
    for key in ("toolkitName", "englishName", "localName"):
        if value(tool, key):
            return str(value(tool, key))
    return "Tool"


def _tool_detail(tool: Any) -> str:
    bits = [value(tool, "localName"), value(tool, "material"), value(tool, "processUsedIn")]
    return " · ".join(str(b) for b in bits if b)


async def _consents(ids: list[str]) -> dict[str, dict[str, dict[str, Any]]]:
    from app.core.db import db

    out: dict[str, dict[str, dict[str, Any]]] = {}
    try:
        for chunk in ai_cache.chunks(ids, QUERY_BATCH):
            for row in await db.query_raw(CONSENT_SQL, chunk):
                out.setdefault(str(row["subjectId"]), {})[str(row["purpose"])] = row
    except Exception as exc:  # noqa: BLE001 - an unreadable consent store reads as not recorded
        logger.warning("consent unreadable for the profile sheets (%s)", type(exc).__name__)
        return {}
    return out


def _row(
    section: str, item: str, detail: Any = "", *, fixed: bool = False, **extra: Any
) -> dict[str, Any]:
    return {
        "section": section,
        "item": item,
        "detail": "" if detail is None else str(detail),
        "_fixed": fixed,
        **extra,
    }


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.casefold()).strip("_")


def _translate(ctx: ReportContext, rows: list[dict[str, Any]]) -> None:
    for row in rows:
        fixed = row.pop("_fixed", False)
        section = row["section"]
        row["section"] = ai_cache.say(ctx, f"profile.section.{_slug(section)}", section)
        if fixed:
            row["item"] = ai_cache.say(ctx, f"profile.item.{_slug(row['item'])}", row["item"])
        if row.get("status"):
            row["status"] = ai_cache.say(ctx, f"status.{_slug(row['status'])}", row["status"])


def _links(ctx: ReportContext, **links: Link | None) -> dict[str, Link | None]:
    if not ctx.options.links:
        return {}
    return {k: v for k, v in links.items() if v is not None}


def _interviews_by_artisan(ctx: ReportContext) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for interview in ai_cache.items(ctx, "interviews"):
        iid = value(interview, "id")
        for link in value(interview, "artisans", []) or []:
            aid = value(link, "artisanId") or value(value(link, "artisan"), "id")
            if iid and aid:
                out.setdefault(str(aid), set()).add(str(iid))
    return out


def profile_rows(
    ctx: ReportContext, artisan: Any, related: dict[str, Any], consents: dict[str, dict[str, Any]]
) -> list[dict[str, Any]]:
    from app.services import transcript_export
    from app.services.records import derive_age

    aid = str(value(artisan, "id"))
    rows: list[dict[str, Any]] = []
    media = related["media"]

    if ctx.options.thumbnails:
        photo = next(
            (m for m in media if str(value(m, "mediaType", "")).upper().endswith("IMAGE")), None
        )
        if photo is not None:
            pid = str(value(photo, "id"))
            rows.append(
                _row(
                    "Photo",
                    ai_cache.file_label(photo),
                    "",
                    fixed=False,
                    photo=Image(pid, str(value(artisan, "name") or "")),
                    **_links(
                        ctx,
                        open=Link("Open", ctx.links.open_media(pid)),
                        download=Link("Download", ctx.links.download(pid)),
                    ),
                )
            )

    facts = "Key facts"
    location = value(artisan, "location")
    phone = " ".join(
        str(p) for p in (value(artisan, "phoneCountryCode"), value(artisan, "phone")) if p
    )
    age = derive_age(value(artisan, "dateOfBirth"))
    workshops = [
        value(value(w, "workshop"), "title") for w in value(artisan, "workshops", []) or []
    ]
    pehchan = value(artisan, "pehchanCardAvailable")
    entries: list[tuple[str, Any, dict[str, Any]]] = [
        (
            "Name",
            value(artisan, "name"),
            _links(ctx, open=Link("Open record", ctx.links.open_record("artisan", aid))),
        ),
        ("Local name", value(artisan, "localName"), {}),
        ("Gender", value(artisan, "gender"), {}),
        ("Age", age, {}),
        ("Experience", _experience(artisan), {}),
        ("Craft", value(value(artisan, "craft"), "name"), {}),
        ("Place", value(artisan, "place"), {}),
        ("Location", _place(location), _links(ctx, open=_maps(location))),
        ("Address", value(artisan, "address"), {}),
        ("Phone", phone, {}),
        ("Email", value(artisan, "email"), {}),
        ("Workshops", ", ".join(str(w) for w in workshops if w), {}),
        (
            "Pehchan card",
            (
                "Yes"
                + (
                    f", {exclusions.identity_view(ctx.user, artisan, 'pehchanCardNumber', value(artisan, 'pehchanCardNumber'))}"
                    if value(artisan, "pehchanCardNumber")
                    else ""
                )
            )
            if pehchan
            else ("No" if pehchan is not None else ""),
            {},
        ),
        ("Do", value(artisan, "dos"), {}),
        ("Don't", value(artisan, "donts"), {}),
        ("Notes", value(artisan, "notes"), {}),
    ]
    for item, detail, extra in entries:
        if detail not in (None, ""):
            rows.append(_row(facts, item, detail, fixed=True, **extra))
    rows.append(
        _row(
            facts,
            "Record",
            f"Recorded by {value(value(artisan, 'createdBy'), 'name')}"
            if value(value(artisan, "createdBy"), "name")
            else "",
            fixed=True,
            when=_when(value(artisan, "recordedAt")),
            status=_label(value(artisan, "status")),
        )
    )
    if value(artisan, "reviewedAt") or value(artisan, "reviewNotes"):
        rows.append(
            _row(
                facts,
                "Review",
                value(artisan, "reviewNotes"),
                fixed=True,
                when=_when(value(artisan, "reviewedAt")),
            )
        )

    for purpose, label in PURPOSES:
        found = consents.get(purpose)
        state = (
            STATES.get(str(found.get("state")), _label(found.get("state")))
            if found
            else "Not recorded"
        )
        bits = (
            [BASES.get(str(found.get("basis")), _label(found.get("basis"))), found.get("note")]
            if found
            else []
        )
        rows.append(
            _row(
                "Consent",
                label,
                " · ".join(str(b) for b in bits if b),
                fixed=True,
                when=_when(found.get("recordedAt")) if found else None,
                status=state,
            )
        )

    for tool in related["tools"]:
        tid = str(value(tool, "id"))
        rows.append(
            _row(
                "Tools",
                _tool_name(tool),
                _tool_detail(tool),
                when=_when(value(tool, "recordedAt")),
                status=_label(value(tool, "status")),
                **_links(ctx, open=Link("Open record", ctx.links.open_record("tool", tid))),
            )
        )

    for product in related["products"]:
        pid = str(value(product, "id"))
        rows.append(
            _row(
                "Products",
                str(value(product, "productName") or "Product"),
                " · ".join(
                    str(b)
                    for b in (value(product, "localName"), _label(value(product, "productType")))
                    if b
                ),
                when=_when(value(product, "recordedAt")),
                status=_label(value(product, "status")),
                **_links(ctx, open=Link("Open record", ctx.links.open_record("product", pid))),
            )
        )

    for process in related["processes"]:
        pid = str(value(process, "id"))
        steps = len(value(process, "steps", []) or [])
        product = value(value(process, "product"), "productName")
        detail = " · ".join(
            b
            for b in (
                f"For {product}" if product else "",
                f"{steps} step{'' if steps == 1 else 's'}" if steps else "",
            )
            if b
        )
        rows.append(
            _row(
                "Processes",
                str(value(process, "name") or "Process"),
                detail,
                when=_when(value(process, "recordedAt")),
                status=_label(value(process, "status")),
                **_links(ctx, open=Link("Open record", ctx.links.open_record("process", pid))),
            )
        )

    for m in media[:RECORDINGS_CAP]:
        mid = str(value(m, "id"))
        kind = MEDIA_TYPES.get(
            str(value(m, "mediaType", "")).upper(), _label(value(m, "mediaType"))
        )
        has_text = transcript_export.has_text(m)
        rows.append(
            _row(
                "Recordings",
                ai_cache.file_label(m),
                f"{kind} · transcript" if has_text else kind,
                when=_when(value(m, "recordedAt")) or _when(value(m, "createdAt")),
                size=value(m, "sizeBytes"),
                **_links(
                    ctx,
                    open=Link("Open", ctx.links.open_media(mid)),
                    download=Link("Download", ctx.links.download(mid)),
                    transcript=Link("Transcript", ctx.links.transcript_file(mid, "docx"))
                    if has_text
                    else None,
                ),
            )
        )
    if len(media) > RECORDINGS_CAP:
        rows.append(
            _row(
                "Recordings",
                f"{len(media) - RECORDINGS_CAP} more",
                "The Media sheet lists every file.",
            )
        )

    for row in rows:
        row["artisanId"] = aid
    _translate(ctx, rows)
    return rows


def columns(ctx: ReportContext | None = None) -> list[Column]:
    _answers, links = ai_cache.options_of(ctx)
    thumbnails = ctx is None or bool(ctx.options.thumbnails)
    cols = [
        Column("section", "Section", "text", "Which part of the profile the row belongs to.", 14),
        Column("item", "Item", "text", "The fact, record or file.", 26),
        Column("detail", "Detail", "longtext", "Its value or a short description.", 48),
        Column(
            "when", "When", "datetime", "When it was recorded.", 18, source="Artisan.recordedAt"
        ),
        Column(
            "status", "Status", "status", "Review or consent state.", 14, source="Artisan.status"
        ),
        Column(
            "size", "Size", "bytes", "File size, for recordings.", 10, source="MediaFile.sizeBytes"
        ),
    ]
    if thumbnails:
        cols.append(
            Column(
                "photo",
                "Photo",
                "image",
                "A small photo of the artisan.",
                14,
                source="MediaFile.id",
            )
        )
    if links:
        cols += [
            Column("open", "Open", "link", "Opens the record or file in the portal.", 12),
            Column(
                "download", "Download", "link", "Downloads the file.", 12, source="MediaFile.id"
            ),
            Column(
                "transcript",
                "Transcript",
                "link",
                "Downloads the transcript as .docx.",
                12,
                source="MediaFile.transcriptText",
            ),
        ]
    cols.append(
        Column(
            "artisanId",
            "Artisan ID",
            "id",
            "The artisan's id.",
            26,
            hidden=True,
            group="ids",
            source="Artisan.id",
        )
    )
    return cols


def related_records(ctx: ReportContext, artisan_ids: set[str]) -> dict[str, dict[str, list[Any]]]:
    from app.services.tool_scope import tool_artisan_ids

    out: dict[str, dict[str, list[Any]]] = {
        aid: {"media": [], "tools": [], "products": [], "processes": []} for aid in artisan_ids
    }
    for tool in ai_cache.items(ctx, "tools"):
        for aid in tool_artisan_ids(tool):
            if aid in out:
                out[aid]["tools"].append(tool)
    for product in ai_cache.items(ctx, "products"):
        aid = value(product, "artisanId")
        if aid in out:
            out[aid]["products"].append(product)
    for process in ai_cache.items(ctx, "processes"):
        aid = value(value(process, "product"), "artisanId")
        if aid in out:
            out[aid]["processes"].append(process)
    interviews = _interviews_by_artisan(ctx)
    by_interview: dict[str, set[str]] = {}
    for aid, iids in interviews.items():
        if aid in out:
            for iid in iids:
                by_interview.setdefault(iid, set()).add(aid)
    for m in ai_cache.items(ctx, "media"):
        owners = set(by_interview.get(str(value(m, "questionnaireInterviewId", "")), set()))
        aid = value(m, "artisanId")
        if aid in out:
            owners.add(aid)
        for owner in owners:
            out[owner]["media"].append(m)
    return out


class ProfileSheets:
    key = "profiles"
    title = "Profiles"
    order = 170
    option = "profiles"
    portals = ("designer", "fieldRepository")

    def catalog(self) -> list[Sheet]:
        return [Sheet(key="profile", name="Profile", columns=columns(), rows=[], group="profiles")]

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        artisans = [a for a in ai_cache.items(ctx, "artisans") if value(a, "id")]
        artisans = list({str(value(a, "id")): a for a in artisans}.values())
        artisans.sort(key=lambda a: (ai_cache.normalise(value(a, "name", "")), str(value(a, "id"))))
        total = len(artisans)
        shown = artisans[:PROFILES_CAP]
        if not shown:
            return []
        ids = [str(value(a, "id")) for a in shown]
        consents = await ai_cache.cached(ctx, "profile-consent", ids, lambda: _consents(ids))
        related = related_records(ctx, set(ids))
        cols = columns(ctx)
        taken: set[str] = set()
        sheets: list[Sheet] = []
        label = ai_cache.say(ctx, "sheet.profile", "Profile")
        for index, artisan in enumerate(shown, start=1):
            aid = str(value(artisan, "id"))
            note = None
            if index == 1 and total > PROFILES_CAP:
                note = ai_cache.say(
                    ctx,
                    "banner.profiles.capped",
                    "Profiles for the first {n} of {total} artisans, by name. Open a smaller folder for the rest.",
                    n=PROFILES_CAP,
                    total=total,
                )
            sheets.append(
                Sheet(
                    key=f"profile-{aid}",
                    name=ai_cache.sheet_name(
                        str(value(artisan, "name") or "Artisan"), taken, prefix=f"{label} · "
                    ),
                    columns=cols,
                    rows=profile_rows(ctx, artisan, related[aid], consents.get(aid, {})),
                    note=note,
                    group="profiles",
                )
            )
        return sheets


register(ProfileSheets())
