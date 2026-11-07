from __future__ import annotations

import ast
import asyncio
from pathlib import Path
from typing import Any

import pytest

from app.api.routes import data_browser
from app.api.routes.data_browser import Scope

VISIBLE = {"createdById": "u1"}
FILTERED = Scope(
    records={"AND": [VISIBLE, {"status": "APPROVED"}]},
    media={"AND": [VISIBLE, {"status": "APPROVED"}]},
    visible=VISIBLE,
)


class _Seen(Exception):
    pass


@pytest.mark.parametrize(
    "path",
    [
        "by-workshop/w1",
        "by-workshop/w1/crafts",
        "by-uploader/w1",
    ],
)
def test_a_status_filter_never_hides_the_container_itself(monkeypatch, path):
    seen: list[Any] = []

    async def require(_delegate: Any, _rec_id: str, _what: str, include: Any = None, scope_where: Any = None) -> Any:
        seen.append(scope_where)
        raise _Seen()

    monkeypatch.setattr(data_browser, "_require", require)
    with pytest.raises(_Seen):
        asyncio.run(data_browser._list_level(path, FILTERED))
    assert seen == [VISIBLE]


def test_every_container_lookup_resolves_against_visibility_not_status():
    source = Path(data_browser.__file__).read_text(encoding="utf-8")
    calls = [
        node
        for node in ast.walk(ast.parse(source))
        if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "_require"
    ]
    assert calls
    for call in calls:
        for keyword in call.keywords:
            if keyword.arg == "scope_where":
                assert ast.unparse(keyword.value) != "scope.records", ast.unparse(call)


def test_the_readable_scope_drops_the_status_filter():
    assert FILTERED.readable == VISIBLE
    assert Scope(records={"a": 1}, media={}).readable == {"a": 1}
