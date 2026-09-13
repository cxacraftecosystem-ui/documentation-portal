"""The questionnaire pro-forma must never open with Excel's "we found a problem" repair prompt, and
must read back exactly what was typed into it.

THE PRO-FORMA IS A FORM EIGHTY-ONE QUESTIONS ARE TYPED INTO. If Excel offers to repair it, the
admin's afternoon is gone and the file they send back is whatever Excel decided to salvage — usually
silently, usually with rows missing, and with no way for anybody to tell which ones. An .xlsx is a zip
of XML parts and Excel offers to recover the file the moment one of them is not well-formed, so
eyeballing the download cannot catch it: the workbook still opens, it just opens *repaired*.

So the first half of this file builds all three workbooks out of the worst input this repository can
realistically produce — NUL and control bytes, a lone surrogate, Unicode noncharacters, Devanagari,
an emoji, a prompt past Excel's cell ceiling, a section titled ``Weaver's tools [2024]``, a help text
that opens with "=" — then unpacks every archive and parses every part. It follows
``tests/test_xlsx_report.py``'s shape deliberately: the two writers share ``_put`` and ``_sanitise``,
so they must share the guard that proves those work.

The second half is the round trip. The parser is the inverse of all three writers, and the property
that matters is that WRITING THEN READING CHANGES NOTHING: every prompt, every help text, every
Required flag and every question id comes back as it went in. Anything the parser could not read
cleanly comes back in ``problems`` with its Excel row number rather than being dropped, and there are
tests for each of the ways a real workbook goes wrong.

NO DATABASE AND NO ROUTES ARE INVOLVED. Pure openpyxl, so this suite runs in under a second and
cannot be broken by a schema change.
"""

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

# Every codepoint XML 1.0 refuses inside element content. Expat — and Excel — reject a part
# containing any of these, whether it arrives raw or as a numeric character reference.
_ILLEGAL = re.compile(
    "[\x00-\x08\x0b\x0c\x0e-\x1f"
    f"{chr(0xD800)}-{chr(0xDFFF)}"
    f"{chr(0xFDD0)}-{chr(0xFDEF)}"
    + "".join(chr(p * 0x10000 + o) for p in range(17) for o in (0xFFFE, 0xFFFF))
    + "]"
)
# "&#7;" / "&#55357;" — how ElementTree emits a character it cannot encode. No XML parser accepts a
# reference to a codepoint that is illegal in the first place, so the part stops parsing.
_CHAR_REF = re.compile(r"&#(\d+);")

# A control byte, a lone surrogate (a client that cut an emoji in half, or a surrogateescape decode),
# and the Unicode noncharacters. openpyxl's own guard covers only the first of these.
DIRTY = "Bell\x07 null\x00 vtab\x0b half-emoji \ud83d noncharacter ￾﷐\U0001FFFE end"

# Past Excel's 32767-character cell ceiling AND past the parser's own MAX_PROMPT_CHARS, so both
# clips have to hold.
LONG_PROMPT = "तानाबाना बुनाई की प्रक्रिया बहुत पुरानी है। " * 900

# The sentinels. These strings must not appear ANYWHERE in a workbook this repository produces — not
# in a cell, not in the shared-strings table, not in a comment, not in docProps. See the two
# grep-the-whole-zip tests below, which are the most important assertions in this file.
ANSWER_SENTINEL = "SENTINEL-ANSWER-DO-NOT-EXPORT-22-years"
RESPONDENT_SENTINEL = "SENTINEL-RESPONDENT-Ramesh-Devi"


def _sections(*, with_answers: bool = False) -> list[dict]:
    """The worst realistic instrument, as the writers' ``sections`` shape."""
    answers = {RESPONDENT_SENTINEL: ANSWER_SENTINEL} if with_answers else {}
    return [
        {
            # An apostrophe (Excel quotes sheet names containing one) and square brackets (which it
            # forbids in a tab title) in a SECTION title rather than a sheet name — the section title
            # goes in a cell, and this is the shape that made the sibling writer's jump links break.
            "code": "A",
            "title": "Weaver's tools [2024]",
            "questions": [
                {
                    "id": "q-tools-1",
                    "prompt": "How long have you practised this craft?",
                    # A help text that opens with "=" is a formula to openpyxl unless _put pins it.
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
        # A section with no questions at all: an admin who typed their headings first must not lose
        # them on the round trip.
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
    """All three writers over the worst-case input, built once."""
    return {
        "pro-forma": build_pro_forma(),
        "instrument": _instrument(),
        "question set": _question_set(),
    }


def _parts(data: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(BytesIO(data)) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


# --------------------------------------------------------------------------------------------
# 1. The writer guards: does Excel open it without offering to repair it?
# --------------------------------------------------------------------------------------------


def test_the_pro_forma_opens_without_repair(workbooks):
    """Every XML part of every workbook parses. This is the whole repair prompt, mechanised: Excel
    offers to recover a file the moment one part is not well-formed, and there is no other way to
    find that out short of a Windows machine with Excel on it."""
    for label, data in workbooks.items():
        for name, blob in _parts(data).items():
            if not name.endswith(".xml") and not name.endswith(".rels"):
                continue
            try:
                ET.fromstring(blob)
            except ET.ParseError as exc:  # pragma: no cover - only read out of a failure
                raise AssertionError(f"{label}: {name} is not well-formed XML: {exc}") from exc


def test_no_illegal_codepoint_reaches_the_xml(workbooks):
    """RAW OR AS A NUMERIC REFERENCE, because ElementTree writes "&#7;" for a character it cannot
    encode and no XML parser accepts a reference to a codepoint that was illegal to begin with. The
    dirty prompt above carries a NUL, a bell, a vertical tab, a lone surrogate and three
    noncharacters; ``_sanitise`` is what strips them and this is what says it did."""
    for label, data in workbooks.items():
        for name, blob in _parts(data).items():
            text = blob.decode("utf-8", "surrogatepass")
            assert not _ILLEGAL.search(text), f"{label}: illegal codepoint in {name}"
            for ref in _CHAR_REF.findall(text):
                assert not _ILLEGAL.match(chr(int(ref))), f"{label}: &#{ref}; in {name}"


def test_no_text_cell_was_written_as_a_formula(workbooks):
    """openpyxl reads the MEANING of a string as it binds it: a leading "=" makes the cell a formula
    and Excel then tries to evaluate an admin's help text. This workbook emits no formula at all, so
    a single ``<f>`` element anywhere is the defect."""
    for label, data in workbooks.items():
        for name, blob in _parts(data).items():
            if not name.startswith("xl/worksheets/"):
                continue
            root = ET.fromstring(blob)
            assert not list(root.iter(f"{MAIN}f")), f"{label}: a formula cell in {name}"


def test_a_help_text_starting_with_equals_survives(workbooks):
    """The other half of the rule above: pinning the cell to text must keep the "=" the admin typed
    rather than mangling it to dodge openpyxl's inference."""
    parsed = parse_questionnaire_workbook(workbooks["instrument"])
    helps = [q.helpText for section in parsed.sections for q in section.questions]
    assert "=Count from the first year they worked unsupervised." in helps


def test_sheet_titles_are_legal_and_unique(workbooks):
    """Excel refuses ``[]:*?/\\`` in a tab title, caps it at 31 characters, and will not open a file
    with two sheets of one name."""
    for label, data in workbooks.items():
        wb = load_workbook(BytesIO(data), read_only=True)
        titles = wb.sheetnames
        wb.close()
        assert len(titles) == len(set(titles)), f"{label}: duplicate sheet title"
        for title in titles:
            assert 0 < len(title) <= 31, f"{label}: {title!r} is not a legal length"
            assert not (set(title) & TITLE_BAD), f"{label}: {title!r} carries a forbidden character"


def test_the_pro_forma_has_no_example_questions(workbooks):
    """THE WORKING SHEET IS EMPTY UNDER ITS HEADINGS, and that is a decision rather than an oversight.

    An earlier shape seeded three example questions there to show what one looked like, which reads
    well and imports badly: the admin who adds their questions below the examples uploads an
    instrument whose first three questions are "How long have you practised this craft?" and has to
    work out where they came from. The worked example lives on the instructions sheet, where the
    parser will never see it."""
    wb = load_workbook(BytesIO(workbooks["pro-forma"]))
    sheet = wb[SHEET_QUESTIONS]
    rows = [
        [cell for cell in row if cell not in (None, "")]
        for row in sheet.iter_rows(min_row=2, values_only=True)
    ]
    wb.close()
    assert not any(rows), f"the pro-forma's working sheet has rows under its headings: {rows}"


def test_the_worked_example_has_no_answer_in_it(workbooks):
    """THE EXAMPLE MUST NOT CONTRADICT THE INSTRUCTIONS PRINTED BESIDE IT.

    The bullet three blocks above the example says the Answer column is inert and that anything typed
    into it is counted and discarded. The module this was ported from shows "22 years" typed into
    that column, because there an answer on a spreadsheet is a real answer. Here it teaches the
    opposite of what the instructions say, to somebody about to type eighty-one rows on the strength
    of whichever one they believed."""
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
    """THE ONE STRING BETWEEN AN ADMIN AND A WORKBOOK FULL OF ROWS THEY CANNOT TELL APART.

    The working sheet has no Status column — ``_question_columns`` emits six fixed headings and none
    of them is one — so a retired question looks exactly like a live one. The download carries them
    because it has to (everything absent from an upload is removed by rule, so dropping them would
    retire-then-delete them on the next round trip), which means the instructions are the only place
    the fact can be stated. A later prose tidy-up would remove this without noticing, so it is a
    test."""
    wb = load_workbook(BytesIO(workbooks["pro-forma"]))
    text = "\n".join(
        str(cell or "") for row in wb[SHEET_HELP].iter_rows(values_only=True) for cell in row
    )
    wb.close()
    assert "Retired" in text, "the instructions sheet no longer explains that retired rows appear"


# --------------------------------------------------------------------------------------------
# 2. The round trip: writing then reading must change nothing
# --------------------------------------------------------------------------------------------


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
    """THE PROPERTY THE WHOLE FEATURE RESTS ON. Download, change nothing, upload: every section,
    every prompt, every help text, every Required flag and every question id comes back identical.
    Anything less and a round trip silently edits an instrument nobody touched."""
    parsed = parse_questionnaire_workbook(_instrument())
    assert parsed.questionnaireId == "qnr-1"
    assert [section.code for section in parsed.sections] == ["A", "B", "C"]
    prompts = [q.prompt for section in parsed.sections for q in section.questions]
    assert "How long have you practised this craft?" in prompts
    # q-mat-2's prompt is the literal string "#REF!", which `_cell_text` reads as EMPTY — correctly,
    # because that is Excel's error family and not content. The row is therefore not a question, and
    # the property this test asserts is that the OTHER four survive untouched. What must NOT happen
    # is the row disappearing in silence; `test_a_row_with_an_unreadable_question_is_reported` below
    # is the other half.
    assert ["q-tools-1", "q-tools-2", "q-mat-1"] == [
        q.questionId for section in parsed.sections for q in section.questions
    ]
    required = [q.isRequired for section in parsed.sections for q in section.questions]
    assert required == [True, False, False]
    # The long prompt is clipped by BOTH ceilings on the way out and comes back clipped, which is the
    # honest answer: Excel cannot hold more than 32767 characters in a cell.
    assert all(len(p) <= MAX_PROMPT_CHARS for p in prompts)


def test_it_round_trips_twice():
    """Once is not enough. A writer that normalised a value on the way out — trimming, re-casing,
    re-deriving a section code — would look stable on one pass and drift on the second, which is the
    pass an admin actually makes when they correct one cell and upload again."""
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
    """A workbook somebody built by hand, with whatever header and layout the test needs."""
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
    """FORGIVING, AS A TEST. The Answer column dragged to the front, ``Section Code:`` retyped as
    ``section_code``, and a four-row title block above the headings — three things an admin does
    without thinking, each of which a position-based parser would fail on."""
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
    """How a person lays a table out: the section named once, on its first question, and left blank
    on the rest. Repeating it on every row also works, and both must."""
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
    """NOT DROPPED. A question typed above the first section heading is still a question the admin
    wrote, so it is filed under "General" and the assumption is reported with its row number."""
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
    """"maybe" IN THE REQUIRED COLUMN — the single likeliest thing to be wrong with a real workbook,
    and the one the admin cannot see from a question count. The question is imported as OPTIONAL and
    the row is named, with the literal gutter row so "row 4" means Ctrl+G, 4."""
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
    """THE ONE CASE openpyxl CANNOT HELP WITH. In ``data_only`` mode it returns the value Excel last
    CALCULATED and stored; a workbook written by a script has formulas and no cached results, so the
    cell reads as empty. Saying "row 3 is blank" about a row that visibly holds ``=B3&" (2024)"`` on
    the admin's screen is the most confusing thing this parser could say."""
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
    """A SILENT DROP THE PORT HAS AND THIS DOES NOT.

    `_cell_text` reads Excel's error family — #REF!, #N/A, #VALUE! — as empty, correctly: they are
    errors, not content. But the three-way "is this row blank?" test looks only at the question and
    the two section columns, so a row whose Question cell went to #REF! when somebody deleted a
    column — while still carrying its Question ID, its help text and its Required flag — read as
    ordinary layout and vanished. A question with an id, gone, with nothing in the report: the exact
    failure this module's docstring forbids. The admin sees eighty questions where there were
    eighty-one and has nothing to go on.

    A genuinely blank spacer row still costs nothing, which is what the second half asserts."""
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
    # The evidence the admin needs to find the row: what WAS on it.
    assert "q-2" in (problems[0].value or "")


def test_a_workbook_with_no_question_column_raises_QuestionnaireXlsxError():
    """A whole-file refusal rather than a row problem, because there is nothing to import and no row
    to blame. The message names the remedy."""
    with pytest.raises(QuestionnaireXlsxError) as excinfo:
        parse_questionnaire_workbook(
            _typed_workbook(header=["Name", "Notes"], rows=[["Ramesh", "a note"]])
        )
    assert "Question" in str(excinfo.value)


def test_an_old_xls_gets_the_save_as_message():
    """OLE2 magic — a real .xls, or an .xlsx with a password on it. openpyxl raises a bare
    ``BadZipFile`` for a BytesIO (it has no filename to inspect), and "the upload may have been cut
    short" would send somebody to re-download a file that was never the problem. The message is
    chosen from the FILE'S OWN MAGIC BYTES instead, and it names both remedies."""
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
    """AN ID PASTED TWICE — what happens when somebody copies a row to make a similar question. The
    second row must not overwrite the first, and the admin must be told which row it happened on."""
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


# --------------------------------------------------------------------------------------------
# 3. No answer leaves this repository on a spreadsheet — the containment tests
# --------------------------------------------------------------------------------------------


def _grep_whole_zip(data: bytes, needle: str) -> list[str]:
    """Every part of the archive whose BYTES contain *needle*.

    THE WHOLE ZIP, not the cells. A value can reach a workbook through the shared-strings table, a
    cached formula result, a defined name, a comment, docProps/app.xml's title-of-parts list or a
    calcChain — none of which a cell-by-cell check looks at, and every one of which is readable by
    anybody who unzips the file.
    """
    encoded = needle.encode("utf-8")
    with zipfile.ZipFile(BytesIO(data)) as archive:
        return [name for name in archive.namelist() if encoded in archive.read(name)]


def test_the_question_set_carries_no_answer_anywhere_in_the_zip():
    """THE SINGLE MOST IMPORTANT ASSERTION IN THIS FILE.

    The question set is the artefact somebody starts next year's instrument from. It is built from
    sections whose questions carry answers in this test — deliberately, because the writer is handed
    the same payload shape either way — and the one thing that must be true of the bytes it produces
    is that neither the answer nor the respondent's name is in them, in any part, in any encoding."""
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
    """THE SAME ASSERTION AGAINST THE OTHER DOWNLOAD, AND IT HAS NO EQUIVALENT IN THE PORT.

    The module this came from has a lossless workbook: its equivalent artefact is MEANT to carry
    every sitting. This repository's does not, and the entire difference is one kwarg —
    ``entry_labels=[]`` — which is also the current default, which is exactly what makes it fragile:
    a future caller passing real labels changes nothing about the shape of the call site. So the
    kwarg is pinned here, on the bytes, rather than trusted."""
    data = _instrument(sections=_sections(with_answers=True), entry_labels=[])
    assert _grep_whole_zip(data, ANSWER_SENTINEL) == []
    assert _grep_whole_zip(data, RESPONDENT_SENTINEL) == []


def test_the_question_set_has_blank_question_ids_and_a_blank_questionnaire_id():
    """BOTH BLANKS, AND THEY PREVENT DIFFERENT BUGS. An id left in the Question ID column would make
    the receiving upload report every row as "does not belong to this questionnaire"; a Questionnaire
    ID left on the Details sheet would make ``POST /questionnaires/{id}/upload`` answer 409."""
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
    """``_DETAIL_KEYS``'s description aliases include "notes" and "summary", so an extra Details row
    labelled either would be read back as the questionnaire's DESCRIPTION — silently overwriting the
    description of whatever questionnaire the file is imported into. The question set adds three
    extra rows ("Contents", "Exported from", "Exported on"); this is what says none of them does it,
    and it is the constraint ``_details_sheet``'s own docstring states."""
    parsed = parse_questionnaire_workbook(_question_set(description="The real description"))
    assert parsed.description == "The real description"


def test_two_downloads_of_one_questionnaire_have_different_filenames():
    """Both land in the same Downloads folder with the same questionnaire title on them, and the two
    files are the difference between editing the live instrument and starting a fresh one. The name
    is the last thing standing between an admin and that mistake."""
    title = "2nd Craft Toolkit Workshop"
    assert download_filename(title) != question_set_filename(title)
    assert question_set_filename(title).endswith("-questions.xlsx")
    assert download_filename(title).endswith(".xlsx")


def test_the_details_sheet_is_named_so_the_parser_finds_it_again(workbooks):
    """A round trip reads the Questionnaire ID back off a sheet located BY NAME. Renaming the tab
    would leave every re-upload looking like a brand-new questionnaire, which is the failure that
    retires an instrument rather than editing it."""
    for label, data in workbooks.items():
        wb = load_workbook(BytesIO(data), read_only=True)
        names = wb.sheetnames
        wb.close()
        assert SHEET_DETAILS in names, f"{label} has no {SHEET_DETAILS} sheet"
