from __future__ import annotations

import functools
import json
from pathlib import Path
from typing import Any

from app.services.datareport import tables
from app.services.datareport.contract import Column, ReportContext, Sheet, register

DATA = Path(__file__).resolve().parents[3] / "data"

DEFINITIONS: dict[str, str] = {
    "questionnaire_questions.json": "Craft questionnaire (first workshop)",
    "craft_toolkit_workshop_2_questions.json": "Craft toolkit questionnaire (workshop 2)",
    "craft_toolkit_workshop_3_questions.json": "Craft toolkit questionnaire (workshop 3)",
    "questionnaire_questions_w3.json": "Craft questionnaire (workshop 3)",
    "questionnaire_questions_w4.json": "Craft questionnaire (workshop 4)",
}

COLUMNS = [
    Column("questionnaire", "Questionnaire", "text", "The questionnaire version the question belongs to.", width=34),
    Column("sectionCode", "Section code", "text", "The section's code.", width=10),
    Column("section", "Section", "text", "The section's title.", width=36),
    Column("number", "Question no.", "int", "The question's place within its section.", width=10),
    Column("question", "Question", "longtext", "The question as printed.", width=80, wrap=True),
    Column("file", "Definition file", "text", "The definition the portal seeds questions from.", hidden=True, group="ids"),
]


def definition_files() -> list[Path]:
    return sorted(p for p in DATA.glob("*question*.json") if p.is_file())


SNAPSHOTS = Path(__file__).resolve().parents[1]


def snapshot_file(portal: str) -> Path:
    return SNAPSHOTS / f"bank_{portal}.json"


def dump(rows: tuple[tuple[str, str, str, int, str, str], ...]) -> str:
    return "[\n" + ",\n".join(json.dumps(list(r), ensure_ascii=False) for r in rows) + "\n]\n"


@functools.cache
def snapshot(portal: str) -> tuple[tuple[str, str, str, int, str, str], ...]:
    path = snapshot_file(portal)
    if not path.is_file():
        return ()
    return tuple((str(a), str(b), str(c), int(d), str(e), str(f)) for a, b, c, d, e, f in json.loads(path.read_text(encoding="utf-8")))


@functools.cache
def bank() -> tuple[tuple[str, str, str, int, str, str], ...]:
    out: list[tuple[str, str, str, int, str, str]] = []
    for path in definition_files():
        label = DEFINITIONS.get(path.name, path.stem.replace("_", " ").capitalize())
        try:
            data: Any = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            continue
        for section in data if isinstance(data, list) else []:
            for question in section.get("questions") or []:
                out.append(
                    (
                        label,
                        str(section.get("code") or ""),
                        str(section.get("title") or ""),
                        int(question.get("sortOrder") or 0),
                        str(question.get("prompt") or ""),
                        path.name,
                    )
                )
    return tuple(out)


class QuestionBankPlugin:
    key = "questionBank"
    title = "Question bank"
    order = 99
    option = None
    portals = ("designer", "fieldRepository")

    def catalog(self) -> list[tuple[str, list[Column], str]]:
        return [(self.key, COLUMNS, self.title)]

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        rows = [
            {"questionnaire": q, "sectionCode": code, "section": title, "number": n, "question": prompt, "file": name}
            for q, code, title, n, prompt, name in bank()
        ]
        return [
            Sheet(
                key=self.key,
                name=self.title,
                columns=COLUMNS,
                rows=rows,
                color=tables.SITTING,
                description="Every section and question of every questionnaire version the portal ships, answered or not.",
                count=len(rows),
            )
        ]


register(QuestionBankPlugin())
