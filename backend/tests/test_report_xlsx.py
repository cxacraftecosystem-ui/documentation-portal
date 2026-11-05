from __future__ import annotations

import asyncio
import io
import os
import shutil
import subprocess
import tempfile
import tracemalloc
import warnings
import zipfile
from pathlib import Path
from typing import Any

import pytest

from app.services.datareport import build, contract, links, portal, writer
from app.services.datareport.contract import ROW_KIND, Column, Link, Sheet
from tests import report_fixtures as rf

openpyxl = pytest.importorskip("openpyxl")


def _render(options: contract.ReportOptions | None = None, **kw: Any) -> tuple[bytes, contract.ReportContext]:
    ctx = rf.context(options, **kw)
    payload = asyncio.run(build.render(ctx))
    return payload, ctx


def _book(payload: bytes) -> Any:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        return openpyxl.load_workbook(io.BytesIO(payload))


def _rows(ws: Any) -> list[list[Any]]:
    return [list(r) for r in ws.iter_rows(values_only=True)]


def _header(ws: Any) -> list[str]:
    return [c for c in _rows(ws)[writer.TOP] if c is not None]


def test_the_workbook_is_valid_and_round_trips() -> None:
    payload, _ctx = _render()
    found = rf.validate(payload)
    assert found["tables"]
    book = _book(payload)
    assert book.sheetnames[0] == "Overview"
    assert book.sheetnames[-1] == "Data dictionary"
    for name in ("Workshops", "Artisans", "Transcripts", "Media"):
        assert name in book.sheetnames
    for ws in book.worksheets:
        for table in ws.tables.values():
            first, _ = table.ref.split(":")
            row = ws[first][0].row if hasattr(ws[first], "__iter__") else ws[first].row
            header = [c.value for c in ws[row]][: len(table.tableColumns)]
            start = openpyxl.utils.column_index_from_string("".join(ch for ch in first if ch.isalpha())) - 1
            header = [c.value for c in ws[row]][start : start + len(table.tableColumns)]
            assert header == [col.name for col in table.tableColumns], (ws.title, table.name)


def test_every_transcript_segment_has_its_translation_row_right_after() -> None:
    payload, _ctx = _render()
    ws = _book(payload)["Transcripts"]
    header = _header(ws)
    row_col, text_col, status_col = header.index("Row"), header.index("Text"), header.index("Status")
    rows = [r for r in _rows(ws)[writer.TOP + 1 :] if r[0]]
    kinds = [r[row_col] for r in rows]
    for index, kind in enumerate(kinds):
        if kind == "Original":
            assert kinds[index + 1] == "English translation"
        if kind == "Whole transcript":
            assert kinds[index + 1] == "Whole transcript · English translation"
    translated = [r for r in rows if r[row_col] == "English translation"]
    assert any(r[text_col] == "The indigo colour" and r[status_col] == "Translated" for r in translated)
    statuses = {r[status_col] for r in translated if not r[text_col]}
    assert "Translating 50%" in statuses
    assert any(str(s).startswith("Failed: quota exceeded") for s in statuses)


def test_cells_that_look_like_formulas_or_hold_control_characters_stay_text() -> None:
    payload, _ctx = _render()
    ws = _book(payload)["Transcripts"]
    texts = [c for r in _rows(ws) for c in r if isinstance(c, str)]
    assert any(t.startswith("=SUM(A1)") for t in texts)
    assert not any("\x07" in t for t in texts)


def test_links_are_durable_portal_urls_and_bulk_banners_name_a_bundle() -> None:
    payload, ctx = _render()
    book = _book(payload)
    ws = book["Media"]
    urls = [c.hyperlink.target for r in ws.iter_rows() for c in r if c.hyperlink is not None and c.hyperlink.target]
    assert any("/data/go/media/m0/download" in u for u in urls)
    assert any("/data?preview=m0" in u for u in urls)
    assert all("amazonaws" not in u and "X-Amz" not in u for u in urls)
    banner = ws.cell(row=3, column=1)
    assert banner.value.startswith("Download all 4 files in this sheet")
    assert "bundle=bundle" in banner.hyperlink.target
    transcripts = book["Transcripts"]
    plays = [c.hyperlink.target for r in transcripts.iter_rows() for c in r if c.hyperlink is not None and "t=" in (c.hyperlink.target or "")]
    assert any(p.endswith("t=4") for p in plays)
    cover = _rows(book["Overview"])
    assert any(isinstance(c, str) and c.startswith("Download everything in this report") for r in cover for c in r)
    assert ctx.shared["bundles"]


def test_links_off_drops_link_columns_and_banners() -> None:
    payload, _ctx = _render(contract.ReportOptions(links=False))
    book = _book(payload)
    assert "Download" not in _header(book["Media"])
    assert "Play from" not in _header(book["Transcripts"])
    assert not any(c.hyperlink for r in book["Media"].iter_rows() for c in r if c.row > writer.TOP)


def test_answers_original_and_english_keep_only_their_rows() -> None:
    original, _ = _render(contract.ReportOptions(answers="original"))
    english, _ = _render(contract.ReportOptions(answers="english"))
    o = _book(original)["Transcripts"]
    e = _book(english)["Transcripts"]
    o_kinds = {r[_header(o).index("Row")] for r in _rows(o)[writer.TOP + 1 :] if r[0]}
    e_kinds = {r[_header(e).index("Row")] for r in _rows(e)[writer.TOP + 1 :] if r[0]}
    assert "English translation" not in o_kinds and "Original" in o_kinds
    assert "Original" not in e_kinds and "English translation" in e_kinds


def test_filtered_rows_follow_the_search() -> None:
    payload, _ctx = _render(contract.ReportOptions(rows="filtered", q="sita verma"))
    ws = _book(payload)["Artisans"]
    names = [r[_header(ws).index("Name")] for r in _rows(ws)[writer.TOP + 1 :] if r[0] and r[0] != "Total"]
    assert names == ["Sita Verma"]


def test_sheet_choice_keeps_only_the_chosen_sheets() -> None:
    payload, _ctx = _render(contract.ReportOptions(sheets=["Artisans", "Transcript turns"]))
    names = _book(payload).sheetnames
    assert "Artisans" in names and "Transcripts" in names
    assert "Media" not in names and "Workshops" not in names


def test_a_consent_refused_recording_shows_no_words() -> None:
    ctx = rf.context()
    for m in ctx.material["records"]["media"]:
        if m.id == "m0":
            m.transcriptWithheld = True
    payload = asyncio.run(build.render(ctx))
    ws = _book(payload)["Transcripts"]
    header = _header(ws)
    mine = [r for r in _rows(ws)[writer.TOP + 1 :] if r[0] == "talk-0.m4a"]
    assert mine and all(not r[header.index("Text")] for r in mine)
    assert all(str(r[header.index("Status")]).startswith("Withheld") for r in mine)


def test_a_required_column_is_never_queried_for_null() -> None:
    from app.services.datareport import schema, tables

    asked: list[tuple[str, str]] = []

    class Strict(rf.FakeDelegate):
        def __init__(self, model: str) -> None:
            super().__init__()
            self.model = model

        async def find_many(self, where: Any = None, take: int | None = None, **kw: Any) -> list[Any]:
            for name, cond in (where or {}).items():
                if cond is None:
                    asked.append((self.model, name))
            return await super().find_many(where, take, **kw)

    class StrictDB(rf.FakeDB):
        def __getattr__(self, name: str) -> Any:
            if name.startswith("_"):
                raise AttributeError(name)
            return self._tables.setdefault(name, Strict(name))

    for which in ("designer", "fieldRepository"):
        asked.clear()
        ctx = rf.context(db=StrictDB(raw=rf.turns_raw))
        ctx.portal = which
        asyncio.run(tables.load_store(ctx, ctx.shared["db"]))
        models = {m.lower(): m for m in schema.load(which)}
        for delegate, name in asked:
            field = schema.load(which)[models[delegate]].field(name)
            assert field is not None and field.optional, (which, delegate, name)


def test_secrets_never_reach_the_workbook() -> None:
    payload, _ctx = _render()
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        text = " ".join(z.read(n).decode("utf-8", "ignore") for n in z.namelist() if n.endswith(".xml"))
    assert "Aadhaar last" not in text
    assert ">secret<" not in text and ">1234<" not in text


def test_hindi_report_translates_fixed_text() -> None:
    payload, _ctx = _render(locale="hi")
    book = _book(payload)
    rf.validate(payload)
    assert book.sheetnames[0] != "Overview"
    assert any(ch >= "ऀ" for name in book.sheetnames for ch in name)


def test_status_cells_are_coloured_by_conditional_format() -> None:
    payload, _ctx = _render()
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        sheets = [z.read(n).decode() for n in z.namelist() if n.startswith("xl/worksheets/sheet")]
        styles = z.read("xl/styles.xml").decode()
    assert any("conditionalFormatting" in s and "Translating" in s for s in sheets)
    for colour in ("DCFCE7", "FEF3C7", "FEE2E2"):
        assert colour in styles


def test_print_setup_and_frozen_header() -> None:
    payload, _ctx = _render()
    ws = _book(payload)["Artisans"]
    assert ws.page_setup.orientation == "landscape"
    assert ws.freeze_panes is not None
    assert ws.print_title_rows
    assert "Page &P of &N" in (ws.oddFooter.center.text or "") or "&P" in str(ws.oddFooter.right.text or "")


def test_dictionary_describes_every_column_of_every_sheet() -> None:
    payload, _ctx = _render()
    book = _book(payload)
    described = {(r[0], r[1]) for r in _rows(book["Data dictionary"])[4:] if r[0]}
    for name in book.sheetnames[1:-1]:
        if name == "Chart data":
            continue
        for label in _header(book[name]):
            assert (name, label) in described, (name, label)


def test_thumbnails_go_in_as_pictures_when_asked() -> None:
    from PIL import Image

    out = io.BytesIO()
    Image.new("RGB", (40, 30), (118, 44, 177)).save(out, format="PNG")
    ctx = rf.context(contract.ReportOptions(thumbnails=True))
    ctx.thumbnails["m1"] = out.getvalue()
    payload = asyncio.run(build.render(ctx))
    rf.validate(payload)
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        assert any(n.startswith("xl/media/") for n in z.namelist())


def test_cover_charts_become_native_charts() -> None:
    ctx = rf.context()
    ctx.cover_charts.append(contract.ChartSpec("Media by type", "doughnut", ["Audio", "Image"], [("Files", [2, 2])]))
    ctx.cover_charts.append(contract.ChartSpec("Recordings per day", "column", ["1 Nov", "2 Nov"], [("Recordings", [3, 5])]))
    payload = asyncio.run(build.render(ctx))
    rf.validate(payload)
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        assert len([n for n in z.namelist() if n.startswith("xl/charts/chart")]) == 2


def _plain_context() -> contract.ReportContext:
    return rf.context()


def _write_rows(path: str, count: int) -> None:
    ctx = _plain_context()
    book = writer.ReportWriter(path, ctx)
    columns = [
        Column("file", "File", "link"),
        Column("row", "Row", "text"),
        Column("start", "Start", "duration_ms", total="sum"),
        Column("text", "Text", "longtext"),
        Column("status", "Status", "status"),
        Column("size", "Size", "bytes", total="sum"),
        Column("when", "When", "datetime"),
        Column("id", "ID", "id", hidden=True),
    ]
    sheet = Sheet("big", "Big", columns, [], row_pairs=True, databar_columns=["size"])
    state = book.begin(sheet, bundle_url=None)
    batch: list[dict[str, Any]] = []
    from datetime import UTC, datetime

    moment = datetime(2026, 11, 5, tzinfo=UTC)
    for i in range(count):
        batch.append(
            {
                ROW_KIND: "original" if i % 2 == 0 else "translation",
                "file": Link(f"talk-{i}.m4a", f"https://portal.example.org/data?preview=m{i}"),
                "row": "Original" if i % 2 == 0 else "English translation",
                "start": i * 1000,
                "text": f"line {i} नील का रंग " * 3,
                "status": "Translated" if i % 3 else "Translating 40%",
                "size": i * 10,
                "when": moment,
                "id": f"id{i}",
            }
        )
        if len(batch) >= 5000:
            state.write_rows(batch)
            batch = []
    state.write_rows(batch)
    book.end(state)
    book.write_cover(everything_url=None, everything_files=0, everything_bytes=0, kpis=[("Rows", f"{count:,}", "")], charts=[])
    book.write_dictionary()
    writer.close(book)


def test_a_hundred_thousand_rows_stay_within_memory() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "big.xlsx")
        tracemalloc.start()
        _write_rows(path, 100_000)
        _current, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()
        assert peak < 160 * 1024 * 1024, f"peak {peak / 1e6:.0f} MB"
        payload = Path(path).read_bytes()
    found = rf.validate(payload)
    assert any(t[1].endswith(str(100_000 + writer.TOP + 2)) for t in found["tables"])


def _soffice() -> str | None:
    return shutil.which("soffice") or shutil.which("libreoffice")


def test_libreoffice_opens_a_large_report_without_repair() -> None:
    binary = _soffice()
    if binary is None:
        if os.environ.get("REQUIRE_LIBREOFFICE") == "1":
            pytest.fail("LibreOffice is required on CI but soffice is not on PATH")
        pytest.skip("LibreOffice is not installed here")
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "large.xlsx")
        _write_rows(source, 20_000)
        result = subprocess.run(
            [binary, "--headless", "--norestore", f"-env:UserInstallation=file://{tmp}/profile", "--convert-to", "csv", "--outdir", tmp, source],
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        out = Path(tmp) / "large.csv"
        assert out.is_file() and out.stat().st_size > 0, result.stdout + result.stderr


def test_links_build_the_documented_portal_routes() -> None:
    made = links.PortalLinks("https://portal.example.org/", portal.DESIGNER)
    assert made.download("m 1") == "https://portal.example.org/data/go/media/m%201/download"
    assert made.open_record("artisan", "a1") == "https://portal.example.org/data/go/open/artisan/a1"
    assert made.play_from("m1", 65_400) == "https://portal.example.org/data?preview=m1&t=65"
    assert made.transcript_file("m1", "srt") == "https://portal.example.org/data/go/transcript/m1/srt"
    assert made.section("i1", "B 2") == "https://portal.example.org/questionnaire/sessions/i1/view?section=B+2"
    assert made.data_folder("by-workshop/w1") == "https://portal.example.org/data?path=by-workshop%2Fw1"
    assert made.record_path("designWorkshop", "d1") == "/design-workshops/d1"
    assert portal.FIELD_REPOSITORY.open_paths.get("designWorkshop") is None


def test_sheet_titles_are_valid_and_unique() -> None:
    used: set[str] = set()
    names = [writer.sheet_title(n, used) for n in ("A/B: c?", "History", "x" * 40, "x" * 40, "'quoted'")]
    assert all(0 < len(n) <= 31 for n in names)
    assert len({n.lower() for n in names}) == len(names)
    assert "history" not in {n.lower() for n in names}
    assert not any(set(n) & set("[]:*?/\\") for n in names)


def _production_shaped(path: str = "") -> contract.ReportContext:
    from datetime import UTC, datetime
    from types import SimpleNamespace as N

    now = datetime(2026, 11, 5, 10, 30, tzinfo=UTC)
    statuses = ("APPROVED", "PENDING", "NEEDS_REVISION", "REJECTED")
    ctx = rf.context(contract.ReportOptions(extras={"everything": True}))
    records = ctx.material["records"]
    for i, artisan in enumerate(records["artisans"]):
        artisan.status = statuses[i % len(statuses)]
    records["workshops"].append(N(id="w2", title="Sanganer dabu", status="REJECTED", createdAt=now, createdById="u1", locationId=None, place="Sanganer"))
    records["products"] = [N(id=f"p{i}", productName=f"Indigo stole {i}", artisanId=f"a{i}", workshopId="w1", status=statuses[i], createdAt=now, createdById="u1", locationId=None) for i in range(3)]
    records["processes"] = [N(id="pr1", name="Dabu resist", productId="p0", status="PENDING", createdAt=now, createdById="u1")]
    records["tools"] = [N(id=f"t{i}", toolkitName=f"Block {i}", artisanId=f"a{i}", status=statuses[i + 1], createdAt=now, createdById="u1", locationId=None) for i in range(2)]
    records["interviews"] = [N(id=f"i{i}", title=f"Sitting {i}", workshopId="w1", status=statuses[i], createdAt=now, createdById="u1", locationId=None, questionnaireId="q1") for i in range(2)]
    records["media"][1].linkedRecordType = "product"
    records["media"][1].linkedRecordId = "p0"
    records["media"][3].questionnaireInterviewId = "i0"
    ctx.path = path
    ctx.shared["db"] = rf.FakeDB(
        {
            "ProcessStep": [N(id="s1", processId="pr1", sortOrder=1, name="Mud paste", createdAt=now)],
            "WorkshopArtisan": [N(workshopId="w1", artisanId=f"a{i}", createdAt=now) for i in range(3)],
            "QuestionnaireInterviewArtisan": [N(interviewId="i0", artisanId="a0", createdAt=now, sortOrder=0), N(interviewId="i1", artisanId="a1", createdAt=now, sortOrder=0)],
            "Questionnaire": [N(id="q1", title="Craft questionnaire", createdAt=now), N(id="q2", title="Unused questionnaire", createdAt=now)],
            "QuestionnaireQuestion": [N(id="qq1", questionnaireId="q1", prompt="Which dye do you use?", sectionCode="A", isActive=True, lineageId="qq1", createdAt=now)],
            "QuestionnaireResponse": [N(id="r1", interviewId="i0", questionId="qq1", answerText="Indigo", createdAt=now, updatedAt=now, answeredById="u1")],
        },
        raw=rf.turns_raw,
    )
    return ctx


def _data_rows(ws: Any) -> int:
    return sum(1 for r in _rows(ws)[writer.TOP + 1 :] if any(v is not None for v in r) and r[0] != "Total")


def test_a_production_shaped_report_with_no_filter_has_rows_on_every_record_sheet() -> None:
    ctx = _production_shaped()
    payload = asyncio.run(build.render(ctx))
    rf.validate(payload)
    book = _book(payload)
    expected = {"Workshops": 2, "Artisans": 3, "Products": 3, "Processes": 1, "Tools": 2, "Questionnaire sittings": 2, "Media": 4, "Answers": 1, "Transcripts": 1, "Process steps": 1, "Questionnaires": 1}
    for name, least in expected.items():
        assert name in book.sheetnames, f"{name} is missing"
        assert _data_rows(book[name]) >= least, f"{name} has {_data_rows(book[name])} rows"
    record_keys = {key for key in build.RECORD_SHEETS if key != "designWorkshops"}
    written = {sheet.key for _plugin, sheet in asyncio.run(build.collect_sheets(_production_shaped(), []))}
    assert record_keys <= written
    types = {r[0] for r in _rows(book["Everything"])[writer.TOP + 1 :] if r[0]}
    assert {"Workshop", "Artisan", "Product", "Tool", "Questionnaire sitting", "File"} <= types


def test_every_questionnaire_is_in_a_dataset_root_report_for_someone_who_sees_everything() -> None:
    root = _production_shaped("")
    asyncio.run(build.render(root))
    assert {q.id for q in root.shared["store"].rows["Questionnaire"]} == {"q1", "q2"}
    folder = _production_shaped("by-workshop/w1")
    asyncio.run(build.render(folder))
    assert {q.id for q in folder.shared["store"].rows["Questionnaire"]} == {"q1"}
    narrow = _production_shaped("")
    narrow.scope.records = {"createdById": "u1"}
    asyncio.run(build.render(narrow))
    assert {q.id for q in narrow.shared["store"].rows["Questionnaire"]} == {"q1"}
