"""Stitch the parts of one recording back into one transcript.

Speakers are NOT the same people across parts: each part was diarized on its own, so "Speaker 1"
in part 2 need not be "Speaker 1" in part 1. Each part keeps its own numbering, segment speakers
are written ``<part>.<speaker>`` and the result carries a note that says so.
"""

from __future__ import annotations

from collections import Counter

from app.transcription.audio import PartPlan
from app.transcription.contract import Segment, SttResult

PER_PART_NOTE = "Speakers are numbered separately in each 10-minute part."


def _local_numbers(segments: list[Segment]) -> dict[str | None, int]:
    order: list[str | None] = []
    for segment in segments:
        if segment.speaker is not None and segment.speaker not in order:
            order.append(segment.speaker)
    return {speaker: index + 1 for index, speaker in enumerate(order)}


def stitch(parts: list[tuple[PartPlan, SttResult]]) -> SttResult:
    if not parts:
        raise ValueError("nothing to stitch")
    if len(parts) == 1:
        return parts[0][1]
    ordered = sorted(parts, key=lambda pair: pair[0].index)
    texts: list[str] = []
    segments: list[Segment] = []
    languages: Counter[str] = Counter()
    speakers = 0
    diarized = False
    for plan, result in ordered:
        if result.text.strip():
            texts.append(result.text.strip())
        if result.language:
            languages[result.language] += 1
        speakers = max(speakers, result.speakers)
        diarized = diarized or result.speakers > 1
        numbers = _local_numbers(result.segments)
        for segment in result.segments:
            start = plan.start_ms + segment.start_ms
            end = plan.start_ms + segment.end_ms
            # The overlap at the head of this part was already heard at the tail of the last one.
            if plan.index and end <= plan.own_start_ms:
                continue
            label = (
                None if segment.speaker is None else f"{plan.index + 1}.{numbers[segment.speaker]}"
            )
            segments.append(Segment(start, end, label, segment.text))
    first = ordered[0][1]
    text = "\n\n".join(texts)
    return SttResult(
        status="COMPLETED" if text else "EMPTY",
        text=text,
        provider=first.provider,
        model=first.model,
        segments=segments,
        language=languages.most_common(1)[0][0] if languages else None,
        audio_ms=ordered[-1][0].end_ms,
        speakers=speakers,
        degraded=any(result.degraded for _, result in ordered),
        chunks=len(ordered),
        note=PER_PART_NOTE if diarized else None,
    )
