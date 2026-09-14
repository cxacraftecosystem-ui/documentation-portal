"""A dimension a model guessed must be distinguishable from one a person measured.

WHAT WAS TRUE BEFORE THIS FILE EXISTED, in the words of the columns themselves.
``ProductDocumentation.lengthInches`` is printed as a documented dimension by
``services/record_fields.py`` and read by somebody costing a production run. Three processes wrote it
— a typed tape reading, ``photoMeasure``'s arithmetic, and Gemini's estimate off a grid-sheet
photograph — and the row recorded no difference between them. Worse: ``merge_field_provenance`` stamps
every changed field with the ``{by, byName, at}`` of whoever saved the form, and the dimension columns
are not in its skip list, so a model's estimate that auto-filled the field was stored attributed BY
NAME to a researcher. The record did not fail to say a machine produced the number; it asserted a
human had measured it.

**THE FOUR PROPERTIES THIS FILE HOLDS, AND WHAT EACH COSTS IN THE FIELD IF IT STOPS HOLDING:**

1. **Every grid reading states its method, provider and model.** Without it the number is
   indistinguishable from a tape measurement the moment it lands in a field, and no later reader — no
   auditor, no ministry — can separate them again. This is the defect.
2. **No confidence is ever invented.** Gemini's ``confidence`` is a model's claim about itself and
   nothing in this repository calibrates it. A default would be a fabricated fact about certainty, and
   a clamped out-of-range value would be the loudest possible one.
3. **A malformed marker becomes UNRECORDED and never TYPED.** The fallback's DIRECTION is the safety
   property: resolving to TYPED would let slightly-wrong JSON turn a model's estimate into an apparent
   human measurement — the original defect, reachable by accident.
4. **The offline geometry path is not burdened.** ``PhotoMeasure`` is deterministic, needs no network,
   and is the primary path on the handset that goes to the village. It must state its method and must
   NOT need an acceptance step, a server round trip, or anything this file could make it wait for. A
   fix that made a courtyard measurement worse to repair a cloud one would be a bad trade.

NO DATABASE AND NO NETWORK, and nothing here skips. The vocabulary tests are pure functions. The two
route tests drive the real router over HTTP with ``db`` replaced by a tripwire that raises the moment
anything reads a delegate off it — so "the reader writes nothing" is a fact about the calls made
rather than an inference from a status code.

EVERY TEST IS SYNCHRONOUS AND USES ``asyncio.run`` WHERE IT NEEDS A COROUTINE. ``pytest-asyncio`` is
in no requirements file here and ``asyncio_mode`` was deliberately removed from ``pyproject.toml``
(its comment says why), so an ``async def test_`` is silently not run on CI. The convention that
works everywhere is ``pytestmark = pytest.mark.anyio``; this module needs no fixture to be async, so
it takes the simpler road and marks nothing.
"""

import ast
import asyncio
import dataclasses
import json
import math
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.services import ai
from app.services.measurement_provenance import (
    DIMENSION_FIELDS,
    GEOMETRY_TECHNIQUES,
    MARKER_BODY_KEY,
    MARKER_CONFIDENCE_KEY,
    UNRECORDED,
    MeasurementMethod,
    marker_body_problems,
    method_stamps,
    provenance_of_marker,
    self_reported_confidence,
    vision_model_provenance,
)
from app.services.records import PROVENANCE_SKIP_FIELDS, merge_field_provenance

BACKEND = Path(__file__).resolve().parents[1]
MODEL_ID = "gemini-2.5-flash-lite"


def _settings(**overrides) -> SimpleNamespace:
    base = {"gemini_measurement_model": MODEL_ID}
    base.update(overrides)
    return SimpleNamespace(**base)


def assert_sentence(text: str) -> None:
    """Every refusal in this lane is field copy: a real sentence, no error code, and it ends.

    These are shown verbatim to somebody standing over a craft object with a form open — both clients
    print ``detail`` as it arrives.
    """
    assert text and text.strip(), "a refusal must say something"
    assert len(text) > 30, f"too terse to be an explanation: {text!r}"
    assert text.rstrip().endswith("."), f"not a sentence: {text!r}"
    assert "code " not in text.lower(), f"names an error code: {text!r}"


# --------------------------------------------------------------------------------------
# The vocabulary
# --------------------------------------------------------------------------------------


def test_the_four_methods_and_nothing_else():
    """Enumerated, so a fifth added without thought fails here. Each of these four answers a different
    question about what a later reader can do with the number; a fifth that did not would be a synonym,
    and two spellings of one method is how a report ends up unable to group its own rows."""
    assert sorted(m.value for m in MeasurementMethod) == [
        "PHOTO_GEOMETRY",
        "TYPED",
        "UNRECORDED",
        "VISION_MODEL",
    ]


def test_only_an_irreproducible_reading_needs_a_person_to_accept_it():
    """**THE ASYMMETRY THAT IS THE WHOLE POINT, AND PROPERTY 4 OF THIS FILE.**

    If ``PHOTO_GEOMETRY`` ever answers True here, a researcher in a courtyard with no signal has to
    get a server's permission before a ratio of two pixel distances can fill a field — an offline
    feature made worse to fix an online one. A typed number needs no acceptance either: the person
    typing it IS the act. Only the model's estimate is neither re-derivable nor anybody's own act.
    """
    assert MeasurementMethod.VISION_MODEL.requires_acceptance is True
    assert MeasurementMethod.PHOTO_GEOMETRY.requires_acceptance is False
    assert MeasurementMethod.TYPED.requires_acceptance is False
    # UNRECORDED answers False, which is not permission — see the property's docstring. A True here
    # would reject every legacy row on its next save.
    assert MeasurementMethod.UNRECORDED.requires_acceptance is False


def test_reproducibility_is_three_valued_and_typed_is_the_third():
    """``None`` for TYPED rather than False. False would imply a tape reading is untrustworthy, which is
    wrong and would put a warning beside almost every dimension in the repository; True would claim a
    determinism nobody measured. The question does not apply, and that is a third answer."""
    assert MeasurementMethod.PHOTO_GEOMETRY.reproducible is True
    assert MeasurementMethod.VISION_MODEL.reproducible is False
    assert MeasurementMethod.TYPED.reproducible is None
    assert MeasurementMethod.UNRECORDED.reproducible is None


def test_the_honest_unknown_is_one_word_and_the_member_spells_it_the_same():
    """Two spellings of one discipline is how an export prints "UNRECORDED" in one column and
    "UNKNOWN" in the next and a reader concludes they mean different things. The constant and the enum
    member are pinned to each other so neither can drift alone."""
    assert UNRECORDED == "UNRECORDED"
    assert MeasurementMethod.UNRECORDED.value == UNRECORDED


def test_a_method_marker_may_only_name_a_dimension_column():
    """Closed, so a marker cannot stamp a method onto an unrelated column. ``{"costOfMaking": {...}}``
    would otherwise be written into the provenance blob, where it reads as though this system had an
    opinion about how a cost was arrived at."""
    assert sorted(DIMENSION_FIELDS) == ["breadthInches", "heightInches", "lengthInches"]


def test_the_unit_less_legacy_height_may_never_carry_a_marker():
    """**THE COLUMN THIS REPOSITORY GETS WRONG IF NOBODY WRITES IT DOWN.** ``ToolDocumentation`` has
    BOTH ``height`` (the old unit-less column — rows hold values in it and nothing in the database can
    say what unit those are in) and ``heightInches`` (migration 20260913120100, the one a grid reading
    belongs in). A marker on ``height`` would attach a method to a number whose unit is unknown, which
    is a worse claim than no method at all: it would read as though the measurement were documented.

    ``width``, ``thickness``, ``weight`` and ``radius`` are out for the milder reason — they are
    ordinary typed inputs with no measurement control pointed at them.
    """
    for not_documented in ("height", "width", "thickness", "weight", "radius"):
        assert not_documented not in DIMENSION_FIELDS
        assert method_stamps({not_documented: {"method": "VISION_MODEL"}}) == {}


def test_the_geometry_techniques_are_the_two_the_clients_can_actually_produce():
    """Mirrors the offline photo-measure path's own ``"SCALE" | "RECTIFIED"``, which both clients
    spell the same way. A DIFFERENT AXIS
    from the method vocabulary — both are PHOTO_GEOMETRY to a reader of the record — which is why they
    are a separate field and not two more enum members."""
    assert sorted(GEOMETRY_TECHNIQUES) == ["RECTIFIED", "SCALE"]


# --------------------------------------------------------------------------------------
# Property 2: no invented confidence
# --------------------------------------------------------------------------------------


def test_a_confidence_the_model_gave_travels():
    assert self_reported_confidence({"confidence": 0.82}) == 0.82
    assert self_reported_confidence({"confidence": 0}) == 0.0
    assert self_reported_confidence({"confidence": 1}) == 1.0


def test_a_confidence_the_model_did_not_give_stays_missing():
    """**THE HONEST-UNKNOWN RULE, AND THE ONE MOST LIKELY TO BE "HELPFULLY" DEFAULTED.** No 0.5 because
    it answered at all, no inference from whether the value parsed. A number the model did not give is
    a number this system does not have, and a default would be a fabricated fact about certainty
    sitting beside a dimension somebody is about to cost a production run from."""
    assert self_reported_confidence({"valueInches": 12}) is None
    assert self_reported_confidence({"confidence": None}) is None
    assert self_reported_confidence({}) is None
    assert self_reported_confidence(None) is None
    assert self_reported_confidence("not a mapping") is None


def test_a_confidence_off_the_scale_is_dropped_and_never_clamped():
    """A model answering 1.5 has not told us it is completely certain — it has failed to answer the
    question the prompt asked. Clamping to 1.0 would manufacture the loudest possible claim out of a
    malformed one, and it would be invisible: 1.0 is exactly what a perfect read looks like."""
    for off_scale in (1.5, -0.2, 7, 100):
        assert self_reported_confidence({"confidence": off_scale}) is None


def test_a_boolean_confidence_is_not_a_confidence_of_one():
    """``True`` is an ``int`` in Python, so a model answering ``"confidence": true`` would otherwise be
    recorded as completely certain — the worst possible reading of a non-answer."""
    assert self_reported_confidence({"confidence": True}) is None
    assert self_reported_confidence({"confidence": False}) is None


def test_a_number_the_model_wrote_as_a_string_is_still_a_number():
    """Reading ``"0.8"`` is not inventing it. Refusing it would drop a fact the model did give, on a
    JSON-mode response where a stringified number is a routine provider quirk."""
    assert self_reported_confidence({"confidence": "0.8"}) == 0.8
    assert self_reported_confidence({"confidence": " 0.8 "}) == 0.8
    assert self_reported_confidence({"confidence": "high"}) is None
    assert self_reported_confidence({"confidence": ""}) is None


def test_nan_and_infinity_are_not_confidences():
    """``float("nan")`` survives a naive range check — every comparison against it is False, so
    ``not (0 <= x <= 1)`` is True and it is refused; ``inf`` likewise. Pinned because a later
    "simplification" to ``min(1, max(0, x))`` would let NaN through into a stored record, where it
    serialises to invalid JSON and breaks the whole row's read."""
    assert self_reported_confidence({"confidence": math.nan}) is None
    assert self_reported_confidence({"confidence": math.inf}) is None
    assert self_reported_confidence({"confidence": -math.inf}) is None


def test_the_confidence_is_never_labelled_calibrated():
    """There is no code path that sets this True and there must not be one until somebody measures the
    thing against a tape. It exists so the number beside it cannot be mistaken for ``photoMeasure``'s
    ``uncertainty``, which IS a propagated error bar — the two travel on the same wire."""
    payload = vision_model_provenance(
        {"confidence": 0.9}, provider="gemini", model_id=MODEL_ID
    ).payload()
    assert payload["confidenceIsCalibrated"] is False
    assert payload["selfReportedConfidence"] == 0.9


def test_no_code_path_anywhere_in_this_backend_can_set_the_calibrated_flag_true():
    """**THE HALF THE TEST ABOVE CANNOT SEE.** ``payload()`` hard-codes ``False`` today, and asserting
    one call's answer says nothing about a SECOND writer added later — a route that "enriches" the
    response, a provider adapter that thinks it knows better. The flag's whole job is that a
    self-reported number can never be mistaken for a measured error bar, and that job is only done
    while nobody can flip it.

    Swept across the whole application source rather than one module, because the response dict is
    assembled in ``services/ai.py`` and handed back verbatim by ``api/routes/media.py``; a second
    writer would most naturally appear in one of those.

    **PARSED, NOT GREPPED, AND THE FIRST VERSION OF THIS TEST WAS GREPPED AND WENT RED ON ITS OWN
    DOCUMENTATION.** A line scan for the key name matches the specification block in
    ``measurement_provenance``'s docstring and the client note in ``routes/media``, both of which say
    the flag is false in PROSE — lower case, in a sentence, with no ``False`` token on the line. A
    guard that punishes a repository for explaining itself gets deleted the first time it is in
    somebody's way. The AST walk below sees only the three shapes that can actually SET the key: a
    dict literal, a keyword argument, and an assignment into a subscript.
    """
    checked = 0
    offenders: list[str] = []

    def _verify(where: str, line: int, node: ast.AST) -> None:
        nonlocal checked
        checked += 1
        if not (isinstance(node, ast.Constant) and node.value is False):
            offenders.append(f"{where}:{line}: {ast.dump(node)[:120]}")

    for path in sorted((BACKEND / "app").rglob("*.py")):
        text = path.read_text(encoding="utf-8")
        if "confidenceIsCalibrated" not in text:
            continue
        where = str(path.relative_to(BACKEND))
        for node in ast.walk(ast.parse(text, filename=str(path))):
            if isinstance(node, ast.Dict):
                for key, value in zip(node.keys, node.values):
                    if isinstance(key, ast.Constant) and key.value == "confidenceIsCalibrated":
                        _verify(where, key.lineno, value)
            elif isinstance(node, ast.keyword) and node.arg == "confidenceIsCalibrated":
                _verify(where, node.value.lineno, node.value)
            elif isinstance(node, ast.Assign):
                for target in node.targets:
                    if (
                        isinstance(target, ast.Subscript)
                        and isinstance(target.slice, ast.Constant)
                        and target.slice.value == "confidenceIsCalibrated"
                    ):
                        _verify(where, node.lineno, node.value)

    assert offenders == [], f"confidenceIsCalibrated is set to something other than False: {offenders}"
    # The positive control: the walk found the one place that writes the key. Without this the test
    # would pass just as loudly on a day the key had been renamed and the flag quietly dropped.
    assert checked == 1, f"expected exactly one writer of the calibrated flag, found {checked}"


# --------------------------------------------------------------------------------------
# Property 3: reading a marker back, and the direction of the fallback
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "marker",
    [
        None,
        {},
        "TYPED",
        ["TYPED"],
        {"method": "typed"},
        {"method": "MEASURED"},
        {"method": ""},
        {"method": None},
        {"method": 1},
        {"provider": "gemini"},
    ],
)
def test_a_marker_nobody_can_read_is_unrecorded_and_never_typed(marker):
    """**THE DIRECTION OF THIS FALLBACK IS THE SAFETY PROPERTY OF THE WHOLE MODULE.**

    Resolving a malformed marker to TYPED would let a client turn a model's estimate into an apparent
    human measurement by sending slightly wrong JSON — the original defect, reachable by accident and
    invisible afterwards. Resolving to UNRECORDED loses information the client meant to send, which is
    visible and recoverable and never a false claim.

    ``{"method": "typed"}`` is in the list deliberately. A case-insensitive read would be a kindness
    that turns a token no part of this system writes into an assertion that a person measured
    something.
    """
    assert provenance_of_marker(marker).method is MeasurementMethod.UNRECORDED


def test_a_marker_that_names_a_method_is_taken_at_its_word():
    """Including TYPED. It is a claim on a request body, exactly as trustworthy as the number it
    describes — the module docstring says so rather than leaving somebody to discover it. The fallback
    above protects against malformed input, not against a client that lies."""
    assert provenance_of_marker({"method": "TYPED"}).method is MeasurementMethod.TYPED
    assert (
        provenance_of_marker({"method": "PHOTO_GEOMETRY"}).method
        is MeasurementMethod.PHOTO_GEOMETRY
    )
    assert provenance_of_marker({"method": "VISION_MODEL"}).method is MeasurementMethod.VISION_MODEL


def test_a_vision_marker_round_trips_through_the_wire_unchanged():
    """What the endpoint hands out is what the record stores. If these two ever disagree the stored
    provenance describes a different reading from the one the researcher accepted."""
    given = vision_model_provenance(
        {"valueInches": 12, "confidence": 0.8}, provider="gemini", model_id=MODEL_ID
    )
    returned = provenance_of_marker(given.marker())
    assert returned == given
    assert returned.stamp() == {
        "method": "VISION_MODEL",
        "methodProvider": "gemini",
        "methodModelId": MODEL_ID,
        "methodConfidence": 0.8,
    }


def test_a_client_cannot_store_a_confidence_the_scale_does_not_have():
    """The marker goes through the same validator the provider's own answer does, so a client cannot
    send back a certainty of 7 — or ``true``, or ``"high"`` — and have it stored."""
    for lie in (7, True, "high", None, -1):
        assert (
            provenance_of_marker(
                {"method": "VISION_MODEL", MARKER_CONFIDENCE_KEY: lie}
            ).self_reported_confidence
            is None
        )
    assert (
        provenance_of_marker(
            {"method": "VISION_MODEL", MARKER_CONFIDENCE_KEY: 0.55}
        ).self_reported_confidence
        == 0.55
    )


def test_the_provider_and_the_marker_spell_the_confidence_differently_on_purpose():
    """**THE BUG THIS PAIR OF CONSTANTS EXISTS TO STOP.** The provider answers under ``confidence``
    (its own word, because that is what the prompt asks for) and the wire carries
    ``selfReportedConfidence`` (the label, so no client prints "confidence: 80%" beside a dimension as
    though it were measured). Reading the marker under the provider's spelling silently drops the
    number on every echo, and the stored stamp loses the only figure on it.

    Each spelling is read under its own key and NOT the other, so the mismatch cannot come back."""
    from app.services.measurement_provenance import PROVIDER_CONFIDENCE_KEY as provider_key

    marker_key = MARKER_CONFIDENCE_KEY

    assert provider_key == "confidence"
    assert marker_key == "selfReportedConfidence"
    assert self_reported_confidence({provider_key: 0.4}) == 0.4
    assert self_reported_confidence({marker_key: 0.4}) is None
    assert self_reported_confidence({marker_key: 0.4}, key=marker_key) == 0.4
    # A marker still carrying the provider's spelling is not read: it is not the shape this endpoint
    # hands out, so trusting it would mean accepting a number from a client that composed it by hand.
    assert (
        provenance_of_marker({"method": "VISION_MODEL", provider_key: 0.9}).self_reported_confidence
        is None
    )


def test_only_a_technique_the_geometry_actually_has_is_recorded():
    """An unrecognised technique is dropped rather than stored, and the method survives: the reading is
    still PHOTO_GEOMETRY, which is the fact that matters to a reader. Storing the raw token would put a
    word in the record that no client wrote and no reader can look up."""
    assert (
        provenance_of_marker({"method": "PHOTO_GEOMETRY", "technique": "SCALE"}).technique == "SCALE"
    )
    assert (
        provenance_of_marker({"method": "PHOTO_GEOMETRY", "technique": "RECTIFIED"}).technique
        == "RECTIFIED"
    )
    dubious = provenance_of_marker({"method": "PHOTO_GEOMETRY", "technique": "EYEBALLED"})
    assert dubious.technique is None
    assert dubious.method is MeasurementMethod.PHOTO_GEOMETRY


def test_a_stamp_always_states_a_method_and_omits_what_it_does_not_know():
    """``method`` is always there — a stamp whose method had to be inferred from which OTHER keys exist
    is the absence this module was written to end. The rest appear only when they are facts: a
    ``methodProvider`` on a hand-typed dimension is an answer to a question that does not apply, and
    writing UNRECORDED into it on every save would fill the blob with noise."""
    assert provenance_of_marker({"method": "TYPED"}).stamp() == {"method": "TYPED"}
    assert provenance_of_marker(None).stamp() == {"method": "UNRECORDED"}
    assert provenance_of_marker({"method": "PHOTO_GEOMETRY", "technique": "SCALE"}).stamp() == {
        "method": "PHOTO_GEOMETRY",
        "methodTechnique": "SCALE",
    }


def test_a_provenance_cannot_be_edited_after_the_fact():
    """Frozen because it is a statement about something that has already happened. A mutable one is a
    provenance a later line can quietly "correct" — and the correction nobody notices is a provider
    name changed to the one that must have produced a reading."""
    with pytest.raises(dataclasses.FrozenInstanceError):
        vision_model_provenance({}, provider="gemini", model_id=MODEL_ID).provider = "openai"


def test_a_caller_with_nothing_to_say_has_to_say_unrecorded_deliberately():
    """No defaults on the two provenance arguments of ``vision_model_provenance``, and a blank becomes
    the word rather than an empty string. A defaulted provider is the one that silently becomes wrong
    the day a second provider joins the chain — and this backend already runs a provider chain for
    speech-to-text, so that day is not hypothetical."""
    blank = vision_model_provenance({}, provider="  ", model_id="")
    assert blank.provider == UNRECORDED
    assert blank.model_id == UNRECORDED
    # ...and neither is offered as a keyword default.
    with pytest.raises(TypeError):
        vision_model_provenance({})


# --------------------------------------------------------------------------------------
# The record half: stamps for the columns a save touches
# --------------------------------------------------------------------------------------


def test_every_dimension_a_save_touches_states_a_method_even_when_nobody_declared_one():
    """**AN ABSENT STAMP AND A STAMP READING UNRECORDED MUST NOT BE THE SAME THING.** A reader must be
    able to see a state machine rather than infer one from an absence. Without this, a save from a
    client that has not implemented its half is indistinguishable from a row written before any of
    this existed, and telling those apart is the entire point."""
    stamps = method_stamps(
        {"lengthInches": {"method": "VISION_MODEL", "provider": "gemini", "modelId": MODEL_ID}},
        fields=["lengthInches", "breadthInches", "remarks"],
    )
    assert stamps == {
        "breadthInches": {"method": "UNRECORDED"},
        "lengthInches": {
            "method": "VISION_MODEL",
            "methodProvider": "gemini",
            "methodModelId": MODEL_ID,
        },
    }


def test_a_marker_naming_something_that_is_not_a_dimension_is_dropped_in_silence():
    """This runs inside somebody's record save. Failing the whole edit over a stray key in a provenance
    hint would trade a real loss — the researcher's typing — for a cosmetic one. The BOUNDARY refuses
    the same key by name; see the schema section below for why both layers exist."""
    assert method_stamps({"costOfMaking": {"method": "TYPED"}, "id": {"method": "TYPED"}}) == {}


def test_a_save_that_touches_no_dimension_gets_no_stamps():
    """A remarks edit must not grow a provenance blob about measurements it did not touch."""
    assert method_stamps(None, fields=["remarks", "localName"]) == {}
    assert method_stamps({"lengthInches": {"method": "TYPED"}}, fields=["remarks"]) == {}


def test_the_marker_body_key_is_the_one_both_halves_name():
    """The client sends it, ``merge_field_provenance`` pops it. A string typed twice is a marker
    silently ignored on every save, which looks exactly like a client that never implemented it."""
    assert MARKER_BODY_KEY == "measurementMethods"


# --------------------------------------------------------------------------------------
# Property 1: the endpoint states what produced the number
#
# These are the assertions that fail against the code before this change: the result dict carried
# `analysis`, `keysTried` and `raw`, and nothing about where the number came from — while the model id
# was in hand two lines above the return.
# --------------------------------------------------------------------------------------


class _Response:
    def __init__(self, payload: dict) -> None:
        self.status_code = 200
        self.headers: dict[str, str] = {}
        self.text = ""
        self._payload = payload

    def json(self) -> dict:
        return self._payload

    def raise_for_status(self) -> None:
        return None


@pytest.fixture
def gemini(monkeypatch: pytest.MonkeyPatch):
    """The provider replaced by a canned answer, and the key pool made non-empty."""

    def install(model_json: str):
        payload = {"candidates": [{"content": {"parts": [{"text": model_json}]}}]}
        monkeypatch.setattr(ai.requests, "post", lambda *a, **k: _Response(payload))
        monkeypatch.setattr(ai.managed_secrets, "peek_secret", lambda name: "key")
        monkeypatch.setattr(ai.managed_secrets, "gemini_key_pool", lambda: ["key"])

        async def _primed() -> None:
            return None

        monkeypatch.setattr(ai.managed_secrets, "refresh_if_stale", _primed)

    return install


def test_a_grid_reading_says_what_produced_it(gemini):
    """**THE DEFECT, PINNED.** Before this, the response was ``{available, status, analysis, keysTried,
    raw}`` — a number with no origin — and both clients put it straight into a form field, where
    ``merge_field_provenance`` stamped it with the name of whoever pressed Save."""
    gemini('{"valueInches": 12.5, "confidence": 0.8, "notes": "clear grid"}')

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings(), "height")

    assert result["method"] == "VISION_MODEL"
    assert result["provider"] == "gemini"
    assert result["modelId"] == MODEL_ID
    assert result["selfReportedConfidence"] == 0.8
    assert result["confidenceIsCalibrated"] is False
    assert result["requiresAcceptance"] is True
    assert result["methodMarker"] == {
        "method": "VISION_MODEL",
        "provider": "gemini",
        "modelId": MODEL_ID,
        "selfReportedConfidence": 0.8,
    }


def test_the_value_is_still_exactly_where_every_shipped_client_reads_it(gemini):
    """**THE COMPATIBILITY HALF, AND IT IS NOT OPTIONAL.** The provenance is ADDITIVE: ``analysis`` is
    the provider's own parsed JSON, untouched. A handset in a village cannot be updated, it reads
    ``response.analysis?.valueInches``, and ``ApiClient.kt`` decodes with ``ignoreUnknownKeys = true``
    — so new keys are ignored by old builds and the old key still answers. Moving the value would have
    broken every installed client at once to fix a provenance problem."""
    gemini('{"valueInches": 12.5, "confidence": 0.8}')

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings(), "height")

    assert result["analysis"]["valueInches"] == 12.5
    assert result["available"] is True
    assert result["status"] == "COMPLETED"


def test_a_model_that_reported_no_confidence_reports_none(gemini):
    """Property 2 at the endpoint, not just in the parser: the key is present and null, so a client can
    tell "the model said nothing" from "the model said 0" without inventing either."""
    gemini('{"valueInches": 4}')

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings())

    assert result["selfReportedConfidence"] is None
    assert "selfReportedConfidence" not in result["methodMarker"]
    assert result["methodMarker"]["method"] == "VISION_MODEL"


def test_prose_instead_of_json_still_carries_its_provenance(gemini):
    """The only honest discriminator this endpoint has is negative: a model that answers in prose
    produces ``{"rawText": ...}``, no value arrives, and the clients say "enter it manually". The
    provenance must survive that, or the one case where a client most wants to explain itself is the
    case with nothing to explain it with."""
    gemini("I cannot see a grid in this photograph.")

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings())

    assert "valueInches" not in result["analysis"]
    assert result["method"] == "VISION_MODEL"
    assert result["modelId"] == MODEL_ID


def test_an_unconfigured_server_still_names_the_model_it_would_have_used(monkeypatch):
    """An operator reading a log beside a failed grid read needs to know which model id was configured
    at the time; a researcher needs the sentence. Both are on the same answer.

    ``requiresAcceptance`` is asserted on this failure path deliberately: it is a statement about what
    KIND of thing this endpoint produces, and a key that appeared only on success is one every client
    would forget to check.
    """
    monkeypatch.setattr(ai.managed_secrets, "gemini_key_pool", list)

    async def _primed() -> None:
        return None

    monkeypatch.setattr(ai.managed_secrets, "refresh_if_stale", _primed)

    result = asyncio.run(
        ai.analyze_measurement_image_bytes(b"image", "grid.jpg", "image/jpeg", _settings())
    )

    assert result["available"] is False
    assert result["modelId"] == MODEL_ID
    assert result["method"] == "VISION_MODEL"
    assert result["selfReportedConfidence"] is None
    assert result["requiresAcceptance"] is True
    assert_sentence(result["message"])


def test_a_provider_that_answered_and_failed_still_says_which_model_answered(monkeypatch):
    """The other failure path. A researcher is told to enter it manually; the response still carries
    the provider and the model, because an operator diagnosing a run of failures cannot do it from a
    message that names neither."""
    monkeypatch.setattr(ai.managed_secrets, "gemini_key_pool", lambda: ["key"])

    async def _primed() -> None:
        return None

    monkeypatch.setattr(ai.managed_secrets, "refresh_if_stale", _primed)

    def _boom(*a, **k):
        raise ai.requests.RequestException("provider exploded")

    monkeypatch.setattr(ai.requests, "post", _boom)

    result = asyncio.run(
        ai.analyze_measurement_image_bytes(b"image", "grid.jpg", "image/jpeg", _settings())
    )

    assert result["status"] == "FAILED"
    assert result["method"] == "VISION_MODEL"
    assert result["provider"] == "gemini"
    assert result["modelId"] == MODEL_ID
    assert result["requiresAcceptance"] is True
    assert_sentence(result["message"])


def test_the_response_is_json_serialisable_exactly_as_the_clients_will_receive_it(gemini):
    """A ``Decimal``, a ``float('nan')`` or an enum member in this payload is a 500 on a working read.
    The confidence goes through the parser that rejects NaN, and the method through ``.value``, so this
    is the assertion that keeps both of those true at the boundary."""
    gemini('{"valueInches": 12.5, "confidence": 0.8}')

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings(), "length")

    rendered = json.loads(json.dumps(result))
    assert rendered["method"] == "VISION_MODEL"
    assert isinstance(rendered["methodMarker"]["selfReportedConfidence"], float)


# --------------------------------------------------------------------------------------
# The route: the provenance reaches the client, and the reader still writes nothing
#
# The real router over HTTP with `db` replaced by a tripwire. ``tests/test_media_upload_bounds_routes``
# already owns the size ceiling and the remedy sentence for this route; what is asserted here is the
# half this change is responsible for.
# --------------------------------------------------------------------------------------


class _DatabaseTouched(Exception):
    """The route's guards all passed and its body started working."""


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


_CALLER: dict[str, object] = {"user": None}


def _person(role: str):
    return SimpleNamespace(id="usr_1", email="x@example.test", name="Test", role=role)


@pytest.fixture
def api(monkeypatch):
    """The media router, mounted with every module's ``db`` rebound to the tripwire.

    The modules do ``from app.core.db import db``, so each holds its OWN reference and patching the
    source alone would miss all of them. Rebinding by identity finds every one already imported.
    """
    import sys

    import httpx
    from fastapi import FastAPI

    import app.core.db as core_db
    from app.api.routes import media as routes
    from app.core import deps

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", tripwire)

    seen = SimpleNamespace(provider_calls=[])
    state = SimpleNamespace(
        result={
            "available": True,
            "status": "COMPLETED",
            "analysis": {"valueInches": 12.5, "confidence": 0.8},
            "method": "VISION_MODEL",
            "provider": "gemini",
            "modelId": MODEL_ID,
            "selfReportedConfidence": 0.8,
            "confidenceIsCalibrated": False,
            "requiresAcceptance": True,
            "methodMarker": {"method": "VISION_MODEL", "provider": "gemini", "modelId": MODEL_ID},
        }
    )

    async def _analyze(content, filename, mime, settings, dimension=None):
        seen.provider_calls.append((len(content), filename, mime, dimension))
        return dict(state.result)

    monkeypatch.setattr(routes, "analyze_measurement_image_bytes", _analyze)

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]

    def call(role: str, method: str, path: str, body=None, files=None):
        _CALLER["user"] = _person(role)

        async def run():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport, base_url="http://measurement.test"
            ) as client:
                response = await client.request(
                    method, f"/api{path}", json=body if files is None else None, files=files
                )
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return SimpleNamespace(
                reached=False,
                status_code=response.status_code,
                detail=str(detail),
                body=payload,
            )

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return SimpleNamespace(reached=True, status_code=None, detail="", body={})

    yield SimpleNamespace(call=call, seen=seen, state=state, routes=routes)
    _CALLER["user"] = None


def _grid(size: int = 64, mime: str = "image/jpeg"):
    """A real multipart body: FastAPI validates the form parts BEFORE the handler runs, so a JSON body
    would 422 on the missing ``file`` and the test would pass for the wrong reason."""
    return {"file": ("grid.jpg", b"\xff" * size, mime)}


def test_a_researcher_reaches_the_reader_and_gets_the_provenance_with_the_number(api):
    """The route hands the provider's answer back VERBATIM, which is the only reason the keys added in
    ``services/ai`` reach a client at all. A route that rebuilt the response from a field list — the
    obvious-looking tidy-up — would drop every one of them and nothing else would notice."""
    outcome = api.call("RESEARCHER", "POST", "/media/analyze-measurement", files=_grid())
    assert outcome.status_code == 200
    assert outcome.body["analysis"]["valueInches"] == 12.5
    assert outcome.body["method"] == "VISION_MODEL"
    assert outcome.body["requiresAcceptance"] is True
    assert outcome.body["confidenceIsCalibrated"] is False
    assert outcome.body["methodMarker"]["modelId"] == MODEL_ID
    assert len(api.seen.provider_calls) == 1


def test_the_reader_writes_nothing(api):
    """**THE PROPERTY THE WHOLE ENDPOINT RESTS ON**, and the tripwire is what proves it rather than a
    docstring: a successful read never touched a database delegate, so no product row, no tool row and
    no media row was created or updated. The number is a PROPOSAL until somebody saves it through the
    ordinary record route, which is where the acceptance is recorded."""
    outcome = api.call("RESEARCHER", "POST", "/media/analyze-measurement", files=_grid())
    assert outcome.reached is False, "the reader touched the database"
    assert outcome.status_code == 200


# --------------------------------------------------------------------------------------
# THE RECORD HALF: the method is stored BESIDE the signature, never instead of it
#
# Pure dict work. ``merge_field_provenance`` takes the payload dict a route has already cleaned and
# mutates it in place, so the whole of the record half is assertable with no Postgres — which is why
# this section lives here rather than in a DB-backed suite that cannot run on a field laptop.
# --------------------------------------------------------------------------------------


class _Row:
    """A record stub that answers ``None`` for every column nobody set."""

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):  # only reached for names __init__ did not set
        return None


def _saver():
    return SimpleNamespace(id="usr_7", name="R. Menon")


#: The minimum a CREATE body needs before any other validator on it is reached.
#:
#: ``ProductCreate`` / ``ToolCreate`` carry ``require_location`` as a ``model_validator(mode="after")``
#: DECLARED BEFORE ``_measurement_methods``, and pydantic stops at the first after-validator that
#: raises. So a create body with no location never reaches the marker validator at all: the refusal
#: that comes back is about the location, the marker is never looked at, and a test asserting only
#: "it was refused" passes while measuring nothing.
_LOCATION = {"latitude": 23.24, "longitude": 69.66, "state": "Gujarat", "district": "Kachchh"}


def _machine_marker(**overrides):
    marker = {
        "method": "VISION_MODEL",
        "provider": "gemini",
        "modelId": MODEL_ID,
        MARKER_CONFIDENCE_KEY: 0.8,
    }
    marker.update(overrides)
    return marker


def _machine_stamp():
    """A stored vision-model stamp, as it sits on a row saved before the one under test."""
    return {
        "by": "usr_7",
        "byName": "R. Menon",
        "at": "2026-09-01T00:00:00+00:00",
        "method": "VISION_MODEL",
        "methodProvider": "gemini",
        "methodModelId": MODEL_ID,
        "methodConfidence": 0.8,
    }


def _stored_provenance(new_data):
    """The ``fieldProvenance`` blob ``merge_field_provenance`` just wrote.

    Unwrapped from the Prisma ``Json`` wrapper deliberately rather than indexed through it: the column
    is a Json column and ``merge_field_provenance`` assigns ``Json(base_extra)``, so a test that
    indexed the wrapper would be asserting on prisma's ``__getitem__`` instead of on what is stored.
    """
    wrapper = new_data["extraMetadata"]
    stored = getattr(wrapper, "data", wrapper)
    return stored["fieldProvenance"]


def test_a_model_reading_is_stored_as_a_model_reading_beside_the_person_who_accepted_it():
    """**THE DEFECT, PINNED AS FIXED.** Before this the row said "R. Menon measured 8.5 inches". It now
    says "a vision model estimated 8.5 inches, and R. Menon accepted it into the record at that
    moment" — a true sentence, and the one an auditor needs.

    Both halves are asserted. Dropping ``{by, byName, at}`` is the other way of getting this wrong: it
    would delete the only person who can be asked about the number.
    """
    new_data = {
        "lengthInches": "8.5",
        MARKER_BODY_KEY: {"lengthInches": _machine_marker()},
    }

    merge_field_provenance(new_data, _saver())

    stamp = _stored_provenance(new_data)["lengthInches"]
    assert stamp["by"] == "usr_7"
    assert stamp["byName"] == "R. Menon"
    assert stamp["at"], "the moment of acceptance is the whole value of the human half"
    assert stamp["method"] == "VISION_MODEL"
    assert stamp["methodProvider"] == "gemini"
    assert stamp["methodModelId"] == MODEL_ID
    assert stamp["methodConfidence"] == 0.8


def test_the_marker_never_reaches_the_database_as_a_column():
    """``measurementMethods`` is not a column on either documentation table, and ``clean_data`` only
    drops ``None`` — so a marker that survived this call would be handed to
    ``db.productdocumentation.create(data=...)`` as an unknown column: a 500 on the save rather than a
    validation error a researcher could act on. The pop is the only thing removing it.

    Both assertions below move with the pop and nothing else: replace ``new_data.pop(...)`` with
    ``new_data.get(...)`` and the marker is handed to Prisma as a column AND attributed as a field.
    The skip-set entry is NOT what saves either of them — see the test under this one.
    """
    new_data = {"lengthInches": "8.5", MARKER_BODY_KEY: {"lengthInches": _machine_marker()}}

    merge_field_provenance(new_data, _saver())

    assert MARKER_BODY_KEY not in new_data
    assert MARKER_BODY_KEY not in _stored_provenance(new_data)


def test_the_skip_set_entry_for_the_marker_is_redundancy_and_no_behaviour_can_observe_it():
    """**THIS ASSERTS A CONSTANT, DELIBERATELY, AND IT IS NOT EVIDENCE THAT ANYTHING WORKS.** It is
    split out and labelled so nobody counts it among this file's behaviour controls — the failure mode
    this whole module exists to prevent, applied to its own suite.

    ``merge_field_provenance`` pops ``MARKER_BODY_KEY`` off ``new_data`` BEFORE the loop that reads
    ``PROVENANCE_SKIP_FIELDS``, so by the time the set is consulted the key is already gone. Lifting
    the key out of the set changes NOTHING a test can see.

    IT IS KEPT ANYWAY, for the reason the set's own comment gives: a future caller that merges
    provenance without popping first would attribute the marker as though it were a field somebody
    filled in — putting a researcher's name against a dictionary they never saw. The entry is the
    defence for a caller that does not exist yet, which is exactly the kind of guard that gets deleted
    as dead weight by somebody who checked only whether a test went red.
    """
    assert MARKER_BODY_KEY in PROVENANCE_SKIP_FIELDS


def test_every_changed_field_gets_its_own_stamp_and_not_one_shared_dict():
    """This line used to assign the SAME ``stamp`` object to every changed field, so any later per-field
    edit of the blob silently edited all of them. Merging the method in gives each field its own dict,
    which is worth holding deliberately rather than as a side effect."""
    new_data = {"lengthInches": "8.5", "remarks": "A water pot."}

    merge_field_provenance(new_data, _saver())

    provenance = _stored_provenance(new_data)
    assert provenance["lengthInches"] is not provenance["remarks"]
    # And a method is stamped only on the columns that hold a documented dimension.
    assert "method" not in provenance["remarks"]


def test_a_dimension_nobody_declared_a_method_for_says_so_in_that_word():
    """An old web build or an installed handset writes an explicit UNRECORDED rather than nothing.

    A missing stamp and a stamp reading UNRECORDED would otherwise be indistinguishable from a row
    written before any of this existed, and being able to tell them apart is the point. Expect a burst
    of these from the fleet after this ships; that is the design degrading correctly, not a bug.
    """
    new_data = {"lengthInches": "8.5"}

    merge_field_provenance(new_data, _saver())

    assert _stored_provenance(new_data)["lengthInches"]["method"] == UNRECORDED


def test_an_untouched_dimension_keeps_the_method_it_was_saved_with():
    """**THE REGRESSION TEST FOR THE WORST WAY THIS FEATURE CAN GO WRONG.**

    Both record forms re-send every dimension on every save. A client that blanket-sent
    ``{"method": "TYPED"}`` for every box would therefore launder every accepted vision-model
    measurement in the database into an apparent human one the next time somebody fixed a typo in
    ``remarks`` — reintroducing the exact defect this work exists to remove, silently, through the fix
    for it. This passes only because the merge sits inside the changed-fields loop.
    """
    previous = _Row(
        lengthInches="8.50",
        remarks="Old.",
        extraMetadata={"fieldProvenance": {"lengthInches": _machine_stamp()}},
    )
    new_data = {
        "lengthInches": "8.5",
        "remarks": "A water pot.",
        MARKER_BODY_KEY: {"lengthInches": {"method": "TYPED"}},
    }

    merge_field_provenance(new_data, _saver(), previous=previous)

    stamp = _stored_provenance(new_data)["lengthInches"]
    assert stamp["method"] == "VISION_MODEL"
    assert stamp["methodModelId"] == MODEL_ID
    # The field that DID change is re-attributed, so this is not passing by doing nothing at all.
    assert _stored_provenance(new_data)["remarks"]["byName"] == "R. Menon"


def test_a_trailing_zero_is_not_a_change_and_therefore_not_a_downgrade():
    """The guard above rests entirely on ``values_match``, and the value makes a round trip that could
    defeat it: ``lengthInches`` is a Prisma ``Decimal`` that reaches a client as the string "8.50",
    goes into a text input and comes back as 8.5. ``deps.values_match`` falls back to
    ``Decimal(str(a)) == Decimal(str(b))``, so those compare equal — asserted here with that literal
    pair, because if it ever stopped being true EVERY dimension would count as changed on every save
    and the downgrade above would fire despite the guard."""
    previous = _Row(
        lengthInches="8.50", extraMetadata={"fieldProvenance": {"lengthInches": _machine_stamp()}}
    )
    new_data = {"lengthInches": 8.5, MARKER_BODY_KEY: {"lengthInches": {"method": "TYPED"}}}

    merge_field_provenance(new_data, _saver(), previous=previous)

    assert _stored_provenance(new_data)["lengthInches"]["method"] == "VISION_MODEL"


def test_overtyping_a_machine_number_makes_it_the_persons_own():
    """A researcher who types over an accepted machine number has made it their own number. The stamp
    is REPLACED WHOLESALE, so no provider or model id is left behind claiming gemini produced a figure
    somebody read off a tape.

    THIS IS THE SERVER HALF OF THE ACCEPTANCE RULE. The client half — re-check the box against the
    accepted string character for character, and forget the acceptance from the box's own onChange —
    is what stops a TYPED marker from being sent in the first place. This is what the record does once
    it arrives.
    """
    previous = _Row(
        lengthInches="8.50", extraMetadata={"fieldProvenance": {"lengthInches": _machine_stamp()}}
    )
    new_data = {"lengthInches": "9", MARKER_BODY_KEY: {"lengthInches": {"method": "TYPED"}}}

    merge_field_provenance(new_data, _saver(), previous=previous)

    stamp = _stored_provenance(new_data)["lengthInches"]
    assert stamp["method"] == "TYPED"
    for residue in ("methodProvider", "methodModelId", "methodConfidence", "methodTechnique"):
        assert residue not in stamp, f"{residue} outlived the reading it described"


@pytest.mark.parametrize(
    "marker",
    [
        {"method": "typed"},  # lower case is not a token any part of this system writes
        "TYPED",  # a string where a mapping belongs
        {"method": ["TYPED"]},
        None,
    ],
)
def test_a_malformed_marker_is_unrecorded_and_never_typed_in_the_record_too(marker):
    """``provenance_of_marker`` guarantees the direction of this fallback and the pure tests above
    already pin it. This asserts the guarantee survives the record-half integration, which is where the
    direction actually costs something: resolving to TYPED here would let slightly-wrong JSON turn a
    model's estimate into an apparent human measurement inside a stored government record."""
    new_data = {"lengthInches": "8.5", MARKER_BODY_KEY: {"lengthInches": marker}}

    merge_field_provenance(new_data, _saver())

    assert _stored_provenance(new_data)["lengthInches"]["method"] == UNRECORDED


def test_a_marker_naming_a_column_the_save_is_not_writing_stamps_nothing():
    """``fields=new_data.keys()`` narrows the stamps to the dimensions this save actually carries. A
    marker for a column the body does not mention describes nothing that is happening here, and writing
    it would attribute a method to a value this save never saw.

    THE FIRST ASSERTION IS WHERE THE NARROWING IS OBSERVABLE AND IT IS PURE, WHICH IS THE POINT OF
    PUTTING IT HERE. Drop the ``fields=`` argument and ``method_stamps`` answers
    ``{"heightInches": {"method": "VISION_MODEL", ...}}`` — a machine method for a column this save is
    not writing — which is a different value, immediately, at the seam that produces it. The
    record-half lines under it state the end-to-end guarantee but cannot fail on their own:
    ``merge_field_provenance`` builds ``provenance`` from ``new_data.items()``, so nothing could ever
    have put a key there that the save does not carry.
    """
    assert method_stamps({"heightInches": _machine_marker()}, fields={"lengthInches"}) == {
        "lengthInches": {"method": UNRECORDED}
    }

    new_data = {"lengthInches": "8.5", MARKER_BODY_KEY: {"heightInches": _machine_marker()}}

    merge_field_provenance(new_data, _saver())

    provenance = _stored_provenance(new_data)
    assert "heightInches" not in provenance
    assert provenance["lengthInches"]["method"] == UNRECORDED


def test_the_marker_is_not_logged_as_an_edit_somebody_made(monkeypatch):
    """**THE HANDOFF THAT HAD TO LAND FIRST, ASSERTED AS BEHAVIOUR AND NOT AS SET MEMBERSHIP.**

    ``guard_record_edit`` runs ``record_revision`` on the raw ``data`` BEFORE
    ``merge_field_provenance`` pops the marker, and ``record_revision`` diffs every key that is not in
    ``REVISION_SKIP_FIELDS`` against the record. ``getattr(product, "measurementMethods", None)`` is
    None and ``values_match(None, {...})`` is False — so without the skip entry EVERY marker-bearing
    PATCH records a change to a key that is not a value, breaking ``record_revision``'s own contract
    ("No-op when nothing meaningful changed") and filling the admin audit trail with an edit nobody
    made. In an APPEND-ONLY table: landing the entry afterwards cannot un-write those rows, which is
    why it had to come before the schema declaration that makes the key sendable at all.

    THE LEDGER IS A STUB AND THE ASSERTIONS ARE ABOUT WHAT REACHES IT. The first case is the one a
    membership check cannot make: a payload carrying BOTH a real edit and a marker still writes its
    revision — so this cannot pass by the whole call being skipped for an unrelated reason — and the
    marker is absent from the ``changes`` it writes. The second is the pure marker-bearing save, where
    the correct number of rows is zero.
    """
    from app.services import access

    written: list[dict] = []

    async def _create(data):
        written.append(data)
        return SimpleNamespace(id="rev_1")

    monkeypatch.setattr(
        access, "db", SimpleNamespace(recordrevision=SimpleNamespace(create=_create))
    )

    product = SimpleNamespace(id="prd_1", createdById="usr_7", remarks="Old", lengthInches="8.5")
    marker_body = {MARKER_BODY_KEY: {"lengthInches": _machine_marker()}}

    asyncio.run(
        access.record_revision(product, _saver(), {"remarks": "New", **marker_body}, "product")
    )

    assert len(written) == 1, "a real field edit beside a marker must still be audited"
    changes = getattr(written[0]["changes"], "data", written[0]["changes"])
    assert set(changes) == {"remarks"}, f"the marker was logged as an edit: {sorted(changes)}"

    written.clear()
    asyncio.run(access.record_revision(product, _saver(), dict(marker_body), "product"))

    assert written == [], "a marker on its own is not an edit and must write no revision row"
    assert MARKER_BODY_KEY in access.REVISION_SKIP_FIELDS


def test_the_schemas_accept_the_marker_the_endpoint_hands_out():
    """**THE HARD BLOCKER ON THE CLIENT HALF, AND IT FAILS LOUDLY IN THE FIELD.** ``APIModel`` is
    ``ConfigDict(extra="forbid")``, so an undeclared key is not ignored — it 422s the whole save,
    naming a key the researcher has never heard of. Worse, the web's ``saveOrQueue`` refuses to queue a
    4xx ("the server saw it and said no") and Android's outbox parks it, so with no signal the work is
    thrown away rather than retried.

    All four models, not just the Update pair: a client that implemented its half against
    ``ProductUpdate`` alone would find every product CREATE rejected.

    THE BODIES CARRY A ``lengthInches`` DELIBERATELY. A marker is a statement about a number, so the
    number has to be in the same request — see the refusal for that rule below.
    """
    from app.schemas.records import ProductCreate, ProductUpdate, ToolCreate, ToolUpdate

    body = {"lengthInches": "8.5", MARKER_BODY_KEY: {"lengthInches": _machine_marker()}}
    ProductCreate(
        craftName="Pottery",
        place="Bhuj",
        artisanName="A",
        productName="Pot",
        location=_LOCATION,
        **body,
    )
    ProductUpdate(**body)
    ToolCreate(
        craftName="Pottery",
        place="Bhuj",
        artisanName="A",
        toolkitName="Wheel",
        location=_LOCATION,
        **body,
    )
    ToolUpdate(**body)


def test_a_save_that_does_not_use_the_feature_sends_exactly_what_it_sent_before():
    """**THE OPTIONALITY, ASSERTED ON THE DUMP RATHER THAN PROMISED IN A COMMENT.** Every save in the
    installed fleet omits this key, and a new optional that changed the shape of those bodies would
    make this a migration for every client instead of an addition.

    ``model_dump(exclude_unset=True)`` of a body that never mentions the marker must not contain it —
    that dump is literally what ``routes/products`` and ``routes/tools`` hand to Prisma — and the
    record written from it must be what today's record is: a ``{by, byName, at}`` stamp on the fields
    that changed, plus the explicit UNRECORDED that says nobody declared a method, which is the one
    thing that IS new and is new on purpose.
    """
    from app.schemas.records import ProductUpdate, ToolUpdate

    for model_cls in (ProductUpdate, ToolUpdate):
        dumped = model_cls(remarks="A water pot.").model_dump(exclude_unset=True)
        assert dumped == {"remarks": "A water pot."}, dumped

    new_data = {"remarks": "A water pot."}
    merge_field_provenance(new_data, _saver())
    stamp = _stored_provenance(new_data)["remarks"]
    assert set(stamp) == {"by", "byName", "at"}, (
        f"a save that touches no dimension grew a key it did not have before: {stamp!r}"
    )


def test_an_explicit_null_is_read_as_absence_by_this_server_even_though_clients_must_not_send_one():
    """TWO DIFFERENT AUDIENCES, AND THEY WANT OPPOSITE THINGS, so both halves are pinned here.

    THIS server must treat ``measurementMethods: null`` as "nothing to say" — degrading a save over a
    client's null would cost a researcher their form for a key that carries no information either way.

    A CLIENT must still omit the key rather than send null, and that rule is not about this server at
    all: the web deploys to Vercel and this API to EC2 separately, so a newer web build meets an older
    API routinely, and against an API that predates this field ``extra="forbid"`` turns the null into a
    422 on the WHOLE save that neither queue will retry. The clients' own helpers are where that rule
    is enforced; this test records that the server side of it is the forgiving half.
    """
    from app.schemas.records import ProductUpdate

    model = ProductUpdate(lengthInches="8.5", measurementMethods=None)
    assert model.measurementMethods is None

    new_data = model.model_dump(exclude_unset=True)
    assert new_data[MARKER_BODY_KEY] is None
    merge_field_provenance(new_data, _saver())
    assert MARKER_BODY_KEY not in new_data
    assert _stored_provenance(new_data)["lengthInches"]["method"] == UNRECORDED


# --------------------------------------------------------------------------------------
# THE BOUNDARY REFUSES — one test per refusal, because a silent drop here is a silent lie
#
# ``marker_body_problems`` (the API boundary) and ``provenance_of_marker`` (inside the save) answer
# the SAME malformed marker differently, on purpose. The degrade is asserted above and it stays: a
# save must not fail over a provenance hint. This section asserts the half that keeps the degrade from
# being the ONLY line of defence — because at the boundary the alternative to a refusal is not a safe
# default but a silent lie of omission. A researcher presses Accept on a vision-model reading, the
# client sends a typo in the method name, the degrade writes UNRECORDED, and the stored row is now
# indistinguishable from one saved by a client that never implemented any of this.
#
# EVERY TEST HERE GOES THROUGH A REAL SCHEMA rather than calling ``marker_body_problems`` directly.
# The refusal is worth nothing unless it is WIRED, and the wiring is one
# ``model_validator(mode="after")`` line per model that is easy to drop when a schema is edited — an
# unattached validator is a function with passing tests and no effect.
# --------------------------------------------------------------------------------------


def _refusal(model_cls, **body) -> str:
    """The sentence ``model_cls`` refuses ``body`` with. Fails the test if it accepts it.

    Reads ``ValidationError`` rather than an HTTP status: these validators ARE the whole of what makes
    the 422, and going through the router would need the database this section deliberately does not.
    The ``Value error, `` prefix pydantic adds is stripped so the assertions below read against the
    sentence as written in ``marker_body_problems``.
    """
    import pydantic

    try:
        model_cls(**body)
    except pydantic.ValidationError as error:
        return "; ".join(e["msg"].removeprefix("Value error, ") for e in error.errors())
    raise AssertionError(f"{model_cls.__name__} accepted a marker it must refuse: {body!r}")


def test_a_method_naming_a_column_that_is_not_a_documented_dimension_is_refused_by_name():
    """``DIMENSION_FIELDS`` is enumerated so a marker cannot stamp a method onto an unrelated column.

    ``height`` IS THE CASE THAT MATTERS AND IT IS NOT HYPOTHETICAL IN THIS REPOSITORY. Until migration
    20260913120100 a tool had no ``heightInches``, so a grid-measured tool height had nowhere
    documented to go and the unit-less ``height`` box was the only column that would take it — which
    is how every grid-measured tool height already in this database came to be stored with no
    recoverable unit. A client written against that world aims its marker at ``height``.
    ``method_stamps`` would drop it in silence; here it is told which three columns a method may
    describe, which is the whole answer it needs to move the value one box over.
    """
    from app.schemas.records import ProductUpdate, ToolUpdate

    sentence = _refusal(
        ToolUpdate,
        height="6",
        lengthInches="12",
        measurementMethods={"height": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}},
    )
    assert_sentence(sentence)
    assert "height" in sentence, "a refusal that does not name the offending key cannot be acted on"
    for allowed in sorted(DIMENSION_FIELDS):
        assert allowed in sentence, f"the refusal must say {allowed} is available instead"

    sentence = _refusal(
        ProductUpdate,
        lengthInches="8.5",
        measurementMethods={"costOfMaking": {"method": "TYPED"}},
    )
    assert_sentence(sentence)
    assert "costOfMaking" in sentence


def test_a_method_this_server_does_not_know_is_refused_and_a_lower_case_typed_is_not_typed():
    """Case-sensitive, for the reason ``provenance_of_marker`` gives: accepting ``"typed"`` would turn
    an unrecognised token into an assertion about how somebody measured something. Refused here it
    costs the client one corrected string; degraded on the save path it costs a row that says nothing
    where a person had said TYPED.

    The refusal lists the vocabulary, so a client author fixes it from the message without opening
    this repository.
    """
    from app.schemas.records import ProductUpdate

    for unknown in ("typed", "PHOTO", "GUESS", ""):
        sentence = _refusal(
            ProductUpdate,
            lengthInches="8.5",
            measurementMethods={"lengthInches": {"method": unknown}},
        )
        assert_sentence(sentence)
        assert f"{MARKER_BODY_KEY}.lengthInches" in sentence
        assert repr(unknown) in sentence, "the refusal must quote back what was actually sent"
        for known in sorted(m.value for m in MeasurementMethod):
            assert known in sentence, f"the vocabulary must be offered: {known}"

    # A method key that is not a string at all lands in the same refusal rather than a TypeError.
    for not_a_string in (7, True, None, ["TYPED"]):
        assert_sentence(
            _refusal(
                ProductUpdate,
                lengthInches="8.5",
                measurementMethods={"lengthInches": {"method": not_a_string}},
            )
        )


def test_a_method_for_a_dimension_this_request_is_not_sending_is_refused():
    """**A METHOD IS A STATEMENT ABOUT A NUMBER, SO THE NUMBER HAS TO BE IN THE SAME REQUEST.**

    ``merge_field_provenance`` narrows its stamps to the columns the save is actually writing, so a
    marker for a dimension this body does not carry describes nothing that is happening here and is
    dropped — AFTER the researcher pressed Accept on it. That drop is correct on the save path and
    wrong at the boundary, which is the whole two-layer argument.

    BOTH WAYS OF NOT SENDING A NUMBER ARE THE SAME REFUSAL, and the second is the one a schema could
    easily miss. ``validate_measurement_methods`` reads non-null rather than ``model_fields_set``, so
    a dimension sent as an explicit ``null`` — a researcher CLEARING the box, which
    ``routes/products._CLEARABLE_COLUMNS`` exists to let through — counts as absent. A method
    describing a cleared measurement describes nothing.

    NOTE WHAT THIS DOES NOT REFUSE: a dimension whose value is UNCHANGED. That key is present, so it
    passes here, and the changed-fields loop inside ``merge_field_provenance`` is what declines to
    re-stamp it. That is the anti-laundering rule and it belongs there, where the stored row is in
    scope; a schema cannot see the stored row and must not pretend to.
    """
    from app.schemas.records import ToolUpdate

    omitted = _refusal(
        ToolUpdate,
        lengthInches="12",
        measurementMethods={"heightInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}},
    )
    assert_sentence(omitted)
    assert "heightInches" in omitted
    assert "same request" in omitted, "the refusal must say what would make it acceptable"

    cleared = _refusal(
        ToolUpdate,
        heightInches=None,
        measurementMethods={"heightInches": {"method": "TYPED"}},
    )
    assert cleared == omitted, (
        "clearing a dimension and never sending it are the same absence, and a client that gets two "
        "different sentences for them will read the difference as meaningful"
    )

    # The positive control: the identical marker is accepted the moment the number rides with it.
    ToolUpdate(
        heightInches="7",
        measurementMethods={"heightInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}},
    )


def test_a_photo_geometry_technique_this_server_does_not_know_is_refused():
    """``GEOMETRY_TECHNIQUES`` mirrors the offline photo-measure path's own ``"SCALE" | "RECTIFIED"``.
    ``provenance_of_marker`` drops an unknown one to ``None``, which loses the only fact that lets
    somebody re-derive the number; the boundary says which two exist instead."""
    from app.schemas.records import ProductCreate

    sentence = _refusal(
        ProductCreate,
        craftName="Pottery",
        place="Bhuj",
        artisanName="A",
        productName="Pot",
        location=_LOCATION,
        lengthInches="8.5",
        measurementMethods={"lengthInches": {"method": "PHOTO_GEOMETRY", "technique": "CALIPER"}},
    )
    assert_sentence(sentence)
    assert "'CALIPER'" in sentence
    for technique in sorted(GEOMETRY_TECHNIQUES):
        assert technique in sentence


def test_a_technique_on_a_reading_that_has_no_geometry_is_refused():
    """Stored, this puts ``methodTechnique: "SCALE"`` beside ``method: "VISION_MODEL"`` in an audit
    trail: a geometry that did not happen, asserted next to the method that did.

    Nothing in this repository composes that — ``vision_model_provenance`` never sets a technique and
    the offline path sets one only with PHOTO_GEOMETRY — so a body carrying it is a client that has
    copied a marker from the wrong branch, and it is worth telling it so rather than storing the
    contradiction.
    """
    from app.schemas.records import ProductUpdate

    for method in ("VISION_MODEL", "TYPED", "UNRECORDED"):
        sentence = _refusal(
            ProductUpdate,
            lengthInches="8.5",
            measurementMethods={"lengthInches": {"method": method, "technique": "SCALE"}},
        )
        assert_sentence(sentence)
        assert method in sentence, "the refusal must name the method it is objecting to"
        assert "PHOTO_GEOMETRY" in sentence, "and the one method a technique belongs on"

    # The positive control: the same technique on the method that has a geometry is accepted.
    ProductUpdate(
        lengthInches="8.5",
        measurementMethods={"lengthInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}},
    )


def test_a_confidence_the_scale_does_not_have_is_refused_but_an_absent_one_is_not():
    """``self_reported_confidence`` DROPS an unreadable confidence on the save path — deliberately,
    because clamping 1.5 to 1.0 would manufacture the loudest possible claim out of a malformed one.
    At the boundary the drop is silent, so it is refused instead.

    **AN EXPLICIT NULL IS AN ABSENCE, NOT A CLAIM, AND MUST NOT BE REFUSED.**
    ``MeasurementProvenance.payload`` sends ``selfReportedConfidence: null`` whenever the model
    reported no confidence, and the client specification says to echo the marker back VERBATIM. A
    server that refused the null would refuse its own answer relayed back to it — breaking exactly the
    clients that followed the instruction, and only for the readings where the model was too honest to
    give a number.
    """
    from app.schemas.records import ProductUpdate

    for unreadable in (7, -0.5, "high", True, float("nan"), float("inf"), [0.8]):
        sentence = _refusal(
            ProductUpdate,
            lengthInches="8.5",
            measurementMethods={
                "lengthInches": {"method": "VISION_MODEL", MARKER_CONFIDENCE_KEY: unreadable}
            },
        )
        assert_sentence(sentence)
        assert MARKER_CONFIDENCE_KEY in sentence
        assert "0 to 1" in sentence, "the refusal must state the scale it wanted"

    ProductUpdate(
        lengthInches="8.5",
        measurementMethods={"lengthInches": {"method": "VISION_MODEL", MARKER_CONFIDENCE_KEY: None}},
    )
    # And a number the model wrote as a string is a number the model gave, here as on the save path.
    ProductUpdate(
        lengthInches="8.5",
        measurementMethods={
            "lengthInches": {"method": "VISION_MODEL", MARKER_CONFIDENCE_KEY: "0.8"}
        },
    )


def test_a_marker_key_a_newer_server_added_is_ignored_rather_than_refused():
    """**OPEN KEYS, CLOSED VALUES — the one accept-and-drop deliberately left in this design.**

    A marker is echoed back verbatim as the endpoint handed it out. If a later server adds a key to
    ``MeasurementProvenance.marker`` and a client echoes it at an OLDER instance mid-deploy, an
    ``extra="forbid"`` sub-model would 422 the whole save for a reason no researcher can act on and no
    client author can have anticipated — and the web and the API deploy separately here, so mid-deploy
    skew is the ordinary case. So an unknown key inside a marker is ignored.

    This is also why ``measurementMethods`` is typed ``dict[str, dict[str, Any]]`` and not a pydantic
    model per marker. Do not "tighten" it into one.
    """
    from app.schemas.records import ProductUpdate

    model = ProductUpdate(
        lengthInches="8.5",
        measurementMethods={
            "lengthInches": {"method": "TYPED", "aKeyFromTheFuture": {"nested": [1, 2]}}
        },
    )
    assert model.measurementMethods["lengthInches"]["aKeyFromTheFuture"] == {"nested": [1, 2]}


def test_the_two_shape_failures_belong_to_the_annotation_and_not_to_this_validator():
    """``marker_body_problems`` has a branch for "not an object" at both levels, and NEITHER is
    reachable through a schema: ``dict[str, dict[str, Any]]`` refuses those two shapes before any
    ``model_validator(mode="after")`` runs.

    That is the design and not an oversight — pydantic's own error carries a ``loc`` pointing at the
    exact offending key, which is better than a hand-written sentence can do — but it means the two
    branches are the defence for a DIRECT caller of ``marker_body_problems``, and a reader who found
    them and went looking for a schema test would not find one. Both halves are pinned here so neither
    gets deleted as dead code.
    """
    import pydantic

    from app.schemas.records import ProductUpdate

    for body, expected_loc in (
        (["lengthInches"], (MARKER_BODY_KEY,)),
        ({"lengthInches": "TYPED"}, (MARKER_BODY_KEY, "lengthInches")),
    ):
        with pytest.raises(pydantic.ValidationError) as caught:
            ProductUpdate(lengthInches="8.5", measurementMethods=body)
        errors = caught.value.errors()
        assert [e["loc"] for e in errors] == [expected_loc], (
            "the annotation must name the offending key, which is the whole reason it is preferred "
            f"to a sentence here: {errors!r}"
        )
        assert all(e["type"] == "dict_type" for e in errors)

    # The branches themselves, exercised the only way anything can reach them.
    assert marker_body_problems(["lengthInches"], present_fields={"lengthInches"})
    assert marker_body_problems({"lengthInches": "TYPED"}, present_fields={"lengthInches"})


def test_all_four_bodies_refuse_and_not_only_the_update_pair():
    """``validate_measurement_methods`` is attached by a separate line on each of four models, and
    three of those lines are easy to forget. A client that implemented its half against
    ``ProductUpdate`` alone and got a silent drop on CREATE would store the defect this module exists
    to remove on exactly the save where the record is first written.

    Paired with ``test_the_schemas_accept_the_marker_the_endpoint_hands_out``, which asserts the same
    four accept a good marker: together they say the validator is attached AND is not refusing
    everything.
    """
    from app.schemas.records import ProductCreate, ProductUpdate, ToolCreate, ToolUpdate

    bad = {"lengthInches": "8.5", MARKER_BODY_KEY: {"lengthInches": {"method": "typed"}}}
    creating = {"place": "Bhuj", "artisanName": "A", "craftName": "P", "location": _LOCATION}
    required = {
        ProductCreate: {**creating, "productName": "Pot"},
        ToolCreate: {**creating, "toolkitName": "Wheel"},
        ProductUpdate: {},
        ToolUpdate: {},
    }
    for model_cls, extra in required.items():
        sentence = _refusal(model_cls, **extra, **bad)
        assert_sentence(sentence)
        # THE ASSERTION THAT MAKES THIS TEST MEAN ANYTHING. Asserting only "it was refused" passes on
        # the two create models for the WRONG REASON: they are also refused by ``require_location``,
        # which is declared ahead of ``_measurement_methods`` and stops pydantic before the marker is
        # ever read. See ``_LOCATION``.
        assert f"{MARKER_BODY_KEY}.lengthInches" in sentence, (
            f"{model_cls.__name__} refused this body for some other reason than the marker, so this "
            f"says nothing about whether the validator is attached to it: {sentence!r}"
        )


def test_every_refusal_names_the_key_the_value_and_what_to_send_instead():
    """The cost of a refusal is not free and this test is what buys it: a ``ValueError`` out of a
    pydantic validator is a 422 on the WHOLE request, and neither client queue retries a 4xx — so a
    client bug in the marker throws away a form a researcher filled in, and offline it is not retried
    either.

    That trade is only right while every sentence is one a client author can act on WITHOUT reading
    this repository. Swept across every refusal in one place so a new one added later cannot be terser
    than the ones beside it.
    """
    bodies = (
        {"height": {"method": "TYPED"}},
        {"lengthInches": {"method": "typed"}},
        {"heightInches": {"method": "TYPED"}},
        {"lengthInches": {"method": "PHOTO_GEOMETRY", "technique": "CALIPER"}},
        {"lengthInches": {"method": "VISION_MODEL", "technique": "SCALE"}},
        {"lengthInches": {"method": "VISION_MODEL", MARKER_CONFIDENCE_KEY: 7}},
        ["lengthInches"],
        {"lengthInches": "TYPED"},
    )
    seen = 0
    for body in bodies:
        for sentence in marker_body_problems(body, present_fields={"lengthInches"}):
            seen += 1
            assert_sentence(sentence)
            assert MARKER_BODY_KEY in sentence, (
                f"a refusal that does not name the body key leaves the client guessing: {sentence!r}"
            )
    assert seen == len(bodies), f"a refusal stopped firing or two now share a body: {seen}"


# --------------------------------------------------------------------------------------
# The tool half, end to end — the table that had nowhere to put a height until recently
# --------------------------------------------------------------------------------------


def test_an_accepted_photo_geometry_reading_on_a_tool_height_round_trips():
    """**THE WHOLE CHAIN IN ONE TEST**, because every link was landed separately and each is inert
    without the others:

    1. ``ToolUpdate`` ACCEPTS the marker — without the declaration, ``extra="forbid"`` makes this body
       a 422 in full and neither client queue retries it;
    2. ``validate_measurement_methods`` lets it through — ``heightInches`` is a documented dimension,
       the value rides in the same request, and ``SCALE`` is a geometry PHOTO_GEOMETRY actually has;
    3. ``model_dump(exclude_unset=True)`` keeps BOTH keys — the marker is not a column but it has to
       survive the dump to reach the merge, which is what a route rebuilding ``data`` from a column
       list would silently break;
    4. ``merge_field_provenance`` POPS the marker — so Prisma never sees an unknown column, which
       would be a 500 rather than something a researcher could act on;
    5. and the stamp says PHOTO_GEOMETRY beside the person who accepted it, not instead of them.

    ``heightInches`` AND NOT ``height``: the unit-less column is what a tool height used to land in,
    and a marker naming it is refused by name (see the boundary section). ``PHOTO_GEOMETRY`` rather
    than ``VISION_MODEL`` because it is the method with a ``technique`` — the one marker key with no
    equivalent anywhere else in the stamp — and it is the method a courtyard with no signal can
    actually produce.
    """
    from decimal import Decimal

    from app.schemas.records import ToolUpdate

    body = ToolUpdate(
        heightInches="7",
        measurementMethods={"heightInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}},
    )
    new_data = body.model_dump(exclude_unset=True)
    assert set(new_data) == {"heightInches", MARKER_BODY_KEY}, (
        "the marker must survive the dump to reach the merge, and nothing else may ride along"
    )

    merge_field_provenance(new_data, _saver())

    assert MARKER_BODY_KEY not in new_data, (
        "the marker is not a column on ToolDocumentation; leaving it in hands Prisma an unknown "
        "column and turns a provenance hint into a 500 on the save"
    )
    assert new_data["heightInches"] == Decimal(7), "the measurement itself must still be written"

    stamp = _stored_provenance(new_data)["heightInches"]
    assert stamp["method"] == "PHOTO_GEOMETRY"
    assert stamp["methodTechnique"] == "SCALE", (
        "which of the two geometries produced the number is the only fact that lets a later reader "
        "re-derive it, and it has nowhere else to be stored"
    )
    # BESIDE the signature and not instead of it — read aloud, the row now says "arithmetic over
    # marks on a photograph produced this, and R. Menon accepted it into the record at that moment".
    assert stamp["by"] == "usr_7"
    assert stamp["byName"] == "R. Menon"
    assert stamp["at"], "the moment of acceptance is the whole value of the human half"
    # Keys whose answer is not a fact are OMITTED rather than stored as UNRECORDED: there is no
    # provider and no model behind a geometry reading, and the questions do not apply.
    assert not {"methodProvider", "methodModelId", "methodConfidence"} & set(stamp), (
        f"a geometry reading has no provider, model or confidence to state: {stamp!r}"
    )


def test_the_create_route_chain_carries_the_marker_from_the_body_to_the_stamp():
    """**THE CHAIN THE ROUTE ACTUALLY RUNS, NOT A HAND-BUILT DICT.** ``routes/products.create_product``
    composes ``decimal_to_string(clean_data(payload.model_dump()))`` and hands the result to
    ``merge_field_provenance``, and every link in that is a chance to lose a key that is not a column:

    * ``model_dump()`` WITHOUT ``exclude_unset`` — the create path dumps every optional, so a body
      that never mentions the marker arrives carrying ``measurementMethods: None``;
    * ``clean_data`` drops keys whose value is None and is therefore what turns that into the absence
      the record half expects. It is ALSO what would hand a sent marker straight to Prisma if the pop
      were ever removed, because it drops nothing else;
    * ``decimal_to_string`` recurses into nested dicts, so it walks INSIDE the marker; a confidence
      that came back as a string instead of a float would be a stored stamp that no longer matches
      what the endpoint handed out.

    Asserted for both shapes — the create that uses the feature and the create that does not — because
    the second is every save in the installed fleet.
    """
    from app.schemas.records import ProductCreate
    from app.services.records import clean_data, decimal_to_string

    def _chain(**body):
        payload = ProductCreate(
            craftName="Pottery",
            place="Bhuj",
            artisanName="A",
            productName="Pot",
            location=_LOCATION,
            **body,
        )
        return decimal_to_string(clean_data(payload.model_dump()))

    used = _chain(lengthInches="8.5", measurementMethods={"lengthInches": _machine_marker()})
    assert used[MARKER_BODY_KEY] == {"lengthInches": _machine_marker()}, (
        "the marker must reach the merge unaltered — decimal_to_string walks inside it"
    )
    merge_field_provenance(used, _saver())
    assert MARKER_BODY_KEY not in used
    stamp = _stored_provenance(used)["lengthInches"]
    assert stamp["method"] == "VISION_MODEL"
    assert stamp["methodConfidence"] == 0.8
    assert stamp["byName"] == "R. Menon"

    unused = _chain(lengthInches="8.5")
    assert MARKER_BODY_KEY not in unused, (
        "a create that never mentions the marker must not carry it into the Prisma data dict as a "
        "null; clean_data is what removes it, and a client that has never heard of this feature is "
        "every save in the installed fleet"
    )
    merge_field_provenance(unused, _saver())
    assert _stored_provenance(unused)["lengthInches"]["method"] == UNRECORDED


def test_the_queued_writer_still_fills_a_dimension_with_nobody_in_the_room():
    """**AN OPEN DEFECT, PINNED AS A NAMED DECISION RATHER THAN LEFT AS AN ABSENCE A READER MUST
    NOTICE.** This asserts the CURRENT, WRONG behaviour, in the same spirit as
    ``test_tool_height_columns``'s open-half test: the day somebody closes it, this goes red and tells
    them what to change here.

    ``media_queue._measurement_update_data`` writes ``lengthInches`` / ``breadthInches`` from a vision
    model's estimate whenever the column is empty, and it does NOT pass through
    ``merge_field_provenance`` — so the number lands with no provenance stamp at all, not even the
    false human one this module was written to end. A background worker read a photograph, nobody saw
    the answer, and a costed, printed dimension changed.

    IT IS OUT OF SCOPE FOR THE PROVENANCE WORK ON PURPOSE: closing it is a behaviour change to the
    media pipeline (stop writing the two columns; keep ``measurementAnalysis`` so a client can still
    show the reading and a person can accept it through the ordinary save path), and that belongs to
    whoever owns this queue. WHEN IT IS CLOSED, invert this test to
    ``assert set(data) & DIMENSION_FIELDS == set()`` and delete the note above
    ``_measurement_update_data``.
    """
    from app.services import media_queue

    data = media_queue._measurement_update_data(
        "med_1", "COMPLETED", {"lengthInches": 8.5, "breadthInches": 4}, _Row()
    )

    assert set(data) & DIMENSION_FIELDS == {"lengthInches", "breadthInches"}, (
        "the queued writer no longer fills a documented dimension — if that is deliberate, this "
        "defect is CLOSED: invert this test and delete the warning above _measurement_update_data"
    )
    assert "extraMetadata" not in data, (
        "the queued writer still records no provenance for the dimensions it writes"
    )
    assert data["measurementAnalysisStatus"] == "COMPLETED"
    assert data["measurementImageId"] == "med_1"


# --------------------------------------------------------------------------------------
# Property 4: the offline path is not burdened, asserted on the module rather than promised
# --------------------------------------------------------------------------------------


def test_nothing_in_this_module_can_write_read_or_wait_for_anything():
    """**WHY THIS MATTERS FOR THE COURTYARD AND NOT ONLY FOR THE TESTS.** The offline geometry path
    computes its number on the handset with no signal. If stating a method needed a database row, a
    network call or an ``await``, a researcher under a tree could not state one — and the feature would
    have to either lie or stop working. Every function here is pure, so the marker for a courtyard
    measurement is a dictionary literal the client already has every fact for.

    Asserted on the source rather than by importing and hoping: an ``import`` added later would pass a
    behavioural test that happened not to reach it. ``ast.parse`` reads the file without executing it,
    so that property is kept.

    PARSED RATHER THAN SCANNED FOR SUBSTRINGS, and that is not pedantry. This module's comments carry
    ``grep -n ... backend/prisma/schema.prisma`` re-check commands — the house rule that a
    state-of-the-world claim must say how to disprove it — and a substring scan for "prisma" goes red
    on the documentation discipline it shares a repository with. Parsing is also STRICTER: ``await x``
    with two spaces, ``async  def``, and ``import prisma as p`` all slip past a substring match and
    none of them survives an AST walk.
    """
    path = BACKEND / "app" / "services" / "measurement_provenance.py"
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

    forbidden_roots = {"prisma", "requests", "httpx", "aiohttp", "boto3"}
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module)

    for module in sorted(imported):
        root = module.split(".")[0]
        assert root not in forbidden_roots, f"measurement_provenance imports {module!r}"
        assert not module.startswith("app.core.db"), f"measurement_provenance imports {module!r}"

    # No coroutine can be declared and nothing can be waited on, anywhere in the file — the whole of
    # what makes a marker composable on a handset with no signal.
    for node in ast.walk(tree):
        assert not isinstance(node, ast.AsyncFunctionDef), (
            f"measurement_provenance declares a coroutine: {node.name!r}"
        )
        assert not isinstance(node, (ast.Await, ast.AsyncFor, ast.AsyncWith)), (
            f"measurement_provenance waits for something on line {node.lineno}"
        )

    # The positive control: the walk above is reaching real nodes and not an empty tree.
    assert imported, "nothing was parsed — this guard would pass on an empty file"


def test_the_geometry_path_needs_no_server_to_state_its_method():
    """The whole offline obligation, end to end, with nothing else in the room: build the marker, read
    it back, and confirm nobody has to accept anything."""
    marker = {"method": "PHOTO_GEOMETRY", "technique": "RECTIFIED"}
    provenance = provenance_of_marker(marker)
    assert provenance.requires_acceptance is False
    assert provenance.reproducible is True
    assert method_stamps({"lengthInches": marker}, fields=["lengthInches"]) == {
        "lengthInches": {"method": "PHOTO_GEOMETRY", "methodTechnique": "RECTIFIED"}
    }


def test_the_client_specification_is_written_down_and_names_the_call_sites():
    """The client half is a handful of edits in files this change deliberately does not touch, so the
    only thing standing between it and being forgotten is that the specification names the exact call
    sites. Pinned so a later tidy-up of this docstring cannot quietly delete the handover — which is
    the one part of this work that has no code to fail if it goes missing."""
    from app.services import measurement_provenance

    spec = measurement_provenance.__doc__ or ""
    for call_site in ("GridMeasurement.tsx", "ProductForm.tsx", "ToolForm.tsx", "MainActivity.kt"):
        assert call_site in spec, f"the specification does not say what {call_site} must do"
    for obligation in ("requiresAcceptance", MARKER_BODY_KEY, "merge_field_provenance"):
        assert obligation in spec

    # THE ACCEPTANCE RULE IS THE MECHANISM, and it lives only on the clients — this server cannot
    # enforce it and says so. If these two sentences go missing, the next client author implements
    # "remember that a machine filled this box" instead of "re-check the string", which is the version
    # that keeps claiming VISION_MODEL after somebody has typed over the number.
    assert "CHARACTER FOR CHARACTER" in spec, (
        "the specification no longer says the accepted value is re-checked against the box"
    )
    assert "onChange" in spec, (
        "the specification no longer says the acceptance is forgotten when the box is edited, which "
        "is the one case value-equality cannot see"
    )

    # AND THE ORDER THE TWO SERVER-SIDE HANDOFFS MUST LAND IN, which is the part that costs something
    # irreversible if it is read wrong: the schema declaration is what makes the marker sendable, and
    # landing it before ``access.REVISION_SKIP_FIELDS`` gains the key means every marker-bearing save
    # writes a RecordRevision nobody made, into a table nothing retracts.
    assert "REVISION_SKIP_FIELDS" in spec, (
        "the specification no longer names the skip-list handoff that has to land before the schemas"
    )
