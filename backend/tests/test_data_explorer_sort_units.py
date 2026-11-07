from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.services.data_explorer import browse, sorting, tree


def _media(**fields):
    base = {
        "recordedAt": None,
        "createdAt": None,
        "updatedAt": None,
        "mediaType": "IMAGE",
        "status": "PENDING",
        "workshopId": "w1",
        "artisanId": "a1",
        "craftId": "c1",
    }
    base.update(fields)
    return SimpleNamespace(**base)


def _file(name, ident, **media):
    kind = media.pop("kind", "IMAGE")
    size = media.pop("size", None)
    return {
        "name": name,
        "path": f"root/{name}",
        "kind": "file",
        "mediaId": ident,
        "mediaType": kind,
        "sizeBytes": size,
        "_media": _media(mediaType=kind, **media),
    }


def _folder(name, **dates):
    entry = {"name": name, "path": f"root/{name}", "kind": "folder", "recordType": "artisan"}
    entry.update(dates)
    return entry


def _day(n):
    return datetime(2026, 1, n, tzinfo=UTC)


def _names(entries):
    return [e["name"] for e in entries]


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, sorting.TREE_DEFAULT),
        ("", sorting.TREE_DEFAULT),
        ("name:desc", sorting.Sort("name", "desc")),
        ("recorded", sorting.Sort("recorded", "desc")),
        ("size:ASC", sorting.Sort("size", "asc")),
    ],
)
def test_parse_reads_key_and_direction(raw, expected):
    assert sorting.parse(raw, sorting.TREE_KEYS, sorting.TREE_DEFAULT) == expected


@pytest.mark.parametrize("raw", ["shoe:asc", "name:up", "relevance:desc", "folder:asc", "name;drop table", ":asc"])
def test_parse_refuses_anything_off_the_allow_list(raw):
    with pytest.raises(ValueError):
        sorting.parse(raw, sorting.TREE_KEYS, sorting.TREE_DEFAULT)


def test_relevance_sorts_descending_only():
    keys = ("relevance", "name")
    assert sorting.parse("relevance:desc", keys, sorting.Sort("relevance", "desc")).dir == "desc"
    with pytest.raises(ValueError):
        sorting.parse("relevance:asc", keys, sorting.Sort("relevance", "desc"))


def test_folders_stay_first_and_ties_break_by_name_then_id():
    entries = [
        _file("b.jpg", "m2", size=10),
        _folder("Zed"),
        _file("a.jpg", "m3", size=10),
        _file("A.jpg", "m1", size=10),
        _folder("amber"),
    ]
    ordered = sorting.order(entries, sorting.Sort("size", "desc"))
    assert _names(ordered) == ["amber", "Zed", "A.jpg", "a.jpg", "b.jpg"]
    assert [e["mediaId"] for e in ordered[2:4]] == ["m1", "m3"]


def test_missing_values_sort_last_in_either_direction():
    entries = [
        _file("none.jpg", "m1"),
        _file("old.jpg", "m2", recordedAt=_day(1)),
        _file("new.jpg", "m3", recordedAt=_day(9)),
    ]
    entries[0]["_media"] = None
    assert _names(sorting.order(entries, sorting.Sort("recorded", "desc"))) == ["new.jpg", "old.jpg", "none.jpg"]
    assert _names(sorting.order(entries, sorting.Sort("recorded", "asc"))) == ["old.jpg", "new.jpg", "none.jpg"]


def test_recorded_falls_back_to_created_and_folders_use_their_record_date():
    entries = [
        _file("uploaded.jpg", "m1", createdAt=_day(5)),
        _file("taken.jpg", "m2", recordedAt=_day(2), createdAt=_day(20)),
        _folder("Workshop B", **sorting.record_dates(SimpleNamespace(startDate=None, date=_day(3), createdAt=_day(30), updatedAt=None))),
        _folder("Workshop A", **sorting.record_dates(SimpleNamespace(startDate=_day(7), date=_day(1), createdAt=_day(1), updatedAt=None))),
    ]
    ordered = sorting.order(entries, sorting.Sort("recorded", "desc"))
    assert _names(ordered) == ["Workshop A", "Workshop B", "uploaded.jpg", "taken.jpg"]


def test_name_descending_reverses_the_case_folded_order():
    entries = [_file(n, f"m{i}") for i, n in enumerate(["beta", "Alpha", "gamma"])]
    assert _names(sorting.order(entries, sorting.Sort("name", "desc"))) == ["gamma", "beta", "Alpha"]


def test_type_size_status_and_facets_compare_their_own_values():
    entries = [
        _file("v", "m1", kind="VIDEO", size=5, status="APPROVED"),
        _file("a", "m2", kind="AUDIO", size=50, status="REJECTED"),
        _file("i", "m3", kind="IMAGE", size=1, status="DRAFT"),
    ]
    assert _names(sorting.order(entries, sorting.Sort("type", "asc"))) == ["a", "i", "v"]
    assert _names(sorting.order(entries, sorting.Sort("size", "asc"))) == ["i", "v", "a"]
    assert _names(sorting.order(entries, sorting.Sort("status", "desc"))) == ["a", "i", "v"]
    entries[0]["_facets"] = {"workshop": "Bagru", "artisan": "Zoya", "craft": None}
    entries[1]["_facets"] = {"workshop": "ajrakh", "artisan": "Meera", "craft": "Block print"}
    assert _names(sorting.order(entries, sorting.Sort("workshop", "asc"))) == ["a", "v", "i"]
    assert _names(sorting.order(entries, sorting.Sort("craft", "asc"))) == ["a", "i", "v"]


def test_paging_a_sorted_list_never_repeats_or_skips():
    entries = [_file(f"f{n:02d}", f"m{n:02d}", size=n % 4, recordedAt=_day(1 + n % 3)) for n in range(23)]
    for sort in (sorting.Sort("size", "desc"), sorting.Sort("recorded", "asc"), sorting.Sort("name", "desc")):
        whole = [e["mediaId"] for e in sorting.order(list(entries), sort)]
        paged: list[str] = []
        cursor = None
        for _ in range(10):
            page, total, cursor = asyncio.run(
                tree.shape([dict(e) for e in entries], lean=True, cursor=cursor, limit=5, sort=sort)
            )
            paged.extend(e["mediaId"] for e in page)
            assert total == 23
            if not cursor:
                break
        assert paged == whole


def test_available_offers_only_what_means_something_here():
    plain = [_file("a", "m1", kind="IMAGE", size=1, recordedAt=_day(1)), _file("b", "m2", kind="IMAGE", size=2)]
    assert sorting.available(plain, sorting.TREE_KEYS) == ["name", "recorded", "size"]
    mixed = [
        _file("a", "m1", kind="AUDIO", workshopId="w1", status="PENDING"),
        _file("b", "m2", kind="IMAGE", workshopId="w2", status="APPROVED"),
    ]
    assert sorting.available(mixed, sorting.TREE_KEYS) == ["name", "duration", "type", "workshop", "status"]
    assert "size" in sorting.available(plain[:0], sorting.TREE_KEYS, sort=sorting.Sort("size", "asc"))


def test_browse_orders_in_the_database_with_a_stable_tail():
    assert browse.order_for("artisan", sorting.BROWSE_DEFAULT) == [{"createdAt": "desc"}, {"id": "desc"}]
    assert browse.order_for("artisan", sorting.Sort("name", "asc")) == [{"name": "asc"}, {"id": "asc"}]
    assert browse.order_for("workshop", sorting.Sort("recorded", "desc")) == [
        {"date": "desc"},
        {"title": "asc"},
        {"id": "asc"},
    ]
    assert browse.order_for("tool", sorting.Sort("modified", "asc"))[0] == {"updatedAt": "asc"}
    assert browse.media_order(sorting.Sort("size", "desc")) == [
        {"sizeBytes": "desc"},
        {"originalFilename": "asc"},
        {"id": "asc"},
    ]


def test_every_route_key_is_a_known_key_with_a_default_direction():
    for keys in (sorting.TREE_KEYS, sorting.TRANSCRIPT_KEYS, sorting.BROWSE_KEYS, sorting.BROWSE_MEDIA_KEYS):
        assert set(keys) <= set(sorting.KEYS)
        assert all(key in sorting.DEFAULT_DIR for key in keys)
