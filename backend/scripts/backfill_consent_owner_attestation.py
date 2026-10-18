"""Record consent for every recording that already exists (owner attestation, 18 Oct 2026).

THE OWNER'S WORDS: consent exists for all existing recordings, including those still waiting to be
transcribed; backfill consent records for them, attested by the owner on 18 Oct 2026, source
'owner attestation', and do not flag them.

WHAT IT WRITES: one ``RecordingConsent`` row (GRANTED, ``source = 'owner attestation'``,
``attestedAt = 2026-10-18``, the owner as ``attestedById``, the attestation note) for every AUDIO
``MediaFile`` that has no consent row of its own and whose artisan has none either. Designer-portal's
twin (``scripts/backfill_consent_owner_attestation.py`` there) does the same per workshop.

WHAT IT NEVER DOES: create a transcription job, call a provider, overwrite an answer a person
recorded, or flag a recording. The queue is paused anyway.

IDEMPOTENT: a second run finds nothing to do, because every recording it covered now has a row.

USAGE (from backend/; ``DATABASE_URL`` comes from ``backend/.env``. NEVER point this at production
for a dry run you do not mean: the dry run reads only, the ``--fix`` run writes):

    python scripts/backfill_consent_owner_attestation.py                        # DRY RUN
    python scripts/backfill_consent_owner_attestation.py --fix --owner-email owner@example.org
"""

from __future__ import annotations

import argparse
import asyncio
import pathlib
import sys
from datetime import UTC, datetime

BACKEND = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

from app.core.db import connect_db, db, disconnect_db  # noqa: E402
from app.transcription.consent import (  # noqa: E402
    GRANTED,
    OWNER_ATTESTATION_NOTE,
    OWNER_ATTESTATION_SOURCE,
)

ATTESTED_AT = datetime(2026, 10, 18, 6, 30, tzinfo=UTC)
PAGE = 500


async def recordings_without_consent() -> list[str]:
    """AUDIO recordings with no consent row of their own and none for their artisan."""
    covered_media = {
        row.mediaFileId
        for row in await db.recordingconsent.find_many(where={"mediaFileId": {"not": None}})
    }
    covered_artisans = {
        row.artisanId
        for row in await db.recordingconsent.find_many(where={"artisanId": {"not": None}, "mediaFileId": None})
    }
    missing: list[str] = []
    skip = 0
    while True:
        page = await db.mediafile.find_many(
            where={"mediaType": "AUDIO"}, order={"createdAt": "asc"}, skip=skip, take=PAGE
        )
        if not page:
            break
        for media in page:
            if media.id in covered_media:
                continue
            if media.artisanId and media.artisanId in covered_artisans:
                continue
            missing.append(media.id)
        skip += len(page)
    return missing


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fix", "--execute", action="store_true", help="apply (default: dry run)")
    parser.add_argument("--owner-email", help="the owner's account, recorded as the attesting person")
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    await connect_db()
    try:
        owner_id = None
        if args.fix:
            if not args.owner_email:
                print("--fix needs --owner-email: the row must name who attested.", file=sys.stderr)
                return 2
            owner = await db.user.find_first(where={"email": args.owner_email.strip().lower()})
            if owner is None:
                print(f"No account for {args.owner_email}.", file=sys.stderr)
                return 2
            owner_id = owner.id
        missing = await recordings_without_consent()
        if args.fix and owner_id:
            for start in range(0, len(missing), PAGE):
                await db.recordingconsent.create_many(
                    data=[
                        {
                            "mediaFileId": media_id,
                            "decision": GRANTED,
                            "source": OWNER_ATTESTATION_SOURCE,
                            "attestedById": owner_id,
                            "attestedAt": ATTESTED_AT,
                            "note": OWNER_ATTESTATION_NOTE,
                        }
                        for media_id in missing[start : start + PAGE]
                    ]
                )
        verb = "Recorded" if args.fix else "Would record"
        print(f"{verb} consent (owner attestation, 18 Oct 2026) for {len(missing)} recording(s).")
        print("No transcription job was created and no provider was called.")
    finally:
        await disconnect_db()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
