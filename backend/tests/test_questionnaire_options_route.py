"""``GET /questionnaires/options`` — the route the shared Android start sheet asks both portals for.

The questionnaire tree on the handset is shared with the designer portal, and its
``QuestionnaireApi.formOptions`` reads ``questionnaires/options``. This server had no such route, so
the path fell through to ``/{questionnaire_id}`` with ``"options"`` as the id, answered 404, and the
start sheet offered no form at all. Pinned here: the route exists, it is declared ABOVE the
parameterised one (FastAPI matches in declaration order), it answers a bare array with the designer
portal's row keys, and the handset still asks for exactly this path.
"""

from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import questionnaire as routes

pytestmark = pytest.mark.anyio

REPO = Path(__file__).resolve().parents[2]
OPTION_KEYS = {
    "id",
    "title",
    "version",
    "designWorkshopId",
    "isShared",
    "kind",
    "kindLabel",
    "isDefault",
    "sectionCount",
    "attachedElsewhere",
}


def _form(fid: str, *, deleted: bool = False) -> dict[str, Any]:
    return {
        "id": fid,
        "title": f"Form {fid}",
        "description": None,
        "isActive": True,
        "isDefault": fid == "f1",
        "version": 2,
        "sectionCount": 3,
        "questionCount": 9,
        "isShared": True,
        "kind": None,
        "kindLabel": "",
        "deletedAt": "2026-10-01T00:00:00Z" if deleted else None,
    }


async def test_options_answers_a_bare_array_of_live_forms_with_the_shared_keys(monkeypatch):
    seen: dict[str, Any] = {}

    async def fake_list_forms(user: Any, **kwargs: Any) -> list[dict[str, Any]]:
        seen.update(kwargs)
        return [_form("f1"), _form("f2", deleted=True)]

    monkeypatch.setattr(routes.questionnaire_library, "list_forms", fake_list_forms)
    rows = await routes.questionnaire_options(designWorkshopId=None, current_user=SimpleNamespace(id="u-1"))
    assert isinstance(rows, list)
    assert [row["id"] for row in rows] == ["f1"], "a soft-deleted form is never offered to start a session on"
    assert set(rows[0]) == OPTION_KEYS
    assert rows[0]["isDefault"] is True and rows[0]["sectionCount"] == 3
    assert seen.get("active_only") is True


def test_options_is_declared_above_the_parameterised_form_route():
    paths = [getattr(route, "path", "") for route in routes.instruments_router.routes]
    assert "/questionnaires/options" in paths
    assert paths.index("/questionnaires/options") < paths.index("/questionnaires/{questionnaire_id}"), (
        "declared below /{questionnaire_id}, the literal is swallowed as a form id and answers 404"
    )


def test_the_shared_handset_still_asks_for_this_path():
    api = next((REPO / "android").rglob("questionnaire/data/QuestionnaireApi.kt"))
    source = api.read_text(encoding="utf-8")
    assert '@GET("questionnaires/options")' in source
    assert "List<QFormOption>" in source
