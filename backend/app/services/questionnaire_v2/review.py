"""Review of a session (DESIGN 3.10): the approve gate and the cascade.

* Approve is REFUSED, with the reason, for: 0 participants; 0 live recordings and 0 answers; any
  NEEDS_DECISION take (409 ``APPROVE_BLOCKED`` carrying ``reasons``).
* Approving a session approves, in the same transaction, its FILED live recordings' files and its
  answer revisions. A removed take or a NEEDS_DECISION take is never approved with it.
* New content on an approved session arrives PENDING with a review item; the session stays APPROVED.
* The consent gate is NEVER consulted here.
"""

from __future__ import annotations

from typing import Any

from app.services.questionnaire_v2.common import fail


async def approve_blockers(client: Any, session_id: str) -> list[dict[str, Any]]:
    rows = await client.query_raw(
        'SELECT (SELECT count(*) FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1) AS participants, '
        '(SELECT count(*) FROM "MediaFile" m WHERE m."questionnaireInterviewId" = $1 AND m."removedAt" IS NULL) AS media, '
        '(SELECT count(*) FROM "QuestionnaireAnswerRevision" WHERE "sessionId" = $1) AS answers, '
        '(SELECT count(*) FROM "QuestionnaireResponse" WHERE "interviewId" = $1) AS responses, '
        '(SELECT count(*) FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        "   WHERE m.\"questionnaireInterviewId\" = $1 AND r.\"removedAt\" IS NULL AND r.\"filing\" = 'NEEDS_DECISION') AS undecided",
        session_id,
    )
    r = rows[0]
    reasons = []
    if int(r["participants"]) == 0:
        reasons.append({"code": "NO_PARTICIPANTS", "message": "Add who was there first."})
    if int(r["media"]) == 0 and int(r["answers"]) == 0 and int(r["responses"]) == 0:
        reasons.append({"code": "NO_CONTENT", "message": "Nothing recorded or answered yet."})
    if int(r["undecided"]):
        n = int(r["undecided"])
        reasons.append({"code": "NEEDS_DECISION", "message": f"Decide on {n} take{'s' if n != 1 else ''} first.", "count": n})
    return reasons


async def assert_may_approve(client: Any, session_id: str) -> None:
    reasons = await approve_blockers(client, session_id)
    if reasons:
        fail("APPROVE_BLOCKED", reasons[0]["message"], reasons=reasons)


async def excluded_media_ids(client: Any, session_id: str) -> set[str]:
    """Files the cascade must not approve: removed, or holding a NEEDS_DECISION take."""
    rows = await client.query_raw(
        'SELECT m."id" FROM "MediaFile" m LEFT JOIN "QuestionnaireRecording" r ON r."mediaFileId" = m."id" '
        'WHERE m."questionnaireInterviewId" = $1 AND (m."removedAt" IS NOT NULL OR r."removedAt" IS NOT NULL '
        "OR r.\"filing\" = 'NEEDS_DECISION')",
        session_id,
    )
    return {str(r["id"]) for r in rows}


async def approve_revisions(tx: Any, session_id: str) -> int:
    return int(
        await tx.execute_raw(
            'UPDATE "QuestionnaireAnswerRevision" SET "status" = \'APPROVED\' WHERE "sessionId" = $1 AND "status" = \'PENDING\'', session_id
        )
        or 0
    )
