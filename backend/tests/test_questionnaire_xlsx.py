import re
import xml.etree.ElementTree as ET
import zipfile
from io import BytesIO

import pytest
from openpyxl import Workbook, load_workbook

from app.services.questionnaire_xlsx import (
    _OLE2_MAGIC,
    MAX_PROMPT_CHARS,
    SHEET_DETAILS,
    SHEET_HELP,
    SHEET_QUESTIONS,
    QuestionnaireXlsxError,
    build_pro_forma,
    build_question_set_workbook,
    build_questionnaire_workbook,
    download_filename,
    parse_questionnaire_workbook,
    question_set_filename,
)

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
TITLE_BAD = set(r"[]:*?/\\")

_ILLEGAL = re.compile(
    "[\x00-\x08\x0b\x0c\x0e-\x1f"
    f"{chr(0xD800)}-{chr(0xDFFF)}"
    f"{chr(0xFDD0)}-{chr(0xFDEF)}"
    + "".join(chr(p * 0x10000 + o) for p in range(17) for o in (0xFFFE, 0xFFFF))
    + "]"
)
_CHAR_REF = re.compile(r"&#(\d+);")

DIRTY = "Bell\x07 null\x00 vtab\x0b half-emoji \ud83d noncharacter ￾﷐\U0001FFFE end"

LONG_PROMPT = "तानाबाना बुनाई की प्रक्रिया बहुत पुरानी है। " * 900

ANSWER_SENTINEL = "SENTINEL-ANSWER-DO-NOT-EXPORT-22-years"
RESPONDENT_SENTINEL = "SENTINEL-RESPONDENT-Ramesh-Devi"


def _sections(*, with_answers: bool = False) -> list[dict]:
    answers = {RESPONDENT_SENTINEL: ANSWER_SENTINEL} if with_answers else {}
    return [
        {
            "code": "A",
            "title": "Weaver's tools [2024]",
            "questions": [
                {
                    "id": "q-tools-1",
                    "prompt": "How long have you practised this craft?",
                    "helpText": "=Count from the first year they worked unsupervised.",
                    "isRequired": True,
                    "answers": dict(answers),
                    "answerNotes": {},
                },
                {
                    "id": "q-tools-2",
                    "prompt": DIRTY,
                    "helpText": "चरखा / spinning wheel 🧵",
                    "isRequired": False,
                    "answers": {},
                    "answerNotes": dict(answers) if with_answers else {},
                },
            ],
        },
        {
            "code": "B",
            "title": "Materials",
            "questions": [
                {
                    "id": "q-mat-1",
                    "prompt": LONG_PROMPT,
                    "helpText": None,
                    "isRequired": False,
                    "answers": {},
                    "answerNotes": {},
                },
                {
                    "id": "q-mat-2",
                    "prompt": "#REF!",
                    "helpText": "==== read this ====",
                    "isRequired": True,
                    "answers": {},
                    "answerNotes": {},
                },
            ],
        },
        {"code": "C", "title": "Selling", "questions": []},
    ]


def _instrument(**kwargs) -> bytes:
    payload = {
        "title": "Craft toolkit questionnaire 🧵",
        "description": DIRTY,
        "questionnaire_id": "qnr-1",
        "version": 3,
        "sections": _sections(),
        "entry_labels": [],
    }
    payload.update(kwargs)
    return build_questionnaire_workbook(**payload)


def _question_set(**kwargs) -> bytes:
    payload = {
        "title": "Craft toolkit questionnaire 🧵",
        "description": "A description",
        "sections": _sections(),
        "source_title": "2nd Craft Toolkit Workshop",
        "exported_on": "2026-09-13",
    }
    payload.update(kwargs)
    return build_question_set_workbook(**payload)


@pytest.fixture(scope="module")
def workbooks() -> dict[str, bytes]:
    return {
        "pro-forma": build_pro_forma(),
        "instrument": _instrument(),
        "question set": _question_set(),
    }


def _parts(data: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(BytesIO(data)) as archive:
        return {name: archive.read(name) for name in archive.namelist()}



def test_the_pro_forma_opens_without_repair(workbooks):
    for label, data in workbooks.items():
        for name, blob in _parts(data).items():
            if not name.endswith(".xml") and not name.endswith(".rels"):
                continue
            try:
                ET.fromstring(blob)
            except ET.ParseError as exc:  # pragma: no cover
                raise AssertionError(f"{label}: {name} is not well-formed XML: {exc}") from exc


def test_no_illegal_codepoint_reaches_the_xml(workbooks):
    for label, data in workbooks.items():
        for name, blob in _parts(data).items():
            text = blob.decode("utf-8", "surrogatepass")
            assert not _ILLEGAL.search(text), f"{label}: illegal codepoint in {name}"
            for ref in _CHAR_REF.findall(text):
                assert not _ILLEGAL.match(chr(int(ref))), f"{label}: &#{ref}; in {name}"


def test_no_text_cell_was_written_as_a_formula(workbooks):
    for label, data in workbooks.items():
        for name, blob in _parts(data).items():
            if not name.startswith("xl/worksheets/"):
                continue
            root = ET.fromstring(blob)
            assert not list(root.iter(f"{MAIN}f")), f"{label}: a formula cell in {name}"


def test_a_help_text_starting_with_equals_survives(workbooks):
    parsed = parse_questionnaire_workbook(workbooks["instrument"])
    helps = [q.helpText for section in parsed.sections for q in section.questions]
    assert "=Count from the first year they worked unsupervised." in helps


def test_sheet_titles_are_legal_and_unique(workbooks):
    for label, data in workbooks.items():
        wb = load_workbook(BytesIO(data), read_only=True)
        titles = wb.sheetnames
        wb.close()
        assert len(titles) == len(set(titles)), f"{label}: duplicate sheet title"
        for title in titles:
            assert 0 < len(title) <= 31, f"{label}: {title!r} is not a legal length"
            assert not (set(title) & TITLE_BAD), f"{label}: {title!r} carries a forbidden character"


def test_the_pro_forma_has_no_example_questions(workbooks):
    wb = load_workbook(BytesIO(workbooks["pro-forma"]))
    sheet = wb[SHEET_QUESTIONS]
    rows = [
        [cell for cell in row if cell not in (None, "")]
        for row in sheet.iter_rows(min_row=2, values_only=True)
    ]
    wb.close()
    assert not any(rows), f"the pro-forma's working sheet has rows under its headings: {rows}"


def test_the_worked_example_has_no_answer_in_it(workbooks):
    wb = load_workbook(BytesIO(workbooks["pro-forma"]))
    sheet = wb[SHEET_HELP]
    rows = list(sheet.iter_rows(values_only=True))
    wb.close()
    header = next(
        (index for index, row in enumerate(rows) if row and row[0] == "Section Code"), None
    )
    assert header is not None, "the worked example's header row is not on the instructions sheet"
    answer_column = list(rows[header]).index("Answer")
    for row in rows[header + 1 : header + 4]:
        assert not (row[answer_column] or ""), (
            "the worked example types an answer into a column the instructions beside it call inert: "
            f"{row[answer_column]!r}"
        )


def test_the_instructions_sheet_says_retired_rows_appear(workbooks):
    wb = load_workbook(BytesIO(workbooks["pro-forma"]))
    text = "\n".join(
        str(cell or "") for row in wb[SHEET_HELP].iter_rows(values_only=True) for cell in row
    )
    wb.close()
    assert "Retired" in text, "the instructions sheet no longer explains that retired rows appear"



def _shape(parsed) -> list[tuple]:
    return [
        (
            section.code,
            section.title,
            [(q.questionId, q.prompt, q.helpText, q.isRequired) for q in section.questions],
        )
        for section in parsed.sections
    ]


def test_generate_then_parse_is_an_identity():
    parsed = parse_questionnaire_workbook(_instrument())
    assert parsed.questionnaireId == "qnr-1"
    assert [section.code for section in parsed.sections] == ["A", "B", "C"]
    prompts = [q.prompt for section in parsed.sections for q in section.questions]
    assert "How long have you practised this craft?" in prompts
    assert ["q-tools-1", "q-tools-2", "q-mat-1"] == [
        q.questionId for section in parsed.sections for q in section.questions
    ]
    required = [q.isRequired for section in parsed.sections for q in section.questions]
    assert required == [True, False, False]
    assert all(len(p) <= MAX_PROMPT_CHARS for p in prompts)


def test_it_round_trips_twice():
    once = parse_questionnaire_workbook(_instrument())
    again = parse_questionnaire_workbook(
        build_questionnaire_workbook(
            title=once.title or "",
            description=once.description,
            questionnaire_id=once.questionnaireId or "",
            version=3,
            sections=[
                {
                    "code": section.code,
                    "title": section.title,
                    "questions": [
                        {
                            "id": q.questionId or "",
                            "prompt": q.prompt,
                            "helpText": q.helpText,
                            "isRequired": q.isRequired,
                            "answers": {},
                            "answerNotes": {},
                        }
                        for q in section.questions
                    ],
                }
                for section in once.sections
            ],
            entry_labels=[],
        )
    )
    assert _shape(again) == _shape(once)


def _typed_workbook(rows: list[list], header: list[str], *, title_block: int = 0) -> bytes:
    wb = Workbook()
    sheet = wb.active
    sheet.title = "Sheet1"
    for index in range(title_block):
        sheet.cell(row=index + 1, column=1, value=f"Craft toolkit — draft {index}")
    for column, label in enumerate(header, start=1):
        sheet.cell(row=title_block + 1, column=column, value=label)
    for offset, row in enumerate(rows, start=title_block + 2):
        for column, value in enumerate(row, start=1):
            if value is not None:
                sheet.cell(row=offset, column=column, value=value)
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def test_a_renamed_reordered_and_pushed_down_header_still_reads():
    parsed = parse_questionnaire_workbook(
        _typed_workbook(
            header=["Answer", "section_code", "Section  Name ", "Question", "Required"],
            rows=[
                ["", "A", "About the craft", "How long have you practised this craft?", "Yes"],
                ["", "", "", "Who taught you?", "No"],
            ],
            title_block=4,
        )
    )
    assert [s.code for s in parsed.sections] == ["A"]
    assert [q.prompt for q in parsed.sections[0].questions] == [
        "How long have you practised this craft?",
        "Who taught you?",
    ]


def test_a_section_carries_down_blank_rows():
    parsed = parse_questionnaire_workbook(
        _typed_workbook(
            header=["Section Code", "Section Title", "Question"],
            rows=[
                ["A", "About the craft", "How long?"],
                [None, None, "Who taught you?"],
                [None, None, "Where did you learn?"],
                ["B", "Materials", "Where do you buy yarn?"],
            ],
        )
    )
    assert [(s.code, len(s.questions)) for s in parsed.sections] == [("A", 3), ("B", 1)]


def test_a_question_before_any_section_is_filed_under_general_with_a_warning():
    parsed = parse_questionnaire_workbook(
        _typed_workbook(
            header=["Section Title", "Question"],
            rows=[[None, "A question with no section"], ["Materials", "Where do you buy yarn?"]],
        )
    )
    assert parsed.sections[0].title == "General"
    warning = next(p for p in parsed.problems if "General" in p.reason)
    assert warning.severity == "warning"
    assert warning.row == 2


def test_an_unreadable_required_value_is_a_warning_with_its_excel_row_number():
    parsed = parse_questionnaire_workbook(
        _typed_workbook(
            header=["Section Title", "Question", "Required"],
            rows=[
                ["About", "How long have you practised this craft?", "Yes"],
                [None, "Who taught you?", "maybe"],
            ],
        )
    )
    question = parsed.sections[0].questions[1]
    assert question.isRequired is False
    problem = next(p for p in parsed.problems if "yes/no" in p.reason)
    assert problem.severity == "warning"
    assert problem.row == 3
    assert "maybe" in problem.reason


def test_a_formula_with_no_cached_result_is_reported_as_a_formula_not_as_blank():
    wb = Workbook()
    sheet = wb.active
    sheet["A1"] = "Section Title"
    sheet["B1"] = "Question"
    sheet["A2"] = "About"
    sheet["B2"] = "How long have you practised this craft?"
    sheet["B3"] = '=B2&" (2024)"'
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_questionnaire_workbook(buffer.getvalue())
    problem = next(p for p in parsed.problems if "formula" in p.reason)
    assert problem.severity == "error"
    assert problem.row == 3
    assert "B2" in (problem.value or "")


def test_a_row_with_an_unreadable_question_is_reported_rather_than_dropped():
    parsed = parse_questionnaire_workbook(
        _typed_workbook(
            header=["Section Title", "Question ID", "Question", "Help text", "Required"],
            rows=[
                ["About", "q-1", "How long have you practised this craft?", "", "Yes"],
                [None, None, None, None, None],
                [None, "q-2", "#REF!", "Ask before the loom is warped.", "Yes"],
            ],
        )
    )
    assert [q.questionId for q in parsed.sections[0].questions] == ["q-1"]
    problems = [p for p in parsed.problems if "no readable question" in p.reason]
    assert len(problems) == 1, [p.reason for p in parsed.problems]
    assert problems[0].row == 4
    assert problems[0].severity == "error"
    assert "q-2" in (problems[0].value or "")


def test_a_workbook_with_no_question_column_raises_QuestionnaireXlsxError():
    with pytest.raises(QuestionnaireXlsxError) as excinfo:
        parse_questionnaire_workbook(
            _typed_workbook(header=["Name", "Notes"], rows=[["Ramesh", "a note"]])
        )
    assert "Question" in str(excinfo.value)


def test_an_old_xls_gets_the_save_as_message():
    with pytest.raises(QuestionnaireXlsxError) as excinfo:
        parse_questionnaire_workbook(_OLE2_MAGIC + b"\x00" * 4096)
    message = str(excinfo.value)
    assert "Save As" in message
    assert "password" in message


def test_a_pdf_gets_the_not_a_workbook_message():
    with pytest.raises(QuestionnaireXlsxError) as excinfo:
        parse_questionnaire_workbook(b"%PDF-1.7\n" + b"\x00" * 2048)
    assert "not an Excel workbook" in str(excinfo.value)


def test_a_duplicate_question_id_imports_as_new_and_says_so():
    parsed = parse_questionnaire_workbook(
        _typed_workbook(
            header=["Section Title", "Question ID", "Question"],
            rows=[
                ["About", "q-1", "How long have you practised this craft?"],
                [None, "q-1", "Who taught you?"],
            ],
        )
    )
    questions = parsed.sections[0].questions
    assert [q.questionId for q in questions] == ["q-1", None]
    problem = next(p for p in parsed.problems if "more than once" in p.reason)
    assert problem.severity == "warning"
    assert problem.row == 3



def _grep_whole_zip(data: bytes, needle: str) -> list[str]:
    encoded = needle.encode("utf-8")
    with zipfile.ZipFile(BytesIO(data)) as archive:
        return [name for name in archive.namelist() if encoded in archive.read(name)]


def test_the_question_set_carries_no_answer_anywhere_in_the_zip():
    data = build_question_set_workbook(
        title="Craft toolkit questionnaire",
        description="A description",
        sections=_sections(with_answers=True),
        source_title="2nd Craft Toolkit Workshop",
        exported_on="2026-09-13",
    )
    assert _grep_whole_zip(data, ANSWER_SENTINEL) == []
    assert _grep_whole_zip(data, RESPONDENT_SENTINEL) == []


def test_the_instrument_workbook_carries_no_answer_anywhere_in_the_zip():
    data = _instrument(sections=_sections(with_answers=True), entry_labels=[])
    assert _grep_whole_zip(data, ANSWER_SENTINEL) == []
    assert _grep_whole_zip(data, RESPONDENT_SENTINEL) == []


def test_the_question_set_has_blank_question_ids_and_a_blank_questionnaire_id():
    sections = _sections()
    for section in sections:
        for question in section["questions"]:
            question["id"] = ""
    parsed = parse_questionnaire_workbook(_question_set(sections=sections))
    assert parsed.questionnaireId is None
    assert all(
        q.questionId is None for section in parsed.sections for q in section.questions
    )


def test_extra_details_rows_do_not_collide_with_the_description():
    parsed = parse_questionnaire_workbook(_question_set(description="The real description"))
    assert parsed.description == "The real description"


def test_two_downloads_of_one_questionnaire_have_different_filenames():
    title = "2nd Craft Toolkit Workshop"
    assert download_filename(title) != question_set_filename(title)
    assert question_set_filename(title).endswith("_Questions.xlsx")
    assert download_filename(title).endswith(".xlsx")


def test_the_details_sheet_is_named_so_the_parser_finds_it_again(workbooks):
    for label, data in workbooks.items():
        wb = load_workbook(BytesIO(data), read_only=True)
        names = wb.sheetnames
        wb.close()
        assert SHEET_DETAILS in names, f"{label} has no {SHEET_DETAILS} sheet"
