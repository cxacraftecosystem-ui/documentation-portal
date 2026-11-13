from __future__ import annotations

import copy
import json
import os
import re
import warnings
from functools import lru_cache
from pathlib import Path
from typing import Any

import pytest
from pydantic import TypeAdapter, ValidationError

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = json.loads((ROOT / "shared" / "request-contract-samples.json").read_text(encoding="utf-8"))
SAMPLE_STRINGS = {SAMPLES["placeholder"]} | {
    value for group in ("fields", "suffixes") for value in SAMPLES[group].values() if isinstance(value, str)
}
SUBSTITUTABLE = {"literal_error", "enum"}
NEVER_BLANK = set(SAMPLES.get("neverBlank", ()))
RESTORED: list[str] = []


def _android_files() -> list[Path]:
    raw = os.environ.get("REQUEST_CONTRACT_FIXTURES", "")
    return [Path(part) for part in raw.split(os.pathsep) if part.strip()]


def _entries() -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    web = ROOT / "shared" / "web-request-fixtures.json"
    if web.is_file():
        for entry in json.loads(web.read_text(encoding="utf-8")):
            found.append({"app": "web", **entry})
    for path in _android_files():
        data = json.loads(path.read_text(encoding="utf-8"))
        found.extend(data["entries"])
    return found


ENTRIES = _entries()
FILLED = {
    (e["app"], e["method"], e["path"], e.get("dto") or e.get("function") or ""): e["body"]
    for e in ENTRIES
    if e.get("variant") == "filled"
}


def _entry_id(entry: dict[str, Any]) -> str:
    who = entry.get("dto") or entry.get("function") or ""
    return f"{entry['app']}:{entry['method']} {entry['path']}:{who}:{entry.get('variant', '')}"


def _walk(routes: Any, prefix: str = "") -> Any:
    from fastapi.routing import APIRoute

    for route in routes:
        if isinstance(route, APIRoute):
            yield prefix + route.path_format, route
        elif hasattr(route, "original_router"):
            context = getattr(route, "include_context", None)
            yield from _walk(route.original_router.routes, prefix + (getattr(context, "prefix", "") or ""))
        elif hasattr(route, "routes"):
            yield from _walk(route.routes, prefix + (getattr(route, "path", "") or ""))


@lru_cache(maxsize=1)
def _routes() -> list[tuple[str, re.Pattern[str], int, Any]]:
    from app.main import app

    table: list[tuple[str, re.Pattern[str], int, Any]] = []
    for template, route in _walk(app.routes):
        wildcards = template.count("{")
        pattern = re.compile("^" + re.sub(r"\\{[^}]+\\}", "[^/]+", re.escape(template)) + "$")
        for method in route.methods or ():
            table.append((method, pattern, wildcards, (template, route)))
    return table


def _route_for(method: str, path: str) -> Any:
    concrete = re.sub(r"\{[^}]+\}", "rec-7c1f6b52", path)
    hits = [(wild, route) for verb, pattern, wild, route in _routes() if verb == method and pattern.match(concrete)]
    if not hits:
        return None
    return min(hits, key=lambda hit: hit[0])[1]


def _adapters(route: Any) -> tuple[str, Any]:
    params = list(route.dependant.body_params)
    if not params:
        return "none", None
    if len(params) == 1 and not getattr(params[0].field_info, "embed", False):
        return "single", TypeAdapter(params[0].field_info.annotation)
    return "embedded", {param.alias: (TypeAdapter(param.field_info.annotation), param.field_info.is_required()) for param in params}


def _get(body: Any, loc: tuple[Any, ...]) -> Any:
    node = body
    for key in loc:
        try:
            node = node[key]
        except (KeyError, IndexError, TypeError):
            return None
    return node


def _set(body: Any, loc: tuple[Any, ...], value: Any) -> None:
    node = body
    for key in loc[:-1]:
        node = node[key]
    node[loc[-1]] = value


def _first_allowed(expected: str) -> str | None:
    match = re.search(r"'([^']*)'", expected or "")
    return match.group(1) if match else None


def _validate_once(kind: str, adapter: Any, body: Any) -> list[dict[str, Any]]:
    try:
        if kind == "single":
            adapter.validate_python(body)
            return []
        errors: list[dict[str, Any]] = []
        for name, (one, required) in adapter.items():
            if not isinstance(body, dict) or name not in body:
                if required:
                    errors.append({"type": "missing", "loc": (name,), "msg": "Field required"})
                continue
            try:
                one.validate_python(body[name])
            except ValidationError as exc:
                errors.extend({**e, "loc": (name, *e["loc"])} for e in exc.errors(include_url=False))
        return errors
    except ValidationError as exc:
        return list(exc.errors(include_url=False))


def _blank(value: Any) -> bool:
    return isinstance(value, str) and not value.strip()


def _restore_never_blank(body: Any, filled: Any, ids: bool = False) -> bool:
    changed = False
    if isinstance(body, dict) and isinstance(filled, dict):
        for key, twin in filled.items():
            value = body.get(key)
            wanted = key.endswith(("Id", "Ids")) if ids else key in NEVER_BLANK
            if wanted and (key not in body or _blank(value) or value is None) and twin is not None and not _blank(twin):
                body[key] = copy.deepcopy(twin)
                RESTORED.append(key)
                changed = True
            elif isinstance(value, (dict, list)):
                changed = _restore_never_blank(value, twin, ids) or changed
    elif isinstance(body, list) and isinstance(filled, list):
        for item, twin in zip(body, filled):
            changed = _restore_never_blank(item, twin) or changed
    return changed


def _validate(kind: str, adapter: Any, body: Any, filled: Any = None) -> list[dict[str, Any]]:
    body = copy.deepcopy(body)
    for _ in range(80):
        errors = _validate_once(kind, adapter, body)
        if not errors:
            return []
        changed = False
        for error in errors:
            loc = tuple(error["loc"])
            current = _get(body, loc)
            if error["type"] in SUBSTITUTABLE and current in SAMPLE_STRINGS:
                allowed = _first_allowed(str((error.get("ctx") or {}).get("expected", "")))
                if allowed is not None:
                    _set(body, loc, allowed)
                    changed = True
            elif filled is not None and loc and (current is None or _blank(current)) and _get(filled, loc) is not None and not _blank(_get(filled, loc)):
                try:
                    _set(body, loc, copy.deepcopy(_get(filled, loc)))
                except (KeyError, IndexError, TypeError):
                    continue
                RESTORED.append(".".join(str(part) for part in loc))
                changed = True
        if not changed and filled is not None:
            changed = _restore_never_blank(body, filled) or _restore_never_blank(body, filled, ids=True)
        if not changed:
            return errors
    return errors


def test_the_request_contract_has_something_to_check() -> None:
    if os.environ.get("REQUEST_CONTRACT_REQUIRE_ANDROID") == "1":
        files = _android_files()
        assert files, "REQUEST_CONTRACT_FIXTURES names no Android fixture file"
        for path in files:
            data = json.loads(path.read_text(encoding="utf-8"))
            assert len(data["entries"]) >= 80, f"{path} holds only {len(data['entries'])} request bodies"


@pytest.mark.parametrize("entry", ENTRIES, ids=[_entry_id(e) for e in ENTRIES])
def test_every_request_body_a_client_sends_validates_against_its_route(entry: dict[str, Any]) -> None:
    path = entry["path"]
    if path.startswith("http"):
        pytest.skip("not this backend")
    found = _route_for(entry["method"], path)
    assert found is not None, f"{entry['method']} {path} has no route on this backend"
    template, route = found
    kind, adapter = _adapters(route)
    if kind == "none":
        pytest.skip(f"{template} reads its body by hand")
    twin = None
    if entry.get("variant") == "blank-optionals":
        twin = FILLED.get((entry["app"], entry["method"], entry["path"], entry.get("dto") or entry.get("function") or ""))
    RESTORED.clear()
    errors = _validate(kind, adapter, entry["body"], twin)
    if RESTORED:
        warnings.warn(
            f"{entry['app']} {entry['method']} {path}: the server requires {', '.join(sorted(set(RESTORED)))}, "
            "which the client type allows to be empty; the form must refuse a blank before sending",
            stacklevel=1,
        )
    readable = [f"{'.'.join(str(p) for p in e['loc'])}: {e['type']}: {e['msg']}" for e in errors]
    assert not errors, (
        f"{entry.get('interface', '')}.{entry.get('function', '')} sends {entry.get('dto', 'a body')} "
        f"({entry.get('variant')}) to {entry['method']} {template} and the server refuses it:\n  "
        + "\n  ".join(readable)
    )
