from __future__ import annotations

import asyncio
import os
import sys
from datetime import UTC, datetime
from urllib.parse import urlsplit

HERE = os.getcwd()
sys.path.insert(0, HERE)

LINES = (
    (0, "speaker_0", "ନୂଆ ଶିଖାଳିଙ୍କ ପାଇଁ କେଉଁ ସ୍ତର ସବୁଠାରୁ କଷ୍ଟକର?", "Which stage is the hardest for a beginner to learn?"),
    (9_000, "speaker_1", "ଛେଣିରେ ମୋଟାମୋଟି କାଟିବା। ହାତ ସ୍ଥିର ନ ରହିଲେ ପଥର ଫାଟିଯାଏ।", "The rough cutting with the chisel. If the hand is not steady, the stone cracks."),
    (42_000, "speaker_0", "ପଥରକୁ ଆଗରୁ କାହିଁକି ଭିଜାନ୍ତି?", "Why do you soak the stone first?"),
    (50_000, "speaker_1", "ରାତିସାରା ପାଣିରେ ଭିଜାଉ। ପଥର ନରମ ହୁଏ, ଛେଣି ସଫା ଚାଲେ।", "We keep it in water overnight. The stone softens and the chisel moves cleanly."),
    (96_000, "speaker_0", "ଆଉ ଶେଷରେ ଘଷିବା?", "And the polishing at the end?"),
    (104_000, "speaker_1", "ଓଦା ବାଲିରେ କିଛି ଦିନ ଘଷୁ। ନୂଆ ଶିଖାଳିଙ୍କୁ ଏଥିରେ ବର୍ଷେ ଲାଗେ।", "We rub it with wet sand for days. A new learner needs a year to get an even finish."),
    (150_000, "speaker_0", "ଏହା କେତେ ଦିନ ଲାଗେ?", "How many days does it take?"),
    (158_000, "speaker_1", "ବଡ଼ ମୂର୍ତ୍ତି ପାଇଁ ପ୍ରାୟ ଏକ ମାସ।", "About a month for a large figure."),
)

SPEAKERS = (("speaker_0", "Interviewer", "RESEARCHER"), ("speaker_1", "Meera Devi", "ARTISAN"))


def _refuse_remote() -> None:
    host = (urlsplit(os.environ.get("DATABASE_URL", "")).hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("seed_take_transcripts.py writes; it runs only against a loopback CI database.")


def _lines(duration_ms: int) -> list[tuple[int, int, str, str, str]]:
    scale = duration_ms / 192_000 if duration_ms > 0 else 1
    starts = [round(start * scale) for start, *_ in LINES]
    ends = [*(s - 1 for s in starts[1:]), max(duration_ms, starts[-1] + 1)]
    return [(starts[i], ends[i], key, text, english) for i, (_, key, text, english) in enumerate(LINES)]


async def seed(media_ids: list[str]) -> None:
    _refuse_remote()
    import app  # noqa: F401
    from app.core.db import db
    from app.services import transcript_store
    from app.transcription.transcript.turns import Turn

    await db.connect()
    try:
        for media_id in media_ids:
            media = await db.mediafile.find_unique(where={"id": media_id})
            if media is None:
                raise SystemExit(f"no media {media_id}")
            rec = await db.questionnairerecording.find_first(where={"mediaFileId": media_id})
            duration = int(getattr(rec, "durationMs", 0) or 0) or 192_000
            lines = _lines(duration)
            await db.mediaprocessingjob.delete_many(where={"mediaFileId": media_id, "jobType": "TRANSCRIPTION"})
            turns = [Turn(index=i, speaker_key=key, start_ms=start, end_ms=end, language="or", text=text) for i, (start, end, key, text, _) in enumerate(lines)]
            version = await transcript_store.create_version(media_id, turns, stt_provider="ci", stt_model=None, detected="or", duration_ms=duration)
            for i, (*_, english) in enumerate(lines):
                await db.transcriptturn.update_many(
                    where={"versionId": version.id, "position": i},
                    data={"machineEnglish": english, "translationStatus": "TRANSLATED"},
                )
            await db.transcriptversion.update(where={"id": version.id}, data={"translationStatus": "COMPLETED", "translatedAt": datetime.now(UTC)})
            for key, name, role in SPEAKERS:
                await db.transcriptspeaker.upsert(
                    where={"mediaFileId_speakerKey": {"mediaFileId": media_id, "speakerKey": key}},
                    data={"create": {"mediaFileId": media_id, "speakerKey": key, "name": name, "role": role}, "update": {"name": name, "role": role}},
                )
            await db.mediafile.update(
                where={"id": media_id},
                data={"transcriptStatus": "COMPLETED", "transcriptText": "\n".join(t.text for t in turns), "transcriptLanguage": "or", "transcriptProvider": "ci"},
            )
    finally:
        await db.disconnect()
    print(f"gave {len(media_ids)} takes a transcript")


if __name__ == "__main__":
    asyncio.run(seed(sys.argv[1:]))
