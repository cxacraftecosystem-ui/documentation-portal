"""Shared craft and question codes (C26): one key for the same craft or question in both portals.

── WHAT THIS IS FOR ─────────────────────────────────────────────────────────────────────────────

Before this, the two portals matched a craft only by how somebody spelled it, and a question only by
its position and wording. "Bandhej" in one and "Bandhani" in the other were two crafts; the 285
interview questions were the same questions only because nobody had reworded one yet. Nothing that
pools across the portals (the artisan link in C27, the cross-portal feeds in section D of the plan)
can stand on that.

So each craft TRADITION and each shared interview question has a stable code, declared once in
``app/data/cross_portal_codes.json``. That file is byte-identical in the designer portal and the
documentation portal (``tests/test_cross_portal_codes.py`` holds it when both checkouts are present),
and so is this module. Craft codes are the cxa-cms public site's craft slugs wherever the site already
has the craft, so the same key reaches the public site too.

── HOW A CRAFT GETS ITS CODE ────────────────────────────────────────────────────────────────────

Through the SPELLING MAPPING TABLE, ``CraftSpelling``. Every spelling in the shared file is a row with
``source = "SHARED"``; an administrator who maps a craft whose name is not yet known adds a row with
``source = "ADMIN"`` (local to that portal until somebody adds it to the shared file). A name is
NORMALISED before it is looked up — lower case, accents folded, every run of punctuation or space
turned into one space — so "Block-printing", "block printing" and "BLOCK PRINTING " are one spelling.
The backfill migration applies the same folding in SQL; :func:`sql_normalise_emulation` is that SQL,
written in Python, so a test can prove the two agree on every spelling and prompt in the file.

A craft that matches nothing keeps a NULL code and behaves exactly as before. Nothing guesses: no
fuzzy match, no "closest" code. A wrong code is worse than none, because it silently merges two
traditions in every count that reads it.

── HOW A QUESTION GETS ITS CODE ─────────────────────────────────────────────────────────────────

By its section code and its normalised wording, which no two shared questions share (the test checks
that too). A question reworded through the builder becomes a NEW row (the supersede rule both
schemas document), and the new row gets a code only if its new wording is in the file: "How many
looms?" reworded to "How many weavers?" is a different question, and carrying the code across would
pool answers to two questions as one.

Both portals carry this module under the same name, byte for byte; change both.
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

CODES_FILE = Path(__file__).resolve().parents[1] / "data" / "cross_portal_codes.json"

#: ``CraftSpelling.source`` values.
SOURCE_SHARED = "SHARED"
SOURCE_ADMIN = "ADMIN"

_NON_ALNUM = re.compile(r"[^a-z0-9]+")

#: The accented letters the SQL backfill folds with ``translate()``. Postgres has no NFKD without
#: the ``unaccent`` extension, which this deployment does not install, so the migration spells the
#: letters out. Kept here so the emulation below is the migration's own table.
SQL_ACCENTS_FROM = "àáâãäåèéêëìíîïòóôõöùúûüñçýÀÁÂÃÄÅÈÉÊËÌÍÎÏÒÓÔÕÖÙÚÛÜÑÇÝ"
SQL_ACCENTS_TO = "aaaaaaeeeeiiiiooooouuuuncyaaaaaaeeeeiiiiooooouuuuncy"


def normalise(text: str | None) -> str:
    """The folded form a spelling or a prompt is compared in. ``""`` for nothing."""
    decomposed = unicodedata.normalize("NFKD", text or "")
    stripped = "".join(ch for ch in decomposed if not unicodedata.combining(ch)).lower()
    return " ".join(_NON_ALNUM.sub(" ", stripped).split())


def sql_normalise_emulation(text: str | None) -> str:
    """What the backfill migration's SQL expression computes, in Python.

    ``btrim(regexp_replace(translate(lower(x), FROM, TO), '[^a-z0-9]+', ' ', 'g'))``. Exists so a
    test can show the migration matched exactly the rows :func:`normalise` would have.
    """
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
    #: normalised spelling -> craft code
    spelling_index: dict[str, str]
    #: (section code, normalised prompt) -> question code
    question_index: dict[tuple[str, str], str]

    def craft(self, code: str) -> CraftEntry | None:
        return next((c for c in self.crafts if c.code == code), None)


@lru_cache(maxsize=1)
def shared() -> SharedCodes:
    """The shared file, parsed once per process."""
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
    """The code the shared file gives this spelling, or ``None``. No database."""
    key = normalise(name)
    return shared().spelling_index.get(key) if key else None


def question_code(section_code: str | None, prompt: str | None) -> str | None:
    """The shared code of the question with this section and wording, or ``None``. No database."""
    if not section_code or not prompt:
        return None
    return shared().question_index.get((section_code, normalise(prompt)))


def is_known_code(code: str | None) -> bool:
    return bool(code) and shared().craft(code) is not None  # type: ignore[arg-type]


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Database side. Every function takes the Prisma client (or a transaction) as ``client`` so the
# routes pass their own and the tests pass a fake.
# ──────────────────────────────────────────────────────────────────────────────────────────────


async def ensure_code_row(client: Any, code: str) -> None:
    """Make sure ``CraftCode`` holds *code*, so the foreign key on ``Craft.craftCode`` accepts it.

    The backfill migration wrote every code the file had on the day it shipped. A code the file gains
    later is written here, on first use, from the file — so adding a craft is an edit to the file and
    nothing else, with no seeding step for a deploy to forget.
    """
    entry = shared().craft(code)
    existing = await client.craftcode.find_unique(where={"code": code})
    if existing is not None:
        return
    name = entry.name if entry is not None else code
    await client.craftcode.create(data={"code": code, "name": name})


@dataclass(frozen=True)
class SpellingIndex:
    """This portal's admin spellings, read once: ``spelling -> code`` and ``code -> spellings``.

    For a caller that resolves MANY names in one pass (``craft_master.sync_master`` over the whole
    master list, ``map_crafts.plan`` over every legacy craft and free-text record): handed to
    :func:`resolve_craft_code` and ``craft_master.alias_matches``, it answers what their per-name
    reads of ``CraftSpelling`` answered, from one read of the table. Only for a pass that writes no
    spelling while it holds the index.
    """

    by_spelling: dict[str, str]
    by_code: dict[str, frozenset[str]]


async def load_spelling_index(client: Any) -> SpellingIndex:
    """Every ``CraftSpelling`` row, indexed both ways. One read."""
    by_spelling: dict[str, str] = {}
    by_code: dict[str, set[str]] = {}
    for row in await client.craftspelling.find_many():
        by_spelling[row.spelling] = row.craftCode
        by_code.setdefault(row.craftCode, set()).add(row.spelling)
    return SpellingIndex(by_spelling, {code: frozenset(names) for code, names in by_code.items()})


async def resolve_craft_code(
    client: Any, name: str | None, *, spellings: SpellingIndex | None = None
) -> str | None:
    """The code for a craft name: the shared file first, then this portal's admin spellings.

    ``spellings``, when given, answers the second step from memory instead of a read per name.
    """
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
    """Resolve a code for a craft about to be created and make sure its row exists. ``None`` if none."""
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
    """An administrator's mapping of one craft to a code (or clearing it).

    With ``remember_spelling`` the craft's spelling is added to the mapping table as an ADMIN row, so
    the next craft written the same way is mapped on creation, and every other craft already written
    that way with no code is mapped now. A spelling the SHARED file already holds is never
    overwritten: the file is the agreement between the two portals, and a local row cannot vote
    against it.

    ``changed``, when given, receives every craft whose code this call actually changed, as the row
    was BEFORE the write — the picked craft and each twin mapped with it — so the caller can write
    one audit entry per craft rather than one for the craft it was asked about.
    """
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
    """Bring ``QuestionnaireQuestion.questionCode`` into line with each row's section and wording.

    ONE CALL AT THE END OF A WRITE, rather than a stamp at each of the many places a question is
    created or reworded (the builder, the workbook import, the seeder, a reword that supersedes). The
    code is a pure function of the shared file, the section code and the prompt, so recomputing it is
    always right, and a path that writes questions without knowing about codes cannot leave one wrong
    for longer than the next write. A few hundred rows; one read and only the changed rows written.
    Returns how many rows changed.
    """
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
    """The same for one designer-authored questionnaire's questions (DESIGNER PORTAL ONLY).

    A designer's copy of a toolkit instrument keeps the toolkit's section codes and wording, so its
    questions carry the shared codes too; a question the designer wrote or reworded has none.
    """
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
    """Every shared craft code, for a picker. No database."""
    return [{"code": c.code, "name": c.name} for c in shared().crafts]
