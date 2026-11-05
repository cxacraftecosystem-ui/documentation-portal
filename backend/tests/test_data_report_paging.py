from __future__ import annotations

from app.api.routes import data_browser
from app.services.data_explorer import report_tools


def _pages(sheet: dict, fetch) -> list[dict]:
    pages = []
    offset = 0
    while True:
        (page,) = fetch([sheet], offset)
        pages.append(page)
        offset += len([n for n in page["rowNumbers"] if n > 0])
        total = page.get("matchedRows", page["totalRows"])
        if offset >= total or not page["rows"]:
            return pages


def test_the_preview_pages_to_the_last_row_of_a_long_sheet():
    rows = [[f"row {i}"] for i in range(1, 451)]
    sheet = data_browser._sheet("Long", "#000000", ["n"], rows)
    pages = _pages(sheet, lambda s, at: data_browser._preview(s, set(), at))
    seen = [n for page in pages for n in page["rowNumbers"]]
    assert seen == list(range(1, 451))
    assert [r for page in pages for r in page["rows"]] == rows
    assert [page["offset"] for page in pages] == [0, 200, 400]
    assert pages[-1]["preview"] is False and pages[0]["preview"] is True
    assert all(page["totalRows"] == 450 for page in pages)


def test_the_first_page_without_an_offset_is_unchanged():
    sheet = data_browser._sheet("Long", "#000000", ["n"], [[i] for i in range(250)])
    (plain,) = data_browser._preview([sheet], set())
    (first,) = data_browser._preview([sheet], set(), 0)
    assert plain == first
    assert plain["rowNumbers"] == list(range(1, data_browser.PREVIEW_ROWS + 1))


def test_a_cap_note_rides_only_on_the_first_and_last_pages(monkeypatch):
    monkeypatch.setattr(data_browser, "REPORT_TAKE", 450)
    sheet = data_browser._sheet("Capped", "#000000", ["n"], [[i] for i in range(600)])
    first, middle, last = (data_browser._preview([sheet], set(), at)[0] for at in (0, 200, 400))
    assert first["rowNumbers"][-1] == 0 and last["rowNumbers"][-1] == 0
    assert 0 not in middle["rowNumbers"]
    assert middle["rowNumbers"] == list(range(201, 401))
    assert last["rowNumbers"][:-1] == list(range(401, 451))


def test_a_search_pages_to_its_last_match():
    rows = [["loom" if i % 2 else "needle", i] for i in range(1, 901)]
    sheet = {"name": "Tools", "columns": ["Tool", "n"], "rows": rows, "prose": []}
    pages = _pages(sheet, lambda s, at: report_tools.search(s, "loom", offset=at))
    found = [n for page in pages for n in page["rowNumbers"]]
    assert found == list(range(1, 901, 2))
    assert len(found) == pages[0]["matchedRows"] == 450
    assert pages[-1]["preview"] is False and pages[0]["truncated"] is True
    assert report_tools.search([sheet], "loom")[0]["rowNumbers"] == found[: report_tools.SEARCH_MAX_ROWS]
