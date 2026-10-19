"""ONE legacy resolver (DESIGN 7.2): where an old clip belongs, by CONTENT before position.

Used by the backfill and the old-build shims only. In order:

1. ``extraMetadata.questionId`` or ``sectionId``                       -> LEGACY_META
2. the prompt text (Android caption "Question audio: F7 <prompt>", web ``extraMetadata.questionPrompt``)
   matched against the question texts across each lineage (normalised, similarity >= 0.85)
                                                                       -> LEGACY_CAPTION_TEXT
3. no prompt text: candidates ranked by similarity between the clip's COMPLETED transcript and the
   question texts;
4. position ("F7" -> the 7th question) only as a tiebreak among those candidates, or when the
   section has not been edited since the upload;
5. the filename token (``F_7_...``, ``F_SEC_...``)                     -> LEGACY_FILENAME
6. otherwise the section from "Section audio: F"; if even that is missing: general, NEEDS_DECISION
   (UNRESOLVED_LEGACY), with up to 3 candidates for the inbox.

NEVER USED: the interview title ("section F" titles were notes, not placement).
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from typing import Any

from app.services.questionnaire_v2.common import aware, get

PROMPT_MATCH = 0.85
TRANSCRIPT_MIN = 0.12
_CAPTION_QUESTION = re.compile(r"^\s*question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b\s*(.*)$", re.IGNORECASE | re.DOTALL)
_CAPTION_SECTION = re.compile(r"^\s*section\s+audio:\s*([A-Za-z]{1,3})\b", re.IGNORECASE)
_FILE_QUESTION = re.compile(r"^([A-Za-z]{1,3})_(\d+)_")
_FILE_SECTION = re.compile(r"^([A-Za-z]{1,3})_SEC_", re.IGNORECASE)


@dataclass
class Resolution:
    targets: list[dict[str, str]] = field(default_factory=list)  # [{"sectionId"} | {"questionLineageId"}]
    provenance: str = "LEGACY_META"
    step: str = ""
    needs_decision: bool = False
    candidates: list[dict[str, Any]] = field(default_factory=list)


def normalise(text: str | None) -> str:
    text = unicodedata.normalize("NFKC", text or "").casefold()
    text = re.sub(r"[^\w\s]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def similarity(a: str | None, b: str | None) -> float:
    na, nb = normalise(a), normalise(b)
    if not na or not nb:
        return 0.0
    return SequenceMatcher(None, na, nb).ratio()


def overlap(transcript: str | None, prompt: str | None) -> float:
    t, p = set(normalise(transcript).split()), set(normalise(prompt).split())
    p = {w for w in p if len(w) > 2}
    return len(t & p) / len(p) if p else 0.0


def _meta(media: Any) -> dict[str, Any]:
    meta = get(media, "extraMetadata") or {}
    return meta if isinstance(meta, dict) else {}


def resolve(media: Any, questions: list[dict[str, Any]], sections: list[dict[str, Any]]) -> Resolution:
    """``questions``: every row of the instrument (all wordings), each with ``id``, ``lineageId``,
    ``prompt``, ``sectionId``, ``sectionCode``, ``sortOrder``, ``isActive``, ``updatedAt``;
    ``sections``: ``id``, ``code``."""
    meta = _meta(media)
    by_id = {str(q["id"]): q for q in questions}
    section_by_code = {str(s["code"]).upper(): s for s in sections}
    section_ids = {str(s["id"]) for s in sections}

    # 1. typed metadata
    qid = meta.get("questionId")
    if qid and str(qid) in by_id:
        q = by_id[str(qid)]
        return Resolution([{"questionLineageId": str(q.get("lineageId") or q["id"])}], "LEGACY_META", "meta.questionId")
    sid = meta.get("sectionId")
    if sid and str(sid) in section_ids:
        return Resolution([{"sectionId": str(sid)}], "LEGACY_META", "meta.sectionId")

    caption = get(media, "caption") or ""
    cq = _CAPTION_QUESTION.match(caption)
    code = number = None
    prompt = meta.get("questionPrompt")
    if cq:
        code, number = cq.group(1).upper(), int(cq.group(2))
        prompt = prompt or cq.group(3).strip() or None

    def in_section(c: str | None) -> list[dict[str, Any]]:
        if not c:
            return questions
        return [q for q in questions if str(q.get("sectionCode") or "").upper() == c] or questions

    # 2. prompt text across the lineage chain
    if prompt:
        scored = sorted(((similarity(prompt, q.get("prompt")), q) for q in in_section(code)), key=lambda x: -x[0])
        if scored and scored[0][0] >= PROMPT_MATCH:
            q = scored[0][1]
            return Resolution([{"questionLineageId": str(q.get("lineageId") or q["id"])}], "LEGACY_CAPTION_TEXT", "caption.prompt")

    def positional(c: str, n: int) -> dict[str, Any] | None:
        live = sorted(
            {str(q.get("lineageId") or q["id"]): q for q in questions
             if str(q.get("sectionCode") or "").upper() == c and q.get("isActive", True)}.values(),
            key=lambda q: (q.get("sortOrder") or 0, str(q["id"])),
        )
        return live[n - 1] if 0 < n <= len(live) else None

    def unedited_since(c: str) -> bool:
        uploaded = aware(get(media, "createdAt"))
        stamps = [aware(q.get("updatedAt")) for q in questions if str(q.get("sectionCode") or "").upper() == c]
        return bool(uploaded) and all(s is not None and s <= uploaded for s in stamps)

    # 3 + 4. transcript ranking, position as the tiebreak
    transcript = get(media, "transcriptText") if str(get(media, "transcriptStatus") or "").upper() == "COMPLETED" else None
    ranked: list[tuple[float, dict[str, Any]]] = []
    if transcript:
        ranked = sorted(((overlap(transcript, q.get("prompt")), q) for q in in_section(code)), key=lambda x: -x[0])
        ranked = [r for r in ranked if r[0] >= TRANSCRIPT_MIN]
    if code and number:
        guess = positional(code, number)
        if ranked and guess is not None:
            top = ranked[0][0]
            ties = [q for s, q in ranked if top - s < 0.05]
            if any(str(q.get("lineageId") or q["id"]) == str(guess.get("lineageId") or guess["id"]) for q in ties):
                return Resolution([{"questionLineageId": str(guess.get("lineageId") or guess["id"])}], "LEGACY_CAPTION_TEXT", "transcript+position")
        if guess is not None and unedited_since(code):
            return Resolution([{"questionLineageId": str(guess.get("lineageId") or guess["id"])}], "LEGACY_CAPTION_TEXT", "caption.position")
    if ranked and (len(ranked) == 1 or ranked[0][0] - ranked[1][0] >= 0.25):
        q = ranked[0][1]
        return Resolution([{"questionLineageId": str(q.get("lineageId") or q["id"])}], "LEGACY_CAPTION_TEXT", "transcript")

    # 5. the filename token
    name = str(get(media, "originalFilename") or "")
    fq, fs = _FILE_QUESTION.match(name), _FILE_SECTION.match(name)
    if fq:
        c, n = fq.group(1).upper(), int(fq.group(2))
        guess = positional(c, n)
        if guess is not None and unedited_since(c):
            return Resolution([{"questionLineageId": str(guess.get("lineageId") or guess["id"])}], "LEGACY_FILENAME", "filename.question")
        if c in section_by_code:
            return Resolution([{"sectionId": str(section_by_code[c]["id"])}], "LEGACY_FILENAME", "filename.question-section")
    if fs and fs.group(1).upper() in section_by_code:
        return Resolution([{"sectionId": str(section_by_code[fs.group(1).upper()]["id"])}], "LEGACY_FILENAME", "filename.section")

    # 6. the section caption, else unresolved
    cs = _CAPTION_SECTION.match(caption)
    section_code = (cs.group(1).upper() if cs else None) or code
    if section_code and section_code in section_by_code:
        return Resolution([{"sectionId": str(section_by_code[section_code]["id"])}], "LEGACY_CAPTION_TEXT", "caption.section")
    candidates = [
        {"questionLineageId": str(q.get("lineageId") or q["id"]), "prompt": q.get("prompt"), "score": round(s, 3)}
        for s, q in (ranked or [])[:3]
    ]
    return Resolution([], "LEGACY_META", "unresolved", needs_decision=True, candidates=candidates)


async def load_instrument(client: Any, questionnaire_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    questions = await client.query_raw(
        'SELECT "id", "lineageId", "prompt", "sectionId", "sectionCode", "sortOrder", "isActive", "updatedAt" '
        'FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1', questionnaire_id,
    )
    sections = await client.query_raw('SELECT "id", "code" FROM "QuestionnaireSection" WHERE "questionnaireId" = $1', questionnaire_id)
    return questions, sections


async def resolve_legacy_target(client: Any, media: Any, questionnaire_id: str) -> Resolution:
    questions, sections = await load_instrument(client, questionnaire_id)
    return resolve(media, questions, sections)
