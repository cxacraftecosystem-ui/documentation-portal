from __future__ import annotations

import asyncio
from datetime import date
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from starlette.datastructures import QueryParams

from app.services.data_explorer import filters, peer


def _parse(text: str) -> filters.Filter:
    return filters.parse(QueryParams(text))


def test_csv_and_repeated_values_are_one_selection():
    flt = _parse("craftIds=a,b&craftIds=c&craftIds=a&mediaTypes=video,AUDIO&languages=HIN")
    assert flt.crafts == ("a", "b", "c")
    assert flt.media_types == ("VIDEO", "AUDIO")
    assert flt.languages == ("hin",)
    assert flt.active and flt.media_only


def test_absent_or_blank_parameters_are_no_filter():
    flt = _parse("workshopIds=&craftIds=,,&portals=")
    assert not flt.active
    assert flt.portals == ("this",)
    assert filters.media_where(flt) is None


@pytest.mark.parametrize(
    "text",
    [
        "mediaTypes=MOVIE",
        "statuses=DONE",
        "portals=elsewhere",
        "dateFrom=08-11-2026",
        "dateFrom=2026-11-09&dateTo=2026-11-01",
    ],
)
def test_bad_values_answer_422(text: str):
    with pytest.raises(HTTPException) as refused:
        _parse(text)
    assert refused.value.status_code == 422


def test_too_many_values_answer_422():
    many = ",".join(f"id{n}" for n in range(filters.VALUES_MAX + 1))
    with pytest.raises(HTTPException):
        _parse(f"artisanIds={many}")


def test_dates_are_whole_local_days():
    flt = _parse("dateFrom=2026-11-01&dateTo=2026-11-08")
    assert flt.date_from == date(2026, 11, 1) and flt.date_to == date(2026, 11, 8)
    low, high = filters.day_bounds(flt.date_from, flt.date_to)
    assert low is not None and high is not None
    assert low.isoformat() == "2026-11-01T00:00:00+05:30"
    assert high.isoformat() == "2026-11-09T00:00:00+05:30"
    where = filters.media_where(flt)
    assert where == {"recordedAt": {"gte": low, "lt": high}}


def test_every_dimension_is_anded_and_each_is_ored_within():
    flt = _parse(
        "workshopIds=w1&craftIds=c1,c2&artisanIds=a1&mediaTypes=IMAGE&statuses=APPROVED"
        "&recordedByIds=u1&languages=ben&dateFrom=2026-01-01"
    )
    where = filters.media_where(flt)
    assert where is not None and len(where["AND"]) == 8
    text = repr(where)
    for needle in ("'w1'", "'c1', 'c2'", "'a1'", "'IMAGE'", "'APPROVED'", "'u1'", "'ben'", "recordedAt"):
        assert needle in text


def test_narrow_adds_the_filter_to_media_only_and_keeps_the_scope_type():
    from dataclasses import dataclass

    @dataclass(frozen=True)
    class Scope:
        records: dict[str, Any]
        media: dict[str, Any]
        explorer_filter: Any = None

    base = Scope(records={"createdById": "me"}, media={"uploadedById": "me"})
    flt = _parse("statuses=APPROVED")
    narrowed = filters.narrow(base, flt)
    assert narrowed.records == base.records
    assert narrowed.media == {"AND": [{"uploadedById": "me"}, {"status": {"in": ["APPROVED"]}}]}
    assert narrowed.explorer_filter is flt
    assert filters.narrow(base, filters.NONE) is base


def _folder(path: str, record_type: str, count: int | None = None) -> dict[str, Any]:
    return {"kind": "folder", "path": path, "recordType": record_type, "count": count}


def test_prune_drops_unselected_record_folders_and_documents_when_excluded():
    flt = _parse("craftIds=c1&mediaTypes=VIDEO")
    entries = [
        _folder("by-workshop/w/crafts/c1", "craft"),
        _folder("by-workshop/w/crafts/c2", "craft"),
        _folder("by-workshop/w/crafts/_none", "category"),
        {"kind": "file", "path": "by-workshop/w/details.txt", "content": "Scheme: x"},
        {"kind": "file", "path": "by-workshop/w/v.mp4", "mediaId": "m1"},
    ]
    kept = [e["path"].rsplit("/", 1)[-1] for e in filters.prune(entries, flt)]
    assert kept == ["c1", "v.mp4"]
    keep_text = filters.prune(entries, _parse("craftIds=c1"))
    assert any(e["path"].endswith("details.txt") for e in keep_text)


def test_drop_empty_counts_hidden_and_keeps_a_selected_record_without_media_filters():
    entries = [
        _folder("by-workshop/w1", "workshop", 0),
        _folder("by-workshop/w2", "workshop", 3),
        _folder("by-workshop/w3", "workshop", None),
        _folder("by-workshop/x/products", "category", 0),
    ]
    kept, hidden = filters.drop_empty(entries, _parse("workshopIds=w1,w2,w3"))
    assert [e["path"] for e in kept] == ["by-workshop/w1", "by-workshop/w2", "by-workshop/w3"]
    assert hidden == 1
    kept, hidden = filters.drop_empty(entries, _parse("workshopIds=w1,w2,w3&mediaTypes=AUDIO"))
    assert [e["path"] for e in kept] == ["by-workshop/w2", "by-workshop/w3"]
    assert hidden == 2
    same, none_hidden = filters.drop_empty(entries, filters.NONE)
    assert same is entries and none_hidden == 0


def test_report_rows_follow_the_record_dimensions():
    artisans = [
        SimpleNamespace(id="a1", craftId="c1", workshopId="w1", workshops=[], status="APPROVED", designWorkshopId=None),
        SimpleNamespace(id="a2", craftId="c2", workshopId="w1", workshops=[], status="PENDING", designWorkshopId=None),
    ]
    products = [SimpleNamespace(id="p1", artisanId="a1", craftId="c1", workshopId="w1", status="APPROVED", designWorkshopId=None)]
    processes = [SimpleNamespace(id="r1", productId="p1"), SimpleNamespace(id="r2", productId="p9")]
    data = {"artisans": list(artisans), "products": products, "processes": processes, "media": []}
    out = filters.filter_report(data, _parse("craftIds=c1"))
    assert [a.id for a in out["artisans"]] == ["a1"]
    assert [p.id for p in out["processes"]] == ["r1"]
    out = filters.filter_report({"artisans": list(artisans)}, _parse("statuses=PENDING"))
    assert [a.id for a in out["artisans"]] == ["a2"]


def test_media_only_filters_keep_records_that_own_a_matching_file():
    artisans = [SimpleNamespace(id="a1", status="APPROVED"), SimpleNamespace(id="a2", status="APPROVED")]
    media = [SimpleNamespace(artisanId="a2", productId=None, toolId=None, questionnaireInterviewId=None, workshopId=None, craftId=None, linkedRecordType=None, linkedRecordId=None)]
    out = filters.filter_report({"artisans": artisans, "media": media}, _parse("mediaTypes=VIDEO"))
    assert [a.id for a in out["artisans"]] == ["a2"]


def test_browse_where_maps_each_dimension_onto_each_record_type():
    flt = _parse("craftIds=c1&artisanIds=a1&statuses=APPROVED")
    artisan = repr(filters.browse_where("artisan", flt))
    assert "craftId" in artisan and "'a1'" in artisan and "APPROVED" in artisan
    craft = repr(filters.browse_where("craft", flt))
    assert "'c1'" in craft and "status" not in craft
    assert filters.browse_where("media", flt) is None
    with_media = repr(filters.browse_where("product", _parse("mediaTypes=AUDIO")))
    assert "'media': {'some'" in with_media


def test_search_wheres_narrow_every_record_kind():
    wheres = {"artisans": {}, "workshops": {}, "products": {}, "tools": {}, "media": {"uploadedById": "me"}}
    out = filters.search_wheres(wheres, _parse("craftIds=c1"))
    assert all(out[k] for k in ("artisans", "workshops", "products", "tools"))
    assert out["media"]["AND"][0] == {"uploadedById": "me"}
    assert filters.search_wheres(wheres, filters.NONE) is wheres


def test_as_query_round_trips():
    text = "workshopIds=w1,w2&mediaTypes=IMAGE&dateFrom=2026-11-01&dateTo=2026-11-02"
    flt = _parse(text)
    assert filters.parse(QueryParams(filters.as_query(flt))) == flt


def test_facet_order_and_params_match_the_contract():
    import json
    from pathlib import Path

    contract = json.loads(
        (Path(__file__).resolve().parents[2] / "shared" / "data-explorer-contract.json").read_text(encoding="utf-8")
    )
    section = contract["filters"]
    assert tuple(section["facetOrder"]) == filters.FACET_ORDER
    for param, (facet, label) in filters.PARAMS.items():
        assert section["params"][param]["facet"] == facet
        assert section["params"][param]["label"] == label
    assert section["params"]["mediaTypes"]["values"] == list(filters.MEDIA_TYPES)
    assert section["params"]["statuses"]["values"] == list(filters.STATUSES)
    assert section["params"]["portals"]["values"] == list(filters.PORTALS)
    assert contract["limits"]["facetOptionsMax"] == filters.FACET_OPTIONS_MAX
    assert contract["limits"]["peerPageSizeMax"] == peer.PAGE_SIZE_MAX
    route = next(r for r in contract["routes"] if r["id"] == "peer")
    assert route["browseTypeToPeerType"] == peer.BROWSE_TO_PEER


def test_peer_rows_carry_the_portal_chip_and_the_remote_link():
    shaped = peer.row(
        {"type": "artisans", "id": "r1", "title": "Meera", "openUrl": "https://peer/artisans/r1"},
        "fieldRepository",
        "Field Repository",
        None,
    )
    assert shaped is not None
    assert shaped["portal"] == "fieldRepository" and shaped["portalLabel"] == "Field Repository"
    assert shaped["href"] == "/remote/artisans/r1" and shaped["local"] is False
    assert peer.row({"type": "nonsense", "id": "x"}, "fieldRepository", "FR", None) is None
    assert peer.not_applied(_parse("workshopIds=w&craftIds=c&statuses=APPROVED")) == ["workshopIds", "statuses"]


def _run(coro: Any) -> Any:
    return asyncio.run(coro)


def test_peer_explore_states(monkeypatch):
    user = SimpleNamespace(id="u1", email="u1@example.org", role="PROFESSOR")
    common = {"kind": "artisan", "q": None, "page": 1, "page_size": 20, "portal": "fieldRepository", "portal_label": "Field Repository"}
    off = _run(peer.explore(None, user, filters.NONE, allowed=True, active=False, **common))
    assert off["status"] == peer.STATUS_OFF and off["items"] == []
    refused = _run(peer.explore(None, user, filters.NONE, allowed=False, active=True, **common))
    assert refused["status"] == peer.STATUS_NOT_PERMITTED

    async def no_code(ids: tuple[str, ...]) -> list[str] | None:
        return None if ids else []

    monkeypatch.setattr(peer, "craft_codes", no_code)
    cut = _run(peer.explore(None, user, _parse("craftIds=c1"), allowed=True, active=True, **common))
    assert cut["status"] == peer.STATUS_FILTERED_OUT

    sent: dict[str, Any] = {}

    async def fake_call(settings: Any, method: str, path: str, **kw: Any) -> tuple[int, Any]:
        sent.update(kw["body"], path=path)
        return 200, {"items": [{"type": "artisans", "id": "r9", "title": "Meera"}], "total": 41}

    async def codes(ids: tuple[str, ...]) -> list[str]:
        return ["C-01"] if ids else []

    monkeypatch.setattr(peer, "craft_codes", codes)
    monkeypatch.setattr(peer, "peer_call", fake_call)
    answer = _run(peer.explore(None, user, _parse("craftIds=c1&dateFrom=2026-11-01"), allowed=True, active=True, **common))
    assert answer["status"] == peer.STATUS_OK
    assert answer["total"] == 41 and answer["pages"] == 3
    assert answer["items"][0]["href"] == "/remote/artisans/r9"
    assert sent["path"] == "/explore" and sent["type"] == "artisans"
    assert sent["filters"]["craftCodes"] == ["C-01"]
    assert sent["filters"]["dateFrom"].startswith("2026-11-01T00:00:00")
    assert sent["requester"]["email"] == "u1@example.org"


def test_stage_folders_are_counted_at_their_own_path_not_a_rebuilt_one(monkeypatch):
    from fastapi import HTTPException as Refused

    from app.services import transcript_export
    from app.services.data_explorer import counts

    root = "by-design-workshop/w1"
    listings = {
        f"{root}/stages/WORKSHOP_SETUP": [{"kind": "file", "name": "Workshop-Details.txt", "path": f"{root}/stages/WORKSHOP_SETUP/Workshop-Details.txt", "content": "Workshop id: w1"}],
        f"{root}/stages/WORKSHOP_PLAN": [
            {"kind": "file", "name": "Plan.txt", "path": f"{root}/stages/WORKSHOP_PLAN/Plan.txt", "content": "Plan: one"},
            {"kind": "file", "name": "Artisans.txt", "path": f"{root}/stages/WORKSHOP_PLAN/Artisans.txt", "content": "Artisans: two"},
        ],
    }

    async def list_level(path: str, _scope: Any) -> tuple[list[dict[str, Any]], None]:
        if path not in listings:
            raise Refused(status_code=404, detail="Unknown data path")
        return [dict(entry) for entry in listings[path]], None

    async def finalize(_files: Any, **_kw: Any) -> None:
        return None

    monkeypatch.setattr(counts, "_browser", lambda: SimpleNamespace(_list_level=list_level))
    monkeypatch.setattr(transcript_export, "finalize_manifest", finalize)
    entries = [
        {"kind": "folder", "name": "01 Workshop Setup", "path": f"{root}/stages/WORKSHOP_SETUP", "recordType": "designWorkshopStage"},
        {"kind": "folder", "name": "03 Plan", "path": f"{root}/stages/WORKSHOP_PLAN", "recordType": "designWorkshopStage"},
        {"kind": "file", "name": "details.txt", "path": f"{root}/details.txt", "content": "Workshop id: w1"},
    ]
    _run(counts.fill(root, entries, SimpleNamespace(media=None)))
    assert [entries[0]["count"], entries[1]["count"]] == [1, 2]
    assert entries[0]["bytes"] == len("Workshop id: w1")
