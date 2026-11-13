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
    tool = _Tool(
        toolkitName="Dyeing vat",
        lengthInches=Decimal("10"),
        breadthInches=Decimal("4"),
        heightInches=Decimal("6"),
    )

    assert _cell(tool, "Dimensions (LxBxH in)") == "10 x 4 x 6"


def test_the_tool_sheet_has_exactly_one_height_column():
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
    tool = _Tool(toolkitName="Chisel", lengthInches=Decimal("8"), breadthInches=Decimal("2"))

    assert _cell(tool, "Dimensions (LxBxH in)") == "8 x 2"


def test_both_tool_forms_now_write_the_measured_height_into_the_inches_column():
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]

    web = (root / "frontend" / "components" / "forms" / "ToolForm.tsx").read_text(encoding="utf-8")
    builders = (root / "frontend" / "lib" / "recordPayloads.ts").read_text(encoding="utf-8")
    payload = builders[builders.index("export function toolBody(") : builders.index("export type ProductBodyContext")]
    call = web[web.index("toolBody(form, {") : web.index("})", web.index("toolBody(form, {"))]
    assert {line.strip() for line in call.splitlines()} >= {"height,", "heightInches,"}, (
        "ToolForm.tsx no longer hands both height boxes to `toolBody`, so what it sends is no longer "
        "what lib/recordPayloads.ts builds"
    )
    assert "setHeightInches" in web, (
        "ToolForm.tsx no longer has a `heightInches` setter, so the grid panel's inches reading has "
        "nowhere unit-bearing to go and the defect migration 20260913120100 describes is live again"
    )
    assert [line for line in payload.splitlines() if line.strip().startswith("heightInches:")], (
        "toolBody no longer SENDS `heightInches:`. The box can be filled and the number still "
        "never reaches the column."
    )
    assert [line for line in payload.splitlines() if line.strip().startswith("height:")], (
        "toolBody stopped sending the bare `height:`. That column is NOT deprecated — rows hold "
        "values in it whose unit nothing can recover, and dropping it from the payload would make "
        "every edit of such a row silently clear it. See migration 20260913120100."
    )

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
    bodies = (
        Path(__file__).resolve().parents[2]
        / "android" / "app" / "src" / "main" / "java" / "com" / "fieldrepository" / "app" / "data"
        / "RecordFormBodies.kt"
    ).read_text(encoding="utf-8")
    tool_body = bodies[bodies.index("fun toolCreateBody("):]
    tool_body = tool_body[: tool_body.find("\nfun ", 1)] if "\nfun " in tool_body[1:] else tool_body
    product_body = bodies[bodies.index("fun productCreateBody("):]
    assert any("heightInches = heightInches," in line for line in tool_region), (
        "the Android ToolForm accepts a height in inches and no longer hands it to toolCreateBody."
    )
    assert "heightInches = fields.heightInches.trim().toDoubleOrNull()" in tool_body, (
        "the Android ToolForm accepts a height in inches and no longer SENDS it. The box would fill "
        "from the grid and the number would never leave the handset."
    )
    assert "heightInches = fields.height.trim().toDoubleOrNull()" in product_body
