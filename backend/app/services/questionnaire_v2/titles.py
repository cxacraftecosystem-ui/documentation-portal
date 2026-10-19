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

from app.services import naming
from app.services.questionnaire_v2.common import as_date


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
    row = rows[0]
    crafts = [c for c in (row.get("crafts") or []) if c]
    locality = row.get("village") or (None if (row.get("district") or row.get("state")) else row.get("place"))
    return {
        "locality": locality,
        "district": row.get("district"),
        "state": row.get("state"),
        "craft": crafts[0] if len(crafts) == 1 else None,
    }


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
