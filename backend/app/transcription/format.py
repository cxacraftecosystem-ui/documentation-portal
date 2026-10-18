"""Speaker turns as the markdown both portals' exporters recognise.

The label shape is fixed by the far end: ``services/transcript_format.py`` in each portal reads a
speaker turn as a BOLD span ending in a colon (``**Speaker 1:**``) at the start of a line. Change
the shape here and the Excel export silently goes back to one unbroken paragraph.
"""

from __future__ import annotations

from typing import Any

from app.transcription.contract import Segment


def speaker_turns(fragments: list[tuple[Any, str]]) -> list[tuple[Any, str]]:
    """``(speaker, fragment)`` pairs merged into one turn per uninterrupted stretch of a voice."""
    turns: list[tuple[Any, str]] = []
    for speaker, fragment in fragments:
        text = fragment.strip()
        if not text:
            continue
        if turns and turns[-1][0] == speaker:
            turns[-1] = (speaker, f"{turns[-1][1]} {text}")
        else:
            turns.append((speaker, text))
    return turns


def speaker_count(turns: list[tuple[Any, str]]) -> int:
    return len({speaker for speaker, _ in turns if speaker is not None})


def diarized_markdown(turns: list[tuple[Any, str]]) -> str | None:
    """Turns as ``**Speaker 1:** ...`` paragraphs, or None when only one voice was heard.

    Speakers are numbered in order of first appearance, not by the provider's own id, which is
    arbitrary and can skip values. A solo recording returns None so it carries no empty label.
    """
    order: list[Any] = []
    for speaker, _ in turns:
        if speaker is not None and speaker not in order:
            order.append(speaker)
    if len(order) < 2:
        return None
    numbers = {speaker: index + 1 for index, speaker in enumerate(order)}
    return "\n\n".join(
        f"**Speaker {numbers[speaker]}:** {text}" if speaker in numbers else text
        for speaker, text in turns
    )


def segments_text(segments: list[Segment], plain: str = "") -> tuple[str, int]:
    """``(transcript, speakers)`` from segments: labelled when several voices spoke, else plain."""
    turns = speaker_turns([(segment.speaker, segment.text) for segment in segments])
    joined = plain.strip() or " ".join(text for _, text in turns).strip()
    return (diarized_markdown(turns) or joined), speaker_count(turns)
