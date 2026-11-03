import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

import app.api.routes.questionnaire as workbook
from app.services.questionnaire_xlsx import (
    ParsedQuestion,
    ParsedQuestionnaire,
    ParsedSection,
)


class _Delegate:
    def __init__(
        self,
        name: str,
        journal: list[tuple[str, str, dict]],
        rows: list[Any],
        defaults: dict[str, Any] | None = None,
    ) -> None:
        self.name = name
        self.journal = journal
        self.rows = rows
        self.defaults = defaults or {}
        self._next = 0

    def _record(self, op: str, **kwargs: Any) -> None:
        self.journal.append((self.name, op, kwargs))

    def _mint(self, prefix: str) -> str:
        self._next += 1
        return f"{prefix}-new-{self._next}"

    async def create(self, data: dict[str, Any]) -> Any:
        self._record("create", data=data)
        fields = {**self.defaults, **data}
        fields.setdefault("id", self._mint(self.name))
        row = SimpleNamespace(**fields)
        self.rows.append(row)
        return row

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        self._record("update", where=where, data=data)
        for row in self.rows:
            if row.id == where.get("id"):
                for key, value in data.items():
                    setattr(row, key, value)
                return row
        return SimpleNamespace(id=where.get("id"), **data)

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        self._record("update_many", where=where, data=data)
        return 0

    async def delete(self, where: dict[str, Any]) -> Any:
        self._record("delete", where=where)
        self.rows = [row for row in self.rows if row.id != where.get("id")]
        return None

    async def count(self, where: dict[str, Any] | None = None) -> int:
        self._record("count", where=where)
        return self.counts if isinstance(getattr(self, "counts", None), int) else 0

    async def find_unique(self, where: dict[str, Any], **_: Any) -> Any:
        self._record("find_unique", where=where)
        return next((row for row in self.rows if row.id == where.get("id")), None)

    async def find_many(self, where: dict[str, Any] | None = None, **kwargs: Any) -> list[Any]:
        self._record("find_many", where=where, **kwargs)
        rows = list(self.rows)
        where = where or {}
        def hit(row: Any, key: str, clause: Any) -> bool:
            if key == "OR":
                return any(all(hit(row, k, c) for k, c in branch.items()) for branch in clause)
            if isinstance(clause, dict) and "in" in clause:
                return getattr(row, key, None) in clause["in"]
            return getattr(row, key, None) == clause

        for key, clause in where.items():
            rows = [r for r in rows if hit(r, key, clause)]
        take = kwargs.get("take")
        if kwargs.get("order") and rows:
            order = kwargs["order"]
            first = order[0] if isinstance(order, list) else order
            field, direction = next(iter(first.items()))
            rows.sort(key=lambda r: getattr(r, field, 0), reverse=direction == "desc")
        return rows[:take] if take else rows


EVIDENCE_TABLES = (
    "questionnairerecordingtarget",
    "questionnaireanswer",
    "questionnaireanswerrevision",
    "questionnairetakedraft",
    "questionnaireanswerdraft",
)


class _Db:
    def __init__(self, **delegates: _Delegate) -> None:
        self._delegates = delegates
        self.transactions = 0

    def tx(self, **_: Any) -> Any:
        fake = self

        class _Block:
            async def __aenter__(self) -> Any:
                fake.transactions += 1
                return fake

            async def __aexit__(self, *_exc: Any) -> bool:
                return False

        return _Block()

    async def query_raw(self, sql: str, *params: Any) -> list[dict[str, Any]]:
        return []

    def __getattr__(self, name: str) -> Any:
        try:
            return self._delegates[name]
        except KeyError as exc:  # pragma: no cover
            raise AssertionError(
                f"the import reached for db.{name}, which this test did not model. If that is "
                "correct, model it; if it is not, the module is touching a table it should not."
            ) from exc


_EDITOR = SimpleNamespace(id="admin-1", role="ADMIN")


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    journal: list[tuple[str, str, dict]] = []

    questionnaire = SimpleNamespace(
        id="qnr-1",
        title="2nd Craft Toolkit Workshop",
        description="The instrument",
        version=4,
        sortOrder=1,
        isActive=True,
        isDefault=True,
        sourceFilename=None,
        createdAt="2026-01-01",
    )
    sections = [
        SimpleNamespace(
            id="sec-a", questionnaireId="qnr-1", code="A", title="About", sortOrder=1, isActive=True
        ),
        SimpleNamespace(
            id="sec-b",
            questionnaireId="qnr-1",
            code="B",
            title="Materials",
            sortOrder=2,
            isActive=True,
        ),
    ]
    questions = [
        SimpleNamespace(
            id="q-1",
            questionnaireId="qnr-1",
            sectionId="sec-a",
            sectionCode="A",
            sectionTitle="About",
            prompt="How long have you practised this craft?",
            helpText=None,
            isRequired=False,
            sortOrder=1,
            isActive=True,
            retiredAt=None,
            supersededById=None,
        ),
        SimpleNamespace(
            id="q-2",
            questionnaireId="qnr-1",
            sectionId="sec-a",
            sectionCode="A",
            sectionTitle="About",
            prompt="Who taught you?",
            helpText=None,
            isRequired=False,
            sortOrder=2,
            isActive=True,
            retiredAt=None,
            supersededById=None,
        ),
        SimpleNamespace(
            id="q-3",
            questionnaireId="qnr-1",
            sectionId="sec-b",
            sectionCode="B",
            sectionTitle="Materials",
            prompt="Where do you buy your yarn?",
            helpText=None,
            isRequired=False,
            sortOrder=1,
            isActive=True,
            retiredAt=None,
            supersededById=None,
        ),
    ]

    delegates = {
        "questionnaire": _Delegate(
            "questionnaire",
            journal,
            [questionnaire],
            defaults={
                "version": 1,
                "isActive": True,
                "isDefault": False,
                "description": None,
                "sourceFilename": None,
                "createdById": None,
                "createdAt": "2026-09-13",
            },
        ),
        "questionnairesection": _Delegate(
            "questionnairesection", journal, sections, defaults={"isActive": True}
        ),
        "questionnairequestion": _Delegate(
            "questionnairequestion",
            journal,
            questions,
            defaults={
                "isActive": True,
                "isRequired": False,
                "helpText": None,
                "retiredAt": None,
                "supersededById": None,
                "sectionId": None,
            },
        ),
        "questionnaireresponse": _Delegate("questionnaireresponse", journal, []),
        "questionnairesectionstatus": _Delegate("questionnairesectionstatus", journal, []),
        "assignedtask": _Delegate("assignedtask", journal, []),
        **{name: _Delegate(name, journal, []) for name in EVIDENCE_TABLES},
    }
    db = _Db(**delegates)
    monkeypatch.setattr(workbook, "db", db)
    return SimpleNamespace(
        db=db,
        journal=journal,
        row=questionnaire,
        sections=sections,
        questions=questions,
        **delegates,
    )


def _answered(world, question_id: str, *, text: str = "Forty years.", notes: str | None = None):
    world.questionnaireresponse.rows.append(
        SimpleNamespace(
            id=f"resp-{question_id}", questionId=question_id, answerText=text, notes=notes
        )
    )


def _parsed(*sections, questionnaire_id: str | None = None, **kwargs) -> ParsedQuestionnaire:
    return ParsedQuestionnaire(
        sections=list(sections),
        sheet="Questionnaire",
        questionnaireId=questionnaire_id,
        **kwargs,
    )


def _section(code: str, title: str, *questions) -> ParsedSection:
    return ParsedSection(code=code, title=title, questions=list(questions))


def _question(prompt: str, row: int, **kwargs) -> ParsedQuestion:
    return ParsedQuestion(prompt=prompt, row=row, **kwargs)


def _calls(world, name: str, op: str) -> list[dict]:
    return [kwargs for model, operation, kwargs in world.journal if model == name and operation == op]


def _run(coro):
    return asyncio.run(coro)



def test_a_new_workbook_writes_sections_then_questions_in_sheet_order(world):
    parsed = _parsed(
        _section("A", "About", _question("How long?", 2), _question("Who taught you?", 3)),
        _section("B", "Materials", _question("Where do you buy yarn?", 4)),
    )
    _run(workbook.create_from_parsed(parsed, created_by_id="admin-1"))

    order = [
        (model, kwargs["data"].get("code") or kwargs["data"].get("prompt"))
        for model, op, kwargs in world.journal
        if op == "create" and model in ("questionnairesection", "questionnairequestion")
    ]
    assert order == [
        ("questionnairesection", "A"),
        ("questionnairequestion", "How long?"),
        ("questionnairequestion", "Who taught you?"),
        ("questionnairesection", "B"),
        ("questionnairequestion", "Where do you buy yarn?"),
    ]


def test_helpText_and_isRequired_reach_the_create_call(world):
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How long?", 2, helpText="Count from the first unsupervised year.", isRequired=True),
        )
    )
    _run(workbook.create_from_parsed(parsed))

    data = _calls(world, "questionnairequestion", "create")[0]["data"]
    assert data["helpText"] == "Count from the first unsupervised year."
    assert data["isRequired"] is True


def test_a_help_text_over_the_cap_is_clipped_and_reported(world):
    parsed = _parsed(
        _section("A", "About", _question("How long?", 7, helpText="x" * 5000))
    )
    _, report = _run(workbook.create_from_parsed(parsed))

    data = _calls(world, "questionnairequestion", "create")[0]["data"]
    assert len(data["helpText"]) == workbook.MAX_HELP_CHARS
    problem = next(p for p in report["problems"] if "help text" in p["reason"])
    assert problem["severity"] == "warning"
    assert problem["row"] == 7


def test_every_question_create_carries_sectionCode_and_sectionTitle(world):
    parsed = _parsed(_section("A", "About", _question("How long?", 2)))
    _run(workbook.create_from_parsed(parsed))

    for call in _calls(world, "questionnairequestion", "create"):
        assert call["data"]["sectionCode"] == "A"
        assert call["data"]["sectionTitle"] == "About"
        assert call["data"]["questionnaireId"]
        assert call["data"]["sectionId"]


def test_no_answer_row_is_ever_written_on_the_create_path(world):
    parsed = _parsed(
        _section("A", "About", _question("How long?", 2, answers={"Ramesh": "Forty years"})),
        entryLabels=["Ramesh"],
    )
    _run(workbook.create_from_parsed(parsed))
    assert not [
        1 for model, op, _ in world.journal if model == "questionnaireresponse" and op != "find_many"
    ]



def test_a_section_rename_updates_every_question_under_it(world):
    parsed = _parsed(
        _section("A", "About the craft", _question("How long have you practised this craft?", 2)),
        _section("B", "Materials", _question("Where do you buy your yarn?", 3)),
    )
    _run(workbook.apply_parsed_edit("qnr-1", parsed))

    renames = _calls(world, "questionnairequestion", "update_many")
    assert len(renames) == 1, renames
    assert renames[0]["where"] == {"sectionId": "sec-a"}
    assert set(renames[0]["data"]) == {"sectionCode", "sectionTitle"}
    assert renames[0]["data"]["sectionTitle"] == "About the craft"


def test_every_existing_section_is_negated_before_any_positive_sort_order_is_written(world):
    parsed = _parsed(
        _section("B", "Materials", _question("Where do you buy your yarn?", 2)),
        _section("A", "About", _question("How long have you practised this craft?", 3)),
    )
    _run(workbook.apply_parsed_edit("qnr-1", parsed))

    writes = [
        (index, kwargs["data"].get("sortOrder"))
        for index, (model, op, kwargs) in enumerate(world.journal)
        if model == "questionnairesection"
        and op in ("update", "create")
        and isinstance(kwargs.get("data", {}).get("sortOrder"), int)
    ]
    negatives = [index for index, value in writes if value < 0]
    positives = [index for index, value in writes if value > 0]
    assert negatives and positives
    assert max(negatives) < min(positives), writes


def test_no_section_is_left_at_a_negative_sort_order(world):
    world.sections.append(
        SimpleNamespace(
            id="sec-c", questionnaireId="qnr-1", code="C", title="Selling", sortOrder=3, isActive=True
        )
    )
    world.sections.append(
        SimpleNamespace(
            id="sec-d", questionnaireId="qnr-1", code="D", title="Tools", sortOrder=4, isActive=True
        )
    )
    parsed = _parsed(
        _section("A", "About", _question("How long have you practised this craft?", 2)),
        _section("B", "Materials", _question("Where do you buy your yarn?", 3)),
        _section("C", "Selling"),
    )
    _run(workbook.apply_parsed_edit("qnr-1", parsed))

    final: dict[str, int] = {}
    for model, op, kwargs in world.journal:
        if model != "questionnairesection" or op != "update":
            continue
        if isinstance(kwargs["data"].get("sortOrder"), int):
            final[kwargs["where"]["id"]] = kwargs["data"]["sortOrder"]
    assert set(final) == {"sec-a", "sec-b", "sec-c", "sec-d"}, final
    assert all(value > 0 for value in final.values()), final


def test_a_section_absent_from_the_workbook_is_never_deleted(world):
    _answered(world, "q-3")
    world.sections.append(
        SimpleNamespace(
            id="sec-c", questionnaireId="qnr-1", code="C", title="Selling", sortOrder=3, isActive=True
        )
    )
    parsed = _parsed(_section("A", "About", _question("How long have you practised this craft?", 2)))
    _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert _calls(world, "questionnairesection", "delete") == []
    deactivations = {
        kwargs["where"]["id"]: kwargs["data"]
        for kwargs in _calls(world, "questionnairesection", "update")
        if kwargs["data"].get("isActive") is False
    }
    assert set(deactivations) == {"sec-b", "sec-c"}
    for data in deactivations.values():
        assert data["isActive"] is False
        assert data["sortOrder"] > 0


def test_a_retired_section_reports_what_still_points_at_it(world):
    world.questionnairesectionstatus.counts = 3
    world.assignedtask.counts = 2
    parsed = _parsed(_section("A", "About", _question("How long have you practised this craft?", 2)))
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    detail = next(d for d in report["details"] if d["action"] == "sectionRetired")
    assert "3 completion verdict" in detail["reason"]
    assert "2 assigned task" in detail["reason"]
    assert report["assignedTasksAffected"] == 2
    assert report["sectionsRetired"] == 1


def test_rewording_an_answered_question_creates_then_retires(world):
    _answered(world, "q-1")
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How many years have you worked this craft?", 2, questionId="q-1"),
            _question("Who taught you?", 3, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    creates = _calls(world, "questionnairequestion", "create")
    assert [c["data"]["prompt"] for c in creates] == ["How many years have you worked this craft?"]
    retire = next(
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-1"
    )
    assert retire["data"]["isActive"] is False
    assert retire["data"]["retiredAt"] is not None
    assert retire["data"]["supersededById"]
    ops = [(m, o, k) for m, o, k in world.journal if m == "questionnairequestion"]
    create_at = next(i for i, (_, o, k) in enumerate(ops) if o == "create")
    retire_at = next(
        i for i, (_, o, k) in enumerate(ops) if o == "update" and k["where"]["id"] == "q-1"
    )
    assert create_at < retire_at

    assert _calls(world, "questionnairequestion", "delete") == []
    assert report["superseded"] == 1
    assert report["versionAfter"] == report["versionBefore"] + 1


def test_rewording_an_unanswered_question_is_a_plain_update(world):
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How many years?", 2, questionId="q-1"),
            _question("Who taught you?", 3, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert _calls(world, "questionnairequestion", "create") == []
    updates = [
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-1"
    ]
    assert len(updates) == 1
    assert updates[0]["data"]["prompt"] == "How many years?"
    assert "retiredAt" not in updates[0]["data"]
    assert report["superseded"] == 0
    assert report["versionAfter"] == report["versionBefore"]


def test_deleting_an_answered_question_retires_it(world):
    _answered(world, "q-2")
    parsed = _parsed(
        _section("A", "About", _question("How long have you practised this craft?", 2, questionId="q-1")),
        _section("B", "Materials", _question("Where do you buy your yarn?", 3, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    retire = next(
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-2"
    )
    assert retire["data"] == {"isActive": False, "retiredAt": retire["data"]["retiredAt"]}
    assert retire["data"]["retiredAt"] is not None
    assert _calls(world, "questionnairequestion", "delete") == []
    assert report["retired"] == 1
    detail = next(d for d in report["details"] if d["action"] == "retired")
    assert "retired rather than deleted" in detail["reason"]


def test_deleting_an_unanswered_question_really_deletes_it(world):
    parsed = _parsed(
        _section("A", "About", _question("How long have you practised this craft?", 2, questionId="q-1")),
        _section("B", "Materials", _question("Where do you buy your yarn?", 3, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert [c["where"]["id"] for c in _calls(world, "questionnairequestion", "delete")] == ["q-2"]
    assert report["removed"] == 1


def test_a_blank_answer_row_does_not_count_as_answered(world):
    _answered(world, "q-1", text="   ")
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How many years?", 2, questionId="q-1"),
            _question("Who taught you?", 3, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert report["superseded"] == 0
    updates = [
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-1"
    ]
    assert updates[0]["data"]["prompt"] == "How many years?"


def test_a_note_with_no_answer_text_still_counts_as_answered(world):
    _answered(world, "q-1", text="", notes="Artisan declined; see the audio clip.")
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How many years?", 2, questionId="q-1"),
            _question("Who taught you?", 3, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))
    assert report["superseded"] == 1


def test_a_question_with_only_blank_interview_rows_is_deactivated_rather_than_deleted(world):
    _answered(world, "q-2", text="  ", notes="  ")
    parsed = _parsed(
        _section("A", "About", _question("How long have you practised this craft?", 2, questionId="q-1")),
        _section("B", "Materials", _question("Where do you buy your yarn?", 3, questionId="q-3")),
    )
    _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert _calls(world, "questionnairequestion", "delete") == []
    off = next(
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-2"
    )
    assert off["data"] == {"isActive": False}


def test_a_question_switched_off_by_the_builder_is_reactivated_by_the_workbook(world):
    world.questions[1].isActive = False
    world.questions[1].retiredAt = None
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How long have you practised this craft?", 2, questionId="q-1"),
            _question("Who first taught you?", 3, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    _run(workbook.apply_parsed_edit("qnr-1", parsed))

    update = next(
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-2"
    )
    assert update["data"]["isActive"] is True
    assert update["data"]["prompt"] == "Who first taught you?"


def test_a_retired_question_is_not_reactivated_and_a_reworded_one_is_warned_about(world):
    world.questions[1].isActive = False
    world.questions[1].retiredAt = "2026-03-01T00:00:00Z"
    world.questions[1].supersededById = "q-9"
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How long have you practised this craft?", 2, questionId="q-1"),
            _question("Who first taught you?", 9, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    touched = [
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-2"
    ]
    assert touched == [], touched
    assert report["unchanged"] >= 1
    problem = next(p for p in report["problems"] if p["row"] == 9)
    assert problem["severity"] == "warning"
    assert "q-9" in problem["reason"]


def test_an_unknown_question_id_imports_as_new_and_reports_it(world):
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How long have you practised this craft?", 2, questionId="q-1"),
            _question("Who taught you?", 3, questionId="q-2"),
            _question("A brand new question", 4, questionId="q-from-another-form"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 5, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    creates = _calls(world, "questionnairequestion", "create")
    assert [c["data"]["prompt"] for c in creates] == ["A brand new question"]
    problem = next(p for p in report["problems"] if "does not belong" in p["reason"])
    assert problem["row"] == 4
    assert problem["severity"] == "warning"


def test_the_prompt_fallback_matches_active_questions_only(world):
    world.questions[1].isActive = False
    world.questions[1].retiredAt = "2026-03-01T00:00:00Z"
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How long have you practised this craft?", 2),
            _question("Who taught you?", 3),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4)),
    )
    _run(workbook.apply_parsed_edit("qnr-1", parsed))

    creates = _calls(world, "questionnairequestion", "create")
    assert [c["data"]["prompt"] for c in creates] == ["Who taught you?"]
    assert not [
        c for c in _calls(world, "questionnairequestion", "update") if c["where"]["id"] == "q-2"
    ]


def test_an_orphaned_question_is_reported_and_never_touched(world):
    world.questions.append(
        SimpleNamespace(
            id="q-orphan",
            questionnaireId="qnr-1",
            sectionId=None,
            sectionCode="Z",
            sectionTitle="Gone",
            prompt="A question with no section",
            helpText=None,
            isRequired=False,
            sortOrder=1,
            isActive=True,
            retiredAt=None,
            supersededById=None,
        )
    )
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How long have you practised this craft?", 2, questionId="q-1"),
            _question("Who taught you?", 3, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    problem = next(p for p in report["problems"] if "q-orphan" in p["reason"])
    assert problem["severity"] == "warning"
    assert not [
        c
        for c in _calls(world, "questionnairequestion", "update")
        + _calls(world, "questionnairequestion", "delete")
        if c.get("where", {}).get("id") == "q-orphan"
    ]


def test_answers_in_the_workbook_are_counted_reported_and_never_written(world):
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question(
                "How long have you practised this craft?",
                2,
                questionId="q-1",
                answers={"Ramesh": "Forty years"},
                answerNotes={"Ramesh": "Said through his son"},
            ),
            _question("Who taught you?", 3, questionId="q-2", answers={"Ramesh": "My father"}),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
        entryLabels=["Ramesh"],
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    writes = [
        op
        for model, op, _ in world.journal
        if model in ("questionnaireresponse", "questionnaireinterview") and op != "find_many"
    ]
    assert writes == [], writes
    assert report["answersSkipped"] == workbook._uploaded_answer_rows(parsed) == 2
    assert report["provenance"]["action"] == "answersNotImported"
    provenance = next(
        p for p in report["problems"] if p["reason"] == report["provenance"]["reason"]
    )
    assert provenance["severity"] == "warning"


REPORT_KEYS = {
    "created",
    "updated",
    "sections",
    "sectionsCreated",
    "sectionsRetired",
    "superseded",
    "retired",
    "removed",
    "unchanged",
    "answersSkipped",
    "assignedTasksAffected",
    "provenance",
    "versionBefore",
    "versionAfter",
    "problems",
    "details",
}


def test_the_report_carries_every_key_the_client_renders(world):
    parsed = _parsed(_section("A", "About", _question("How long?", 2)))
    _, created = _run(workbook.create_from_parsed(parsed))
    edited = _run(workbook.apply_parsed_edit("qnr-1", parsed))
    assert set(created) == REPORT_KEYS
    assert set(edited) == REPORT_KEYS


def test_sections_means_the_same_thing_on_both_paths(world):
    parsed = _parsed(
        _section("A", "About", _question("How long have you practised this craft?", 2)),
        _section("B", "Materials", _question("Where do you buy your yarn?", 3)),
    )
    _, created = _run(workbook.create_from_parsed(parsed))
    edited = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert created["sections"] == edited["sections"] == 2
    assert created["sectionsCreated"] == 2
    assert edited["sectionsCreated"] == 0



def test_a_created_question_keeps_its_help_text_and_required_flag(world):
    payload = workbook.QuestionnaireQuestionCreate(
        sectionId="sec-a",
        prompt="How is the yarn dyed?",
        helpText="Ask about the vat, not the colour.",
        isRequired=True,
    )
    _run(workbook.create_question(payload, _EDITOR))

    data = _calls(world, "questionnairequestion", "create")[0]["data"]
    assert data["helpText"] == "Ask about the vat, not the colour."
    assert data["isRequired"] is True


def test_clearing_help_text_through_the_editor_actually_clears_it(world):
    world.questions[0].helpText = "Something that should go away."
    payload = workbook.QuestionnaireQuestionUpdate(helpText=None)
    _run(workbook.update_question("q-1", payload, _EDITOR))

    data = _calls(world, "questionnairequestion", "update")[-1]["data"]
    assert "helpText" in data, data
    assert data["helpText"] is None


def test_omitting_help_text_from_a_patch_leaves_it_alone(world):
    world.questions[0].helpText = "Keep me."
    payload = workbook.QuestionnaireQuestionUpdate(prompt="A reworded question")
    _run(workbook.update_question("q-1", payload, _EDITOR))

    data = _calls(world, "questionnairequestion", "update")[-1]["data"]
    assert "helpText" not in data, data



def test_a_pro_forma_filled_in_uploaded_downloaded_and_uploaded_again_is_stable(world):
    from io import BytesIO

    from openpyxl import load_workbook

    from app.services.questionnaire_xlsx import (
        SHEET_QUESTIONS,
        build_pro_forma,
        build_questionnaire_workbook,
        parse_questionnaire_workbook,
    )

    book = load_workbook(BytesIO(build_pro_forma()))
    sheet = book[SHEET_QUESTIONS]
    typed = [
        ("A", "About the craft", "How long have you practised this craft?", "Count from the first unsupervised year.", "Yes"),
        ("", "", "Who taught you?", "", "No"),
        ("B", "Materials", "Where do you buy your yarn?", "", "Yes"),
    ]
    for offset, (code, title, prompt, help_text, required) in enumerate(typed, start=2):
        sheet.cell(row=offset, column=1, value=code)
        sheet.cell(row=offset, column=2, value=title)
        sheet.cell(row=offset, column=4, value=prompt)
        sheet.cell(row=offset, column=5, value=help_text)
        sheet.cell(row=offset, column=6, value=required)
    filled = BytesIO()
    book.save(filled)

    parsed = parse_questionnaire_workbook(filled.getvalue(), filename="craft-toolkit.xlsx")
    new_id, created = _run(
        workbook.create_from_parsed(parsed, title="3rd Craft Toolkit Workshop", created_by_id="admin-1")
    )
    assert created["sections"] == 2
    assert created["created"] == 3
    assert [p for p in created["problems"] if p["severity"] == "error"] == []

    stored = [
        c["data"]
        for model, op, c in world.journal
        if model == "questionnairequestion" and op == "create"
    ]
    assert [row["prompt"] for row in stored] == [t[2] for t in typed]
    assert stored[0]["helpText"] == "Count from the first unsupervised year."
    assert [row["isRequired"] for row in stored] == [True, False, True]

    payload = _run(workbook.export_instrument_payload(new_id))
    assert payload is not None
    assert payload["entry_labels"] == [], "the download must never carry an answer column per sitting"
    round_tripped = parse_questionnaire_workbook(build_questionnaire_workbook(**payload))
    assert round_tripped.questionnaireId == new_id

    world.journal.clear()
    report = _run(workbook.apply_parsed_edit(new_id, round_tripped))

    assert (report["created"], report["superseded"], report["retired"], report["removed"]) == (0, 0, 0, 0)
    assert report["sectionsRetired"] == 0
    assert report["unchanged"] == 3
    assert report["versionAfter"] == report["versionBefore"]
    assert _calls(world, "questionnairequestion", "create") == []
    assert _calls(world, "questionnairequestion", "delete") == []
    assert _calls(world, "questionnairesection", "delete") == []



def _recorded(world, question_id: str, *, by_lineage: bool = False) -> None:
    world.questionnairerecordingtarget.rows.append(
        SimpleNamespace(
            recordingId=f"rec-{question_id}",
            targetKey=f"Q:{question_id}",
            askedQuestionId=None if by_lineage else question_id,
            questionLineageId=question_id,
        )
    )


def test_h7_a_question_with_only_a_recording_is_superseded_not_reworded_in_place(world):
    _recorded(world, "q-1")
    parsed = _parsed(
        _section(
            "A",
            "About",
            _question("How many years have you worked this craft?", 2, questionId="q-1"),
            _question("Who taught you?", 3, questionId="q-2"),
        ),
        _section("B", "Materials", _question("Where do you buy your yarn?", 4, questionId="q-3")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert report["superseded"] == 1
    in_place = [
        c for c in _calls(world, "questionnairequestion", "update")
        if c["where"]["id"] == "q-1" and "prompt" in c["data"]
    ]
    assert in_place == []


def test_h7_a_recorded_question_missing_from_the_sheet_is_retired_never_deleted(world):
    _recorded(world, "q-2", by_lineage=True)
    world.questionnaireanswerdraft.rows.append(SimpleNamespace(id="d-1", questionId="q-3"))
    parsed = _parsed(
        _section("A", "About", _question("How long have you practised this craft?", 2, questionId="q-1")),
    )
    report = _run(workbook.apply_parsed_edit("qnr-1", parsed))

    assert _calls(world, "questionnairequestion", "delete") == []
    assert report["retired"] == 2


def test_h7_v2_answers_and_take_drafts_count_by_lineage(world):
    world.questionnaireanswer.rows.append(SimpleNamespace(id="ans-1", questionLineageId="q-2"))
    world.questionnairetakedraft.rows.append(SimpleNamespace(id="td-1", questionLineageId="q-3"))
    answered, _rows = _run(workbook._answer_evidence(world.questions, world.db))
    assert answered == {"q-2", "q-3"}


def test_h7_the_re_upload_runs_in_one_transaction(world):
    parsed = _parsed(
        _section("A", "About", _question("How long have you practised this craft?", 2, questionId="q-1")),
        _section("B", "Materials", _question("Where do you buy your yarn?", 3, questionId="q-3")),
    )
    _run(workbook.apply_parsed_edit("qnr-1", parsed))
    assert world.db.transactions == 1

