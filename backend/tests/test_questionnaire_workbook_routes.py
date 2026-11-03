import asyncio
import sys
from io import BytesIO
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI
from openpyxl import load_workbook

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services.questionnaire_xlsx import (
    _OLE2_MAGIC,
    SHEET_QUESTIONS,
    build_pro_forma,
)

ADMIN = SimpleNamespace(
    id="admin-1",
    email="admin@example.test",
    name="Admin",
    role="ADMIN",
    canManageQuestionnaire=False,
)

_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: ADMIN


def _filled_pro_forma() -> bytes:
    book = load_workbook(BytesIO(build_pro_forma()))
    sheet = book[SHEET_QUESTIONS]
    rows = [
        ("A", "About the craft", "How long have you practised this craft?", "Yes"),
        ("", "", "Who taught you?", "No"),
        ("B", "Materials", "Where do you buy your yarn?", "maybe"),
    ]
    for offset, (code, title, prompt, required) in enumerate(rows, start=2):
        sheet.cell(row=offset, column=1, value=code)
        sheet.cell(row=offset, column=2, value=title)
        sheet.cell(row=offset, column=4, value=prompt)
        sheet.cell(row=offset, column=6, value=required)
    out = BytesIO()
    book.save(out)
    return out.getvalue()


class _Delegate:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows or []
        self._next = 0

    async def create(self, data: dict[str, Any]) -> Any:
        self._next += 1
        row = SimpleNamespace(
            id=data.get("id") or f"made-{self._next}",
            version=1,
            isActive=True,
            isDefault=False,
            sortOrder=data.get("sortOrder", 1),
            sourceFilename=data.get("sourceFilename"),
            description=data.get("description"),
            createdAt="2026-09-13",
            **{k: v for k, v in data.items() if k not in ("id", "sortOrder", "sourceFilename", "description")},
        )
        self.rows.append(row)
        return row

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        for row in self.rows:
            if row.id == where.get("id"):
                for key, value in data.items():
                    setattr(row, key, value)
                return row
        return SimpleNamespace(id=where.get("id"), **data)

    async def update_many(self, **_: Any) -> int:
        return 0

    async def delete(self, **_: Any) -> None:
        return None

    async def count(self, **_: Any) -> int:
        return 0

    async def find_unique(self, where: dict[str, Any], **_: Any) -> Any:
        return next((row for row in self.rows if row.id == where.get("id")), None)

    async def find_many(self, where: dict[str, Any] | None = None, **kwargs: Any) -> list[Any]:
        rows = list(self.rows)
        for key, clause in (where or {}).items():
            if isinstance(clause, dict) and "in" in clause:
                rows = [r for r in rows if getattr(r, key, None) in clause["in"]]
            else:
                rows = [r for r in rows if getattr(r, key, None) == clause]
        return rows[: kwargs["take"]] if kwargs.get("take") else rows


class _Db:
    def __init__(self, **delegates: _Delegate) -> None:
        self._delegates = delegates

    def tx(self, **_: Any) -> Any:
        fake = self

        class _Block:
            async def __aenter__(self) -> Any:
                return fake

            async def __aexit__(self, *_exc: Any) -> bool:
                return False

        return _Block()

    def __getattr__(self, name: str) -> Any:
        try:
            return self._delegates[name]
        except KeyError as exc:  # pragma: no cover
            raise AssertionError(f"the route reached for db.{name}, which this test did not model") from exc


@pytest.fixture
def stub_db(monkeypatch: pytest.MonkeyPatch) -> _Db:
    instrument = SimpleNamespace(
        id="qnr-1",
        title="2nd Craft Toolkit Workshop",
        description=None,
        version=4,
        sortOrder=1,
        isActive=True,
        isDefault=True,
        sourceFilename=None,
        createdAt="2026-01-01",
    )
    db = _Db(
        questionnaire=_Delegate([instrument]),
        questionnairesection=_Delegate(),
        questionnairequestion=_Delegate(),
        questionnaireresponse=_Delegate(),
        questionnairesectionstatus=_Delegate(),
        assignedtask=_Delegate(),
        workshop=_Delegate(),
        questionnairerecordingtarget=_Delegate(),
        questionnaireanswer=_Delegate(),
        questionnaireanswerrevision=_Delegate(),
        questionnairetakedraft=_Delegate(),
        questionnaireanswerdraft=_Delegate(),
    )
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", db)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", db)
    return db


def _post(path: str, *, files: Any = None, data: Any = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://routes.test") as client:
            return await client.post(f"/api{path}", files=files, data=data)

    return asyncio.run(run())


def _get(path: str) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://routes.test") as client:
            return await client.get(f"/api{path}")

    return asyncio.run(run())


XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"



def test_the_pro_forma_downloads_as_a_workbook_with_a_filename_on_it(stub_db: _Db) -> None:
    response = _get("/questionnaires/pro-forma")

    assert response.status_code == 200, response.text
    assert response.headers["content-type"] == XLSX
    assert 'filename="FieldRepository_Questionnaire_Pro-Forma.xlsx"' in response.headers["content-disposition"]
    assert response.content.startswith(b"PK\x03\x04")


def test_a_download_for_a_questionnaire_that_is_gone_is_a_404_not_a_500(stub_db: _Db) -> None:
    for path in ("/questionnaires/nope/xlsx", "/questionnaires/nope/question-set.xlsx"):
        response = _get(path)
        assert response.status_code == 404, (path, response.text)



def test_a_docx_is_refused_on_its_name_before_the_body_is_touched(stub_db: _Db) -> None:
    response = _post(
        "/questionnaires/upload",
        files={"file": ("instrument.docx", b"PK\x03\x04not-a-workbook", "application/msword")},
    )

    assert response.status_code == 415, response.text
    detail = response.json()["detail"]
    assert "instrument.docx" in detail
    assert "Save As" in detail


def test_an_empty_upload_is_a_422_that_says_what_to_attach(stub_db: _Db) -> None:
    response = _post("/questionnaires/upload", files={"file": ("empty.xlsx", b"", XLSX)})

    assert response.status_code == 422, response.text
    assert "pro-forma" in response.json()["detail"]


def test_an_old_xls_renamed_to_xlsx_gets_the_save_as_message(stub_db: _Db) -> None:
    response = _post(
        "/questionnaires/upload",
        files={"file": ("instrument.xlsx", _OLE2_MAGIC + b"\x00" * 4096, XLSX)},
    )

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "Save As" in detail
    assert "password" in detail


def test_a_workbook_with_no_question_column_is_a_422_naming_the_column(stub_db: _Db) -> None:
    from openpyxl import Workbook

    book = Workbook()
    book.active["A1"] = "Name"
    book.active["B1"] = "Notes"
    book.active["A2"] = "Ramesh"
    buffer = BytesIO()
    book.save(buffer)

    response = _post("/questionnaires/upload", files={"file": ("notes.xlsx", buffer.getvalue(), XLSX)})

    assert response.status_code == 422, response.text
    assert "Question" in response.json()["detail"]



def test_an_uploaded_pro_forma_creates_a_questionnaire_and_reports_every_row_it_assumed_about(
    stub_db: _Db,
) -> None:
    response = _post(
        "/questionnaires/upload",
        files={"file": ("craft-toolkit.xlsx", _filled_pro_forma(), XLSX)},
    )

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["report"]["created"] == 3
    assert body["report"]["sections"] == 2
    assert body["questionnaire"]["id"]
    problem = next(p for p in body["report"]["problems"] if "yes/no" in p["reason"])
    assert problem["row"] == 4
    assert problem["severity"] == "warning"


def test_the_title_and_description_are_read_off_the_multipart_body_not_the_query_string(
    stub_db: _Db,
) -> None:
    response = _post(
        "/questionnaires/upload",
        files={"file": ("craft-toolkit.xlsx", _filled_pro_forma(), XLSX)},
        data={"title": "3rd Craft Toolkit Workshop", "description": "81 questions, 22 sections"},
    )

    assert response.status_code == 201, response.text
    created = stub_db.questionnaire.rows[-1]
    assert created.title == "3rd Craft Toolkit Workshop"
    assert created.description == "81 questions, 22 sections"
    assert created.sourceFilename == "craft-toolkit.xlsx"


def test_a_workbook_downloaded_from_another_questionnaire_is_a_409_before_anything_is_written(
    stub_db: _Db,
) -> None:
    from app.services.questionnaire_xlsx import build_questionnaire_workbook

    other = build_questionnaire_workbook(
        title="A different instrument",
        description=None,
        questionnaire_id="qnr-SOMETHING-ELSE",
        version=2,
        sections=[
            {
                "code": "A",
                "title": "About",
                "questions": [
                    {"id": "q-x", "prompt": "How long?", "helpText": None, "isRequired": False,
                     "answers": {}, "answerNotes": {}}
                ],
            }
        ],
        entry_labels=[],
    )

    response = _post("/questionnaires/qnr-1/upload", files={"file": ("wrong.xlsx", other, XLSX)})

    assert response.status_code == 409, response.text
    assert "qnr-SOMETHING-ELSE" in response.json()["detail"]
    assert stub_db.questionnairesection.rows == []
    assert stub_db.questionnairequestion.rows == []


def test_a_reupload_over_a_questionnaire_that_is_gone_is_a_404(stub_db: _Db) -> None:
    response = _post(
        "/questionnaires/nope/upload", files={"file": ("craft-toolkit.xlsx", _filled_pro_forma(), XLSX)}
    )
    assert response.status_code == 404, response.text


def test_an_oversized_workbook_is_a_413_that_names_the_limit(stub_db: _Db) -> None:
    oversized = b"PK\x03\x04" + b"\x00" * (9 * 1024 * 1024)

    response = _post("/questionnaires/upload", files={"file": ("huge.xlsx", oversized, XLSX)})

    assert response.status_code == 413, response.status_code
    detail = response.json()["detail"]
    assert "8 MB" in detail
    assert "questionnaire workbook" in detail
    assert stub_db.questionnaire.rows[-1].id == "qnr-1"
