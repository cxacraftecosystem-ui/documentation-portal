from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

CODES_FILE = Path(__file__).resolve().parents[1] / "data" / "cross_portal_codes.json"

SOURCE_SHARED = "SHARED"
SOURCE_ADMIN = "ADMIN"

_NON_ALNUM = re.compile(r"[^a-z0-9]+")

SQL_ACCENTS_FROM = "àáâãäåèéêëìíîïòóôõöùúûüñçýÀÁÂÃÄÅÈÉÊËÌÍÎÏÒÓÔÕÖÙÚÛÜÑÇÝ"
SQL_ACCENTS_TO = "aaaaaaeeeeiiiiooooouuuuncyaaaaaaeeeeiiiiooooouuuuncy"


def normalise(text: str | None) -> str:
    decomposed = unicodedata.normalize("NFKD", text or "")
    stripped = "".join(ch for ch in decomposed if not unicodedata.combining(ch)).lower()
    return " ".join(_NON_ALNUM.sub(" ", stripped).split())


def sql_normalise_emulation(text: str | None) -> str:
    table = str.maketrans(SQL_ACCENTS_FROM, SQL_ACCENTS_TO)
    return _NON_ALNUM.sub(" ", (text or "").lower().translate(table)).strip()


@dataclass(frozen=True)
class CraftEntry:
    code: str
    name: str
    spellings: tuple[str, ...]


@dataclass(frozen=True)
class QuestionEntry:
    code: str
    instrument: str
    section: str
    prompt: str


@dataclass(frozen=True)
class SharedCodes:
    version: int
    crafts: tuple[CraftEntry, ...]
    questions: tuple[QuestionEntry, ...]
    spelling_index: dict[str, str]
    question_index: dict[tuple[str, str], str]

    def craft(self, code: str) -> CraftEntry | None:
        return next((c for c in self.crafts if c.code == code), None)


@lru_cache(maxsize=1)
def shared() -> SharedCodes:
    raw = json.loads(CODES_FILE.read_text(encoding="utf-8"))
    crafts = tuple(
        CraftEntry(code=c["code"], name=c["name"], spellings=tuple(c["spellings"]))
        for c in raw["crafts"]
    )
    spelling_index: dict[str, str] = {}
    for craft in crafts:
        for spelling in (craft.name, *craft.spellings):
            spelling_index.setdefault(normalise(spelling), craft.code)
    questions = tuple(
        QuestionEntry(
            code=q["code"], instrument=inst["code"], section=q["section"], prompt=q["prompt"]
        )
        for inst in raw["instruments"]
        for q in inst["questions"]
    )
    question_index = {(q.section, normalise(q.prompt)): q.code for q in questions}
    return SharedCodes(
        version=int(raw["version"]),
        crafts=crafts,
        questions=questions,
        spelling_index=spelling_index,
        question_index=question_index,
    )


def shared_craft_code(name: str | None) -> str | None:
    key = normalise(name)
    return shared().spelling_index.get(key) if key else None


def question_code(section_code: str | None, prompt: str | None) -> str | None:
    if not section_code or not prompt:
        return None
    return shared().question_index.get((section_code, normalise(prompt)))


def is_known_code(code: str | None) -> bool:
    return bool(code) and shared().craft(code) is not None  # type: ignore[arg-type]



async def ensure_code_row(client: Any, code: str) -> None:
    entry = shared().craft(code)
    existing = await client.craftcode.find_unique(where={"code": code})
    if existing is not None:
        return
    name = entry.name if entry is not None else code
    await client.craftcode.create(data={"code": code, "name": name})


@dataclass(frozen=True)
class SpellingIndex:
    by_spelling: dict[str, str]
    by_code: dict[str, frozenset[str]]


async def load_spelling_index(client: Any) -> SpellingIndex:
    by_spelling: dict[str, str] = {}
    by_code: dict[str, set[str]] = {}
    for row in await client.craftspelling.find_many():
        by_spelling[row.spelling] = row.craftCode
        by_code.setdefault(row.craftCode, set()).add(row.spelling)
    return SpellingIndex(by_spelling, {code: frozenset(names) for code, names in by_code.items()})


async def resolve_craft_code(
    client: Any, name: str | None, *, spellings: SpellingIndex | None = None
) -> str | None:
    code = shared_craft_code(name)
    if code is not None:
        return code
    key = normalise(name)
    if not key:
        return None
    if spellings is not None:
        return spellings.by_spelling.get(key)
    row = await client.craftspelling.find_unique(where={"spelling": key})
    return getattr(row, "craftCode", None) if row is not None else None


async def code_for_new_craft(
    client: Any, name: str | None, *, spellings: SpellingIndex | None = None
) -> str | None:
    code = await resolve_craft_code(client, name, spellings=spellings)
    if code is not None:
        await ensure_code_row(client, code)
    return code


async def map_craft(
    client: Any,
    *,
    craft: Any,
    code: str | None,
    remember_spelling: bool,
    user_id: str | None,
    changed: list[Any] | None = None,
) -> dict[str, Any]:
    if code is not None and not is_known_code(code):
        raise ValueError("unknown craft code")
    if code is not None:
        await ensure_code_row(client, code)
    await client.craft.update(where={"id": craft.id}, data={"craftCode": code})
    if changed is not None and getattr(craft, "craftCode", None) != code:
        changed.append(craft)
    also_mapped = 0
    key = normalise(getattr(craft, "name", None))
    if code is not None and remember_spelling and key and shared_craft_code(craft.name) is None:
        existing = await client.craftspelling.find_unique(where={"spelling": key})
        if existing is None:
            await client.craftspelling.create(
                data={
                    "spelling": key,
                    "label": craft.name,
                    "craftCode": code,
                    "source": SOURCE_ADMIN,
                    "createdById": user_id,
                }
            )
        elif getattr(existing, "source", None) == SOURCE_ADMIN:
            await client.craftspelling.update(where={"spelling": key}, data={"craftCode": code})
        others = await client.craft.find_many(where={"craftCode": None})
        for other in others:
            if other.id != craft.id and normalise(other.name) == key:
                await client.craft.update(where={"id": other.id}, data={"craftCode": code})
                also_mapped += 1
                if changed is not None:
                    changed.append(other)
    return {"craftId": craft.id, "craftCode": code, "alsoMapped": also_mapped}


async def reconcile_question_codes(client: Any, *, questionnaire_id: str | None = None) -> int:
    where: dict[str, Any] = {}
    if questionnaire_id is not None:
        where["questionnaireId"] = questionnaire_id
    rows = await client.questionnairequestion.find_many(where=where)
    changed = 0
    for row in rows:
        want = question_code(getattr(row, "sectionCode", None), getattr(row, "prompt", None))
        if getattr(row, "questionCode", None) != want:
            await client.questionnairequestion.update(
                where={"id": row.id}, data={"questionCode": want}
            )
            changed += 1
    return changed


async def reconcile_form_question_codes(client: Any, *, questionnaire_id: str) -> int:
    sections = await client.questionnaireformsection.find_many(
        where={"questionnaireId": questionnaire_id}, include={"questions": True}
    )
    changed = 0
    for section in sections:
        for row in getattr(section, "questions", None) or []:
            want = question_code(section.code, getattr(row, "prompt", None))
            if getattr(row, "questionCode", None) != want:
                await client.questionnaireformquestion.update(
                    where={"id": row.id}, data={"questionCode": want}
                )
                changed += 1
    return changed


def catalogue() -> list[dict[str, Any]]:
    return [{"code": c.code, "name": c.name} for c in shared().crafts]
