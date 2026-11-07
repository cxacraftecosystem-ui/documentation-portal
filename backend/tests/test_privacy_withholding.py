from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services import records


def _run(coro):
    return asyncio.run(coro)


def test_the_voice_isolated_key_is_withheld_with_the_recording():
    node = {
        "id": "m1",
        "mediaType": "AUDIO",
        "objectKey": "media/u1/aaaa/rec.m4a",
        "url": "https://example.invalid/media/u1/aaaa/rec.m4a",
        "cleanedObjectKey": "media/u1/bbbb/rec.mp3",
        "cleanedMimeType": "audio/mpeg",
        "uploadedById": "u1",
    }
    viewer = {"id": "u2", "role": "CROWDSOURCE_VOLUNTEER", "canDownloadDataset": False}
    out = records.public_encode(dict(node), viewer, media_urls={"u2"})
    assert "objectKey" not in out and "url" not in out
    assert "cleanedObjectKey" not in out
    kept = records.public_encode(dict(node), viewer, media_urls={"u1", "u2"})
    assert kept["cleanedObjectKey"] == "media/u1/bbbb/rec.mp3"


def test_edit_history_masks_pehchan_numbers_below_professor(monkeypatch):
    from app.api.routes import data_access

    rows = [{
        "id": "rv1", "recordType": "artisan", "recordId": "a1", "editedById": "owner1",
        "changes": {"pehchanCardNumber": {"old": "PC11112222", "new": "PC33334444"}},
        "editedBy": {"id": "owner1", "role": "CROWDSOURCE_VOLUNTEER"},
    }]

    async def owner(*_a, **_k):
        return "owner1"

    async def reviews(*_a, **_k):
        return True

    async def find_many(**_k):
        return [json.loads(json.dumps(r)) for r in rows]

    monkeypatch.setattr(data_access, "_resolve_record_owner", owner)
    monkeypatch.setattr(data_access, "_reviews_owner", reviews)
    monkeypatch.setattr(data_access, "db", SimpleNamespace(recordrevision=SimpleNamespace(find_many=find_many)))
    reviewer = SimpleNamespace(id="r1", role="FIELD_CONTRIBUTOR", canReview=False)
    out = _run(data_access.list_revisions(recordType="artisan", recordId="a1", current_user=reviewer))
    text = json.dumps(out)
    assert "PC11112222" not in text and "PC33334444" not in text
    assert out[0]["changes"]["pehchanCardNumber"]["new"].endswith("4444")
    professor = SimpleNamespace(id="p1", role="PROFESSOR", canReview=True)
    seen = _run(data_access.list_revisions(recordType="artisan", recordId="a1", current_user=professor))
    assert seen[0]["changes"]["pehchanCardNumber"]["new"] == "PC33334444"


class _MediaRows:
    def __init__(self, rows):
        self.rows = rows

    @staticmethod
    def _match(row: dict[str, Any], where: dict[str, Any]) -> bool:
        for key, want in where.items():
            have = row.get(key)
            if isinstance(want, dict) and "not" in want:
                if have == want["not"]:
                    return False
            elif have != want:
                return False
        return True

    async def find_first(self, where, order=None):
        hits = [r for r in self.rows if self._match(r, where)]
        hits.sort(key=lambda r: r["createdAt"])
        return SimpleNamespace(**hits[0]) if hits else None


def _media(mid: str, **extra: Any) -> dict[str, Any]:
    now = datetime(2026, 11, 7, 9, 0, tzinfo=UTC)
    return {
        "id": mid, "contentSha256": "a" * 64, "shaVerifiedAt": now, "createdAt": now,
        "transcriptStatus": "COMPLETED", "transcriptText": "MACHINE TEXT", "removedAt": None,
        "transcriptEditedAt": None, "transcriptEditedById": None, **extra,
    }


@pytest.mark.parametrize(
    "falsified",
    [
        pytest.param({"transcriptEditedAt": datetime(2026, 11, 6, tzinfo=UTC), "transcriptEditedById": "v"}, id="hand-edited"),
        pytest.param({"removedAt": datetime(2026, 11, 7, tzinfo=UTC)}, id="admin-removed"),
    ],
)
def test_dedupe_never_copies_an_edited_or_removed_transcript(falsified):
    from app.services import transcription_enqueue

    earlier = datetime(2026, 11, 6, 9, 0, tzinfo=UTC)
    source = _media("m1", transcriptText="FALSE TEXT", createdAt=earlier, uploadedById="v", **falsified)
    target = _media("m2", uploadedById="r")
    twin = _run(transcription_enqueue._verified_twin(SimpleNamespace(**target), SimpleNamespace(mediafile=_MediaRows([source, target]))))
    assert twin is None


def test_dedupe_still_reuses_a_machine_transcript():
    from app.services import transcription_enqueue

    source = _media("m1", createdAt=datetime(2026, 11, 6, tzinfo=UTC) - timedelta(hours=1))
    target = _media("m2")
    twin = _run(transcription_enqueue._verified_twin(SimpleNamespace(**target), SimpleNamespace(mediafile=_MediaRows([source, target]))))
    assert twin is not None and twin.id == "m1"


class _CardEvents:
    def __init__(self):
        self.rows: list[dict[str, Any]] = []

    async def count(self, where):
        return sum(1 for r in self.rows if r["userId"] == where["userId"] and r["action"] == where["action"])

    async def create(self, data):
        self.rows.append(dict(data))
        return SimpleNamespace(**data)


def _verhoeff_complete(prefix11: str) -> str:
    from app.services.artisan_identity import verhoeff_ok

    return next(prefix11 + d for d in "0123456789" if verhoeff_ok(prefix11 + d))


def test_aadhaar_lookup_stops_answering_after_a_day_of_misses(monkeypatch):
    import app.core.db as core_db
    from app.api.routes import artisans as routes
    from app.services import aadhaar_fingerprint

    events = _CardEvents()
    target = _verhoeff_complete("23456789012")
    target_fp = aadhaar_fingerprint.fingerprint_of(target)

    async def find_first(where, include=None):
        if where == {aadhaar_fingerprint.FINGERPRINT_FIELD: target_fp}:
            return SimpleNamespace(id="artisan-x", name="Dummy", place="P", craft=None, workshop=None)
        return None

    async def no_legacy(_db):
        return False

    monkeypatch.setattr(core_db, "db", SimpleNamespace(artisancardevent=events))
    monkeypatch.setattr(routes, "db", SimpleNamespace(artisan=SimpleNamespace(find_first=find_first)))
    monkeypatch.setattr(aadhaar_fingerprint, "legacy_pending", no_legacy)
    volunteer = SimpleNamespace(id="v1", role="CROWDSOURCE_VOLUNTEER")
    misses = 0
    for n in range(100, 400):
        guess = _verhoeff_complete(f"3{n:010d}")
        try:
            out = _run(routes.lookup_artisan_by_aadhaar_body(routes.AadhaarLookup(number=guess), volunteer))
        except HTTPException:
            break
        assert out["found"] is False
        misses += 1
    assert misses <= 100
    with pytest.raises(HTTPException) as refused:
        _run(routes.lookup_artisan_by_aadhaar_body(routes.AadhaarLookup(number=target), volunteer))
    assert refused.value.status_code == 429


def test_duplicate_check_stops_confirming_pehchan_numbers_after_a_day_of_misses(monkeypatch):
    import app.core.db as core_db
    from app.api.routes import artisan_duplicates as routes
    from app.schemas.duplicates import DuplicateCheckIn
    from app.services import artisan_duplicates

    events = _CardEvents()
    stored = artisan_duplicates.Profile(
        id="artisan-a", name="Dummy Artisan", pehchanCardNumber="TESTPC123456",
        display={"id": "artisan-a", "name": "Dummy Artisan"},
    )

    async def prefilter(profile, **_k):
        return [stored] if profile.pehchanCardNumber == stored.pehchanCardNumber else []

    async def profile_from_form(**kw):
        return artisan_duplicates.Profile(name=kw.get("name") or "", pehchanCardNumber=kw.get("pehchanCardNumber"))

    async def read_all(*_a, **_k):
        return {}

    monkeypatch.setattr(core_db, "db", SimpleNamespace(artisancardevent=events))
    monkeypatch.setattr(artisan_duplicates, "prefilter", prefilter)
    monkeypatch.setattr(routes, "profile_from_form", profile_from_form)
    monkeypatch.setattr(routes, "_check_buckets", SimpleNamespace(take=lambda _k: (True, 0)))
    monkeypatch.setattr(records, "viewable_where", read_all)
    researcher = SimpleNamespace(id="r1", role="RESEARCHER")
    for n in range(150):
        _run(routes.check_duplicates(DuplicateCheckIn(name="zz", pehchanCardNumber=f"TESTPC{n:06d}"), researcher))
    out = _run(routes.check_duplicates(DuplicateCheckIn(name="zz", pehchanCardNumber="TESTPC123456"), researcher))
    assert not any(r["code"] == "PEHCHAN" for c in out["candidates"] for r in c["reasons"])
