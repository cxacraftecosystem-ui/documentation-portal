from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from io import BytesIO
from typing import Any
from zipfile import BadZipFile, ZipFile

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils.exceptions import InvalidFileException
from openpyxl.worksheet.worksheet import Worksheet

from app.services.xlsx_report import XLSX_MIME, _put, _sanitise
from app.services import naming

__all__ = [
    "MAX_QUESTIONS",
    "MAX_SECTIONS",
    "PRO_FORMA_FILENAME",
    "QUESTION_SET_CONTENTS",
    "SHEET_QUESTIONS",
    "XLSX_MIME",
    "ParseProblem",
    "ParsedQuestion",
    "ParsedQuestionnaire",
    "ParsedSection",
    "QuestionnaireXlsxError",
    "build_pro_forma",
    "build_question_set_workbook",
    "build_questionnaire_workbook",
    "derive_section_code",
    "download_filename",
    "parse_questionnaire_workbook",
    "question_set_filename",
]

PRO_FORMA_FILENAME = naming.export_filename("Questionnaire", "Pro-Forma", "xlsx", dated=False)

SHEET_QUESTIONS = "Questionnaire"
SHEET_DETAILS = "Details"
SHEET_HELP = "How to fill this in"

_BRAND = "5B21B6"
_WHITE = "FFFFFFFF"

_WIDTHS = {
    "sectionCode": 14,
    "sectionTitle": 30,
    "questionId": 26,
    "prompt": 62,
    "help": 34,
    "required": 11,
    "answer": 46,
    "notes": 34,
}

MAX_QUESTIONS = 2000
MAX_SECTIONS = 200
MAX_ROWS_SCANNED = 20000
MAX_SHEETS_SCANNED = 12
MAX_COLUMNS = 512
MAX_CELLS = 2_000_000
MAX_INFLATED_BYTES = 200 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 2_000
MAX_HEADER_SCAN_ROWS = 15

MAX_PROMPT_CHARS = 2000
MAX_TITLE_CHARS = 220
MAX_CODE_CHARS = 24
MAX_ANSWER_CHARS = 8000

_DEFAULT_SECTION_TITLE = "General"

_UPLOAD_ENTRY_LABEL = "Answer"


class QuestionnaireXlsxError(ValueError):
    pass


_SPACEY = re.compile(r"[\s  -\u200b  　]+")
_PUNCT = re.compile(r"[^\w\s]+", re.UNICODE)


def _norm_header(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    text = _PUNCT.sub(" ", text.replace("_", " "))
    return _SPACEY.sub(" ", text).strip().lower()


_COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    "sectionCode": (
        "section code",
        "sectioncode",
        "section ref",
        "section id",
        "code",
        "s no section",
    ),
    "sectionTitle": (
        "section title",
        "section name",
        "sectiontitle",
        "section heading",
        "section",
        "part",
        "theme",
    ),
    "questionId": (
        "question id",
        "questionid",
        "id",
        "q id",
        "qid",
        "ref",
        "reference",
        "question ref",
    ),
    "prompt": ("question", "questions", "prompt", "question text", "the question", "item", "q"),
    "help": (
        "help",
        "help text",
        "helptext",
        "guidance",
        "hint",
        "instruction",
        "instructions",
        "description",
    ),
    "required": ("required", "mandatory", "is required", "compulsory", "must answer"),
}
_ANSWER_PREFIXES = ("answer", "response", "reply")
_NOTES_PREFIXES = ("notes", "note", "remarks", "remark", "comment", "comments", "observation")
_LABEL_SPLIT = re.compile(r"\s*(?:[-–—:/|]|\bfor\b|\bby\b)\s*", re.IGNORECASE)

_TRUEISH = {"y", "yes", "true", "t", "1", "required", "mandatory", "compulsory", "x", "✓", "✔", "☑"}
_FALSEISH = {"n", "no", "false", "f", "0", "optional", "not required", "", "-", "—", "na", "n a"}


@dataclass(frozen=True)
class ParseProblem:
    sheet: str
    row: int | None
    severity: str
    reason: str
    value: str | None = None

    def payload(self) -> dict[str, Any]:
        return {
            "sheet": self.sheet,
            "row": self.row,
            "severity": self.severity,
            "reason": self.reason,
            "value": self.value,
        }


@dataclass
class ParsedQuestion:
    prompt: str
    row: int
    questionId: str | None = None
    helpText: str | None = None
    isRequired: bool = False
    answers: dict[str, str] = field(default_factory=dict)
    answerNotes: dict[str, str] = field(default_factory=dict)


@dataclass
class ParsedSection:
    code: str
    title: str
    questions: list[ParsedQuestion] = field(default_factory=list)


@dataclass
class ParsedQuestionnaire:
    sections: list[ParsedSection] = field(default_factory=list)
    problems: list[ParseProblem] = field(default_factory=list)
    title: str | None = None
    description: str | None = None
    questionnaireId: str | None = None
    sheet: str | None = None
    entryLabels: list[str] = field(default_factory=list)

    @property
    def questionCount(self) -> int:
        return sum(len(section.questions) for section in self.sections)

    @property
    def hasAnswers(self) -> bool:
        return any(q.answers for section in self.sections for q in section.questions)

    def payload(self) -> dict[str, Any]:
        return {
            "title": self.title,
            "description": self.description,
            "sheet": self.sheet,
            "sectionCount": len(self.sections),
            "questionCount": self.questionCount,
            "entryLabels": self.entryLabels,
            "problems": [p.payload() for p in self.problems],
        }



class _FormulaCell(str):
    pass


def _cell_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "Yes" if value else "No"
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    text = str(value).strip()
    if text.startswith("#") and text.endswith("!") and len(text) <= 10:
        return ""
    if text in {"#N/A", "#NULL!"}:
        return ""
    return text


def _clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _truthy(text: str) -> bool | None:
    key = _norm_header(text)
    if key in _TRUEISH:
        return True
    if key in _FALSEISH:
        return False
    return None


def derive_section_code(title: str, taken: set[str]) -> str:
    base = re.sub(r"[^A-Za-z0-9]+", "_", unicodedata.normalize("NFKD", title)).strip("_").upper()
    base = base[:MAX_CODE_CHARS].strip("_") or "SECTION"
    candidate = base
    n = 2
    while candidate.lower() in taken:
        suffix = f"_{n}"
        candidate = base[: MAX_CODE_CHARS - len(suffix)] + suffix
        n += 1
    taken.add(candidate.lower())
    return candidate



def _role_for(header: str) -> tuple[str, str | None] | None:
    if not header:
        return None
    for role, aliases in _COLUMN_ALIASES.items():
        if header in aliases:
            return (role, None)
    for prefix in _ANSWER_PREFIXES:
        if header == prefix or header.startswith(prefix + " "):
            return ("answer", _label_after(header, prefix))
    for prefix in _NOTES_PREFIXES:
        if header == prefix or header.startswith(prefix + " "):
            return ("notes", _label_after(header, prefix))
    return None


def _label_after(header: str, prefix: str) -> str | None:
    rest = header[len(prefix) :].strip()
    rest = _LABEL_SPLIT.sub(" ", rest).strip()
    return rest.title() if rest else None


def _scan_header(
    rows: list[tuple[int, list[str]]],
) -> tuple[int, dict[int, tuple[str, str | None]]] | None:
    for row_number, values in rows[:MAX_HEADER_SCAN_ROWS]:
        mapping: dict[int, tuple[str, str | None]] = {}
        seen_roles: set[str] = set()
        for index, raw in enumerate(values):
            role = _role_for(_norm_header(raw))
            if role is None:
                continue
            if role[0] not in ("answer", "notes") and role[0] in seen_roles:
                continue
            seen_roles.add(role[0])
            mapping[index] = role
        if "prompt" in seen_roles:
            return row_number, mapping
    return None


TOO_LARGE = (
    "The workbook is too large to read. Delete empty rows and columns, or split it into "
    "smaller workbooks, and upload it again."
)


class _CellBudget:
    def __init__(self, cells: int = MAX_CELLS) -> None:
        self.left = cells

    def spend(self, cells: int) -> None:
        self.left -= cells
        if self.left < 0:
            raise QuestionnaireXlsxError(TOO_LARGE)


def _bounded_rows(ws: Any, budget: _CellBudget) -> Any:
    reset = getattr(ws, "reset_dimensions", None)
    if reset is not None:
        reset()
    for row in ws.iter_rows(min_row=1, max_row=MAX_ROWS_SCANNED, values_only=True):
        kept = row[:MAX_COLUMNS]
        budget.spend(len(kept))
        yield kept


def _check_archive(data: bytes) -> None:
    try:
        with ZipFile(BytesIO(data)) as archive:
            members = archive.infolist()
    except (BadZipFile, OSError, ValueError):
        return
    if len(members) > MAX_ARCHIVE_MEMBERS:
        raise QuestionnaireXlsxError(TOO_LARGE)
    if sum(member.file_size for member in members) > MAX_INFLATED_BYTES:
        raise QuestionnaireXlsxError(TOO_LARGE)


def _sheet_rows(
    ws: Worksheet, formulas: dict[tuple[int, int], str], budget: _CellBudget | None = None
) -> list[tuple[int, list[str]]]:
    out: list[tuple[int, list[str]]] = []
    for row_number, row in enumerate(_bounded_rows(ws, budget or _CellBudget()), start=1):
        cells: list[str] = []
        for column, value in enumerate(row, start=1):
            text = _cell_text(value)
            if not text:
                formula = formulas.get((row_number, column))
                if formula:
                    cells.append(_FormulaCell(formula))
                    continue
            cells.append(text)
        out.append((row_number, cells))
    return out


def _formula_map(data: bytes) -> dict[str, dict[tuple[int, int], str]]:
    found: dict[str, dict[tuple[int, int], str]] = {}
    try:
        wb = load_workbook(BytesIO(data), data_only=False, read_only=True)
    except (InvalidFileException, OSError, ValueError, KeyError, TypeError):
        return found
    budget = _CellBudget()
    try:
        for ws in wb.worksheets[:MAX_SHEETS_SCANNED]:
            per_sheet: dict[tuple[int, int], str] = {}
            for row_number, row in enumerate(_bounded_rows(ws, budget), start=1):
                for column, value in enumerate(row, start=1):
                    if isinstance(value, str) and value.startswith("="):
                        per_sheet[(row_number, column)] = value
            found[ws.title] = per_sheet
    finally:
        wb.close()
    return found


_DETAIL_KEYS = {
    "title": ("questionnaire title", "title", "name", "questionnaire name", "form title"),
    "description": ("description", "purpose", "about", "notes", "summary"),
    "questionnaireId": ("questionnaire id", "id", "questionnaire ref", "form id"),
}


def _read_details(rows: list[tuple[int, list[str]]]) -> dict[str, str]:
    found: dict[str, str] = {}
    for _row_number, values in rows[:60]:
        if not values:
            continue
        key = _norm_header(values[0])
        for name, aliases in _DETAIL_KEYS.items():
            if key in aliases and name not in found:
                value = next((v for v in values[1:] if v and not isinstance(v, _FormulaCell)), "")
                if value:
                    found[name] = value
    return found


_ZIP_MAGIC = b"PK\x03\x04"
_OLE2_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"


def _unreadable_file_message(data: bytes) -> str:
    head = data[:8]
    if head.startswith(_OLE2_MAGIC):
        return (
            "That file is in the older .xls format, or it is an .xlsx with a password on it. Open "
            "it in Excel, remove any password, then use File > Save As and choose "
            "'Excel Workbook (.xlsx)'."
        )
    if not head.startswith(_ZIP_MAGIC):
        return (
            "That is not an Excel workbook — a .csv, a .numbers file or a PDF will not do. Fill in "
            "the .xlsx pro-forma, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
        )
    return (
        "The workbook could not be opened — it may be password-protected or the upload may have "
        "been cut short. Open it in Excel, use File > Save As to save a fresh copy, and upload that."
    )


def parse_questionnaire_workbook(
    data: bytes, *, filename: str | None = None
) -> ParsedQuestionnaire:
    if not data:
        raise QuestionnaireXlsxError("The upload was empty. Attach the filled-in .xlsx pro-forma.")
    _check_archive(data)
    try:
        wb = load_workbook(BytesIO(data), data_only=True, read_only=True)
    except (InvalidFileException, BadZipFile, OSError, ValueError, KeyError, TypeError) as exc:
        raise QuestionnaireXlsxError(_unreadable_file_message(data)) from exc

    try:
        formulas = _formula_map(data)
        sheets: dict[str, list[tuple[int, list[str]]]] = {}
        budget = _CellBudget()
        for ws in wb.worksheets[:MAX_SHEETS_SCANNED]:
            sheets[ws.title] = _sheet_rows(ws, formulas.get(ws.title, {}), budget)
    finally:
        wb.close()

    if not sheets:
        raise QuestionnaireXlsxError("The workbook has no sheets in it.")

    result = ParsedQuestionnaire()

    for name, rows in sheets.items():
        if _norm_header(name) in (
            "details",
            "about",
            "questionnaire details",
            "info",
            "information",
        ):
            details = _read_details(rows)
            result.title = (
                _clip(details["title"], MAX_TITLE_CHARS) if details.get("title") else None
            )
            result.description = details.get("description") or None
            result.questionnaireId = details.get("questionnaireId") or None
            break

    picked = _pick_sheet(sheets, result.problems)
    if picked is None:
        raise QuestionnaireXlsxError(
            "No sheet in that workbook has a 'Question' column, so there was nothing to import. "
            "Download the pro-forma and type your questions under its headings, or add a header row "
            "with a column called 'Question'."
        )
    sheet_name, header_row, columns = picked
    result.sheet = sheet_name
    _read_questions(sheets[sheet_name], sheet_name, header_row, columns, result)

    if not result.title and filename:
        stem = re.sub(r"\.(xlsx|xlsm|xltx)$", "", filename, flags=re.IGNORECASE).strip()
        stem = re.sub(r"[_-]+", " ", stem).strip()
        if stem:
            result.title = _clip(stem, MAX_TITLE_CHARS)

    if not result.questionCount:
        raise QuestionnaireXlsxError(
            f"The '{sheet_name}' sheet has a Question column but no questions under it. Type at "
            "least one question and upload again."
        )
    return result


def _pick_sheet(
    sheets: dict[str, list[tuple[int, list[str]]]],
    problems: list[ParseProblem],
) -> tuple[str, int, dict[int, tuple[str, str | None]]] | None:
    candidates: list[tuple[str, int, dict[int, tuple[str, str | None]]]] = []
    for name, rows in sheets.items():
        found = _scan_header(rows)
        if found is not None:
            candidates.append((name, found[0], found[1]))
    if not candidates:
        return None
    for candidate in candidates:
        name = candidate[0]
        if _norm_header(name) in (_norm_header(SHEET_QUESTIONS), "questions", "form", "sheet1"):
            if len(candidates) > 1:
                others = [c[0] for c in candidates if c[0] != name]
                problems.append(
                    ParseProblem(
                        sheet=name,
                        row=None,
                        severity="warning",
                        reason=(
                            "More than one sheet looked like a questionnaire; this one was used. "
                            f"Ignored: {', '.join(others)}."
                        ),
                    )
                )
            return candidate
    best = max(candidates, key=lambda c: len(sheets[c[0]]) - c[1])
    if len(candidates) > 1:
        others = [c[0] for c in candidates if c[0] != best[0]]
        problems.append(
            ParseProblem(
                sheet=best[0],
                row=None,
                severity="warning",
                reason=(
                    "No sheet was named 'Questionnaire', so the one with the most questions was "
                    f"used. Ignored: {', '.join(others)}."
                ),
            )
        )
    return best


def _read_questions(
    rows: list[tuple[int, list[str]]],
    sheet: str,
    header_row: int,
    columns: dict[int, tuple[str, str | None]],
    result: ParsedQuestionnaire,
) -> None:
    sections: dict[str, ParsedSection] = {}
    order: list[str] = []
    codes_taken: set[str] = set()
    current: ParsedSection | None = None
    seen_ids: set[str] = set()
    entry_labels: list[str] = []
    kept = 0

    def get(values: list[str], role: str, label: str | None = None) -> str:
        for index, (this_role, this_label) in columns.items():
            if this_role == role and this_label == label and index < len(values):
                value = values[index]
                return "" if isinstance(value, _FormulaCell) else value
        return ""

    def formula_in(values: list[str]) -> str | None:
        for index in sorted(columns):
            if index < len(values) and isinstance(values[index], _FormulaCell):
                return str(values[index])
        return None

    def section_for(code: str, title: str, row_number: int) -> ParsedSection:
        nonlocal current
        key = (code or derive_section_code(title, set())).lower()
        existing = sections.get(key)
        if existing is not None:
            if title and existing.title != title:
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="warning",
                        reason=(
                            f"Section '{existing.code}' is titled '{existing.title}' earlier in the "
                            f"sheet; the first title was kept."
                        ),
                        value=title,
                    )
                )
            current = existing
            return existing
        if len(sections) >= MAX_SECTIONS:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="error",
                    reason=f"More than {MAX_SECTIONS} sections; the rest of the sheet was ignored.",
                )
            )
            raise _StopReading
        final_code = code or derive_section_code(title, codes_taken)
        if code:
            codes_taken.add(code.lower())
        made = ParsedSection(code=final_code, title=title or final_code)
        sections[key] = made
        order.append(key)
        current = made
        return made

    for row_number, values in rows:
        if row_number <= header_row:
            continue
        prompt = get(values, "prompt")
        code = _clip(get(values, "sectionCode"), MAX_CODE_CHARS)
        title = _clip(get(values, "sectionTitle"), MAX_TITLE_CHARS)

        if not prompt and not code and not title:
            formula = formula_in(values)
            if formula:
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="error",
                        reason=(
                            "This row holds a formula whose result is not saved in the file, so "
                            "there was nothing to read. Open the workbook in Excel and save it "
                            "again, or replace the formula with the text it produces."
                        ),
                        value=_clip(formula, 120),
                    )
                )
                continue
            leftovers = [
                _clip(str(values[index]), 60)
                for index, (role, _label) in sorted(columns.items())
                if role not in ("sectionCode", "sectionTitle", "prompt")
                and index < len(values)
                and values[index]
            ]
            if leftovers:
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="error",
                        reason=(
                            "This row has something on it but no readable question, so it was not "
                            "imported. The Question cell is empty or holds an Excel error such as "
                            "#REF! — retype the question text into it and upload again."
                        ),
                        value=_clip(" · ".join(leftovers), 120),
                    )
                )
            continue

        try:
            if not prompt:
                section_for(code, title, row_number)
                continue
            if code or title:
                section_for(code, title, row_number)
            if current is None:
                current = section_for("", _DEFAULT_SECTION_TITLE, row_number)
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="warning",
                        reason=(
                            "This question came before any section was named, so it was filed under "
                            f"'{_DEFAULT_SECTION_TITLE}'. Add a Section Title to move it."
                        ),
                        value=_clip(prompt, 120),
                    )
                )
        except _StopReading:
            break

        if kept >= MAX_QUESTIONS:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="error",
                    reason=f"More than {MAX_QUESTIONS} questions; the rest of the sheet was ignored.",
                )
            )
            break

        if len(prompt) > MAX_PROMPT_CHARS:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="warning",
                    reason=f"The question was longer than {MAX_PROMPT_CHARS} characters and was shortened.",
                    value=_clip(prompt, 120),
                )
            )
            prompt = _clip(prompt, MAX_PROMPT_CHARS)

        question_id = get(values, "questionId") or None
        if question_id and question_id in seen_ids:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="warning",
                    reason=(
                        f"Question ID '{question_id}' appears more than once. This row was imported "
                        "as a NEW question rather than overwriting the earlier one."
                    ),
                    value=_clip(prompt, 120),
                )
            )
            question_id = None
        if question_id:
            seen_ids.add(question_id)

        required_raw = get(values, "required")
        required = _truthy(required_raw)
        if required is None:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="warning",
                    reason=(
                        f"'{_clip(required_raw, 40)}' is not a yes/no value, so the question was "
                        "imported as optional. Use Yes or No."
                    ),
                    value=_clip(prompt, 120),
                )
            )
            required = False

        question = ParsedQuestion(
            prompt=prompt,
            row=row_number,
            questionId=question_id,
            helpText=get(values, "help") or None,
            isRequired=required,
        )
        for index, (role, label) in sorted(columns.items()):
            if role not in ("answer", "notes") or index >= len(values):
                continue
            raw = values[index]
            if isinstance(raw, _FormulaCell):
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="warning",
                        reason=(
                            "The answer on this row is a formula whose result is not saved in the "
                            "file, so the question was imported without it."
                        ),
                        value=_clip(prompt, 120),
                    )
                )
                continue
            if not raw:
                continue
            name = label or _UPLOAD_ENTRY_LABEL
            if name not in entry_labels:
                entry_labels.append(name)
            target = question.answers if role == "answer" else question.answerNotes
            target[name] = _clip(raw, MAX_ANSWER_CHARS)
        current.questions.append(question)
        kept += 1

    result.sections = [sections[key] for key in order]
    result.entryLabels = entry_labels


class _StopReading(Exception):
    pass



def _header(ws: Worksheet, row: int, labels: list[tuple[str, str]]) -> None:
    fill = PatternFill(fill_type="solid", start_color=f"FF{_BRAND}")
    for index, (key, label) in enumerate(labels, start=1):
        cell = _put(ws, row, index, label)
        cell.font = Font(bold=True, color=_WHITE)
        cell.fill = fill
        cell.alignment = Alignment(horizontal="left", vertical="center", wrap_text=True)
        letter = ws.cell(row=row, column=index).column_letter
        ws.column_dimensions[letter].width = _WIDTHS.get(key, 24)
    ws.row_dimensions[row].height = 28
    ws.freeze_panes = f"A{row + 1}"


_DETAILS_NOTE = (
    "Type your questionnaire's name and purpose above. Leave 'Questionnaire ID' exactly as "
    "you found it — it is how the app recognises this file as an edit of a questionnaire you "
    "have already uploaded rather than a brand new one."
)


def _details_sheet(
    ws: Worksheet,
    *,
    title: str,
    description: str,
    questionnaire_id: str,
    version: int | None,
    extra: list[tuple[str, str]] | None = None,
    note: str = _DETAILS_NOTE,
) -> None:
    ws.sheet_properties.tabColor = f"FF{_BRAND}"
    heading = _put(ws, 1, 1, "Questionnaire details")
    heading.font = Font(bold=True, size=14, color=f"FF{_BRAND}")
    pairs = [
        ("Questionnaire title", title),
        ("Description", description),
        ("Questionnaire ID", questionnaire_id),
        ("Version", str(version) if version is not None else ""),
        *(extra or []),
    ]
    for offset, (label, value) in enumerate(pairs):
        row = 3 + offset
        cell = _put(ws, row, 1, label)
        cell.font = Font(bold=True)
        value_cell = _put(ws, row, 2, value)
        value_cell.alignment = Alignment(wrap_text=True, vertical="top")
    note_row = 3 + len(pairs) + 1
    note_cell = _put(ws, note_row, 1, note)
    note_cell.alignment = Alignment(wrap_text=True, vertical="top")
    note_cell.font = Font(italic=True, color="FF6B7280")
    ws.merge_cells(start_row=note_row, start_column=1, end_row=note_row + 2, end_column=4)
    ws.column_dimensions["A"].width = 24
    ws.column_dimensions["B"].width = 62


_HELP_LINES = [
    ("h", "How to fill this in"),
    ("p", "Type your questions on the 'Questionnaire' sheet, one per row, then upload the file."),
    (
        "b",
        "Sections — put the section's name in 'Section Title'. Fill it in on the first question of the section and leave it blank on the rest, or repeat it on every row; both work. Leave 'Section Code' blank and the app will create one for you.",
    ),
    (
        "b",
        "Questions — 'Question' is the only column you must fill in. Every other column is optional.",
    ),
    ("b", "Required — type Yes or No. Anything else is read as No and reported back to you."),
    (
        "b",
        "Answers — leave the 'Answer' column empty. Answers are recorded in the app, against an artisan, by the researcher who ran the interview. Anything typed into this column is counted and reported back to you when you upload, and is NOT stored: a spreadsheet cannot say which artisan gave an answer or who recorded it.",
    ),
    (
        "b",
        "Question ID — leave it blank for a new question. On a questionnaire you downloaded back out of the app it is already filled in; DO NOT EDIT OR DELETE IT. It is how the app knows that row is the same question researchers have already recorded answers against.",
    ),
    (
        "b",
        "You can add columns of your own, move columns around, and put a title above the headings. The app finds the columns by their headings, not by where they are.",
    ),
    ("h", "Editing a questionnaire people have already answered"),
    (
        "p",
        "Once an answer has been recorded against a question, that question stops being editable — an answer only means anything next to the words it was given under.",
    ),
    (
        "b",
        "Change the wording of an answered question and the app keeps the old question and its answers, and adds your new wording as a new question underneath. Nothing is lost and no recorded answer changes meaning. Researchers already part-way through an interview keep answering the old wording; new interviews get the new one.",
    ),
    (
        "b",
        "Delete a row for an answered question and the app retires it instead: it stops being asked, and its answers stay in the record.",
    ),
    ("b", "Questions nobody has answered yet can be reworded, reordered and deleted freely."),
    (
        "b",
        "Retired questions are in the file too, and you should leave them alone. A question that was reworded or deleted after somebody had already answered it is kept — that is what protects the answers — so a workbook you download can contain rows that are no longer asked. They carry a Question ID like any other row. Deleting one from the sheet does nothing; editing one is reported back to you and not applied.",
    ),
    ("h", "The two files you can download"),
    (
        "p",
        "Both carry the questions and neither carries an answer. The difference is the Question ID column.",
    ),
    (
        "b",
        "THIS QUESTIONNAIRE (.xlsx) — the questions with their Question IDs filled in. Edit this one and upload it back to CHANGE this questionnaire: the IDs are how the app knows which question each row is.",
    ),
    (
        "b",
        "QUESTION SET — the same questions with the IDs blank. Uploading this one creates a NEW questionnaire rather than editing this one. Use it to start next year's instrument from this year's.",
    ),
    (
        "b",
        "Neither file contains an answer, a respondent's name or an interview. Answers live against artisans and are read on the Consolidated questionnaire page and in the dataset exports.",
    ),
    ("h", "An example"),
]
_HELP_EXAMPLE = [
    ["Section Code", "Section Title", "Question ID", "Question", "Required", "Answer"],
    ["", "About the craft", "", "How long have you practised this craft?", "Yes", ""],
    ["", "", "", "Who taught you?", "No", ""],
    ["", "Materials", "", "Where do you buy your yarn?", "Yes", ""],
]


def _help_sheet(ws: Worksheet) -> None:
    ws.sheet_properties.tabColor = "FF9CA3AF"
    row = 1
    for kind, text in _HELP_LINES:
        cell = _put(ws, row, 1, text if kind != "b" else f"•  {text}")
        if kind == "h":
            cell.font = Font(bold=True, size=13, color=f"FF{_BRAND}")
            row += 1
        elif kind == "p":
            cell.alignment = Alignment(wrap_text=True, vertical="top")
            ws.merge_cells(start_row=row, start_column=1, end_row=row + 1, end_column=6)
            row += 2
        else:
            cell.alignment = Alignment(wrap_text=True, vertical="top")
            ws.merge_cells(start_row=row, start_column=1, end_row=row + 2, end_column=6)
            row += 3
    row += 1
    for offset, line in enumerate(_HELP_EXAMPLE):
        for column, value in enumerate(line, start=1):
            cell = _put(ws, row + offset, column, value)
            if offset == 0:
                cell.font = Font(bold=True, color=f"FF{_BRAND}")
    ws.column_dimensions["A"].width = 26
    for letter in ("B", "C", "D", "E", "F"):
        ws.column_dimensions[letter].width = 24


def _question_columns(entry_labels: list[str]) -> list[tuple[str, str]]:
    columns = [
        ("sectionCode", "Section Code"),
        ("sectionTitle", "Section Title"),
        ("questionId", "Question ID"),
        ("prompt", "Question"),
        ("help", "Help text"),
        ("required", "Required"),
    ]
    for label in entry_labels or [""]:
        suffix = f" — {label}" if label else ""
        columns.append(("answer", f"Answer{suffix}"))
        columns.append(("notes", f"Notes{suffix}"))
    return columns


def _write_questions_sheet(
    ws: Worksheet,
    sections: list[dict[str, Any]],
    entry_labels: list[str],
) -> None:
    ws.sheet_properties.tabColor = f"FF{_BRAND}"
    columns = _question_columns(entry_labels)
    _header(ws, 1, columns)

    grey = Font(color="FF6B7280")
    wrap = Alignment(wrap_text=True, vertical="top")
    row = 2
    for section in sections:
        first = True
        for question in section.get("questions") or []:
            values: list[Any] = [
                section.get("code") if first else "",
                section.get("title") if first else "",
                question.get("id") or "",
                question.get("prompt") or "",
                question.get("helpText") or "",
                "Yes" if question.get("isRequired") else "No",
            ]
            answers = question.get("answers") or {}
            notes = question.get("answerNotes") or {}
            for label in entry_labels or [""]:
                values.append(answers.get(label, ""))
                values.append(notes.get(label, ""))
            for index, value in enumerate(values, start=1):
                cell = _put(ws, row, index, value)
                cell.alignment = wrap
                if index == 3 and value:
                    cell.font = grey
            first = False
            row += 1
        if not (section.get("questions") or []):
            _put(ws, row, 1, section.get("code") or "")
            _put(ws, row, 2, section.get("title") or "")
            row += 1


def build_pro_forma() -> bytes:
    wb = Workbook()
    questions = wb.active
    questions.title = SHEET_QUESTIONS
    _write_questions_sheet(questions, [], [])
    _details_sheet(
        wb.create_sheet(title=SHEET_DETAILS),
        title="",
        description="",
        questionnaire_id="",
        version=None,
    )
    _help_sheet(wb.create_sheet(title=SHEET_HELP))

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def build_questionnaire_workbook(
    *,
    title: str,
    description: str | None,
    questionnaire_id: str,
    version: int,
    sections: list[dict[str, Any]],
    entry_labels: list[str] | None = None,
) -> bytes:
    labels = list(entry_labels or [])
    wb = Workbook()
    questions = wb.active
    questions.title = SHEET_QUESTIONS
    _write_questions_sheet(questions, sections, labels)
    _details_sheet(
        wb.create_sheet(title=SHEET_DETAILS),
        title=_sanitise(title or ""),
        description=_sanitise(description or ""),
        questionnaire_id=questionnaire_id,
        version=version,
    )
    _help_sheet(wb.create_sheet(title=SHEET_HELP))

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


QUESTION_SET_CONTENTS = "Questions only — no answers, no artisans' names, no recorded interviews."

_QUESTION_SET_NOTE = (
    "This is a QUESTION SET: one questionnaire's questions and nothing else. It carries no answers, "
    "no artisans' names and no recorded interviews, which is what makes it safe to start a new "
    "questionnaire from. Leave 'Questionnaire ID' and the Question ID column blank: uploading this "
    "file creates a NEW questionnaire rather than editing the one it came from. The Answer column is inert: answers are recorded in the app, against an artisan."
)


def build_question_set_workbook(
    *,
    title: str,
    description: str | None,
    sections: list[dict[str, Any]],
    source_title: str | None = None,
    shared_by: str | None = None,
    exported_on: str | None = None,
) -> bytes:
    extra = [("Contents", QUESTION_SET_CONTENTS)]
    if source_title:
        extra.append(("Exported from", source_title))
    if shared_by:
        extra.append(("Shared by", shared_by))
    if exported_on:
        extra.append(("Exported on", exported_on))

    wb = Workbook()
    questions = wb.active
    questions.title = SHEET_QUESTIONS
    _write_questions_sheet(questions, sections, [])
    _details_sheet(
        wb.create_sheet(title=SHEET_DETAILS),
        title=_sanitise(title or ""),
        description=_sanitise(description or ""),
        questionnaire_id="",
        version=None,
        extra=[(label, _sanitise(value)) for label, value in extra],
        note=_QUESTION_SET_NOTE,
    )
    _help_sheet(wb.create_sheet(title=SHEET_HELP))

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def download_filename(title: str | None) -> str:
    return naming.export_filename("Questionnaire", "Export", "xlsx", subject=_sanitise(title or ""))


def question_set_filename(title: str | None) -> str:
    return naming.export_filename(
        "Questionnaire", "Questions", "xlsx", subject=_sanitise(title or "")
    )
