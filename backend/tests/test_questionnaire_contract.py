"""The frozen contract (BUILD_PLAN section 1): every example in ``shared/questionnaire-contract.json``
validates against the Pydantic model of the same name, every route exists with its method, and the
error codes are the closed UPPER_SNAKE list."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from app.schemas.questionnaire_v2 import CONTRACT_MODELS
from app.services.questionnaire_v2.common import MESSAGES, STATUS

CONTRACT = json.loads((Path(__file__).resolve().parents[2] / "shared" / "questionnaire-contract.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("name", sorted(CONTRACT["examples"]))
def test_every_example_validates(name: str) -> None:
    assert name in CONTRACT_MODELS, f"no model for contract example {name}"
    CONTRACT_MODELS[name].model_validate(CONTRACT["examples"][name])


def test_every_route_exists_with_its_method() -> None:
    from fastapi.routing import iter_route_contexts

    from app.api.router import api_router

    have: set[tuple[str, str]] = set()
    # iter_route_contexts, not .routes: since FastAPI 0.137 .routes holds one wrapper per include.
    for route in iter_route_contexts(api_router.routes):
        for method in getattr(route, "methods", set()) or set():
            have.add((method, re.sub(r"\{[^}]+\}", "{}", route.path)))
    missing = [(r["method"], r["path"]) for r in CONTRACT["routes"] if (r["method"], re.sub(r"\{[^}]+\}", "{}", r["path"])) not in have]
    assert not missing, f"routes in the contract that the app does not serve: {missing}"


def test_error_codes_are_the_closed_list_and_upper_snake() -> None:
    assert set(CONTRACT["errorCodes"]) == set(MESSAGES) == set(STATUS)
    assert all(re.fullmatch(r"[A-Z][A-Z0-9_]*", code) for code in CONTRACT["errorCodes"])
    assert set(CONTRACT["errorExample"]["detail"]) >= {"code", "message"}


def test_the_session_example_carries_no_typed_title_input() -> None:
    assert "title" not in CONTRACT["examples"]["SessionCreate"] and "title" not in CONTRACT["examples"]["SessionPatch"]
    from app.schemas.questionnaire_v2 import SessionCreate

    with pytest.raises(Exception):
        SessionCreate.model_validate({**CONTRACT["examples"]["SessionCreate"], "title": "section F"})


def test_the_v2_media_shapes_are_dark_until_the_flag_and_the_old_shapes_keep_their_errors(monkeypatch) -> None:
    from fastapi.testclient import TestClient

    from app.core import deps
    from app.core.config import get_settings
    from app.main import app

    monkeypatch.setattr(get_settings(), "questionnaire_v2", False, raising=False)
    app.dependency_overrides[deps.get_current_user] = lambda: type("U", (), {"id": "u1", "role": "RESEARCHER"})()
    try:
        http = TestClient(app)
        v2 = http.post("/api/media/presign", json=CONTRACT["examples"]["PresignRequest"])
        assert v2.status_code == 404 and v2.json()["detail"]["code"] == "NOT_FOUND"
        done = http.post("/api/media/complete", json=CONTRACT["examples"]["CompleteRequest"])
        assert done.status_code == 404 and done.json()["detail"]["code"] == "NOT_FOUND"
        old = http.post("/api/media/presign", json={"filename": "a.jpg"})
        assert old.status_code == 422 and isinstance(old.json()["detail"], list), "the old route keeps FastAPI's 422 list"
        assert http.get("/api/questionnaire/sessions").status_code == 404
    finally:
        app.dependency_overrides.pop(deps.get_current_user, None)
