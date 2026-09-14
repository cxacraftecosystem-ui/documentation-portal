"""The column-to-box map every save-time marker comparison rests on, pinned on all four forms.

WHY THIS FILE EXISTS, AND WHY IT IS A SOURCE-READING TEST.

``measurementMethodsFor`` on the web and ``MeasurementMarkers.body`` on the handset both decide
whether a marker survives into the request by comparing the ACCEPTED TEXT against WHAT THE BOX HOLDS
NOW. That comparison is exact and it is the whole mechanism -- but it is only ever as true as the map
it is handed, and that map is four object literals buried in two very long form files:

    web   ToolForm.tsx     measurementMethodsFor(accepted, {..., heightInches})
    web   ProductForm.tsx  measurementMethodsFor(accepted, {..., heightInches: height})
    andr  ToolForm         markers.body(mapOf(..., "heightInches" to heightInches))
    andr  ProductForm      markers.body(mapOf(..., "heightInches" to height))

READ THOSE FOUR AGAIN: THE TWO FORMS NAME THE SAME COLUMN DIFFERENTLY, ON BOTH CLIENTS, AND BOTH ARE
CORRECT. The product form's ``height`` state is seeded from ``heightInches`` and sent as
``heightInches``. The tool form's ``height`` is the UNIT-LESS legacy column, and its inches value
lives in a separate ``heightInches`` box (migration 20260913120100). So on a tool form
``heightInches -> height`` is a defect, and on a product form it is the fix.

THAT IS THE TRAP THIS FILE IS NAMED FOR. A one-character "correction" from ``heightInches`` to
``height`` on a tool form -- exactly the edit that looks like tidying an inconsistency between two
neighbouring files -- hands the comparison the WRONG BOX. The accepted inches text is then compared
against the unit-less box, which normally holds something else or nothing, so the marker is dropped
on every save and every machine-measured tool height silently records as UNRECORDED. And worse in
the other direction: if the unit-less box happens to hold the same digits, a marker rides out
attached to a number the route never produced, which is the single thing this whole mechanism exists
to prevent.

NOTHING ELSE CATCHES IT. Both clients' unit tests drive the marker store directly with a map they
build themselves, so they are structurally blind to what the FORMS pass. Type-checking cannot help
either: every value here is a string holding a decimal, so every wrong wiring type-checks cleanly.
And it is invisible in review, because the two spellings are each correct in their own file.

Source-reading, and in the BACKEND suite, for the same reason ``test_tool_height_columns.py`` is:
this is the only suite that runs over the whole repository in CI, and a claim that spans the web and
the handset has to be asserted somewhere that can see both.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]

#: The three columns a marker may name. Deliberately a literal rather than an import from
#: ``measurement_provenance``: this file checks that the CLIENTS agree with the server, and a test
#: that imports its expectation from the thing it is checking agrees with itself for free.
DIMENSIONS = ("lengthInches", "breadthInches", "heightInches")

#: `height` followed by anything that is not `Inches` -- the whole point of this file is telling
#: those two apart, so the negative lookahead is load-bearing rather than defensive.
BARE_HEIGHT = r"height\b(?!Inches)"


def _android_region(name: str) -> str:
    """One ``private fun <name>(`` block out of MainActivity.kt.

    Read as BYTES and decoded with ``errors="replace"``: that file holds non-UTF-8 bytes, reports as
    binary to grep, and a plain ``read_text`` raises on it.
    """
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
    """The text from ``opener`` to the first ``closer`` after it, opener included."""
    at = source.index(opener)
    return source[at : source.index(closer, at)]


def _web_map(form: str) -> str:
    return _between(_web(form), "measurementMethodsFor(accepted, {", "})")


def _android_map(form: str) -> str:
    return _between(_android_region(form), "markers.body(", "),")


# ---------------------------------------------------------------------------------------------
# Every form passes a map, and it names all three columns.
# ---------------------------------------------------------------------------------------------


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


# ---------------------------------------------------------------------------------------------
# And each is wired to the RIGHT box -- the assertion this file is named for.
# ---------------------------------------------------------------------------------------------


def test_the_tool_forms_map_height_inches_to_the_inches_box_and_never_to_the_unit_less_one() -> None:
    """``height`` on a tool form is the unit-less legacy column and its inches value has its own box.

    Mapping ``heightInches`` at the unit-less box compares the accepted inches text against the
    wrong state: every tool marker is dropped, or -- if the two boxes happen to agree -- one rides
    out attached to a number the route never produced.
    """
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
    """The mirror image, asserted so the test above cannot be "fixed" by making all four identical.

    A product form has ONE height box and it is the inches one: seeded from ``editing?.heightInches``
    and sent as ``heightInches``. Renaming it to match the tool form would be churn; mapping it as
    though it were the tool form's would be a defect.
    """
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
    """A marker may only name the three inch columns.

    The unit-less ``height`` declares no unit, so a claim about how ITS number was obtained is a
    claim about a number nobody can read -- and ``marker_body_problems`` refuses the key by name,
    which is a 422 on the WHOLE save rather than a dropped hint.

    Checked on the KEYS only. ``heightInches: height`` on a product form names a legal key and reads
    a legally-named box; it is the two tests above that decide whether that box is the right one.
    """
    body = reader()
    keys = set(re.findall(r'"([A-Za-z]+)"\s+to\s', body))  # Kotlin `"key" to value`
    keys |= set(re.findall(r"(?:^|[{,])\s*([A-Za-z]+)\s*[:,}]", body))  # TS `key: value` / shorthand
    keys.discard("mapOf")
    keys.discard("accepted")
    stray = {key for key in keys if key not in DIMENSIONS}
    assert not stray, (
        f"{where} names {sorted(stray)} in the marker map. Only {', '.join(DIMENSIONS)} may be "
        f"named: the server refuses anything else by name and the save fails whole."
    )
