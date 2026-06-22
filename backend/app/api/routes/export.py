import re
from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import get_current_user, require_dataset_downloader
from app.services.csv_export import PRODUCT_FIELDS, TOOL_FIELDS, records_to_csv
from app.services.records import visibility_where

router = APIRouter(prefix="/export", tags=["export"])


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
# ---------------------------------------------------------------------------

_SAFE = re.compile(r"[^A-Za-z0-9 _.\-]+")


def _seg(value: str | None, fallback: str) -> str:
    cleaned = _SAFE.sub("_", (value or "").strip()).strip(" .")
    return cleaned[:80] if cleaned else fallback


def _details(pairs: list[tuple[str, Any]]) -> str:
    return "\n".join(f"{k}: {v}" for k, v in pairs if v not in (None, "", []))


@router.get("/dataset")
async def dataset_manifest(current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    vis = visibility_where(current_user)
    workshops = await db.workshop.find_many(
        where=vis,
        include={"crafts": {"include": {"craft": True}}, "artisans": {"include": {"artisan": True}}},
    )
    artisans = await db.artisan.find_many(where=vis, include={"craft": True})
    products = await db.productdocumentation.find_many(where=vis)
    tools = await db.tooldocumentation.find_many(where=vis)
    interviews = await db.questionnaireinterview.find_many(
        where=vis,
        include={"artisans": True, "responses": {"include": {"question": True}}},
    )
    processes = await db.process.find_many(where=vis, include={"steps": True})
    media = await db.mediafile.find_many()

    # Group media by (lower record type, record id).
    media_by: dict[tuple[str, str], list[Any]] = {}
    for m in media:
        if m.linkedRecordType and m.linkedRecordId:
            media_by.setdefault((m.linkedRecordType.lower(), m.linkedRecordId), []).append(m)

    files: list[dict[str, str]] = []

    def add_media(prefix: str, rtype: str, rid: str) -> None:
        for m in media_by.get((rtype, rid), []):
            if m.url:
                files.append({"path": f"{prefix}/{_seg(m.originalFilename, m.id)}", "url": m.url})

    def add_text(prefix: str, name: str, content: str) -> None:
        if content.strip():
            files.append({"path": f"{prefix}/{name}", "content": content})

    artisans_by_id = {a.id: a for a in artisans}
    processes_by_product: dict[str, list[Any]] = {}
    for p in processes:
        processes_by_product.setdefault(p.productId, []).append(p)

    placed_products: set[str] = set()
    placed_tools: set[str] = set()
    placed_interviews: set[str] = set()

    def emit_product(prefix: str, product: Any) -> None:
        placed_products.add(product.id)
        base = f"{prefix}/Products/{_seg(product.productName, product.id)}"
        add_text(base, "details.txt", _details([
            ("Product", product.productName), ("Local name", product.localName),
            ("Craft", product.craftName), ("Artisan", product.artisanName),
            ("Place", product.place), ("Type", product.productType),
            ("Selling price", product.sellingPrice), ("Remarks", product.remarks),
        ]))
        add_media(base, "product", product.id)
        for proc in processes_by_product.get(product.id, []):
            pbase = f"{base}/Processes/{_seg(proc.name, proc.id)}"
            add_text(pbase, "details.txt", _details([("Process", proc.name), ("Notes", proc.notes)]))
            add_media(pbase, "process", proc.id)
            for step in (proc.steps or []):
                sbase = f"{pbase}/{_seg(step.name, step.id)}"
                add_media(sbase, "processstep", step.id)

    def emit_tool(prefix: str, tool: Any) -> None:
        placed_tools.add(tool.id)
        base = f"{prefix}/Tools/{_seg(tool.toolkitName, tool.id)}"
        add_text(base, "details.txt", _details([
            ("Tool", tool.toolkitName), ("English name", tool.englishName),
            ("Craft", tool.craftName), ("Artisan", tool.artisanName),
            ("Material", tool.material), ("Remarks", tool.remarks),
        ]))
        add_media(base, "tool", tool.id)

    def emit_interview(prefix: str, interview: Any) -> None:
        placed_interviews.add(interview.id)
        base = f"{prefix}/Questionnaires/{_seg(interview.title, interview.id)}"
        answers = []
        for r in (interview.responses or []):
            q = getattr(r, "question", None)
            prompt = getattr(q, "prompt", r.questionId) if q else r.questionId
            code = getattr(q, "sectionCode", "") if q else ""
            answers.append(f"[{code}] {prompt}\n  -> {r.answerText or ''}\n")
        add_text(base, "answers.txt", _details([("Interview", interview.title), ("Place", interview.place)]) + "\n\n" + "".join(answers))
        add_media(base, "questionnaire", interview.id)

    interviews_for_artisan: dict[str, list[Any]] = {}
    for it in interviews:
        for link in (it.artisans or []):
            interviews_for_artisan.setdefault(link.artisanId, []).append(it)

    for ws in workshops:
        wbase = f"Workshops/{_seg(ws.title, ws.id)}"
        add_text(wbase, "details.txt", _details([
            ("Workshop", ws.title), ("Place", ws.place), ("Description", ws.description),
        ]))
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
            cbase = f"{wbase}/{cname}"
            for aid in artisan_ids:
                artisan = artisans_by_id.get(aid)
                if not artisan:
                    continue
                abase = f"{cbase}/{_seg(artisan.name, aid)}"
                add_media(abase, "artisan", aid)
                for product in products:
                    if product.workshopId == ws.id and product.artisanId == aid:
                        emit_product(abase, product)
                for tool in tools:
                    if tool.workshopId == ws.id and tool.artisanId == aid:
                        emit_tool(abase, tool)
                for it in interviews_for_artisan.get(aid, []):
                    emit_interview(abase, it)

    # Anything not attached to a workshop goes here so nothing is lost.
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
    return {"files": files, "totalFiles": len(files), "totalMedia": media_count}


@router.get("/products.csv")
async def export_products(current_user: Any = Depends(get_current_user)) -> Response:
    records = await db.productdocumentation.find_many(
        where=visibility_where(current_user),
        include={"media": True},
        order={"createdAt": "desc"},
    )
    return csv_response("products.csv", records_to_csv(records, PRODUCT_FIELDS))


@router.get("/tools.csv")
async def export_tools(current_user: Any = Depends(get_current_user)) -> Response:
    records = await db.tooldocumentation.find_many(
        where=visibility_where(current_user),
        include={"media": True},
        order={"createdAt": "desc"},
    )
    return csv_response("tools.csv", records_to_csv(records, TOOL_FIELDS))
