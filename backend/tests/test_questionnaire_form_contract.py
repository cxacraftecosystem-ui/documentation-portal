from __future__ import annotations

import json
import pathlib
import re

_ROOT = pathlib.Path(__file__).resolve().parents[2]

CONTRACT_PATH = _ROOT / "shared" / "questionnaire-form-contract.json"

WEB_FORM = _ROOT / "frontend" / "app" / "(protected)" / "questionnaire" / "page.tsx"
ANDROID_MAIN = (
    _ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "fieldrepository"
    / "app"
    / "MainActivity.kt"
)

WEB_FORM_ANCHOR = re.compile(r"<form\b[^>]*onSubmit=\{submit\}")
ANDROID_FORM_ANCHOR = "private fun QuestionnaireForm("


def contract() -> dict:
    assert CONTRACT_PATH.exists(), (
        f"{CONTRACT_PATH} is missing. It is the contract every assertion in this file is about; "
        "without it there is nothing to measure either client against, and deleting it is not a way "
        "to make a drift pass."
    )
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def contract_labels() -> list[str]:
    return [field["label"] for field in contract()["fields"]]


def controls(client: str) -> dict[str, dict]:
    key = "component" if client == "web" else "composable"
    return {row[key]: row for row in contract()["controls"][client]}



def _strip_ts_comments(source: str) -> str:
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        char = source[i]
        if char in "\"'`":
            quote = char
            out.append(char)
            i += 1
            while i < n:
                if source[i] == "\\":
                    out.append(source[i : i + 2])
                    i += 2
                    continue
                out.append(source[i])
                if source[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "*":
            start = i
            i += 2
            while i + 1 < n and not (source[i] == "*" and source[i + 1] == "/"):
                i += 1
            i += 2
            out.append("\n" * source.count("\n", start, i))
            continue
        out.append(char)
        i += 1
    return "".join(out)


def _strip_kotlin_comments(source: str) -> str:
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        three = source[i : i + 3]
        if three == '"""':
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end])
            i = end
            continue
        char = source[i]
        if char == '"':
            out.append(char)
            i += 1
            while i < n:
                if source[i] == "\\":
                    out.append(source[i : i + 2])
                    i += 2
                    continue
                out.append(source[i])
                if source[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "*":
            depth, start = 0, i
            while i < n:
                if source[i : i + 2] == "/*":
                    depth += 1
                    i += 2
                    continue
                if source[i : i + 2] == "*/":
                    depth -= 1
                    i += 2
                    if depth == 0:
                        break
                    continue
                i += 1
            out.append("\n" * source.count("\n", start, i))
            continue
        out.append(char)
        i += 1
    return "".join(out)


def _balanced(source: str, start: int, opener: str, closer: str) -> str:
    assert source[start] == opener, (
        f"internal: _balanced was handed {source[start]!r} where {opener!r} was expected"
    )
    depth, i, n = 0, start, len(source)
    while i < n:
        char = source[i]
        if char == '"':
            i += 1
            while i < n:
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == '"':
                    break
                i += 1
        elif char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return source[start : i + 1]
        i += 1
    raise AssertionError(f"unbalanced {opener}{closer} from offset {start}")


def _line_of(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1



def _web_form_region() -> tuple[str, int]:
    source = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))
    found = WEB_FORM_ANCHOR.search(source)
    assert found is not None, (
        f"{WEB_FORM_ANCHOR.pattern!r} matches nothing in {WEB_FORM.name}. That pattern is how this "
        "file finds the capture form at all; if the form has been rewritten or moved, this parser "
        "moves with it — do not delete this test to make a refactor pass."
    )
    at = found.start()
    end = source.find("</form>", at)
    assert end > at, (
        f"{WEB_FORM.name} has no </form> after {WEB_FORM_ANCHOR.pattern!r}. Without a closing tag "
        "this parser would read the rest of the page, including the questionnaire builder's own "
        "fields."
    )
    return source[at:end], at


def _component_default_label(path: pathlib.Path, component: str) -> str:
    source = _strip_ts_comments(path.read_text(encoding="utf-8"))
    at = source.find(f"export function {component}")
    assert at >= 0, (
        f"the contract says {component}'s label comes from {path.name}, and that file no longer "
        f"declares `export function {component}`."
    )
    found = re.search(r'\blabel\s*=\s*"([^"]*)"', source[at:])
    assert found, (
        f"{component} in {path.name} no longer defaults its `label` to a string literal. The "
        "contract reads this field's label from there; if the component now computes its label, "
        "the contract has to say where it comes from instead."
    )
    return found.group(1)


def web_form_labels() -> list[tuple[str, int]]:
    region, base = _web_form_region()
    raw = WEB_FORM.read_text(encoding="utf-8")
    stripped = _strip_ts_comments(raw)

    defaults = {
        component: row["labelSource"]
        for component, row in controls("web").items()
        if row["labelFrom"] == "componentDefault"
    }

    marks: list[tuple[int, str]] = []
    for match in re.finditer(r'\blabel="([^"]*)"', region):
        marks.append((match.start(), match.group(1)))
    for component, source_path in defaults.items():
        for match in re.finditer(rf"<{component}\b", region):
            tag = region[match.start() : region.find(">", match.start()) + 1]
            override = re.search(r'\blabel="([^"]*)"', tag)
            if override:
                marks = [mark for mark in marks if mark[0] != match.start() + override.start()]
                marks.append((match.start(), override.group(1)))
            else:
                marks.append((match.start(), _component_default_label(_ROOT / source_path, component)))

    marks.sort()
    return [(label, _line_of(stripped, base + offset)) for offset, label in marks]


def web_control_for(field: dict) -> tuple[str, int] | None:
    region, base = _web_form_region()
    stripped = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))
    registry = controls("web")

    for component, row in registry.items():
        if row["labelFrom"] != "componentDefault":
            continue
        at = region.find(f"<{component}")
        if at < 0:
            continue
        tag = region[at : region.find(">", at) + 1]
        override = re.search(r'\blabel="([^"]*)"', tag)
        drawn = override.group(1) if override else _component_default_label(
            _ROOT / row["labelSource"], component
        )
        if drawn == field["label"]:
            return component, _line_of(stripped, base + at)

    at = region.find(f'label="{field["label"]}"')
    if at < 0:
        return None
    opener = region.rfind("<", 0, at)
    element = re.match(r"<([A-Za-z][A-Za-z0-9_]*)", region[opener:])
    if not element:
        return None
    name = element.group(1)
    if name == "Field":
        inner = re.search(r"<([A-Z][A-Za-z0-9_]*)", region[region.find(">", at) :])
        if not inner:
            return None
        return inner.group(1), _line_of(stripped, base + region.find(">", at))
    return name, _line_of(stripped, base + opener)



def _android_form_region() -> tuple[str, int]:
    source = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    at = source.find(ANDROID_FORM_ANCHOR)
    assert at >= 0, (
        f"{ANDROID_FORM_ANCHOR!r} is no longer in {ANDROID_MAIN.name}. That is how this file finds "
        "the handset's capture form; if it has been renamed or moved out of MainActivity.kt, this "
        "parser moves with it."
    )
    body_at = source.index("{", source.index(")", at))
    return _balanced(source, body_at, "{", "}"), body_at


def _composable_default_label(symbol: str) -> str:
    source = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    at = source.find(f"fun {symbol}(")
    assert at >= 0, (
        f"the contract says a field's label comes from `{symbol}` in {ANDROID_MAIN.name}, and that "
        "file no longer declares it."
    )
    tail = source[at : at + 4000]
    found = re.search(r'\blabel\s*:\s*String\s*=\s*"([^"]*)"', tail) or re.search(
        r'\blabel\s*=\s*"([^"]*)"', tail
    )
    assert found, (
        f"`{symbol}` in {ANDROID_MAIN.name} no longer names a `label` string literal. The contract "
        "reads a field's label from there; if the composable now computes it, the contract has to "
        "say where it comes from instead."
    )
    return found.group(1)


def android_form_controls() -> list[tuple[str, str, int]]:
    region, base = _android_form_region()
    stripped = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    registry = controls("android")

    found: list[tuple[int, str, str]] = []
    for name, row in sorted(registry.items()):
        how = row["labelFrom"]
        for match in re.finditer(rf"\b{name}\s*\(", region):
            args = _balanced(region, match.end() - 1, "(", ")")
            label = None
            explicit = re.search(r'\blabel\s*=\s*"([^"]*)"', args)
            if explicit:
                label = explicit.group(1)
            elif how == "positional":
                lead = re.match(r'\(\s*"([^"]*)"', args)
                if lead:
                    label = lead.group(1)
            elif how == "composableDefault":
                label = _composable_default_label(row["labelSymbol"])
            if label is not None:
                found.append((match.start(), label, name))

    found.sort()
    return [
        (label, name, _line_of(stripped, base + offset)) for offset, label, name in found
    ]



def _report(client: str, path: pathlib.Path, expected: list[str], actual: list[tuple[str, int]]) -> str:
    got = [label for label, _ in actual]
    lines = [
        f"  {client} ({path.name}) draws {len(got)} of the contract's {len(expected)} fields.",
        f"    contract: {expected}",
        f"    {client:<8}: {got}",
    ]
    for index in range(max(len(expected), len(got))):
        here = expected[index] if index < len(expected) else "<missing>"
        there = got[index] if index < len(got) else "<missing>"
        if here != there:
            where = f" at {path.name}:{actual[index][1]}" if index < len(actual) else ""
            lines.append(f"    first difference at #{index + 1}{where}:")
            lines.append(f"      contract: {here!r}")
            lines.append(f"      {client:<8}: {there!r}")
            break
    return "\n".join(lines)



def test_the_contract_and_both_forms_still_parse_to_something():
    spec = contract()
    assert len(spec["fields"]) >= 5, (
        f"{CONTRACT_PATH.name} declares only {len(spec['fields'])} fields. This form has never had "
        "fewer than five; a contract this short is a contract that was emptied rather than edited."
    )

    web = web_form_labels()
    android = android_form_controls()
    assert len(web) >= 5, (
        f"only {len(web)} labelled controls parsed out of {WEB_FORM.name}'s capture form. The "
        f"anchor is {WEB_FORM_ANCHOR.pattern!r} and the labels are read as `label=\"…\"` plus the "
        "components that default their own — if the form now writes labels some third way, this "
        "parser has to learn about it before anything below means anything."
    )
    assert len(android) >= 5, (
        f"only {len(android)} labelled controls parsed out of {ANDROID_MAIN.name}'s "
        f"{ANDROID_FORM_ANCHOR!r}. Every assertion about the handset below is vacuous until this "
        "number is real."
    )
    assert all(label.strip() for label, _ in web), (
        f"a control in {WEB_FORM.name}'s capture form parsed to a BLANK label, which draws a field "
        f"with no name: {[label for label, _ in web if not label.strip()]!r}"
    )
    assert all(label.strip() for label, _, _ in android), (
        f"a control in {ANDROID_MAIN.name}'s capture form parsed to a BLANK label: "
        f"{[(name, line) for label, name, line in android if not label.strip()]!r}"
    )



def test_the_web_form_draws_exactly_the_contract_fields_in_order():
    expected = contract_labels()
    actual = web_form_labels()
    assert [label for label, _ in actual] == expected, (
        "the web capture form no longer draws the contract's fields:\n"
        + _report("web", WEB_FORM, expected, actual)
        + f"\n\n  The web is the reference — the owner ruled it correct — so a difference here is "
        f"either a field that was added to the form and not to {CONTRACT_PATH.name}, or a field "
        "that was removed from the form and left in the contract. Fix the contract in the same "
        "commit as the form, and then carry it to the handset and to all three registers. Never "
        "edit the contract alone to turn this green: the contract is what the handset is measured "
        "against, so weakening it here silently un-guards the client this whole file is about."
    )


def test_the_web_form_draws_each_field_with_the_control_the_contract_names():
    for field in contract()["fields"]:
        found = web_control_for(field)
        assert found is not None, (
            f"the contract's “{field['label']}” could not be located in {WEB_FORM.name}'s capture "
            "form at all, so its control cannot be read. The assertion above says which fields are "
            "present; fix that one first."
        )
        component, line = found
        assert component == field["web"]["component"], (
            f"{WEB_FORM.name}:{line} draws the contract's “{field['label']}” with <{component}>, "
            f"and {CONTRACT_PATH.name} declares <{field['web']['component']}> "
            f"(control kind: {field['control']}).\n"
            "  A control swap is not a styling change: it changes what the researcher can express. "
            "If the web genuinely moved to a different control, the contract and both clients move "
            "with it in one commit."
        )



def test_the_android_form_draws_exactly_the_contract_fields_in_order():
    expected = contract_labels()
    extras = {extra["label"] for extra in contract()["alsoDrawn"]["android"]}
    drawn = android_form_controls()
    actual = [(label, line) for label, _, line in drawn if label not in extras]

    assert [label for label, _ in actual] == expected, (
        "the handset's capture form does not draw the contract's fields:\n"
        + _report("android", ANDROID_MAIN, expected, actual)
        + "\n\n  Declared but not drawn here, in full: "
        + repr([label for label in expected if label not in {a for a, _ in actual}])
        + "\n  Drawn here and not declared: "
        + repr([label for label, _ in actual if label not in expected])
        + f"\n\n  The web is the reference. Bring the handset to {CONTRACT_PATH.name} — do not edit "
        "the contract to describe the handset, and do not add a label to `alsoDrawn` to hide a "
        "field: that register is for controls that are deliberately not fields of this form, and "
        "every row in it has to say why."
    )


def test_the_android_form_draws_each_field_with_the_control_the_contract_names():
    spec = contract()
    extras = {extra["label"] for extra in spec["alsoDrawn"]["android"]}
    drawn = {label: (name, line) for label, name, line in android_form_controls() if label not in extras}

    for field in spec["fields"]:
        found = drawn.get(field["label"])
        assert found is not None, (
            f"the contract's “{field['label']}” is not drawn on the handset's capture form, so its "
            "control cannot be read. The assertion above says which fields are missing; that is the "
            "one to fix first."
        )
        composable, line = found
        assert composable == field["android"]["composable"], (
            f"{ANDROID_MAIN.name}:{line} draws “{field['label']}” with {composable}(), and "
            f"{CONTRACT_PATH.name} declares {field['android']['composable']}() "
            f"(control kind: {field['control']}).\n"
            f"  The web draws it with <{field['web']['component']}>. The owner ruled the web "
            "correct, so the handset changes."
        )


def test_the_handset_labels_its_status_control_the_same_in_both_of_its_arms():
    source = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    for field in contract()["fields"]:
        for symbol in field["android"].get("alsoNamesLabelIn", ()):
            at = source.find(f"fun {symbol}(")
            assert at >= 0, (
                f"the contract says “{field['label']}” is also named inside `{symbol}`, and "
                f"{ANDROID_MAIN.name} no longer declares it."
            )
            body = _balanced(source, source.index("{", source.index(")", at)), "{", "}")
            assert f'"{field["label"]}"' in body, (
                f"`{symbol}` ({ANDROID_MAIN.name}:{_line_of(source, at)}) no longer names "
                f"{field['label']!r} anywhere in its body, and the contract says this is the second "
                "place that field is labelled. The arm most people see is the one that rots "
                "unnoticed: read the whole composable before changing this."
            )



def test_the_artisan_control_is_a_searchable_multiselect_and_never_a_checkbox_wall():
    spec = contract()
    fields = {field["key"]: field for field in spec["fields"]}
    region, base = _android_form_region()
    stripped = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    drawn = {label: (name, line) for label, name, line in android_form_controls()}

    for pin in spec["controlPins"]:
        field = fields[pin["field"]]
        assert field["control"] == pin["pin"], (
            f"{CONTRACT_PATH.name} pins “{field['label']}” to {pin['pin']!r} and declares its "
            f"control as {field['control']!r}. The contract disagrees with itself: one of the two "
            "was edited alone."
        )

        for forbidden in pin["forbidden"]:
            hit = re.search(rf"\b{forbidden}\s*\(", region)
            assert hit is None, (
                f"{ANDROID_MAIN.name}:{_line_of(stripped, base + hit.start())} calls {forbidden}() "
                f"inside the questionnaire capture form, and {CONTRACT_PATH.name} forbids it for "
                f"the “{field['label']}” field.\n"
                f"  {forbidden} paints a wall of checkboxes — every artisan at the workshop, one row "
                "each, straight into the form. The owner asked for this to become a searchable "
                f"multi-select dropdown: use {field['android']['composable']} from "
                "android/app/src/main/java/com/fieldrepository/app/ui/SearchableSelect.kt. Do not "
                "write a third control.\n"
                "  CARRY THE EMPTY-STATE REASONING ACROSS. An empty roster means one of four "
                "different things — in flight, failed, nobody at this workshop, nobody in the "
                "repository — and only two of them are facts about the repository. Both clients "
                "print the same four sentences off the same three facts today; the widget is what "
                "changes, not what the form is able to say."
            )

        found = drawn.get(field["label"])
        assert found is not None and found[0] == field["android"]["composable"], (
            f"the handset does not draw “{field['label']}” with "
            f"{field['android']['composable']}(). Found: "
            + (f"{found[0]}() at {ANDROID_MAIN.name}:{found[1]}" if found else "nothing")
            + f"\n  {CONTRACT_PATH.name} pins this field to {pin['pin']!r} on both clients."
        )

        web_found = web_control_for(field)
        assert web_found is not None and web_found[0] == field["web"]["component"], (
            f"the web no longer draws “{field['label']}” with <{field['web']['component']}>. Found: "
            + (f"<{web_found[0]}> at {WEB_FORM.name}:{web_found[1]}" if web_found else "nothing")
            + "\n  This is the reference implementation of the control the owner asked for. If it "
            "has moved, the contract and the handset move with it — the pin is not one client's."
        )


def test_every_deliberately_different_label_still_exists_and_still_says_it():
    for entry in contract()["distinctLabels"]:
        path = _ROOT / entry["site"]
        assert path.exists(), (
            f"{CONTRACT_PATH.name} records a deliberately different label at {entry['site']}, and "
            "that file no longer exists. Either move the row, or delete it and say in the commit "
            "why the distinction stopped applying."
        )
        source = _strip_kotlin_comments(path.read_text(encoding="utf-8"))
        counterparts = [
            field["label"]
            for field in contract()["fields"]
            if field["control"] == entry["control"]
        ]
        at = source.find(f'"{entry["label"]}"')
        assert at >= 0, (
            f"{entry['site']} no longer uses the label {entry['label']!r}, which "
            f"{CONTRACT_PATH.name} records as a DELIBERATE difference from the capture form's "
            f"{counterparts}.\n"
            "  If the two controls have been unified, delete the row — the reason it gives no "
            "longer holds and leaving it makes the table a worse description of the product than "
            "no table. If it was merely renamed, update the row and keep the argument."
        )


WEB_REGISTER = _ROOT / "frontend" / "components" / "guide" / "steps.ts"
ANDROID_REGISTER = (
    _ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "fieldrepository"
    / "app"
    / "ui"
    / "WalkthroughJourney.kt"
)
PRINTED_GUIDE = _ROOT / "docs" / "WALKTHROUGH.md"


def _loose(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def _names(haystack: str, label: str) -> bool:
    return f" {_loose(label)} " in f" {_loose(haystack)} "


def _web_register_fields() -> list[str]:
    source = _strip_ts_comments(WEB_REGISTER.read_text(encoding="utf-8"))
    at = source.find('id: "questionnaire"')
    assert at >= 0, (
        f'{WEB_REGISTER.name} no longer declares a card with `id: "questionnaire"`. That card is '
        "the web walkthrough's description of this form; if the step was renamed, this file and "
        "`WalkthroughStepsTest.kt` both have to learn the new id."
    )
    opener = re.search(r"\bfields:\s*\[", source[at:])
    assert opener, (
        f"{WEB_REGISTER.name}'s questionnaire card no longer declares a fields[] array. Every card "
        "has one — see the GuideStep type — so this is a refactor of the guide and this parser has "
        "to learn about it."
    )
    block = _balanced(source, at + opener.end() - 1, "[", "]")
    return re.findall(r'"((?:[^"\\]|\\.)*)"', block)


def _android_register_fields() -> list[str]:
    source = _strip_kotlin_comments(ANDROID_REGISTER.read_text(encoding="utf-8"))
    found = re.search(r'"questionnaire"\s+to\s+listOf\(', source)
    assert found, (
        f'{ANDROID_REGISTER.name} no longer declares `"questionnaire" to listOf(...)` in its field '
        "register. The ids are the join between the two walkthroughs; a step whose id this file "
        "cannot find is a step that has been renamed on one client alone."
    )
    block = _balanced(source, found.end() - 1, "(", ")")
    return re.findall(r'"((?:[^"\\]|\\.)*)"', block)


def _printed_guide_sentence() -> str:
    spec = contract()["registers"]["printed"]
    marker = spec["marker"]
    raw = PRINTED_GUIDE.read_text(encoding="utf-8")
    heading = None
    for paragraph in re.split(r"(?:\r?\n){2,}", raw):
        found = re.match(r"^## \d+\. (.+?) [-—]", paragraph)
        if found:
            heading = found.group(1).strip()
        stripped = paragraph.lstrip()
        if not stripped.startswith(marker):
            continue
        if heading == spec["heading"]:
            return " ".join(stripped[len(marker) :].split())
    raise AssertionError(
        f"{PRINTED_GUIDE.name} carries no {marker!r} line under a “{spec['heading']}” heading. "
        "This is the copy that gets printed and carried into a village with no signal — it is the "
        "one rendering whose reader cannot check it against the screen — so a missing list here is "
        "not a documentation nit. If the heading or the marker was reworded, update the contract's "
        "`registers.printed` in the same commit."
    )


def _registers() -> dict[str, tuple[pathlib.Path, str]]:
    return {
        "web walkthrough": (WEB_REGISTER, " · ".join(_web_register_fields())),
        "android walkthrough": (ANDROID_REGISTER, " · ".join(_android_register_fields())),
        "printed guide": (PRINTED_GUIDE, _printed_guide_sentence()),
    }


def test_all_three_registers_still_parse_to_something():
    for name, (path, text) in _registers().items():
        assert len(text) > 30, (
            f"the {name} register ({path.name}) parsed to {text!r}, which is too short to be a "
            "description of this form's boxes. The parser is matching the wrong thing, and until "
            "it is fixed the ceiling assertion below passes over anything."
        )


def test_every_contract_field_is_named_in_all_three_registers():
    spec = contract()
    licensed = {
        (row["register"], row["field"]): row["wording"] for row in spec["paraphrases"]["rows"]
    }
    missing: list[str] = []
    for name, (path, text) in _registers().items():
        for field in spec["fields"]:
            wording = licensed.get((name, field["key"]))
            if wording is not None:
                if _names(text, wording):
                    continue
                missing.append(
                    f"  {name} ({path.name}) · “{field['label']}”\n"
                    f"      the contract licenses the wording {wording!r} here,\n"
                    "      and that wording is no longer in the register."
                )
                continue
            if not _names(text, field["label"]):
                missing.append(f"  {name} ({path.name}) · “{field['label']}”")
    assert not missing, (
        "a register no longer names every field the contract declares:\n"
        + "\n".join(missing)
        + "\n\n  Each of these is a box a researcher has not been told to fill in. Add it to that "
        "step's field list — READ THE FORM FIRST and put it in the contract's order, because that "
        "list gets read with the form open beside it. If a register genuinely needs to say it as a "
        "sentence rather than as a label, add a row to the contract's `paraphrases.rows` saying so "
        "and why; a licence is only ever a licence to say a TRUE thing differently."
    )


def test_no_register_names_a_field_this_form_deliberately_does_not_have():
    offenders: list[str] = []
    for entry in contract()["absent"]:
        for label in entry["labelsThatMustNotAppear"]:
            for name, (path, text) in _registers().items():
                if _names(text, label):
                    offenders.append(f"  {name} ({path.name}) names {label!r}")
    assert not offenders, (
        "a register names a field this form deliberately does not have:\n"
        + "\n".join(offenders)
        + "\n\n  See `absent` in "
        + CONTRACT_PATH.name
        + " for the decision and where it is recorded in the source. Remove the entry from the "
        "register — do NOT add the field to the form to make this green, and do not delete the "
        "`absent` row: it is the only thing in this repository that can notice a guide describing "
        "a box that is not there."
    )


def test_the_registers_name_the_contract_fields_in_the_contract_order():
    expected = contract_labels()
    for name, (path, text) in _registers().items():
        loose = f" {_loose(text)} "
        positions = sorted(
            (loose.find(f" {_loose(label)} "), label)
            for label in expected
            if loose.find(f" {_loose(label)} ") >= 0
        )
        seen = [label for _, label in positions]
        wanted = [label for label in expected if label in seen]
        assert seen == wanted, (
            f"the {name} register ({path.name}) lists this form's fields in a different order from "
            f"{CONTRACT_PATH.name}:\n"
            f"    contract: {wanted}\n"
            f"    register: {seen}\n"
            "  The contract's order is the WEB's screen order and it is contractual — a guide read "
            "with the form open beside it sends the reader to the wrong box."
        )


def test_no_licensed_paraphrase_is_dead():
    spec = contract()
    fields = {field["key"]: field for field in spec["fields"]}
    registers = _registers()
    for row in spec["paraphrases"]["rows"]:
        assert row["field"] in fields, (
            f"{CONTRACT_PATH.name} licenses a wording for the field {row['field']!r}, which the "
            "contract does not declare. The register moved and this table did not follow."
        )
        assert row["register"] in registers, (
            f"{CONTRACT_PATH.name} licenses a wording for the register {row['register']!r}, which "
            f"is not one of {sorted(registers)}."
        )
        _, text = registers[row["register"]]
        assert not _names(text, fields[row["field"]]["label"]), (
            f"{CONTRACT_PATH.name} still licenses a paraphrase for “{fields[row['field']]['label']}” "
            f"in the {row['register']} register, and that register now names the field plainly. "
            "Delete the row: it is a special case that no longer applies, and leaving it makes this "
            "table a worse description of where the guides are allowed to summarise."
        )
        assert _names(text, row["wording"]), (
            f"{CONTRACT_PATH.name} licenses {row['wording']!r} for "
            f"“{fields[row['field']]['label']}” in the {row['register']} register, and that "
            "register does not contain it. Either restore the sentence, or delete the row if the "
            "guide now names the field plainly."
        )



def test_the_contract_is_internally_consistent():
    spec = contract()
    kinds = {"text", "textarea", "select", "searchable-multiselect", "date", "status", "richtext"}
    keys = [field["key"] for field in spec["fields"]]
    labels = [field["label"] for field in spec["fields"]]

    assert len(keys) == len(set(keys)), (
        f"{CONTRACT_PATH.name} declares the same key twice: "
        f"{sorted({key for key in keys if keys.count(key) > 1})}"
    )
    assert len(labels) == len(set(labels)), (
        f"{CONTRACT_PATH.name} declares the same label twice: "
        f"{sorted({label for label in labels if labels.count(label) > 1})}. Two boxes with one name "
        "on one form is a defect on the screen, not only in this file."
    )
    for field in spec["fields"]:
        assert field["control"] in kinds, (
            f"{CONTRACT_PATH.name} gives “{field['label']}” the control kind "
            f"{field['control']!r}, which is not one of {sorted(kinds)}. The vocabulary is closed: "
            "a new kind is a change both clients have to be able to draw, so add it here and say "
            "what draws it on each."
        )
        assert field["label"].strip() == field["label"] and field["label"], (
            f"{CONTRACT_PATH.name} declares the label {field['label']!r}, which is blank or padded. "
            "Labels are compared character for character between the two clients."
        )
        for client, key in (("web", "component"), ("android", "composable")):
            named = field[client][key]
            row = controls(client).get(named)
            assert row is not None, (
                f"{CONTRACT_PATH.name}'s “{field['label']}” says the {client} client draws it with "
                f"{named}, which is not in `controls.{client}`. Register it there with the kinds it "
                "draws and how it names itself, or the scanner cannot find this field at all."
            )
            assert field["control"] in row["kinds"], (
                f"{CONTRACT_PATH.name} declares “{field['label']}” as a {field['control']!r} "
                f"control and says the {client} client draws it with {named}, which "
                f"`controls.{client}` registers as {row['kinds']}. The contract contradicts itself: "
                "one of the two was edited alone, and until they agree this file is holding a "
                "client to a control the contract does not actually claim."
            )

    forbidden = {name for pin in spec["controlPins"] for name in pin["forbidden"]}
    for name in forbidden:
        row = controls("android").get(name)
        assert row is not None, (
            f"{CONTRACT_PATH.name} forbids {name} without registering it in `controls.android`. "
            "The scanner only looks for registered controls, so an unregistered forbidden widget "
            "could be swapped in and reported as a MISSING FIELD rather than as the wall it is."
        )
        assert not (set(row["kinds"]) & kinds), (
            f"{CONTRACT_PATH.name} forbids {name} and registers it as drawing {row['kinds']}, "
            "which is a kind a field is allowed to declare. A forbidden control must not be "
            "reachable as a legitimate one — that is how it gets drawn back in under a contract "
            "that appears to permit it."
        )

    for extra in spec["alsoDrawn"]["android"] + spec["alsoDrawn"]["web"]:
        assert extra["label"] not in labels, (
            f"{CONTRACT_PATH.name} lists {extra['label']!r} both as a contract field and in "
            "`alsoDrawn`. The second is the register of controls that are deliberately NOT fields "
            "of this form; a label in both is how a field gets quietly excused from every "
            "assertion above."
        )
        assert extra.get("why"), (
            f"{CONTRACT_PATH.name} excuses the control {extra['label']!r} from the field list with "
            "no reason given. Every row in `alsoDrawn` has to say why it is not a field — that is "
            "the whole cost of the register, and it is meant to be paid when the control is added."
        )
