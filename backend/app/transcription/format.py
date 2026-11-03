from __future__ import annotations

from typing import Any

from app.transcription.contract import Segment


def speaker_turns(fragments: list[tuple[Any, str]]) -> list[tuple[Any, str]]:
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
    turns = speaker_turns([(segment.speaker, segment.text) for segment in segments])
    joined = plain.strip() or " ".join(text for _, text in turns).strip()
    return (diarized_markdown(turns) or joined), speaker_count(turns)
