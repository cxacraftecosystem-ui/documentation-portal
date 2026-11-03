from __future__ import annotations

import re
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]

DIMENSIONS = ("lengthInches", "breadthInches", "heightInches")

BARE_HEIGHT = r"height\b(?!Inches)"


def _android_region(name: str) -> str:
    path = ROOT / "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt"
    lines = path.read_bytes().decode("utf-8", errors="replace").splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip().startswith(f"private fun {name}("))
    end = next(
        i
        for i, line in enumerate(lines[start + 1 :], start=start + 1)
        if line.strip().startswith("private fun ")
    )
    return "\n".join(lines[start:end])


def _web(form: str) -> str:
    return (ROOT / "frontend/components/forms" / form).read_text(encoding="utf-8")


def _between(source: str, opener: str, closer: str) -> str:
    at = source.index(opener)
    return source[at : source.index(closer, at)]


def _web_map(form: str) -> str:
    return _between(_web(form), "measurementMethodsFor(accepted, {", "})")


def _android_map(form: str) -> str:
    return _between(_android_region(form), "markers.body(", "),")



@pytest.mark.parametrize("form", ["ToolForm.tsx", "ProductForm.tsx"])
@pytest.mark.parametrize("column", DIMENSIONS)
def test_the_web_form_hands_the_comparison_every_dimension_box(form: str, column: str) -> None:
    assert column in _web_map(form), (
        f"{form} no longer passes {column} to measurementMethodsFor. A column absent from that map "
        f"can never match the accepted text, so its marker is dropped on every save and every "
        f"machine-measured {column} silently records as UNRECORDED."
    )


@pytest.mark.parametrize("form", ["ToolForm", "ProductForm"])
@pytest.mark.parametrize("column", DIMENSIONS)
def test_the_android_form_hands_the_comparison_every_dimension_box(form: str, column: str) -> None:
    assert f'"{column}"' in _android_map(form), (
        f"the Android {form} no longer passes {column} to markers.body. Same failure as the web: a "
        f"column absent from the map matches nothing, so its marker is dropped on every save."
    )



def test_the_tool_forms_map_height_inches_to_the_inches_box_and_never_to_the_unit_less_one() -> None:
    assert not re.search(rf"heightInches\s*:\s*{BARE_HEIGHT}", _web_map("ToolForm.tsx")), (
        "ToolForm.tsx maps heightInches at the UNIT-LESS `height` box. On the product form that "
        "exact line is correct and here it is a defect -- see this module's docstring and migration "
        "20260913120100 for why the two columns are not the same thing."
    )

    assert re.search(r'"heightInches"\s+to\s+heightInches\b', _android_map("ToolForm")), (
        "the Android ToolForm no longer maps heightInches at the heightInches box. If it now maps "
        "it at `height`, the comparison is reading the unit-less column."
    )


def test_the_product_forms_map_height_inches_at_their_height_state_which_is_the_inches_one() -> None:
    assert re.search(rf"heightInches\s*:\s*{BARE_HEIGHT}", _web_map("ProductForm.tsx")), (
        "ProductForm.tsx stopped mapping heightInches at its `height` state. If that state was "
        "renamed, update this test; if the mapping changed, the marker no longer follows the box "
        "the researcher actually types in."
    )

    assert re.search(rf'"heightInches"\s+to\s+{BARE_HEIGHT}', _android_map("ProductForm")), (
        "the Android ProductForm stopped mapping heightInches at its `height` state."
    )


@pytest.mark.parametrize(
    ("where", "reader"),
    [
        ("web ToolForm", lambda: _web_map("ToolForm.tsx")),
        ("web ProductForm", lambda: _web_map("ProductForm.tsx")),
        ("android ToolForm", lambda: _android_map("ToolForm")),
        ("android ProductForm", lambda: _android_map("ProductForm")),
    ],
)
def test_no_map_names_a_column_the_server_would_refuse(where: str, reader) -> None:
    body = reader()
    keys = set(re.findall(r'"([A-Za-z]+)"\s+to\s', body))
    keys |= set(re.findall(r"(?:^|[{,])\s*([A-Za-z]+)\s*[:,}]", body))
    keys.discard("mapOf")
    keys.discard("accepted")
    stray = {key for key in keys if key not in DIMENSIONS}
    assert not stray, (
        f"{where} names {sorted(stray)} in the marker map. Only {', '.join(DIMENSIONS)} may be "
        f"named: the server refuses anything else by name and the save fails whole."
    )
