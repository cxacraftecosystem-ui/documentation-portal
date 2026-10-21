"""Questionnaire v2 rows in an artisan merge (DESIGN 3.8): participants are moved by the base merge;
this moves recording subjects, canonical answers, take drafts and consent rows, in one transaction.

On an answer clash (both artisans answered one lineage) the PINNED answer wins, otherwise the one
revised most recently; the other's revisions are kept under the winner, so nothing is lost and the
history reads as one.
"""

from __future__ import annotations

from datetime import timedelta
from typing import Any

TX_TIMEOUT = timedelta(seconds=60)
TX_MAX_WAIT = timedelta(seconds=15)


async def move_rows(tx: Any, keep_id: str, merge_id: str) -> dict[str, int]:
    counts = {"subjects": 0, "answers": 0, "answerClashes": 0, "drafts": 0, "consent": 0}
    # Subjects: where both are on one recording, the survivor keeps the stronger role.
    await tx.execute_raw(
        'UPDATE "QuestionnaireRecordingSubject" k SET "role" = \'ANSWERED\' FROM "QuestionnaireRecordingSubject" m '
        "WHERE k.\"recordingId\" = m.\"recordingId\" AND k.\"artisanId\" = $1 AND m.\"artisanId\" = $2 AND m.\"role\" = 'ANSWERED'",
        keep_id, merge_id,
    )
    await tx.execute_raw(
        'DELETE FROM "QuestionnaireRecordingSubject" m USING "QuestionnaireRecordingSubject" k '
        'WHERE m."recordingId" = k."recordingId" AND m."artisanId" = $2 AND k."artisanId" = $1',
        keep_id, merge_id,
    )
    counts["subjects"] = int(await tx.execute_raw(
        'UPDATE "QuestionnaireRecordingSubject" SET "artisanId" = $1 WHERE "artisanId" = $2', keep_id, merge_id
    ) or 0)
    # Answers: clash per lineage.
    clashes = await tx.query_raw(
        'SELECT k."id" AS keep_answer, m."id" AS merge_answer, k."pinned" AS kp, m."pinned" AS mp, '
        'kr."createdAt" AS kat, mr."createdAt" AS mat FROM "QuestionnaireAnswer" k JOIN "QuestionnaireAnswer" m '
        'ON m."questionLineageId" = k."questionLineageId" AND m."artisanId" = $2 '
        'LEFT JOIN "QuestionnaireAnswerRevision" kr ON kr."id" = k."currentRevisionId" '
        'LEFT JOIN "QuestionnaireAnswerRevision" mr ON mr."id" = m."currentRevisionId" WHERE k."artisanId" = $1',
        keep_id, merge_id,
    )
    for c in clashes:
        keep_wins = bool(c["kp"]) or (not c["mp"] and str(c.get("kat") or "") >= str(c.get("mat") or ""))
        winner, loser = (c["keep_answer"], c["merge_answer"]) if keep_wins else (c["merge_answer"], c["keep_answer"])
        await tx.execute_raw('UPDATE "QuestionnaireAnswerRevision" SET "answerId" = $1 WHERE "answerId" = $2', winner, loser)
        await tx.execute_raw('DELETE FROM "QuestionnaireAnswer" WHERE "id" = $1', loser)
        await tx.execute_raw(
            'UPDATE "QuestionnaireAnswer" SET "artisanId" = $1, "version" = "version" + 1, "updatedAt" = now() WHERE "id" = $2',
            keep_id, winner,
        )
        counts["answerClashes"] += 1
    counts["answers"] = int(await tx.execute_raw(
        'UPDATE "QuestionnaireAnswer" SET "artisanId" = $1, "updatedAt" = now() WHERE "artisanId" = $2', keep_id, merge_id
    ) or 0)
    await tx.execute_raw(
        'DELETE FROM "QuestionnaireTakeDraft" m USING "QuestionnaireTakeDraft" k WHERE m."artisanId" = $2 AND k."artisanId" = $1 '
        'AND k."recordingId" = m."recordingId" AND k."questionLineageId" = m."questionLineageId"',
        keep_id, merge_id,
    )
    counts["drafts"] = int(await tx.execute_raw('UPDATE "QuestionnaireTakeDraft" SET "artisanId" = $1 WHERE "artisanId" = $2', keep_id, merge_id) or 0)
    # Consent: the ledger follows the person; an identical row (same source and moment) folds.
    await tx.execute_raw(
        'DELETE FROM "Consent" m USING "Consent" k WHERE m."subjectType" = \'ARTISAN\' AND k."subjectType" = \'ARTISAN\' '
        'AND m."subjectId" = $2 AND k."subjectId" = $1 AND k."purpose" = m."purpose" AND k."source" = m."source" AND k."recordedAt" = m."recordedAt"',
        keep_id, merge_id,
    )
    counts["consent"] = int(await tx.execute_raw(
        'UPDATE "Consent" SET "subjectId" = $1 WHERE "subjectType" = \'ARTISAN\' AND "subjectId" = $2', keep_id, merge_id
    ) or 0)
    await tx.execute_raw(
        'UPDATE "QuestionnaireRecording" r SET "changeSeq" = nextval(\'questionnaire_change_seq\') '
        'WHERE EXISTS (SELECT 1 FROM "QuestionnaireRecordingSubject" s WHERE s."recordingId" = r."id" AND s."artisanId" = $1)',
        keep_id,
    )
    return counts


async def merge_and_delete(keep_id: str, merge_id: str, *, client: Any = None, merge_record_id: str | None = None,
                           done_data: dict[str, Any] | None = None) -> Any:
    """The v2 rows move, the merged artisan is deleted and the merge is marked done, in ONE
    transaction (DESIGN 3.8; audit M24). Returns the updated ``ArtisanMerge`` row (or None)."""
    from app.core.db import db

    owner = client or db
    if not callable(getattr(type(owner), "query_raw", None)):
        # The artisan-merge suite's in-memory stand-in has no SQL and no transactions.
        await owner.artisan.delete(where={"id": merge_id})
        if merge_record_id and done_data is not None:
            return await owner.artisanmerge.update(where={"id": merge_record_id}, data=done_data)
        return None
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        await move_rows(tx, keep_id, merge_id)
        await tx.artisan.delete(where={"id": merge_id})
        if merge_record_id and done_data is not None:
            return await tx.artisanmerge.update(where={"id": merge_record_id}, data=done_data)
    return None


async def merge_artisan_rows(keep_id: str, merge_id: str, *, client: Any = None) -> dict[str, int]:
    from app.core.db import db

    owner = client or db
    if not callable(getattr(type(owner), "query_raw", None)):
        # The artisan-merge suite runs on an in-memory stand-in with no SQL and no v2 tables;
        # tests/test_questionnaire_v2_merge.py runs this against Postgres.
        return {}
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        return await move_rows(tx, keep_id, merge_id)
