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
    pasting the ``Height (cm)`` row in beneath it, leaving the original one where it was.

    THE LABEL GAINED ITS UNIT ON 2026-09-15 and this assertion moved with it. It read
    ``columns.count("Height") == 1`` while the bare ``height`` column declared no unit at all; the
    tool form now labels that box "Height (cm)" on all four clients and fills it by conversion from
    ``heightInches``, so the header says so too. The bare name is asserted GONE rather than left
    unmentioned, because a header carrying both would be the duplicate this test exists to catch
    wearing a different coat.
    """
    columns = sheet_columns("tool")

    assert columns.count("Height (cm)") == 1, columns
    assert columns.count("Width (cm)") == 1, columns
    assert "Height" not in columns, (
        "the unit-less ``Height`` label is back in the header beside ``Height (cm)`` — two columns "
        "for one value in a public dataset contract"
    )
    assert "Width" not in columns, columns
    assert columns.count("Dimensions (LxBxH in)") == 1
    assert columns.count("Dimensions (LxB in)") == 0, (
        "the old two-number label is still in the header — the cell now prints three numbers under a "
        "name that promises two"
    )


def test_height_and_height_inches_are_two_separate_cells():
    """A TOOL HOLDING BOTH PRINTS BOTH, and they are two units of one measurement rather than one
    number printed twice.

    The two cells must never collapse into one. On a row saved SINCE the pairing they agree to two
    decimals and a reader could be forgiven for thinking one of them redundant; on a row saved BEFORE
    it they hold unrelated numbers, because the bare column declared no unit and nothing converted it
    retroactively. The row below is deliberately that older shape — 14 against 6 — and both numbers
    print exactly as stored, which is what proves the sheet reports the disagreement instead of
    hiding it."""
    tool = _Tool(
        toolkitName="Dyeing vat",
        height=Decimal("14"),
        lengthInches=Decimal("10"),
        breadthInches=Decimal("4"),
        heightInches=Decimal("6"),
    )

    assert _cell(tool, "Height (cm)") == "14"
    assert _cell(tool, "Dimensions (LxBxH in)") == "10 x 4 x 6"


def test_a_tool_with_no_inches_height_still_prints_the_two_it_has():
    """Every row that exists today is this shape: ``heightInches`` NULL, the other two set. The cell
    must not grow an empty slot or a stray separator, or every historical row reads as though a
    number had gone missing."""
    tool = _Tool(toolkitName="Chisel", lengthInches=Decimal("8"), breadthInches=Decimal("2"))

    assert _cell(tool, "Dimensions (LxBxH in)") == "8 x 2"


def test_both_tool_forms_now_write_the_measured_height_into_the_inches_column():
    """THE OPEN HALF IS CLOSED, AND THIS TEST IS THE INVERTED VERSION OF THE ONE THAT SAID SO.

    What stood here asserted the BROKEN state deliberately — that both tool forms still handed the
    grid panel's INCHES reading to the unit-less ``height`` column — so that the open half was a
    named decision in the suite rather than an absence a reader had to notice, and so the day
    somebody landed the fix it would go red and tell them what to change. That is exactly what
    happened, on 2026-09-14, and this is the inversion its own assertion messages asked for.

    The two halves closed separately, which is why the old test named them separately:

      * the WEB form was fixed first — ``ToolForm.tsx`` now wires the panel's ``onHeight`` to
        ``setHeightInches`` and sends ``heightInches:`` beside the bare ``height:``;
      * the ANDROID form was the last client doing it wrong, and had no box for the third dimension
        at all, so its ``onHeight`` had nowhere to write but the unit-less column.

    THE ANDROID HALF IS STILL SCOPED TO THE TOOL FORM ON PURPOSE, and the final assertion below is
    the reason the naive version of this test is wrong. The PRODUCT form's
    ``onHeight = { height = numToText(it) }`` reads like the identical defect and is CORRECT: its
    ``height`` state is seeded from ``editing?.heightInches`` and sent as ``heightInches =``. A
    whole-file regex would call that a bug.

    ``MainActivity.kt`` reports as binary to grep (it holds non-UTF-8 bytes), so it is read as bytes
    and decoded with ``errors="replace"`` and addressed by line index rather than by a text search.
    """
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]

    # ── The web half ────────────────────────────────────────────────────────────────────────────
    web = (root / "frontend" / "components" / "forms" / "ToolForm.tsx").read_text(encoding="utf-8")
    assert "setHeightInches" in web, (
        "ToolForm.tsx no longer has a `heightInches` setter, so the grid panel's inches reading has "
        "nowhere unit-bearing to go and the defect migration 20260913120100 describes is live again"
    )
    assert [line for line in web.splitlines() if line.strip().startswith("heightInches:")], (
        "ToolForm.tsx no longer SENDS `heightInches:`. The box can be filled and the number still "
        "never reaches the column."
    )
    assert [line for line in web.splitlines() if line.strip().startswith("height:")], (
        "ToolForm.tsx stopped sending the bare `height:`. That column is NOT deprecated — rows hold "
        "values in it whose unit nothing can recover, and dropping it from the payload would make "
        "every edit of such a row silently clear it. See migration 20260913120100."
    )

    # ── The Android half ────────────────────────────────────────────────────────────────────────
    android = (
        root / "android" / "app" / "src" / "main" / "java" / "com" / "fieldrepository" / "app"
        / "MainActivity.kt"
    ).read_bytes().decode("utf-8", errors="replace").splitlines()

    def _region(name: str) -> list[str]:
        start = next(i for i, line in enumerate(android) if line.strip().startswith(f"private fun {name}("))
        end = next(
            i
            for i, line in enumerate(android[start + 1 :], start=start + 1)
            if line.strip().startswith("private fun ")
        )
        return android[start:end]

    tool_region = _region("ToolForm")
    handlers = [line.strip() for line in tool_region if "onHeight" in line]
    assert handlers, "the Android ToolForm no longer wires the grid panel's height at all"
    assert any("heightInches = numToText" in line for line in handlers), (
        "the Android ToolForm's onHeight no longer writes `heightInches`. If it writes the bare "
        "`height` again, the inches reading is back in a column that declares no unit — which is "
        "the entire defect migration 20260913120100 exists to close."
    )
    assert any("heightInches = heightInches.toDoubleOrNull()" in line for line in tool_region), (
        "the Android ToolForm accepts a height in inches and no longer SENDS it. The box would fill "
        "from the grid and the number would never leave the handset."
    )

    # The product form, two screens up, has always been right — asserted so the scoping above cannot
    # quietly stop scoping anything.
    assert any(
        "heightInches = height.toDoubleOrNull()" in line for line in _region("ProductForm")
    )
