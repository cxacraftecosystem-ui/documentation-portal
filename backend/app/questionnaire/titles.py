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

from app.questionnaire.ports import naming
from app.questionnaire.common import as_date


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
        'SELECT "sessionDate", "place", "workshopId" FROM "QuestionnaireInterview" WHERE "id" = $1', session_id
    )
    row = rows[0] if rows else {}
    return naming.TitleInput(
        sessionDate=as_date(row.get("sessionDate")),
        place=row.get("place"),
        workshop=await workshop_fields(client, row.get("workshopId")),
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
        'SELECT "id", "sessionDate", "place", "workshopId" FROM "QuestionnaireInterview" WHERE "id" = ANY($1::text[])', sids
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
        out[sid] = naming.TitleInput(
            sessionDate=as_date(row.get("sessionDate")),
            place=row.get("place"),
            workshop=workshops.get(str(wid)) if wid else None,
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
