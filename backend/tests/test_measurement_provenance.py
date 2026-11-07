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
    assert text and text.strip(), "a refusal must say something"
    assert len(text) > 30, f"too terse to be an explanation: {text!r}"
    assert text.rstrip().endswith("."), f"not a sentence: {text!r}"
    assert "code " not in text.lower(), f"names an error code: {text!r}"



def test_the_four_methods_and_nothing_else():
    assert sorted(m.value for m in MeasurementMethod) == [
        "PHOTO_GEOMETRY",
        "TYPED",
        "UNRECORDED",
        "VISION_MODEL",
    ]


def test_only_an_irreproducible_reading_needs_a_person_to_accept_it():
    assert MeasurementMethod.VISION_MODEL.requires_acceptance is True
    assert MeasurementMethod.PHOTO_GEOMETRY.requires_acceptance is False
    assert MeasurementMethod.TYPED.requires_acceptance is False
    assert MeasurementMethod.UNRECORDED.requires_acceptance is False


def test_reproducibility_is_three_valued_and_typed_is_the_third():
    assert MeasurementMethod.PHOTO_GEOMETRY.reproducible is True
    assert MeasurementMethod.VISION_MODEL.reproducible is False
    assert MeasurementMethod.TYPED.reproducible is None
    assert MeasurementMethod.UNRECORDED.reproducible is None


def test_the_honest_unknown_is_one_word_and_the_member_spells_it_the_same():
    assert UNRECORDED == "UNRECORDED"
    assert MeasurementMethod.UNRECORDED.value == UNRECORDED


def test_a_method_marker_may_only_name_a_dimension_column():
    assert sorted(DIMENSION_FIELDS) == ["breadthInches", "heightInches", "lengthInches"]


def test_the_unit_less_legacy_height_may_never_carry_a_marker():
    for not_documented in ("height", "width", "thickness", "weight", "radius"):
        assert not_documented not in DIMENSION_FIELDS
        assert method_stamps({not_documented: {"method": "VISION_MODEL"}}) == {}


def test_the_geometry_techniques_are_the_two_the_clients_can_actually_produce():
    assert sorted(GEOMETRY_TECHNIQUES) == ["RECTIFIED", "SCALE"]



def test_a_confidence_the_model_gave_travels():
    assert self_reported_confidence({"confidence": 0.82}) == 0.82
    assert self_reported_confidence({"confidence": 0}) == 0.0
    assert self_reported_confidence({"confidence": 1}) == 1.0


def test_a_confidence_the_model_did_not_give_stays_missing():
    assert self_reported_confidence({"valueInches": 12}) is None
    assert self_reported_confidence({"confidence": None}) is None
    assert self_reported_confidence({}) is None
    assert self_reported_confidence(None) is None
    assert self_reported_confidence("not a mapping") is None


def test_a_confidence_off_the_scale_is_dropped_and_never_clamped():
    for off_scale in (1.5, -0.2, 7, 100):
        assert self_reported_confidence({"confidence": off_scale}) is None


def test_a_boolean_confidence_is_not_a_confidence_of_one():
    assert self_reported_confidence({"confidence": True}) is None
    assert self_reported_confidence({"confidence": False}) is None


def test_a_number_the_model_wrote_as_a_string_is_still_a_number():
    assert self_reported_confidence({"confidence": "0.8"}) == 0.8
    assert self_reported_confidence({"confidence": " 0.8 "}) == 0.8
    assert self_reported_confidence({"confidence": "high"}) is None
    assert self_reported_confidence({"confidence": ""}) is None


def test_nan_and_infinity_are_not_confidences():
    assert self_reported_confidence({"confidence": math.nan}) is None
    assert self_reported_confidence({"confidence": math.inf}) is None
    assert self_reported_confidence({"confidence": -math.inf}) is None


def test_the_confidence_is_never_labelled_calibrated():
    payload = vision_model_provenance(
        {"confidence": 0.9}, provider="gemini", model_id=MODEL_ID
    ).payload()
    assert payload["confidenceIsCalibrated"] is False
    assert payload["selfReportedConfidence"] == 0.9


def test_no_code_path_anywhere_in_this_backend_can_set_the_calibrated_flag_true():
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
    assert checked == 1, f"expected exactly one writer of the calibrated flag, found {checked}"



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
    assert provenance_of_marker(marker).method is MeasurementMethod.UNRECORDED


def test_a_marker_that_names_a_method_is_taken_at_its_word():
    assert provenance_of_marker({"method": "TYPED"}).method is MeasurementMethod.TYPED
    assert (
        provenance_of_marker({"method": "PHOTO_GEOMETRY"}).method
        is MeasurementMethod.PHOTO_GEOMETRY
    )
    assert provenance_of_marker({"method": "VISION_MODEL"}).method is MeasurementMethod.VISION_MODEL


def test_a_vision_marker_round_trips_through_the_wire_unchanged():
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
    from app.services.measurement_provenance import PROVIDER_CONFIDENCE_KEY as provider_key

    marker_key = MARKER_CONFIDENCE_KEY

    assert provider_key == "confidence"
    assert marker_key == "selfReportedConfidence"
    assert self_reported_confidence({provider_key: 0.4}) == 0.4
    assert self_reported_confidence({marker_key: 0.4}) is None
    assert self_reported_confidence({marker_key: 0.4}, key=marker_key) == 0.4
    assert (
        provenance_of_marker({"method": "VISION_MODEL", provider_key: 0.9}).self_reported_confidence
        is None
    )


def test_only_a_technique_the_geometry_actually_has_is_recorded():
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
    assert provenance_of_marker({"method": "TYPED"}).stamp() == {"method": "TYPED"}
    assert provenance_of_marker(None).stamp() == {"method": "UNRECORDED"}
    assert provenance_of_marker({"method": "PHOTO_GEOMETRY", "technique": "SCALE"}).stamp() == {
        "method": "PHOTO_GEOMETRY",
        "methodTechnique": "SCALE",
    }


def test_a_provenance_cannot_be_edited_after_the_fact():
    with pytest.raises(dataclasses.FrozenInstanceError):
        vision_model_provenance({}, provider="gemini", model_id=MODEL_ID).provider = "openai"


def test_a_caller_with_nothing_to_say_has_to_say_unrecorded_deliberately():
    blank = vision_model_provenance({}, provider="  ", model_id="")
    assert blank.provider == UNRECORDED
    assert blank.model_id == UNRECORDED
    with pytest.raises(TypeError):
        vision_model_provenance({})



def test_every_dimension_a_save_touches_states_a_method_even_when_nobody_declared_one():
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
    assert method_stamps({"costOfMaking": {"method": "TYPED"}, "id": {"method": "TYPED"}}) == {}


def test_a_save_that_touches_no_dimension_gets_no_stamps():
    assert method_stamps(None, fields=["remarks", "localName"]) == {}
    assert method_stamps({"lengthInches": {"method": "TYPED"}}, fields=["remarks"]) == {}


def test_the_marker_body_key_is_the_one_both_halves_name():
    assert MARKER_BODY_KEY == "measurementMethods"



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
    gemini('{"valueInches": 12.5, "confidence": 0.8}')

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings(), "height")

    assert result["analysis"]["valueInches"] == 12.5
    assert result["available"] is True
    assert result["status"] == "COMPLETED"


def test_a_model_that_reported_no_confidence_reports_none(gemini):
    gemini('{"valueInches": 4}')

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings())

    assert result["selfReportedConfidence"] is None
    assert "selfReportedConfidence" not in result["methodMarker"]
    assert result["methodMarker"]["method"] == "VISION_MODEL"


def test_prose_instead_of_json_still_carries_its_provenance(gemini):
    gemini("I cannot see a grid in this photograph.")

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings())

    assert "valueInches" not in result["analysis"]
    assert result["method"] == "VISION_MODEL"
    assert result["modelId"] == MODEL_ID


def test_an_unconfigured_server_still_names_the_model_it_would_have_used(monkeypatch):
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
    gemini('{"valueInches": 12.5, "confidence": 0.8}')

    result = ai._post_gemini_measurement(b"image", "image/jpeg", _settings(), "length")

    rendered = json.loads(json.dumps(result))
    assert rendered["method"] == "VISION_MODEL"
    assert isinstance(rendered["methodMarker"]["selfReportedConfidence"], float)



class _DatabaseTouched(Exception):
    pass


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


_CALLER: dict[str, object] = {"user": None}


def _person(role: str):
    return SimpleNamespace(id="usr_1", email="x@example.test", name="Test", role=role)


@pytest.fixture
def api(monkeypatch):
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

    async def _analyze(content, filename, mime, settings, dimension=None, **_kwargs):
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
    return {"file": ("grid.jpg", b"\xff" * size, mime)}


def test_a_researcher_reaches_the_reader_and_gets_the_provenance_with_the_number(api):
    outcome = api.call("RESEARCHER", "POST", "/media/analyze-measurement", files=_grid())
    assert outcome.status_code == 200
    assert outcome.body["analysis"]["valueInches"] == 12.5
    assert outcome.body["method"] == "VISION_MODEL"
    assert outcome.body["requiresAcceptance"] is True
    assert outcome.body["confidenceIsCalibrated"] is False
    assert outcome.body["methodMarker"]["modelId"] == MODEL_ID
    assert len(api.seen.provider_calls) == 1


def test_the_reader_writes_nothing(api):
    outcome = api.call("RESEARCHER", "POST", "/media/analyze-measurement", files=_grid())
    assert outcome.reached is False, "the reader touched the database"
    assert outcome.status_code == 200



class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


def _saver():
    return SimpleNamespace(id="usr_7", name="R. Menon")


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
    wrapper = new_data["extraMetadata"]
    stored = getattr(wrapper, "data", wrapper)
    return stored["fieldProvenance"]


def test_a_model_reading_is_stored_as_a_model_reading_beside_the_person_who_accepted_it():
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
    new_data = {"lengthInches": "8.5", MARKER_BODY_KEY: {"lengthInches": _machine_marker()}}

    merge_field_provenance(new_data, _saver())

    assert MARKER_BODY_KEY not in new_data
    assert MARKER_BODY_KEY not in _stored_provenance(new_data)


def test_the_skip_set_entry_for_the_marker_is_redundancy_and_no_behaviour_can_observe_it():
    assert MARKER_BODY_KEY in PROVENANCE_SKIP_FIELDS


def test_every_changed_field_gets_its_own_stamp_and_not_one_shared_dict():
    new_data = {"lengthInches": "8.5", "remarks": "A water pot."}

    merge_field_provenance(new_data, _saver())

    provenance = _stored_provenance(new_data)
    assert provenance["lengthInches"] is not provenance["remarks"]
    assert "method" not in provenance["remarks"]


def test_a_dimension_nobody_declared_a_method_for_says_so_in_that_word():
    new_data = {"lengthInches": "8.5"}

    merge_field_provenance(new_data, _saver())

    assert _stored_provenance(new_data)["lengthInches"]["method"] == UNRECORDED


def test_an_untouched_dimension_keeps_the_method_it_was_saved_with():
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
    assert _stored_provenance(new_data)["remarks"]["byName"] == "R. Menon"


def test_a_trailing_zero_is_not_a_change_and_therefore_not_a_downgrade():
    previous = _Row(
        lengthInches="8.50", extraMetadata={"fieldProvenance": {"lengthInches": _machine_stamp()}}
    )
    new_data = {"lengthInches": 8.5, MARKER_BODY_KEY: {"lengthInches": {"method": "TYPED"}}}

    merge_field_provenance(new_data, _saver(), previous=previous)

    assert _stored_provenance(new_data)["lengthInches"]["method"] == "VISION_MODEL"


def test_overtyping_a_machine_number_makes_it_the_persons_own():
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
        {"method": "typed"},
        "TYPED",
        {"method": ["TYPED"]},
        None,
    ],
)
def test_a_malformed_marker_is_unrecorded_and_never_typed_in_the_record_too(marker):
    new_data = {"lengthInches": "8.5", MARKER_BODY_KEY: {"lengthInches": marker}}

    merge_field_provenance(new_data, _saver())

    assert _stored_provenance(new_data)["lengthInches"]["method"] == UNRECORDED


def test_a_marker_naming_a_column_the_save_is_not_writing_stamps_nothing():
    assert method_stamps({"heightInches": _machine_marker()}, fields={"lengthInches"}) == {
        "lengthInches": {"method": UNRECORDED}
    }

    new_data = {"lengthInches": "8.5", MARKER_BODY_KEY: {"heightInches": _machine_marker()}}

    merge_field_provenance(new_data, _saver())

    provenance = _stored_provenance(new_data)
    assert "heightInches" not in provenance
    assert provenance["lengthInches"]["method"] == UNRECORDED


def test_the_marker_is_not_logged_as_an_edit_somebody_made(monkeypatch):
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
    from app.schemas.records import ProductUpdate

    model = ProductUpdate(lengthInches="8.5", measurementMethods=None)
    assert model.measurementMethods is None

    new_data = model.model_dump(exclude_unset=True)
    assert new_data[MARKER_BODY_KEY] is None
    merge_field_provenance(new_data, _saver())
    assert MARKER_BODY_KEY not in new_data
    assert _stored_provenance(new_data)["lengthInches"]["method"] == UNRECORDED



def _refusal(model_cls, **body) -> str:
    import pydantic

    try:
        model_cls(**body)
    except pydantic.ValidationError as error:
        return "; ".join(e["msg"].removeprefix("Value error, ") for e in error.errors())
    raise AssertionError(f"{model_cls.__name__} accepted a marker it must refuse: {body!r}")


def test_a_method_naming_a_column_that_is_not_a_documented_dimension_is_refused_by_name():
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

    for not_a_string in (7, True, None, ["TYPED"]):
        assert_sentence(
            _refusal(
                ProductUpdate,
                lengthInches="8.5",
                measurementMethods={"lengthInches": {"method": not_a_string}},
            )
        )


def test_a_method_for_a_dimension_this_request_is_not_sending_is_refused():
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

    ToolUpdate(
        heightInches="7",
        measurementMethods={"heightInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}},
    )


def test_a_photo_geometry_technique_this_server_does_not_know_is_refused():
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

    ProductUpdate(
        lengthInches="8.5",
        measurementMethods={"lengthInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}},
    )


def test_a_confidence_the_scale_does_not_have_is_refused_but_an_absent_one_is_not():
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
    ProductUpdate(
        lengthInches="8.5",
        measurementMethods={
            "lengthInches": {"method": "VISION_MODEL", MARKER_CONFIDENCE_KEY: "0.8"}
        },
    )


def test_a_marker_key_a_newer_server_added_is_ignored_rather_than_refused():
    from app.schemas.records import ProductUpdate

    model = ProductUpdate(
        lengthInches="8.5",
        measurementMethods={
            "lengthInches": {"method": "TYPED", "aKeyFromTheFuture": {"nested": [1, 2]}}
        },
    )
    assert model.measurementMethods["lengthInches"]["aKeyFromTheFuture"] == {"nested": [1, 2]}


def test_the_two_shape_failures_belong_to_the_annotation_and_not_to_this_validator():
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

    assert marker_body_problems(["lengthInches"], present_fields={"lengthInches"})
    assert marker_body_problems({"lengthInches": "TYPED"}, present_fields={"lengthInches"})


def test_all_four_bodies_refuse_and_not_only_the_update_pair():
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
        assert f"{MARKER_BODY_KEY}.lengthInches" in sentence, (
            f"{model_cls.__name__} refused this body for some other reason than the marker, so this "
            f"says nothing about whether the validator is attached to it: {sentence!r}"
        )


def test_every_refusal_names_the_key_the_value_and_what_to_send_instead():
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



def test_an_accepted_photo_geometry_reading_on_a_tool_height_round_trips():
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
    assert stamp["by"] == "usr_7"
    assert stamp["byName"] == "R. Menon"
    assert stamp["at"], "the moment of acceptance is the whole value of the human half"
    assert not {"methodProvider", "methodModelId", "methodConfidence"} & set(stamp), (
        f"a geometry reading has no provider, model or confidence to state: {stamp!r}"
    )


def test_the_create_route_chain_carries_the_marker_from_the_body_to_the_stamp():
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



def test_nothing_in_this_module_can_write_read_or_wait_for_anything():
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

    for node in ast.walk(tree):
        assert not isinstance(node, ast.AsyncFunctionDef), (
            f"measurement_provenance declares a coroutine: {node.name!r}"
        )
        assert not isinstance(node, (ast.Await, ast.AsyncFor, ast.AsyncWith)), (
            f"measurement_provenance waits for something on line {node.lineno}"
        )

    assert imported, "nothing was parsed — this guard would pass on an empty file"


def test_the_geometry_path_needs_no_server_to_state_its_method():
    marker = {"method": "PHOTO_GEOMETRY", "technique": "RECTIFIED"}
    provenance = provenance_of_marker(marker)
    assert provenance.requires_acceptance is False
    assert provenance.reproducible is True
    assert method_stamps({"lengthInches": marker}, fields=["lengthInches"]) == {
        "lengthInches": {"method": "PHOTO_GEOMETRY", "methodTechnique": "RECTIFIED"}
    }
