from __future__ import annotations

import argparse
import asyncio
import json
import pathlib
import sys
from typing import Any

BACKEND = pathlib.Path(__file__).resolve().parents[1]
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

from app.core import odia_script  # noqa: E402

ODIA_LABELS = frozenset({"odia", "oriya"})

VERSIONS_SQL = (
    'SELECT v."id", v."mediaFileId", v."detectedLanguage", m."extraMetadata" '
    'FROM "TranscriptVersion" v JOIN "MediaFile" m ON m."id" = v."mediaFileId" '
    'WHERE v."supersededAt" IS NULL AND EXISTS ('
    'SELECT 1 FROM "TranscriptTurn" t WHERE t."versionId" = v."id" '
    'AND (t."machineText" ~ $1 OR COALESCE(t."editedText", \'\') ~ $1)) '
    'ORDER BY v."createdAt"'
)
TURNS_SQL = (
    'SELECT "id", "language", "machineText", "editedText", "words", "lowConfidence" '
    'FROM "TranscriptTurn" WHERE "versionId" = $1 '
    'AND ("machineText" ~ $2 OR COALESCE("editedText", \'\') ~ $2) ORDER BY "position"'
)
MEDIA_SQL = 'SELECT "transcriptText", "transcriptSummary" FROM "MediaFile" WHERE "id" = $1'
UPDATE_TURN_SQL = (
    'UPDATE "TranscriptTurn" SET "machineText" = $2, "editedText" = $3, "words" = $4::jsonb, '
    '"lowConfidence" = $5::jsonb, "language" = $6, "revision" = "revision" + 1, "updatedAt" = now() '
    'WHERE "id" = $1'
)
UPDATE_VERSION_SQL = (
    'UPDATE "TranscriptVersion" v SET "revision" = v."revision" + 1, "updatedAt" = now(), '
    '"languages" = CASE WHEN EXISTS (SELECT 1 FROM "TranscriptTurn" t WHERE t."versionId" = v."id" '
    'AND t."language" = \'hi\') THEN v."languages" ELSE ARRAY(SELECT u.x FROM unnest(array_replace('
    'v."languages", \'hi\', \'or\')) WITH ORDINALITY AS u(x, n) GROUP BY u.x ORDER BY min(u.n)) END '
    'WHERE v."id" = $1'
)
UPDATE_MEDIA_SQL = (
    'UPDATE "MediaFile" SET "transcriptText" = $2, "transcriptSummary" = $3 WHERE "id" = $1'
)


def _json(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except ValueError:
            return None
    return value


def _odia_choice(value: Any) -> bool:
    if isinstance(value, list):
        value = value[0] if len(value) == 1 else None
    if not isinstance(value, str):
        return False
    return odia_script.is_odia(value) or value.strip().casefold() in ODIA_LABELS


def version_is_odia(row: dict[str, Any]) -> bool:
    if odia_script.is_odia(row.get("detectedLanguage")):
        return True
    metadata = _json(row.get("extraMetadata"))
    if not isinstance(metadata, dict):
        return False
    return _odia_choice(metadata.get("languageHint")) or _odia_choice(metadata.get("spokenLanguage"))


def convert_turn(row: dict[str, Any]) -> dict[str, Any]:
    machine = str(row.get("machineText") or "")
    converted, positions = odia_script.to_odia_mapped(machine)
    edited = row.get("editedText")
    language = row.get("language")
    return {
        "id": row["id"],
        "machineText": converted,
        "editedText": odia_script.to_odia(edited) if isinstance(edited, str) else edited,
        "words": odia_script.remap_words(_json(row.get("words")), positions),
        "lowConfidence": odia_script.remap_spans(_json(row.get("lowConfidence")), positions),
        "language": "or" if language in (None, "", "hi", "und") else language,
    }


async def run(apply: bool, client: Any = None) -> dict[str, int]:
    from app.core.db import db

    client = client or db
    pattern = odia_script.DEVANAGARI_PATTERN
    counts = {"versions": 0, "turns": 0, "edited": 0, "media": 0, "skipped": 0}
    versions = await client.query_raw(VERSIONS_SQL, pattern)
    for version in versions:
        if not version_is_odia(version):
            counts["skipped"] += 1
            continue
        turns = await client.query_raw(TURNS_SQL, version["id"], pattern)
        if not turns:
            continue
        converted = [convert_turn(t) for t in turns]
        counts["versions"] += 1
        counts["turns"] += len(converted)
        counts["edited"] += sum(1 for t in turns if odia_script.has_devanagari(t.get("editedText")))
        media_rows = await client.query_raw(MEDIA_SQL, version["mediaFileId"])
        media = media_rows[0] if media_rows else {}
        media_changes = any(
            odia_script.has_devanagari(media.get(k)) for k in ("transcriptText", "transcriptSummary")
        )
        counts["media"] += 1 if media_changes else 0
        if not apply:
            continue
        async with client.tx() as tx:
            for turn in converted:
                await tx.execute_raw(
                    UPDATE_TURN_SQL,
                    turn["id"],
                    turn["machineText"],
                    turn["editedText"],
                    json.dumps(turn["words"] if turn["words"] is not None else [], ensure_ascii=False),
                    json.dumps(
                        turn["lowConfidence"] if turn["lowConfidence"] is not None else [], ensure_ascii=False
                    ),
                    turn["language"],
                )
            await tx.execute_raw(UPDATE_VERSION_SQL, version["id"])
            if media_changes:
                await tx.execute_raw(
                    UPDATE_MEDIA_SQL,
                    version["mediaFileId"],
                    *(
                        odia_script.to_odia(media[k]) if isinstance(media.get(k), str) else media.get(k)
                        for k in ("transcriptText", "transcriptSummary")
                    ),
                )
    return counts


async def _main(apply: bool) -> int:
    from app.core.db import connect_db, disconnect_db

    await connect_db()
    try:
        counts = await run(apply)
    finally:
        await disconnect_db()
    print(
        f"Odia transcripts in Devanagari: {counts['versions']} versions, {counts['turns']} turns "
        f"({counts['edited']} with edits), {counts['media']} full transcripts; "
        f"{counts['skipped']} non-Odia versions left alone."
    )
    print("Converted to Odia script." if apply else "Dry run: nothing was changed. Run with --apply to convert.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Rewrite Odia transcripts stored in Devanagari into Odia script.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="count only (the default)")
    mode.add_argument("--apply", action="store_true", help="convert the turns and full transcripts")
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    return asyncio.run(_main(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
