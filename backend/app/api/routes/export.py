from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import Response

# _seg (filesystem-safe segment) and _uniq (collision-free path) are SHARED with the data browser
# rather than re-implemented here: both modules emit the same manifest shape into the same
# client-side zip builder, so the two must name and de-duplicate paths identically or the same
# repository would unpack differently depending on which endpoint produced the manifest.
from app.api.routes.data_browser import _seg, _uniq
from app.core.db import db
from app.core.deps import can_download_dataset, get_current_user
from app.services.access import owner_download_scope
from app.services.csv_export import records_to_csv
from app.services.record_fields import info_panel, info_text, interview_label
from app.services.records import visibility_where

router = APIRouter(prefix="/export", tags=["export"])

# Per-table row cap. The whole repository is pulled into memory here and this runs on a
# single-worker t3.micro, so an unbounded find_many is one bad day away from an OOM; 5000 matches
# the data browser's per-sheet cap (REPORT_TAKE). Hitting any cap raises the response's
# ``truncated`` flag so the client can say so instead of quietly handing over a partial dataset.
EXPORT_TAKE = 5000
# Media rows are the one table that legitimately runs into five figures.
MEDIA_TAKE = 20000


def csv_response(filename: str, body: str) -> Response:
    return Response(
        content=body,
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# ---------------------------------------------------------------------------
# Full-dataset manifest. The client downloads every media object straight from
# S3 (keeping the t3.micro out of the heavy path) and zips them into a directory
# tree:  Workshops/<workshop>/<craft>/<artisan>/{Products/<p>/Processes/<proc>,
# Tools/<t>, Questionnaires/<i>} plus an _Unlinked area so nothing is dropped.
# Each leaf carries the record's media (original, already-nomenclatured filenames)
# and a details.txt. Records with no workshop land under _Unlinked.
#
# Every details.txt body comes from the shared field registry
# (app/services/record_fields.py), so this manifest, the data browser's info cards and the .xlsx
# report always describe a record with the same fields, the same labels and the same value
# coercion — including the masking the registry applies to an artisan's Aadhaar number.
# ---------------------------------------------------------------------------

# Relation includes chosen so every getter in the registry's spec can resolve: a product's
# workshop title, an interview's artisan names, a process's parent product, and the createdBy the
# CSV's provenance columns report.
_WORKSHOP_INCLUDE = {
    "crafts": {"include": {"craft": True}},
    "artisans": {"include": {"artisan": True}},
}
# `location` carries the artisan's State and Pincode, which the record spec prints into details.txt
# and the workbook; without it those two cells silently fall back to the legacy extraMetadata.
_ARTISAN_INCLUDE = {
    "craft": True,
    "workshops": {"include": {"workshop": True}},
    "location": True,
}
_PRODUCT_INCLUDE = {"workshop": True}
_TOOL_INCLUDE = {"workshop": True}
_PROCESS_INCLUDE = {"steps": True, "product": True}
_INTERVIEW_INCLUDE = {
    "artisans": {"include": {"artisan": True}},
    "responses": {"include": {"question": True}},
}

# A media row is filed under exactly ONE record folder, most specific relation first — the same
# precedence data_browser._media_owner_id uses. A row commonly carries several links at once (a
# product photo that also names its workshop, a typed FK alongside the string tag pair), and
# without a single winner the same object would be zipped into two or three folders.
_MEDIA_FK_SLOTS = (
    ("productId", "product"),
    ("toolId", "tool"),
    ("questionnaireInterviewId", "questionnaire"),
    ("artisanId", "artisan"),
    ("workshopId", "workshop"),
)
# Finer-grained than any FK column, because process/process-step attachments have no FK of their
# own and are carried by the tag pair alone.
_MEDIA_TAG_SLOTS = ("process", "processstep")
# The interview tag is written both ways in the wild; the tree slot is "questionnaire".
_TAG_ALIASES = {"questionnaireinterview": "questionnaire"}


def _media_slot(media: Any) -> tuple[str, str] | None:
    """The one ``(record kind, record id)`` slot a media row belongs in, or None when unattached."""
    tag = (media.linkedRecordType or "").strip().lower()
    if tag in _MEDIA_TAG_SLOTS and media.linkedRecordId:
        return tag, media.linkedRecordId
    for fk, kind in _MEDIA_FK_SLOTS:
        rec_id = getattr(media, fk, None)
        if rec_id:
            return kind, rec_id
    if tag and media.linkedRecordId:
        return _TAG_ALIASES.get(tag, tag), media.linkedRecordId
    return None


def _details(kind: str, record: Any) -> str:
    """A record's ``details.txt`` body, straight from the shared field registry."""
    return info_text(info_panel(kind, record))


@router.get("/dataset")
async def dataset_manifest(
    ownerId: str | None = None, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Build the downloadable manifest.

    Without ``ownerId`` this is the whole repository and requires the global dataset-download
    permission. With ``ownerId`` it is scoped to one researcher's data and is authorized by tiered
    data access: an all-data DOWNLOAD+ grant yields everything that owner uploaded; a subset grant
    yields only the granted records. Admins/global downloaders/the owner always get everything.

    Response: ``{files, totalFiles, totalMedia, truncated}`` — ``truncated`` is true when any table
    hit its row cap, so the client can warn rather than present a partial zip as complete.
    """
    rec_where: dict[str, Any] = {}
    media_vis: dict[str, Any] = {}
    scope: dict[str, set[str]] | None = None
    if ownerId:
        scope = await owner_download_scope(current_user, ownerId)
        rec_where = {"createdById": ownerId}
    elif not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required. Ask an admin to grant it, or download a "
            "specific researcher's data you have access to.",
        )
    else:
        rec_where = await visibility_where(current_user)
        # Media carries its own owner column, and the repository-wide download is not a licence to
        # read uploads the caller cannot see anywhere else in the app (GET /media, /search and the
        # data browser all filter on uploadedById). Empty — a no-op — for Professor and above.
        media_vis = await visibility_where(current_user, owner_field="uploadedById")

    def _in_scope(rtype: str, rid: str) -> bool:
        return scope is None or rid in scope.get(rtype, set())

    workshops = await db.workshop.find_many(
        where=rec_where, take=EXPORT_TAKE, include=_WORKSHOP_INCLUDE
    )
    artisans = await db.artisan.find_many(
        where=rec_where, take=EXPORT_TAKE, include=_ARTISAN_INCLUDE
    )
    products = await db.productdocumentation.find_many(
        where=rec_where, take=EXPORT_TAKE, include=_PRODUCT_INCLUDE
    )
    tools = await db.tooldocumentation.find_many(
        where=rec_where, take=EXPORT_TAKE, include=_TOOL_INCLUDE
    )
    interviews = await db.questionnaireinterview.find_many(
        where=rec_where, take=EXPORT_TAKE, include=_INTERVIEW_INCLUDE
    )
    processes = await db.process.find_many(
        where=rec_where, take=EXPORT_TAKE, include=_PROCESS_INCLUDE
    )
    truncated = any(
        len(rows) >= EXPORT_TAKE
        for rows in (workshops, artisans, products, tools, interviews, processes)
    )

    if scope is not None:
        # Subset grant: keep only the explicitly granted records of each type.
        workshops = [w for w in workshops if _in_scope("workshop", w.id)]
        artisans = [a for a in artisans if _in_scope("artisan", a.id)]
        products = [p for p in products if _in_scope("product", p.id)]
        tools = [t for t in tools if _in_scope("tool", t.id)]
        interviews = [i for i in interviews if _in_scope("questionnaire", i.id)]

    # Media is fetched BY the records that will be emitted rather than by scanning the table: only
    # attached media is ever placed in the tree, so ``where={}`` was a full-table read whose extra
    # rows were all discarded. One OR query, so a row matching several conditions is deduped by id.
    media_or: list[dict[str, Any]] = []
    if ownerId:
        # An owner's dataset must also carry media that OTHER users uploaded onto the owner's
        # in-scope records, not just media the owner uploaded themselves.
        media_or.append({"uploadedById": ownerId})
    for fk, tags, rows in (
        ("artisanId", ["artisan"], artisans),
        ("productId", ["product"], products),
        ("toolId", ["tool"], tools),
        ("workshopId", ["workshop"], workshops),
        (
            "questionnaireInterviewId",
            ["questionnaire", "questionnaireinterview"],
            interviews,
        ),
    ):
        ids = [r.id for r in rows]
        if ids:
            media_or.append({fk: {"in": ids}})
            # The typed FK alone misses rows attached only by the string tag pair, and the tag pair
            # alone misses rows attached only by the FK (a NULL linkedRecordType) — which is how a
            # whole class of media used to be fetched and then never emitted. Both, always.
            media_or.append(
                {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": {"in": ids}}]}
            )
    # Processes and their steps have no FK column on MediaFile; they are tag-only attachments.
    proc_ids = [p.id for p in processes]
    step_ids = [s.id for p in processes for s in (p.steps or [])]
    for tag, ids in (("process", proc_ids), ("processstep", step_ids)):
        if ids:
            media_or.append(
                {"AND": [{"linkedRecordType": tag}, {"linkedRecordId": {"in": ids}}]}
            )
    media: list[Any] = []
    if media_or:
        media_where: dict[str, Any] = {"OR": media_or}
        if media_vis:
            media_where = {"AND": [media_where, media_vis]}
        media = await db.mediafile.find_many(where=media_where, take=MEDIA_TAKE)
        truncated = truncated or len(media) >= MEDIA_TAKE

    # Group media by the single slot it belongs in.
    media_by: dict[tuple[str, str], list[Any]] = {}
    for m in media:
        slot = _media_slot(m)
        if slot is not None:
            media_by.setdefault(slot, []).append(m)

    files: list[dict[str, str]] = []
    # Two same-named products (or two photos with the same original filename) used to resolve to
    # the same path and silently overwrite each other when the client zipped them. Folder paths and
    # file paths are de-duplicated separately: a duplicate RECORD has to move its whole subtree
    # ("Chair (2)/..."), while a duplicate FILE only renames itself.
    used_dirs: set[str] = set()
    used_files: set[str] = set()

    def add_media(prefix: str, rtype: str, rid: str) -> None:
        for m in media_by.get((rtype, rid), []):
            if m.url:
                path = _uniq(f"{prefix}/{_seg(m.originalFilename, m.id)}", used_files)
                files.append({"path": path, "url": m.url})

    def add_text(prefix: str, name: str, content: str) -> None:
        if content.strip():
            files.append({"path": _uniq(f"{prefix}/{name}", used_files), "content": content})

    artisans_by_id = {a.id: a for a in artisans}
    processes_by_product: dict[str, list[Any]] = {}
    for p in processes:
        processes_by_product.setdefault(p.productId, []).append(p)

    placed_products: set[str] = set()
    placed_tools: set[str] = set()
    placed_interviews: set[str] = set()

    def emit_product(prefix: str, product: Any) -> None:
        placed_products.add(product.id)
        base = _uniq(f"{prefix}/Products/{_seg(product.productName, product.id)}", used_dirs)
        add_text(base, "details.txt", _details("product", product))
        add_media(base, "product", product.id)
        for proc in processes_by_product.get(product.id, []):
            pbase = _uniq(f"{base}/Processes/{_seg(proc.name, proc.id)}", used_dirs)
            add_text(pbase, "details.txt", _details("process", proc))
            add_media(pbase, "process", proc.id)
            for step in (proc.steps or []):
                sbase = _uniq(f"{pbase}/{_seg(step.name, step.id)}", used_dirs)
                add_media(sbase, "processstep", step.id)

    def emit_tool(prefix: str, tool: Any) -> None:
        placed_tools.add(tool.id)
        base = _uniq(f"{prefix}/Tools/{_seg(tool.toolkitName, tool.id)}", used_dirs)
        add_text(base, "details.txt", _details("tool", tool))
        add_media(base, "tool", tool.id)

    def emit_interview(prefix: str, interview: Any) -> None:
        placed_interviews.add(interview.id)
        # An interview is identified by the artisans it covers, not its internal title — the
        # registry's title function, so the folder matches what the browser calls it.
        label = _seg(interview_label(interview), interview.id)
        base = _uniq(f"{prefix}/Questionnaires/{label}", used_dirs)
        answers = []
        for r in (interview.responses or []):
            q = getattr(r, "question", None)
            prompt = getattr(q, "prompt", r.questionId) if q else r.questionId
            code = getattr(q, "sectionCode", "") if q else ""
            answers.append(f"[{code}] {prompt}\n  -> {r.answerText or ''}\n")
        add_text(base, "answers.txt", _details("interview", interview) + "\n\n" + "".join(answers))
        add_media(base, "questionnaire", interview.id)

    interviews_for_artisan: dict[str, list[Any]] = {}
    for it in interviews:
        for link in (it.artisans or []):
            interviews_for_artisan.setdefault(link.artisanId, []).append(it)

    for ws in workshops:
        wbase = _uniq(f"Workshops/{_seg(ws.title, ws.id)}", used_dirs)
        add_text(wbase, "details.txt", _details("workshop", ws))
        add_media(wbase, "workshop", ws.id)
        ws_artisan_ids = [link.artisanId for link in (ws.artisans or [])]
        covered_craft_ids = [link.craftId for link in (ws.crafts or [])]
        # Group this workshop's artisans by their craft; fall back to a single bucket.
        craft_links = ws.crafts or []
        if not craft_links:
            craft_buckets = [("_Crafts", None, ws_artisan_ids)]
        else:
            craft_buckets = []
            for link in craft_links:
                craft = getattr(link, "craft", None)
                cname = _seg(getattr(craft, "name", None), link.craftId)
                bucket = [aid for aid in ws_artisan_ids if artisans_by_id.get(aid) and artisans_by_id[aid].craftId == link.craftId]
                craft_buckets.append((cname, link.craftId, bucket))
            # Artisans in the workshop whose craft isn't among the covered crafts.
            leftover = [aid for aid in ws_artisan_ids if not (artisans_by_id.get(aid) and artisans_by_id[aid].craftId in covered_craft_ids)]
            if leftover:
                craft_buckets.append(("_OtherCrafts", None, leftover))
        for cname, _cid, artisan_ids in craft_buckets:
            cbase = _uniq(f"{wbase}/{cname}", used_dirs)
            for aid in artisan_ids:
                artisan = artisans_by_id.get(aid)
                if not artisan:
                    continue
                abase = _uniq(f"{cbase}/{_seg(artisan.name, aid)}", used_dirs)
                add_text(abase, "details.txt", _details("artisan", artisan))
                add_media(abase, "artisan", aid)
                for product in products:
                    if product.workshopId == ws.id and product.artisanId == aid:
                        emit_product(abase, product)
                for tool in tools:
                    if tool.workshopId == ws.id and tool.artisanId == aid:
                        emit_tool(abase, tool)
                for it in interviews_for_artisan.get(aid, []):
                    emit_interview(abase, it)

    # Anything not attached to a workshop goes here so nothing is lost. The per-artisan grouping
    # prefix is deliberately NOT uniqued — every record of one artisan belongs in the same folder.
    for product in products:
        if product.id not in placed_products:
            emit_product(f"_Unlinked/{_seg(product.artisanName, 'artisan')}", product)
    for tool in tools:
        if tool.id not in placed_tools:
            emit_tool(f"_Unlinked/{_seg(tool.artisanName, 'artisan')}", tool)
    for it in interviews:
        if it.id not in placed_interviews:
            emit_interview("_Unlinked", it)

    media_count = sum(1 for f in files if "url" in f)
    return {
        "files": files,
        "totalFiles": len(files),
        "totalMedia": media_count,
        "truncated": truncated,
    }


def _require_dataset_download(current_user: Any) -> None:
    """CSV exports are full-dataset downloads — gate them exactly like /export/dataset."""
    if not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required to export CSVs. Ask an admin to grant the "
            "dataset-download permission.",
        )


# createdBy feeds the "Created by" provenance column the registry appends to every row; media and
# workshop feed the media columns and the row's workshop title.
_CSV_INCLUDE = {"media": True, "createdBy": True, "workshop": True}


@router.get("/products.csv")
async def export_products(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    records = await db.productdocumentation.find_many(
        where=await visibility_where(current_user),
        include=_CSV_INCLUDE,
        take=EXPORT_TAKE,
        order={"createdAt": "desc"},
    )
    body = records_to_csv("product", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response("products.csv", body)


@router.get("/tools.csv")
async def export_tools(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    records = await db.tooldocumentation.find_many(
        where=await visibility_where(current_user),
        include=_CSV_INCLUDE,
        take=EXPORT_TAKE,
        order={"createdAt": "desc"},
    )
    body = records_to_csv("tool", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response("tools.csv", body)
