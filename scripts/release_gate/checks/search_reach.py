from __future__ import annotations

import json
import urllib.parse

from gate import Gate

WORDS = ("audio", "craft", "artisan", "workshop", "weaving", "indigo", "product", "tool")
QUICK_TYPES = ("artisans", "workshops", "products", "tools", "media", "designWorkshops")
PAGE_CAP = 400


def _unified(gate: Gate, q: str, *, limit: int, page: int, kind: str | None = None) -> dict:
    params = {"q": q, "scope": "local", "limit": limit, "page": page}
    if kind:
        params["type"] = kind
    return gate.get_json("/api/search/unified?" + urllib.parse.urlencode(params))


def _key(item: dict) -> str:
    return f"{item.get('portal')}:{item.get('type')}:{item.get('id')}"


def _busiest_word(gate: Gate) -> tuple[str, dict]:
    best = ("", {"groups": []})
    for word in WORDS:
        first = _unified(gate, word, limit=5, page=1)
        if sum(g["total"] for g in first.get("groups", [])) > sum(
            g["total"] for g in best[1].get("groups", [])
        ):
            best = (word, first)
    return best


def _all_view(gate: Gate, word: str, first: dict) -> None:
    totals = {g["type"]: g["total"] for g in first.get("groups", [])}
    seen: dict[str, set[str]] = {kind: set() for kind in totals}
    page = 1
    response = first
    while page <= PAGE_CAP:
        added = 0
        for group in response.get("groups", []):
            for item in group.get("items", []):
                bucket = seen.setdefault(group["type"], set())
                if item.get("id") and _key(item) not in bucket:
                    bucket.add(_key(item))
                    added += 1
        if all(len(seen[k]) >= totals[k] for k in totals) or not added:
            break
        page += 1
        response = _unified(gate, word, limit=5, page=page)
    gate.count(all_query=word, all_pages=page, all_total=sum(totals.values()))
    for kind, total in totals.items():
        gate.expect(f"All view for “{word}” reaches every {kind}", total, len(seen[kind]))


def _one_type(gate: Gate, word: str, first: dict) -> None:
    groups = sorted(first.get("groups", []), key=lambda g: g["total"], reverse=True)
    if not groups:
        gate.expect(f"“{word}” finds records", ">0", 0, ok=False)
        return
    kind, total = groups[0]["type"], groups[0]["total"]
    seen: set[str] = set()
    page = 0
    while len(seen) < total and page < PAGE_CAP:
        page += 1
        response = _unified(gate, word, limit=20, page=page, kind=kind)
        items = [i for g in response.get("groups", []) if g["type"] == kind for i in g.get("items", [])]
        before = len(seen)
        seen.update(_key(i) for i in items if i.get("id"))
        if len(seen) == before:
            break
    gate.count(type_query=word, type=kind, type_pages=page, type_total=total)
    gate.expect(f"Show all {total} {kind} for “{word}” reaches the last", total, len(seen))


def _quick(gate: Gate) -> None:
    first = gate.get_json("/api/search?page=1&pageSize=8")
    totals = first.get("totals") or {}
    gate.count(quick_everything_total=first.get("total", 0))
    gate.expect("Everything with nothing typed lists records", True, first.get("total", 0) > 0)
    for kind in QUICK_TYPES:
        total = int(totals.get(kind) or 0)
        if not total:
            continue
        seen: set[str] = set()
        page = 0
        while len(seen) < total and page < PAGE_CAP:
            page += 1
            body = gate.get_json(f"/api/search?types={kind}&page={page}&pageSize=20")
            rows = body.get(kind) or []
            before = len(seen)
            seen.update(str(r.get("id")) for r in rows if r.get("id"))
            if len(seen) == before:
                break
        gate.expect(f"quick search Show all {total} {kind} reaches the last", total, len(seen))


def run(gate: Gate) -> None:
    gate.sign_in()
    word, first = _busiest_word(gate)
    gate.save("all-first-page.json", json.dumps(first, indent=1).encode("utf-8"))
    gate.expect("the All view answers groups with items", True, any(g.get("items") for g in first.get("groups", [])))
    gate.expect("the All view answers a page number", 1, first.get("page"))
    _all_view(gate, word, first)
    _one_type(gate, word, first)
    _quick(gate)
