"""The questionnaire capture form is declared once in `shared/questionnaire-form-contract.json`.

This file is what makes that declaration true of the product.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY THIS EXISTS, IN THE OWNER'S WORDS
══════════════════════════════════════════════════════════════════════════════════════════════

    "all the fields on the questionnaire page that are there on the web application, should be
     exactly there on the android app as well, the checkbox list should be replaced with multi-select
     dropdown for artisans over there as well, web implementation is correct, I want the same thing
     for android as well, create contracts so that the two do not drift"

Third report of the same desync. The first two were closed by editing the handset to match the
browser, which is a fix that lasts exactly until the next person edits one of them, because nothing
in either build can see the other tree.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY A CONTRACT AND NOT THE TWO-WAY PARITY TEST THIS REPOSITORY ALREADY KNOWS HOW TO WRITE
══════════════════════════════════════════════════════════════════════════════════════════════

The sibling designer portal has `backend/tests/test_walkthrough_fields_parity.py`, eight hundred
lines that parse TypeScript on one side and Kotlin on the other and hold the two walkthrough decks
equal. Its scanners are good and its failure message — the first differing index, both strings, named
— is the difference between reporting a drift and reporting the existence of one. Both are ported
here. What is NOT ported is its shape, and the two reasons are worth writing down because they are
the reasons this file is not that file:

1. IT CANNOT SAY WHICH SIDE IS RIGHT. Its own failure text has to end "The web is the register: fix
   steps.ts and WALKTHROUGH_FIELDS together, in one commit. Never edit the Kotlin alone to turn this
   green" — because the assertion cannot tell you that, only the prose can, and prose in an assertion
   is advice. Here the contract is the register, both clients are measured against it, and a failure
   names the file and line that disagrees with a declaration rather than naming two files that
   disagree with each other.

2. IT PASSES WHEN BOTH SIDES DRIFT THE SAME WAY, AND IN THIS REPOSITORY THEY HAVE.
   `android/app/src/test/java/com/fieldrepository/app/ui/WalkthroughStepsTest.kt` already holds
   `walkthroughJourney` equal to `steps.ts`, field for field, and it is green. Both of those
   registers name a "Date" field this form has not had since the comment at page.tsx:997 was written,
   and both omit Workshop, Questionnaire, Status and Interview notes. Two copies agreeing is not
   evidence.

So the register-to-register edge is deliberately NOT repeated below. That edge is guarded, in the
Android job, by the test named above. What was missing is the edge from each register to the FORM,
and that is every assertion in the second half of this file.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY IT READS SOURCE TEXT
══════════════════════════════════════════════════════════════════════════════════════════════

Neither client can be executed here. There is no browser and no emulator in this suite, and the
backend job is the only one that runs with `frontend/`, `android/` and `docs/` all on disk —
`WalkthroughStepsTest.kt` reaches sideways into `steps.ts` for the same reason, but it runs under the
Android job's `pull_request` path filter, so it is the wrong place to guard the half of this that a
frontend-only change is most likely to move.

Parsing source cannot be defeated by either build, and it is brittle to a REFACTOR of either
declaration, which is the intended trade: a rewrite of how a form declares its labels should make
somebody read this file. Every assertion therefore names the file and, where a regex is the
extractor, the LINE — because a parity test that says only "the lists differ" is a test people mute.

It also imports nothing but the standard library, which is worth more than it sounds: the rest of
this directory needs fastapi, prisma, openpyxl and cryptography to so much as collect, so on a
machine where those are not installed — a frontend developer's, an Android developer's — this file
is one of the few here that still runs. The two people most likely to break this contract are the
two least likely to have a backend environment.

══════════════════════════════════════════════════════════════════════════════════════════════
WHAT THIS DOES NOT CHECK, DELIBERATELY
══════════════════════════════════════════════════════════════════════════════════════════════

Whether a label is a GOOD name for a box, whether the two clients lay their fields out identically,
and anything about the location group, the media capture or the instrument's own questions — all
three of which are shared components with their own registers and their own tests, and a second copy
of any of them here would be the exact failure this file exists to prevent. The contract's
`maintenance` section names them and says so.
"""

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

# The declaration each side is read from, named once and quoted in every failure message, so a rename
# reports itself instead of quietly parsing to nothing.
WEB_FORM_ANCHOR = "<form onSubmit={submit}"
ANDROID_FORM_ANCHOR = "private fun QuestionnaireForm("


def contract() -> dict:
    """The one declaration. Read fresh rather than cached at import.

    Nothing here memoises, anywhere in this file. These are small files and a test suite that reads
    them once at import is a test suite that reports a stale answer when somebody edits a form
    between two runs of `pytest --looponfail` — which is exactly when this file is being read.
    """
    assert CONTRACT_PATH.exists(), (
        f"{CONTRACT_PATH} is missing. It is the contract every assertion in this file is about; "
        "without it there is nothing to measure either client against, and deleting it is not a way "
        "to make a drift pass."
    )
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def contract_labels() -> list[str]:
    """The field labels, in the contract's order."""
    return [field["label"] for field in contract()["fields"]]


def controls(client: str) -> dict[str, dict]:
    """``{component or composable: its registry row}`` for one client.

    THE PARSING RULES LIVE IN THE CONTRACT, NOT IN THIS FILE, and that is not tidiness. A registry
    here would be a second declaration of which components draw form fields, sitting in a test, held
    to nothing — which is the shape of the defect this whole file exists to close, one level down.
    Somebody adding a control kind edits the declaration and both readings of it follow.
    """
    key = "component" if client == "web" else "composable"
    return {row[key]: row for row in contract()["controls"][client]}


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Two comment strippers and a bracket scanner, because a regex cannot do this safely
# ──────────────────────────────────────────────────────────────────────────────────────────────
#
# Both source files put PROSE around the code being read, and a lot of it: the web form's field grid
# is two hundred lines of which well over half are comments, and the argument for the artisan
# picker's four empty-state sentences is written out at length on BOTH clients, quoting the very
# labels compared below. A regex over raw text matches inside a paragraph and reports a label the
# screen has never drawn.
#
# The sibling portal records what getting this wrong costs: it "does not fail loudly: it silently
# hands the parser a slab of prose or a slab of code". Both directions are bad here — prose read as
# code invents fields, code read as prose loses them — and neither says anything at the moment it
# happens.


def _strip_ts_comments(source: str) -> str:
    """`//` and `/* … */` removed, string literals left alone.

    Character by character rather than by regex, because both comment forms appear INSIDE the strings
    this file reads: the questionnaire page's own comments quote paths like
    `app/(protected)/questionnaire/page.tsx` and `backend/app/services/records.py`, and a regex that
    does not know where a string starts will cut one in half.

    ── NEWLINES ARE PRESERVED, AND THIS IS THE HALF THIS FUNCTION USED TO GET WRONG ──────────────

    It was ported from the sibling portal's `test_walkthrough_fields_parity.py` UNCHANGED, and this
    docstring said so approvingly. It was indeed correct there — that file reports the differing
    INDEX in a list, never a line number, so a stripper that swallowed a fifty-line comment cost it
    nothing. Here every failure message about the web carries `page.tsx:<line>`, and a block comment
    dropped whole took its newlines with it: this page is 2,690 lines of which 517 are inside `/* …
    */`, so the form's own boxes were reported 230 lines early at the top and 378 early at the foot.
    `label="Place"` lives at 1004 and the assertion said 770 — which in this file is not a blank or a
    near miss but a real line in the middle of the offline submit payload. `_strip_kotlin_comments`
    had carried the fix since it was written and says why in as many words; the two strippers simply
    disagreed, in the one respect neither docstring compared them on.

    The rejected alternative was to stop printing web line numbers and print only the field index,
    which is what the ported original does and would have been true rather than wrong. It is refused
    for the reason `_line_of` states: a line number is the difference between sending a reader to a
    2,690-line file and sending them to the edit. A number a reader trusts has to be right; the
    answer is to make it right, not to withdraw it.

    Block comments do NOT nest in TypeScript — `/* /* */` ends at the first terminator — so unlike
    the Kotlin twin this arm stays a flat scan. That is a real difference between the two languages
    and not a second oversight.
    """
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
            # The newline itself is deliberately NOT consumed here — the loop stops on it and the
            # ordinary path below emits it. A line comment therefore already preserved its own line,
            # which is why only the block arm below was ever off.
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
    """The same, for Kotlin, and it is NOT the same function.

    Three differences, each of which would be a silent wrong answer if the TypeScript stripper were
    pointed at this file instead:

    * KDoc `/** … */` nests in Kotlin. A `/*` inside a doc comment does not end at the first `*/`,
      and the artisan picker's KDoc is exactly the kind of comment somebody puts a snippet in.
    * Raw strings `\"\"\" … \"\"\"` have no escapes at all, so the backslash rule the TS stripper uses
      would walk straight past a terminator.
    * A single quote is a CHARACTER literal here, not a string delimiter. Treating `'` as a quote
      would swallow everything from the first apostrophe in a comment to the next one — and the
      comments in `MainActivity.kt` are English prose full of apostrophes.

    NEWLINES ARE PRESERVED for everything removed, because every failure message below reports a LINE
    NUMBER, and a stripper that collapsed a fifty-line KDoc to nothing would report line numbers that
    are off by fifty in a file of fifteen thousand — which is worse than reporting none, since a
    reader trusts a number.

    THIS IS NO LONGER A FOURTH DIFFERENCE. `_strip_ts_comments` preserved its line comments' newlines
    and dropped its block comments', so it reported the web's fields hundreds of lines early; it was
    fixed on 2026-09-16 and the paragraph in its docstring is the account of it. Newline preservation
    is now an invariant BOTH strippers hold and `_line_of` depends on — whichever of them is edited
    next, it is the property to keep.
    """
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
    """The text from ``source[start]`` (an opener) to its matching closer, inclusive.

    Skips string literals, so a bracket inside a label cannot close the block early — "Involved
    artisan(s)" on the browse screen is exactly that entry, and it is one of the strings this file
    reads. Ported from the sibling portal, where a parenthesised label is why it is a scanner and not
    a `.index(")")`.
    """
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
    """1-indexed line number of ``offset``, for a failure message somebody has to act on.

    THE REASON EVERY MESSAGE BELOW CARRIES ONE. `MainActivity.kt` is fifteen thousand lines and the
    questionnaire form is nine hundred of them; "the handset draws the wrong control" sends a reader
    to a file, and "MainActivity.kt:12512 draws ArtisanMultiSelectField" sends them to the edit.
    """
    return source.count("\n", 0, offset) + 1


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The web form
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _web_form_region() -> tuple[str, int]:
    """The capture `<form>`'s own source, comment-stripped, and its offset in the stripped file.

    SLICED TO THE FORM AND NOT READ WHOLE, because `page.tsx` is two and a half thousand lines and
    most of what is not this form is the questionnaire BUILDER further down it — which draws
    `<Field label="Section code">`, `<Field label="Code">`, `label="Section title"` and half a dozen
    more. Read whole, this parser would report those as fields of the capture form: not a crash, not
    an empty list, just a confident wrong answer about a screen nobody looked at.
    """
    source = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))
    at = source.find(WEB_FORM_ANCHOR)
    assert at >= 0, (
        f"{WEB_FORM_ANCHOR!r} is no longer in {WEB_FORM.name}. That string is how this file finds "
        "the capture form at all; if the form has been rewritten or moved, this parser moves with "
        "it — do not delete this test to make a refactor pass."
    )
    end = source.find("</form>", at)
    assert end > at, (
        f"{WEB_FORM.name} has no </form> after {WEB_FORM_ANCHOR!r}. Without a closing tag this "
        "parser would read the rest of the page, including the questionnaire builder's own fields."
    )
    return source[at:end], at


def _component_default_label(path: pathlib.Path, component: str) -> str:
    """A label a web component supplies for itself, read out of that component's file.

    WHY THIS EXISTS: `<WorkshopSelect state={workshop} saving={saving} />` carries no `label` prop —
    the string lives in `WorkshopSelect`'s own signature as `label = "Workshop"`. A scan of the form
    for quoted labels alone therefore reports the workshop field as MISSING FROM THE WEB, which is
    the parser lying about the reference implementation. That failure would be read as "the contract
    is wrong" and the field would be deleted from the contract to make it green.
    """
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
    """``[(label, line)]`` for every labelled control the capture form draws, in screen order.

    A list of pairs and not a set, because ORDER IS HALF OF THE CONTRACT — see the contract's
    `howOrderIsPartOfIt`. This list read top to bottom is the form read top to bottom.

    TWO KINDS OF LABEL, INTERLEAVED BY OFFSET. Most are written at the call site, either directly
    (`<DictatedTextInput label="Interview title" …>`) or on the `<Field>` wrapper that holds a
    `<Select>`; the workshop's comes from its component's default. Matching both patterns in one
    ordered pass is what keeps the workshop in its true POSITION rather than appended at the end,
    and the position is the thing being asserted.

    Labels written as EXPRESSIONS are invisible here and that is correct rather than a gap:
    `<UploadProgress label={INTERVIEW_SECTION_LABEL}>` and the per-section `<ClipRecorder
    label={`Section ${code} audio`}>` are not form fields, they name progress cards and recorders.
    The day one of them becomes a field it will have to be written as a literal or declared in the
    contract's `alsoDrawn`, and either way somebody will have read this paragraph.
    """
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
            # An explicit prop WINS over the default, and is reported at the element's own position.
            # Without this arm a call site that overrode the label would be read as using the
            # default AND as carrying a literal, and the field would appear twice.
            override = re.search(r'\blabel="([^"]*)"', tag)
            if override:
                marks = [mark for mark in marks if mark[0] != match.start() + override.start()]
                marks.append((match.start(), override.group(1)))
            else:
                marks.append((match.start(), _component_default_label(_ROOT / source_path, component)))

    marks.sort()
    # `stripped` and `region` share an origin, so an offset inside the region maps to the stripped
    # file by adding `base` — and the stripped file preserves newlines, so the line number is the
    # line number in the file on disk.
    return [(label, _line_of(stripped, base + offset)) for offset, label in marks]


def web_control_for(field: dict) -> tuple[str, int] | None:
    """The component that draws *field* on the web form, and the line it is drawn on.

    STRUCTURAL, WHICH IS THE ONLY KIND OF CHECK THAT CATCHES A WIDGET SWAP. A label assertion goes
    green over a checkbox wall that has been renamed; this reads what is actually being rendered.

    THREE SHAPES, because the form uses three, and the third is why this takes the whole contract
    entry rather than just a label:

    * A control that takes its own `label` prop IS the control (`<DictatedTextInput label="Place">`).
    * A `<Field label="…">` wrapper is followed by the control it wraps (`<Select>`,
      `<MultiSelectDropdown>`), so the answer is the first capitalised element after the label.
    * A `componentDefault` field has NO label at its call site at all — `<WorkshopSelect state={…} />`
      keeps its string in its own signature — so there is no label to search back from and the
      element itself is what has to be found. Searching for the label first, which is what the other
      two do, returns nothing here and reports the reference client as missing a field it draws.
    """
    region, base = _web_form_region()
    stripped = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))
    registry = controls("web")

    # The componentDefault arm searches by COMPONENT and then checks the label, which is the reverse
    # of the other two — and it searches every registered self-labelling component rather than only
    # the one this field declares, so that a field drawn by the WRONG one is reported as the swap it
    # is instead of as a field nobody could find.
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


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The Android form
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _android_form_region() -> tuple[str, int]:
    """`QuestionnaireForm`'s body, comment-stripped, and its offset in the stripped file.

    BRACE-BALANCED FROM THE FUNCTION'S OWN `{` AND NOT "UP TO THE NEXT @Composable". The obvious
    slice is wrong here and wrong quietly: the next `@Composable` at column zero after
    `QuestionnaireForm` is two hundred and seventy lines past the end of it, so that slice swallows
    the next composable whole and reports its controls as fields of this form. It was tried while
    writing this file and it silently produced two extra fields.
    """
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
    """A label a Kotlin composable supplies for itself, read out of its own declaration.

    The handset's counterpart to `_component_default_label`, and it matters for three of the eight
    fields rather than one: `WorkshopField` reaches `WorkshopDropdown`'s `label: String = "Workshop"`,
    `StatusControl` reaches `StatusDropdown`'s `label = "Status"`, and `MultiNoteInput` defaults
    `label: String = "Notes"` — which is the whole of why the handset's notes box is called something
    the web's is not, with no call site anywhere saying so.

    Reads the PARAMETER DEFAULT first and a named argument in the body second, in that order. A
    composable that declares `label: String = "X"` and then passes something else through is a
    control lying about itself, and the parameter is the declaration.
    """
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
    """``[(label, composable, line)]`` for every labelled control the handset's form draws, in order.

    THE REGISTRY IS THE CONTRACT'S, NOT THIS FILE'S. Which composables count as labelled controls is
    derived from the contract's own `fields`, `controlPins.forbidden` and `alsoDrawn.android` — so
    adding a control kind is a change to the declaration rather than to the parser, and a composable
    nobody declared is simply not a field.

    WHY NOT SCAN FOR EVERY QUOTED STRING, which is the shorter parser: the form's body holds nineteen
    `Text("…")` calls of running prose and two `AttachedUploadsCard(label = "recording")`, where
    "recording" is a noun in a progress sentence and not a field label at all. A scan for literals
    reads all of them as fields, and the failure it then reports is about the parser rather than
    about the form — which is the report people mute.
    """
    region, base = _android_form_region()
    stripped = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    registry = controls("android")

    found: list[tuple[int, str, str]] = []
    for name, row in sorted(registry.items()):
        how = row["labelFrom"]
        for match in re.finditer(rf"\b{name}\s*\(", region):
            args = _balanced(region, match.end() - 1, "(", ")")
            label = None
            # An explicit `label =` wins wherever it appears, including over a composable's own
            # default — `MultiNoteInput(label = "…")` is a real call shape elsewhere in this file's
            # subject tree, and reading the default over it would report a label no screen draws.
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


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The failure message
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _report(client: str, path: pathlib.Path, expected: list[str], actual: list[tuple[str, int]]) -> str:
    """The first line that actually differs, named, with a file and a line number.

    Printing both lists whole is what comparing two eight-element lists already does badly: the
    reader diffs them by eye and stops at the first thing that looks similar. Naming the index, both
    strings and the source line is the difference between a test that reports a drift and one that
    reports the existence of a drift. Ported from the sibling portal, which made the same argument
    about thirty-element lists and was right about eight.
    """
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


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 0. The guard that stops every assertion below from being vacuously green
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_contract_and_both_forms_still_parse_to_something():
    """Green below means the comparison was MADE, not that three parsers came back empty.

    THE FAILURE A CONTRACT TEST IS MOST PRONE TO, and the one it would report as success: a form is
    reformatted or an anchor renamed, the scanner returns `[]`, `[] == []` passes, and the suite
    reports agreement about a comparison it never performed. The sibling portal opens with the same
    guard and names the cost — the failure reads as "the wiring is fine" when the parser is the
    broken thing.

    THE FLOORS ARE FLOORS AND NOT THE CURRENT COUNTS, deliberately. Pinning the exact number would
    put a second copy of the register in this file, and it would fail on the day the form
    legitimately grows a field — the one event this suite exists to welcome, because that is the day
    somebody opens the contract.
    """
    spec = contract()
    assert len(spec["fields"]) >= 5, (
        f"{CONTRACT_PATH.name} declares only {len(spec['fields'])} fields. This form has never had "
        "fewer than five; a contract this short is a contract that was emptied rather than edited."
    )

    web = web_form_labels()
    android = android_form_controls()
    assert len(web) >= 5, (
        f"only {len(web)} labelled controls parsed out of {WEB_FORM.name}'s capture form. The "
        f"anchor is {WEB_FORM_ANCHOR!r} and the labels are read as `label=\"…\"` plus the "
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


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 1. The WEB form renders exactly the contract fields, in order, with those labels
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_web_form_draws_exactly_the_contract_fields_in_order():
    """The reference implementation, held to the declaration derived from it.

    IT IS NOT CIRCULAR AND IT IS NOT DECORATION. The contract was read off this form, so this
    assertion is green the day it is written — and that is the point: from that day on, a change to
    the web form has to be a change to the contract, in the same commit, or the build says so. Every
    other assertion in this file measures something against the contract, and all of them are worth
    nothing if the contract has quietly stopped describing the screen the owner ruled correct.

    EQUALITY AND NOT CONTAINMENT, and the two directions fail for genuinely different reasons:

    * A contract field MISSING here means the declaration has outlived the form — somebody removed a
      box and this file is the only thing that noticed.
    * A label here the contract does not declare is a NEW field on the reference client, which is the
      original defect running forwards instead of backwards: the handset is now behind by one and
      nothing else in either build can see it. Add it to the contract, then to the handset, then to
      the three registers.
    """
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
    """The labels being right is not the same claim as the controls being right.

    The whole reason `controlPins` exists is that a label assertion passes over a widget swap. This
    holds the reference client to its own declared controls so that the handset's control check
    below is measured against something that is itself checked — otherwise the contract could drift
    from the web on the control kind and the handset would be brought into line with a fiction.
    """
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


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 2. The ANDROID form renders exactly the contract fields, in order, with those labels
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_android_form_draws_exactly_the_contract_fields_in_order():
    """The owner's report, turned into something that cannot be closed by hand twice more.

    Three kinds of difference, and each is a different conversation:

    * A field MISSING on the handset is the report verbatim — "all the fields ... should be exactly
      there on the android app as well". A researcher on a phone cannot say which instrument this
      sitting answers, or set the status, or leave a note.
    * A field whose LABEL differs is the quietest of the three and the one that survives longest.
      One box, two names, across two devices a researcher may use on the same day.
    * A field in a different ORDER is a form that has to be re-learned per device. See the
      contract's `howOrderIsPartOfIt`; the sequence is the contract's, and it is the WEB's.

    THE `alsoDrawn` REGISTER IS WHAT LETS THIS BE AN EQUALITY. The handset legitimately draws two
    labelled controls that are not fields of this contract — the recording-mode preference and the
    per-question answer box — and they are declared, with reasons, in the contract rather than
    filtered out here. A parser that skipped unknown controls would be silent about a NEW box
    appearing on one client and not the other, which is this same drift running the other way.
    """
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
    """The handset's controls, pinned by composable — see assertion 6 for why this is structural.

    Split from the label assertion above rather than folded into it, because the two failures need
    different edits: a label drift is a one-word change at a call site, and a control drift is a
    control being replaced. A single assertion covering both reports whichever it hits first and
    sends the reader to the wrong kind of work.
    """
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
    """A control that is two controls by role has to say one thing in both of them.

    `StatusControl` draws a dropdown for PROFESSOR+ and a locked "Pending" chip for everybody else,
    and the two arms write their label in two different places — the dropdown passes it through to
    `StatusDropdown`, the chip draws its own `Text`. The contract reads the label from the first,
    which means the second could be renamed with nothing noticing. It is the arm MOST researchers
    see, and the one nobody reviewing a status change would think to open.

    Driven by `alsoNamesLabelIn` in the contract rather than by a hard-coded symbol here, so a field
    that grows a second arm declares it where the field is declared.
    """
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


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 6. The artisan control is a SEARCHABLE MULTI-SELECT on both clients, and not a checkbox wall
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# Numbered out of order on purpose: it belongs beside the two form assertions it sharpens, not after
# three sections about guides. The owner asked for this one by name.


def test_the_artisan_control_is_a_searchable_multiselect_and_never_a_checkbox_wall():
    """The one the owner named: "the checkbox list should be replaced with multi-select dropdown".

    PINNED BY THE CONTROL AND NOT BY THE LABEL, which is the entire point of this assertion existing
    separately from the two above. Rename `ArtisanMultiSelectField`'s label to the contract's word
    and every label assertion in this file goes green over a Column of checkboxes — one row per
    artisan, painted straight into a form that is already long, with no summary line, so the only way
    to see what is ticked is to scroll back over it. That is the defect, and a label cannot see it.

    BOTH DIRECTIONS, ON BOTH CLIENTS. The pinned control must be the one drawing the field, AND the
    forbidden ones must not appear anywhere in the form at all — because the interesting regression
    is not "somebody renamed the field", it is "somebody put the wall back beside the dropdown while
    half-landing something else", and a check that only reads the control at the label would miss it.

    `SearchableMultiSelectField` already exists (android/.../ui/SearchableSelect.kt) and is what
    `CheckboxMultiSelectField` was itself rewritten onto. There is no third control to write, and
    writing one would be this whole problem again.
    """
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
    """`distinctLabels` — the sites that use another word on purpose, held so they cannot go stale.

    ONE ENTRY TODAY: the browse screen's artisan filter says "Involved artisan(s)" while the capture
    form says "Artisans interviewed". That is a decision with a reason written out in the contract —
    the filter narrows a list of interviews that already exist and ticking somebody there asserts
    nothing about any record, whereas the capture form's label is a claim about the interview being
    written — and the web agrees by precedent, naming its own browse filter "Filter by artisan".

    WHY IT IS ASSERTED AT ALL, rather than merely written down: a licence nobody checks is the third
    register again. It would keep reading as a considered decision long after the screen it describes
    had been renamed or deleted, and the next person to widen this file would treat the table as an
    accurate description of where the product deliberately differs. Both halves are checked — the
    site must still exist, and it must still use this word — so a rename in either direction reports
    itself instead of leaving a sentence behind that is no longer about anything.
    """
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


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 3, 4 and 5. The three registers that DESCRIBE this form to a researcher
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# A FLOOR AND A CEILING RATHER THAN AN EQUALITY, and the asymmetry is the considered part.
#
# THE FLOOR: every contract field must be NAMED in each register. A field that is on the form, on
# both clients, and that a guide does not name, is a box the researcher has not been told to fill in.
#
# THE CEILING: no register may name a field the contract declares ABSENT. That is not hypothetical —
# all three currently list a "Date" this form deliberately does not have, and a floor-only check is
# blind to it by construction.
#
# WHY NOT AN EQUALITY, which is the obvious stronger thing: a register describes a whole SCREEN and
# this contract describes the interview's own boxes. "Per question: a 'Record this question' audio
# clip, or a typed answer" is a true and necessary thing to tell a researcher and has no single form
# label to be equal to. The sibling portal made this check symmetric once and recorded what it cost:
# the printed guide was reported as wrong FOR BEING RIGHT, and the quickest way to green was deleting
# true sentences about the one field that stops the archive filling with duplicate artisans.

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
    / "WalkthroughSteps.kt"
)
PRINTED_GUIDE = _ROOT / "docs" / "WALKTHROUGH.md"


def _loose(text: str) -> str:
    """Lowercased, with every run of non-alphanumerics collapsed to one space.

    The registers are decorated and the prose is markdown: `steps.ts` writes "Interview title
    (required)" and `docs/WALKTHROUGH.md` writes "**Interview title** *(required)*", and that
    emphasis is typography rather than a different claim about the form.

    DELIBERATELY LOOSER THAN THE CLIENT COMPARISON ABOVE, which is character-for-character including
    punctuation. Between two CLIENTS, "Do's" against "Do's" really is one screen calling one box two
    names and there is nothing to be tolerant about. Between a form label and an English sentence
    describing it there is no such defect to catch, and enforcing it would only teach people to paste
    chips into paragraphs.
    """
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def _names(haystack: str, label: str) -> bool:
    """Does *haystack* name *label*, allowing decoration but not a coincidental substring.

    Padded on both sides rather than a bare `in`: without the padding "Place" is named by the word
    "Placement" and "Status" by "Statuses", and a floor check that can be satisfied by an unrelated
    word is a floor check that reports a guide as complete because of a typo in it.
    """
    return f" {_loose(label)} " in f" {_loose(haystack)} "


def _web_register_fields() -> list[str]:
    """The questionnaire card's `fields[]` out of `steps.ts`, in order."""
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
    """The same list out of `WalkthroughSteps.kt`, in order.

    Kotlin-stripped first, and that is not belt-and-braces: `WalkStep.fields`' own KDoc quotes
    `GuideStep.fields`, names `backend/tests/test_walkthrough_fields_parity.py`, and discusses the
    very strings below. Read raw, this would pick up quoted runs out of a paragraph about the
    register and report them as entries in it.
    """
    source = _strip_kotlin_comments(ANDROID_REGISTER.read_text(encoding="utf-8"))
    at = source.find('id = "questionnaire"')
    assert at >= 0, (
        f'{ANDROID_REGISTER.name} no longer declares a step with `id = "questionnaire"`. The ids '
        "are the join between the two walkthroughs; a step whose id this file cannot find is a step "
        "that has been renamed on one client alone."
    )
    opener = re.search(r"\bfields\s*=\s*listOf\(", source[at:])
    assert opener, (
        f"{ANDROID_REGISTER.name}'s questionnaire step no longer declares `fields = listOf(...)`."
    )
    block = _balanced(source, at + opener.end() - 1, "(", ")")
    return re.findall(r'"((?:[^"\\]|\\.)*)"', block)


def _printed_guide_sentence() -> str:
    """The "What the screen asks for" line under the printed guide's Questionnaire heading.

    Attached to the numbered HEADING above it rather than to a position in the file, so that
    inserting a section does not shift the list quietly onto the next step. That failure would not be
    loud in a useful way: the reader would be sent to the wrong step and told a true thing about a
    screen they were not looking at.

    WHITESPACE COLLAPSED, because the document is hard-wrapped at about column 100 and "Artisans
    interviewed" is split across two lines today. The wrap column is an editor setting, not a claim
    about the product.
    """
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
    """``{name: (file, the text that register uses to describe this form)}``.

    One dict so that the three assertions below are one loop each instead of three near-copies. They
    fail per register with the register named, which is what a reader needs — the fix for the printed
    guide is a sentence and the fix for `steps.ts` is a chip.
    """
    return {
        "web walkthrough": (WEB_REGISTER, " · ".join(_web_register_fields())),
        "android walkthrough": (ANDROID_REGISTER, " · ".join(_android_register_fields())),
        "printed guide": (PRINTED_GUIDE, _printed_guide_sentence()),
    }


def test_all_three_registers_still_parse_to_something():
    """Again the vacuous-green guard, and again because the floor check below is a containment.

    A containment over an empty haystack fails loudly, which sounds safe — but the CEILING check is a
    containment too, and over an empty haystack it PASSES: "no register names a field the form does
    not have" is trivially true of a register that parsed to nothing. That is the assertion most
    likely to be silently switched off by a reformat, and it is the one currently catching a real
    defect in all three registers.
    """
    for name, (path, text) in _registers().items():
        assert len(text) > 30, (
            f"the {name} register ({path.name}) parsed to {text!r}, which is too short to be a "
            "description of this form's boxes. The parser is matching the wrong thing, and until "
            "it is fixed the ceiling assertion below passes over anything."
        )


def test_every_contract_field_is_named_in_all_three_registers():
    """THE FLOOR. A field nobody was told about is a field nobody fills in.

    Assertions 3, 4 and 5 of the brief, in one loop: the web walkthrough register, the Android
    walkthrough register, and the printed guide a researcher carries with no signal.

    WHY ALL THREE AND NOT JUST THE TWO CLIENTS. The printed copy is the only rendering whose reader
    cannot check it against the screen: a researcher in a courtyard with no bars reads the paper,
    fills in what it lists, and leaves. It was held by nothing at all before this file — the printed
    guide says so itself, under "How this document is kept true": "the Android screens are asserted
    to carry the same names and the same fields as the web ones. That parity is real as a design rule
    and is not mechanically checked; if a field exists on one client and not the other, nothing in
    this repository will notice."

    A register may name MORE than this — see the block comment above this section for why making it
    symmetric would report the guides as wrong for being right.
    """
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
    """THE CEILING, and the assertion that a two-way parity test structurally cannot make.

    All three registers currently name a "Date" between the title and the place. The form has not had
    one for as long as the comment at `page.tsx:997` has been there: the server derives
    `interviewDate` from `recordedAt`, which is when the interview was actually captured, and asking
    a researcher to confirm today's date was a field to tab past that could only ever be wrong.
    `RecordedAtField.tsx` is a deprecated stub that renders null across every record form for the
    same reason, and the handset says it again at `MainActivity.kt:12490`.

    THE TWO REGISTERS AGREE WITH EACH OTHER ABOUT THIS AND `WalkthroughStepsTest.kt` IS GREEN. That
    is the whole argument for a contract in one sentence: a parity test compares the copies, and both
    copies say the same wrong thing. Only a declaration of what the form actually is can tell them
    apart, and only a NEGATIVE declaration can catch a field that was removed on purpose — a floor
    check looks for what should be there and is blind by construction to what should not.

    A guide naming a field that was deliberately removed sends the reader looking for it, and the
    printed guide does that to somebody with no signal to check against.
    """
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
    """Order again, one level out: a guide that lists a form's boxes out of sequence.

    The registers are documented as SCREEN ORDER on both clients — `GuideStep.fields` is "the real
    form labels, in screen order, with (required) marked", and `WalkStep.fields` says it copies that
    verbatim. A list in the wrong sequence is read with the form open beside it, so the reader
    reaches for the box two fields further down and, finding a different one, fills it in.

    RESTRICTED TO THE CONTRACT'S FIELDS, because the registers legitimately carry more (the
    per-question line, and the printed guide's prose around it). What is compared is the order of the
    fields both the form and the register have, which is the only sequence the two can disagree about.
    """
    expected = contract_labels()
    for name, (path, text) in _registers().items():
        # PADDED, exactly as `_names` is, and for the same reason: an unpadded find would place
        # "Place" at whatever offset the word "Placement" happens to sit at and then report an
        # ordering defect that is really a coincidence between two words.
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
    """A licence for a field that no longer exists, or no longer needs one, is the drift again.

    The table is empty today and this assertion is therefore trivially green — which is the right
    time to write it, not the wrong one. Both halves fail for a reason worth naming:

    * A row naming a field the contract does not declare means the table is quoting a register that
      has moved. It would keep passing on its own — an unused key is never consulted — while reading
      as a considered decision about a field nobody has been offered since.
    * A row whose field the register now names PLAINLY is a special case still switched on. The next
      person to widen the floor check reads this table as the list of places a guide is allowed to be
      loose, and every stale row makes that list a worse description of the guides than it is. Delete
      it: the plain naming is better than the licence.
    """
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


# ══════════════════════════════════════════════════════════════════════════════════════════════
# The contract's own hygiene
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_contract_is_internally_consistent():
    """The declaration held to itself, because everything above trusts it completely.

    A contract with two fields of the same key, or a control kind nobody has ever drawn, or a
    duplicate label, is a contract that will make one of the assertions above report something
    confusing rather than something wrong. The vocabulary is closed on purpose: a typo like
    "multiselect" for "searchable-multiselect" would otherwise pass into the file and read, forever
    after, as a control kind somebody chose.
    """
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
        # THE JOIN THE CONTROL REGISTRY EXISTS FOR. A field names a control KIND and, separately,
        # the component each client draws it with; nothing but this assertion says those two agree.
        # The first draft of this contract declared `language` as `"control": "text"` and named the
        # handset's `DropdownField` for it in the same entry — a contract that said free text and
        # held the handset to a closed vocabulary, with every assertion in this file green over it.
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
