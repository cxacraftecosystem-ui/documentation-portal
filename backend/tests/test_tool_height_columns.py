"""Two heights on one tool, and exactly one column each in the public header.

THE DEFECT THE SECOND COLUMN CLOSES. ``GridMeasurement.tsx`` reads ``analysis.valueInches`` and hands
an INCHES reading to ``onHeight``; on the TOOL form — and only there — both clients wired that to the
plain ``height`` column, which declares no unit. ``ProductDocumentation`` has carried
``lengthInches``/``breadthInches``/``heightInches`` since it was written and the product form targets
the right one, which is exactly why the bug was invisible: one of the two forms always looked right.

``height`` IS NOT BEING MIGRATED INTO ``heightInches``. Rows already hold values in it and nothing in
the database can say what unit those are in; copying them across would invent a unit for every one,
irreversibly, and a later migration could not tell a copied value from a typed one. So both print,
and neither is redundant.
"""

from decimal import Decimal

from app.services.record_fields import sheet_columns, sheet_row


class _Tool:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


def _cell(tool, label: str) -> str:
    columns = sheet_columns("tool")
    return sheet_row("tool", tool)[columns.index(label)]


def test_the_dimensions_cell_prints_three_numbers():
    """It printed two until ``ToolDocumentation`` had a third dimension to print, and the header said
    so — ``Dimensions (LxB in)``. The cell and its label move together, or the workbook grows a
    column whose name and contents disagree."""
    tool = _Tool(
        toolkitName="Dyeing vat",
        lengthInches=Decimal("10"),
        breadthInches=Decimal("4"),
        heightInches=Decimal("6"),
    )

    assert _cell(tool, "Dimensions (LxBxH in)") == "10 x 4 x 6"


def test_the_tool_sheet_has_exactly_one_height_column():
    """THE MECHANICAL GUARD AGAINST A DUPLICATE COLUMN, and it is needed because nothing else catches
    one. ``sheet_columns`` is a plain list with no dedupe, and it feeds the xlsx workbook, BOTH
    /export CSVs, ``details.txt`` inside the dataset zip and ``datasets._csv_columns('tool')`` — a
    public dataset contract. ``test_dataset_api``'s advertised-columns assertion is DERIVED from the
    same function, so it would agree with a duplicate rather than catch it.

    The obvious wrong edit is what makes this worth a test: replacing the dimensions line alone and
    pasting the ``Height`` row in beneath it, leaving the original ``Height`` row where it was.
    """
    columns = sheet_columns("tool")

    assert columns.count("Height") == 1, columns
    assert columns.count("Dimensions (LxBxH in)") == 1
    assert columns.count("Dimensions (LxB in)") == 0, (
        "the old two-number label is still in the header — the cell now prints three numbers under a "
        "name that promises two"
    )


def test_height_and_height_inches_are_two_separate_cells():
    """A TOOL HOLDING BOTH PRINTS BOTH, and the bare ``Height`` cell is the unit-less one. If these
    ever collapsed into one column, the unit-less legacy value and the measured inches would be
    indistinguishable — which is the whole failure the ``*Inches`` naming exists to prevent."""
    tool = _Tool(
        toolkitName="Dyeing vat",
        height=Decimal("14"),
        lengthInches=Decimal("10"),
        breadthInches=Decimal("4"),
        heightInches=Decimal("6"),
    )

    assert _cell(tool, "Height") == "14"
    assert _cell(tool, "Dimensions (LxBxH in)") == "10 x 4 x 6"


def test_a_tool_with_no_inches_height_still_prints_the_two_it_has():
    """Every row that exists today is this shape: ``heightInches`` NULL, the other two set. The cell
    must not grow an empty slot or a stray separator, or every historical row reads as though a
    number had gone missing."""
    tool = _Tool(toolkitName="Chisel", lengthInches=Decimal("8"), breadthInches=Decimal("2"))

    assert _cell(tool, "Dimensions (LxBxH in)") == "8 x 2"


def test_the_tool_forms_still_write_the_unit_less_height_and_that_is_this_change_s_open_half():
    """THE COLUMN IS HALF THE FIX; THE FORMS ARE THE OTHER HALF, AND THEY ARE NOT IN THIS CHANGE.

    ``frontend/components/forms/ToolForm.tsx`` and the Android ``ToolForm`` belong to files this
    workstream does not own, so as of today the grid panel still writes the unit-less ``height`` on
    the tool form and ``heightInches`` stays empty on every tool. This test asserts THAT — the
    current, broken state — so the open half is a named decision in the suite rather than an absence
    a reader has to notice, and so the day somebody lands the fix this goes red and tells them what
    to change here.

    THE ANDROID HALF IS SCOPED TO THE TOOL FORM ON PURPOSE. The PRODUCT form's
    ``onHeight = { height = numToText(it) }`` is CORRECT: its ``height`` state is seeded from
    ``editing?.heightInches`` and sent as ``heightInches =``. A whole-file regex would call that a
    bug, which is the trap that makes the naive version of this test wrong.

    ``MainActivity.kt`` reports as binary to grep (it holds non-UTF-8 bytes), so it is read as bytes
    and decoded with ``errors="replace"`` and addressed by line index rather than by a text search.
    """
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]

    web = (root / "frontend" / "components" / "forms" / "ToolForm.tsx").read_text(encoding="utf-8")
    tool_payload = [line for line in web.splitlines() if line.strip().startswith("height:")]
    assert tool_payload, (
        "ToolForm.tsx no longer sends a bare `height:` — if it now sends `heightInches:` from the "
        "grid panel, the defect is CLOSED: invert this test and delete the open-half note in "
        "migration 20260913120100"
    )

    android = (
        root / "android" / "app" / "src" / "main" / "java" / "com" / "fieldrepository" / "app"
        / "MainActivity.kt"
    ).read_bytes().decode("utf-8", errors="replace").splitlines()
    tool_form_start = next(
        i for i, line in enumerate(android) if line.strip().startswith("private fun ToolForm(")
    )
    tool_form_end = next(
        i
        for i, line in enumerate(android[tool_form_start + 1 :], start=tool_form_start + 1)
        if line.strip().startswith("private fun ")
    )
    region = android[tool_form_start:tool_form_end]
    handlers = [line.strip() for line in region if "onHeight" in line]
    assert handlers, "the Android ToolForm no longer wires the grid panel's height at all"
    assert any("height = numToText" in line for line in handlers), (
        "the Android ToolForm's onHeight no longer writes the unit-less `height` — if it now writes "
        "`heightInches`, the defect is CLOSED on this client: invert this test"
    )

    # The product form, two screens up, has always been right — asserted so the scoping above cannot
    # quietly stop scoping anything.
    product_start = next(
        i for i, line in enumerate(android) if line.strip().startswith("private fun ProductForm(")
    )
    product_end = next(
        i
        for i, line in enumerate(android[product_start + 1 :], start=product_start + 1)
        if line.strip().startswith("private fun ")
    )
    product_region = "\n".join(android[product_start:product_end])
    assert "heightInches = height.toDoubleOrNull()" in product_region
