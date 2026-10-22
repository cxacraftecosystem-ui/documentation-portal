"""The inputs of a generated session title or recording name, read from the database.

``naming.session_title`` and ``naming.recording_filename`` are pure; this module is the one place
that knows where their fields live in this schema:

* Place: the session ``place``; else the workshop's location ``village`` + ``district``, else
  ``district``, else ``state``; else the workshop's free ``place`` text.
* Craft: the workshop's craft when it has exactly one; else the craft every participant shares.
* Subject: the participants' names (``name``, with ``localName`` for file names when it is ASCII).

``regenerate_title`` rewrites ``title`` inside the caller's transaction, and every change to
participants, date, place or workshop calls it (DESIGN 3.12, E33).
"""

from __future__ import annotations

from typing import Any

from app.questionnaire.common import as_date
from app.questionnaire.ports import naming


async def workshop_fields(client: Any, workshop_id: str | None) -> dict[str, Any] | None:
    if not workshop_id:
        return None
    rows = await client.query_raw(
        'SELECT w."place", l."village", l."district", l."state", '
        '(SELECT array_agg(c."name" ORDER BY c."name") FROM "WorkshopCraft" wc JOIN "Craft" c ON c."id" = wc."craftId" '
        ' WHERE wc."workshopId" = w."id") AS crafts '
        'FROM "Workshop" w LEFT JOIN "Location" l ON l."id" = w."locationId" WHERE w."id" = $1',
        workshop_id,
    )
    if not rows:
        return None
    return _workshop_shape(rows[0])


#: A design workshop (designer-portal) as the title's workshop half: its cluster (else its venue) is
#: the locality, with its district, state and craft (UNIFY B3: "the title port gives DR's Place and
#: Craft from the design workshop"). A table documentation-portal does not have is never read there:
#: its sessions carry no designWorkshopId.
_DESIGN_WORKSHOP_SQL = (
    'SELECT d."id", COALESCE(NULLIF(btrim(d."clusterName"), \'\'), NULLIF(btrim(d."venue"), \'\')) AS "village", '
    'd."district", d."state", d."venue" AS "place", '
    'CASE WHEN d."craftName" IS NULL OR btrim(d."craftName") = \'\' THEN NULL ELSE ARRAY[d."craftName"] END AS crafts '
    'FROM "DesignWorkshop" d WHERE d."id" = ANY($1::text[])'
)


async def design_workshop_fields(client: Any, design_workshop_ids: list[str]) -> dict[str, dict[str, Any]]:
    ids = sorted({str(i) for i in design_workshop_ids if i})
    if not ids:
        return {}
    return {str(r["id"]): _workshop_shape(r) for r in await client.query_raw(_DESIGN_WORKSHOP_SQL, ids)}


async def participant_people(client: Any, session_id: str) -> list[naming.Person]:
    rows = await client.query_raw(
        'SELECT a."name", a."localName", c."name" AS craft FROM "QuestionnaireInterviewArtisan" qa '
        'JOIN "Artisan" a ON a."id" = qa."artisanId" LEFT JOIN "Craft" c ON c."id" = a."craftId" '
        'WHERE qa."interviewId" = $1 ORDER BY qa."sortOrder", qa."createdAt", a."id"',
        session_id,
    )
    return [naming.Person(name=r.get("name"), localName=r.get("localName"), craft=r.get("craft")) for r in rows]


async def title_input(client: Any, session_id: str) -> naming.TitleInput:
    rows = await client.query_raw(
        'SELECT "sessionDate", "place", "workshopId", "designWorkshopId" FROM "QuestionnaireInterview" WHERE "id" = $1', session_id
    )
    row = rows[0] if rows else {}
    workshop = await workshop_fields(client, row.get("workshopId"))
    if workshop is None and row.get("designWorkshopId"):
        workshop = (await design_workshop_fields(client, [row["designWorkshopId"]])).get(str(row["designWorkshopId"]))
    return naming.TitleInput(
        sessionDate=as_date(row.get("sessionDate")),
        place=row.get("place"),
        workshop=workshop,
        participants=await participant_people(client, session_id),
    )


def _workshop_shape(row: dict[str, Any]) -> dict[str, Any]:
    crafts = [c for c in (row.get("crafts") or []) if c]
    locality = row.get("village") or (None if (row.get("district") or row.get("state")) else row.get("place"))
    return {
        "locality": locality,
        "district": row.get("district"),
        "state": row.get("state"),
        "craft": crafts[0] if len(crafts) == 1 else None,
    }


async def title_inputs(client: Any, session_ids: list[str]) -> dict[str, naming.TitleInput]:
    """:func:`title_input` for many sessions, keyed by session id, in at most three reads.

    The same fields from the same rows as asking one session at a time; a list of recordings
    spread over many sessions no longer pays three round trips per session to name its files.
    """
    sids = list(dict.fromkeys(str(s) for s in session_ids if s))
    if not sids:
        return {}
    rows = await client.query_raw(
        'SELECT "id", "sessionDate", "place", "workshopId", "designWorkshopId" FROM "QuestionnaireInterview" WHERE "id" = ANY($1::text[])', sids
    )
    by_session = {str(r["id"]): r for r in rows}
    wids = sorted({str(r["workshopId"]) for r in rows if r.get("workshopId")})
    workshops: dict[str, dict[str, Any]] = {}
    if wids:
        for w in await client.query_raw(
            'SELECT w."id", w."place", l."village", l."district", l."state", '
            '(SELECT array_agg(c."name" ORDER BY c."name") FROM "WorkshopCraft" wc JOIN "Craft" c ON c."id" = wc."craftId" '
            ' WHERE wc."workshopId" = w."id") AS crafts '
            'FROM "Workshop" w LEFT JOIN "Location" l ON l."id" = w."locationId" WHERE w."id" = ANY($1::text[])',
            wids,
        ):
            workshops[str(w["id"])] = _workshop_shape(w)
    design = await design_workshop_fields(client, [str(r["designWorkshopId"]) for r in rows if r.get("designWorkshopId") and not r.get("workshopId")])
    people: dict[str, list[naming.Person]] = {sid: [] for sid in sids}
    for r in await client.query_raw(
        'SELECT qa."interviewId" AS sid, a."name", a."localName", c."name" AS craft FROM "QuestionnaireInterviewArtisan" qa '
        'JOIN "Artisan" a ON a."id" = qa."artisanId" LEFT JOIN "Craft" c ON c."id" = a."craftId" '
        'WHERE qa."interviewId" = ANY($1::text[]) ORDER BY qa."sortOrder", qa."createdAt", a."id"',
        sids,
    ):
        people[str(r["sid"])].append(naming.Person(name=r.get("name"), localName=r.get("localName"), craft=r.get("craft")))
    out: dict[str, naming.TitleInput] = {}
    for sid in sids:
        row = by_session.get(sid, {})
        wid = row.get("workshopId")
        dwid = row.get("designWorkshopId")
        out[sid] = naming.TitleInput(
            sessionDate=as_date(row.get("sessionDate")),
            place=row.get("place"),
            workshop=workshops.get(str(wid)) if wid else design.get(str(dwid)) if dwid else None,
            participants=people[sid],
        )
    return out


async def regenerate_title(client: Any, session_id: str) -> str:
    """Compute and store the generated title (inside the caller's transaction)."""
    title = naming.session_title(await title_input(client, session_id))
    await client.execute_raw('UPDATE "QuestionnaireInterview" SET "title" = $1 WHERE "id" = $2', title, session_id)
    return title


async def preview_title(
    client: Any, *, session_date: Any, place: str | None, workshop_id: str | None, artisan_ids: list[str]
) -> str:
    """The title a session WOULD get, before it exists (the start sheet's preview)."""
    people: list[naming.Person] = []
    if artisan_ids:
        rows = await client.query_raw(
            'SELECT a."id", a."name", a."localName", c."name" AS craft FROM "Artisan" a '
            'LEFT JOIN "Craft" c ON c."id" = a."craftId" WHERE a."id" = ANY($1::text[])',
            artisan_ids,
        )
        by_id = {str(r["id"]): r for r in rows}
        people = [
            naming.Person(name=by_id[a].get("name"), localName=by_id[a].get("localName"), craft=by_id[a].get("craft"))
            for a in artisan_ids if a in by_id
        ]
    return naming.session_title(
        naming.TitleInput(
            sessionDate=as_date(session_date), place=place,
            workshop=await workshop_fields(client, workshop_id), participants=people,
        )
    )
