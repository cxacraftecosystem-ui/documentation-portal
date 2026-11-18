from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import io
import json
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import cross_portal, field_voices_portal, naming
from app.services.field_voices import (
    contract,
    images,
    items,
    ledger,
    pages,
    people,
    scrub,
    transcripts,
)
from app.services.field_voices.sources import Sources, iso_moment, ist_day
from app.services.field_voices.stages import StagePlacement

REPO = Path(__file__).resolve().parents[2]
VECTORS = json.loads((REPO / "shared" / "field-voices-scrub-vectors.json").read_text(encoding="utf-8"))
GOLDEN = json.loads((REPO / "shared" / "naming-golden.json").read_text(encoding="utf-8"))
TOKEN = "t" * 40


def _held(spec: dict[str, Any]) -> scrub.HeldBack:
    return scrub.held_back(numbers=spec.get("numbers", []), emails=spec.get("emails", []))


@pytest.mark.parametrize("case", VECTORS["scrub"], ids=lambda c: c["name"])
def test_transcript_text_is_scrubbed_as_the_vectors_say(case: dict[str, Any]) -> None:
    result = scrub.scrub(case["text"], _held(case["held"]))
    assert result.text == case["expected"]
    assert result.counts == case["counts"]


@pytest.mark.parametrize("case", VECTORS["screen"], ids=lambda c: c["name"])
def test_labels_are_scrubbed_like_text_and_nulled_only_when_nothing_is_left(case: dict[str, Any]) -> None:
    assert scrub.label(case["label"], _held(case["held"])) == case["expected"]


def test_the_vectors_name_only_the_legal_floor_kinds() -> None:
    assert scrub.KINDS == ("EMAIL", "NUMBER")
    assert all(set(case["counts"]) == set(scrub.KINDS) for case in VECTORS["scrub"])
    assert all(set(case["held"]) <= {"numbers", "emails"} for case in VECTORS["scrub"] + VECTORS["screen"])


def test_a_label_counts_what_it_scrubbed_and_names_what_it_nulled() -> None:
    labels = items.Labels(scrub.held_back(numbers=["RJ4512"]))
    assert labels.screen("caption", "Block from Bagru, card RJ4512") == "Block from Bagru, card [number]"
    assert labels.screen("people[0].name", "9829012345") is None
    assert labels.screen("session.title", "  ") is None
    assert labels.counts == {"EMAIL": 0, "NUMBER": 2}
    assert labels.nulled == ["people[0].name"]


def test_the_scrub_patterns_are_kalavigyans_in_its_order() -> None:
    assert list(scrub.PERSONAL_DATA) == [
        "email address",
        "spoken email address",
        "Aadhaar-like number",
        "phone-like number",
        "PAN-like code",
        "voter ID-like code",
        "IFSC-like code",
    ]
    assert scrub.PERSONAL_DATA["email address"].pattern == r"[^\s@]+@[^\s@]+\.[^\s@]+"
    assert scrub.PERSONAL_DATA["phone-like number"].pattern == r"(?<!\d)(?:\+?\d[ -]?){10,}(?!\d)"
    assert scrub.PERSONAL_DATA["Aadhaar-like number"].pattern == r"(?<!\d)\d{4}[ .-]?\d{4}[ .-]?\d{4}(?!\d)"
    assert scrub.PERSONAL_DATA["PAN-like code"].pattern == r"(?<![A-Za-z0-9])[A-Za-z]{5}\d{4}[A-Za-z](?![A-Za-z0-9])"
    assert scrub.PERSONAL_DATA["voter ID-like code"].pattern == r"(?<![A-Za-z0-9])[A-Za-z]{3}\d{7}(?![A-Za-z0-9])"
    assert scrub.PERSONAL_DATA["IFSC-like code"].pattern == r"(?<![A-Za-z0-9])[A-Za-z]{4}0[A-Za-z0-9]{6}(?![A-Za-z0-9])"
    assert set(scrub.PATTERN_KINDS) == set(scrub.PERSONAL_DATA)


@pytest.mark.parametrize("case", VECTORS["identityImage"], ids=lambda c: c["name"])
def test_the_legal_floor_image_pattern_holds_the_vectors(case: dict[str, Any]) -> None:
    assert scrub.identity_image(case["text"]) is case["identity"]


def test_scrubbing_drops_nul_characters() -> None:
    assert scrub.scrub("a\x00b", scrub.HeldBack()).text == "ab"


@pytest.mark.parametrize(
    ("caption", "filename", "identity"),
    [
        ("Aadhar card front", None, True),
        (None, "IMG_voter_id.jpg", True),
        ("Signature of the artisan", None, True),
        ("Consent form", None, True),
        ("Pehchan card", None, True),
        ("Bank passbook first page", None, True),
        ("Bank account details", None, True),
        ("Cancelled cheque", None, True),
        ("UPI QR for payment", None, True),
        ("Thumb impression on the register", None, True),
        ("Fingerprint scan", None, True),
        ("Indigo dabu table runner", "IMG_0001.jpg", False),
        ("Portrait of Ismail at his workshop", None, False),
        ("Dyeing cloth at the riverbank", None, False),
        ("Rupinder carving a block", None, False),
        ("Opening ceremony with the officials", None, False),
    ],
)
def test_identity_documents_signatures_and_bank_details_are_the_images_withheld(
    caption: str | None, filename: str | None, identity: bool
) -> None:
    assert scrub.identity_image(caption, filename) is identity


@pytest.mark.parametrize(
    "key",
    [
        "phone", "phoneCountryCode", "Email", "e_mail", "lat", "lng", "village", "placeName", "place",
        "pincode", "aadhaarLast4", "pehchanCardNumber", "dateOfBirth", "gender", "age", "city", "venue",
        "clusterName", "ifsc", "upi", "otp", "bankAccount", "passwordHash", "signatureMediaId",
        "empanelmentNo", "addressLine", "subjectLatitude", "whatsapp", "mobile", "gps", "accuracy",
    ],
)
def test_every_never_sent_key_is_refused_wherever_it_sits(key: str) -> None:
    payload = {"changes": [{"people": [{"area": {key: "x"}}]}]}
    assert contract.forbidden_keys(payload) == [f"changes[0].people[0].area.{key}"]
    with pytest.raises(contract.NeverSent):
        contract.assert_sendable(payload)


def _transcript_item() -> dict[str, Any]:
    area = {"state": "Gujarat", "district": "Kachchh", "stateLgd": 24, "districtLgd": 449}
    return {
        "state": "LIVE", "id": "cm1", "revision": "0" * 32, "updatedAt": "2026-11-17T12:00:00.000001Z",
        "recordingId": "cmr", "mediaType": "AUDIO", "recordedAt": "2026-03-14T10:22:00+05:30", "durationMs": 1,
        "crafts": [{"id": "c", "slug": "Kutch-Ajrakh", "code": "ajrakh", "name": "Ajrakh Print of Kutch"}],
        "workshop": {"kind": "WORKSHOP", "id": "w", "code": None, "title": None, "startsOn": "2026-03-12",
                     "endsOn": None, "area": area},
        "session": {"id": "s", "title": None, "date": "2026-03-14", "purpose": "FIRST_SITTING",
                    "questionnaire": {"id": "q", "title": "Craft documentation questionnaire"}},
        "topics": [{"sectionId": "x", "sectionCode": "C", "sectionTitle": "Process", "questionId": "y",
                    "questionCode": "C-Q03", "question": "How?"}],
        "stage": {"key": "SKETCH_REVIEW", "title": "Sketch Review", "entity": "sketchReview",
                  "entityLabel": "Sketch reviews", "rowId": "r", "rowLabel": None, "field": "voiceFeedback",
                  "fieldLabel": "Voice feedback"},
        "record": {"kind": "PRODUCT", "id": "p", "name": "Ajrakh stole", "localName": None},
        "area": area,
        "people": [{"id": "a", "kind": "ARTISAN", "name": "N", "localName": None, "yearsInCraft": 31,
                    "area": area, "samePersonIn": [{"portal": "DESIGNER", "id": "b"}], "mergedIds": []}],
        "speakers": [{"key": "speaker_0", "label": "N", "role": "ARTISAN", "personId": "a"}],
        "transcript": {"source": "TURNS", "version": 2, "languages": ["gu"], "detectedLanguage": "gu",
                       "segments": [{"id": "t", "position": 0, "speakerKey": "speaker_0", "startMs": 0,
                                     "endMs": 1, "language": "gu", "text": "x", "textSource": "EDITED",
                                     "english": "x", "englishSource": "ORIGINAL", "scrubbed": ["NUMBER"]}],
                       "english": None},
        "scrubbing": items.scrubbing_block({"NUMBER": 2}, ["session.title"]),
    }


def _image_item() -> dict[str, Any]:
    return {
        "state": "LIVE", "id": "cmi", "revision": "0" * 32, "updatedAt": "2026-11-17T12:00:01.000003Z",
        "takenAt": "2026-06-24T11:40:00+05:30", "caption": "Second prototype", "artisanLinked": True,
        "crafts": [{"id": "c", "slug": "Bagru-Hand-Block-Print", "code": None, "name": "Bagru Hand Block Print"}],
        "belongsTo": [
            {"kind": "DESIGN_STAGE", "stage": {"key": "PROTOTYPE_DEVELOPMENT", "title": "Prototype Development"},
             "entity": {"key": "prototype", "label": "Prototypes"}, "row": {"id": "cme", "label": None},
             "field": {"key": "prototypePhotos", "label": "Prototype photographs"}, "prototypeId": "cme"},
            {"kind": "PRODUCT", "id": "p", "name": None, "localName": None, "productType": "FINISHED_GOOD"},
            {"kind": "TOOL", "id": "t", "name": "Block", "localName": None, "englishName": None},
            {"kind": "PROCESS", "id": "pr", "name": "Dabu", "productId": "p"},
            {"kind": "PROCESS_STEP", "id": "st", "processId": "pr", "process": "Dabu", "name": "Mud", "order": 1},
            {"kind": "CRAFT"},
            {"kind": "ARTISAN", "id": "a", "name": "Ismail", "localName": None},
            {"kind": "SESSION", "id": "s", "name": "Interview at Dhamadka"},
            {"kind": "WORKSHOP", "id": "w", "name": "Ajrakh documentation visit"},
            {"kind": "DESIGN_WORKSHOP", "id": "d", "name": "Hand block printing design workshop"},
            {"kind": "DESIGNER", "id": "u", "name": "Meera"},
        ],
        "workshop": None,
        "people": [],
        "credits": [{"personId": "a", "as": "ARTISAN"}, {"personId": "u", "as": "DESIGNER"}],
        "permission": {"statement": contract.PERMISSION_STATEMENT, "rightsHolder": "IIT", "licence": None},
        "image": {"url": "https://x/field-voices/a.jpg", "expiresAt": "2026-11-18T02:15:03Z",
                  "mimeType": "image/jpeg", "width": 2400, "height": 1600, "bytes": 1, "sha256": "a"},
        "scrubbing": items.scrubbing_block({}, []),
    }


def test_the_contract_shapes_carry_no_never_sent_key() -> None:
    assert contract.forbidden_keys(_transcript_item()) == []
    assert contract.forbidden_keys(_image_item()) == []


def test_replacement_counts_are_a_list_because_their_kinds_are_never_sent_keys() -> None:
    assert contract.forbidden_keys({"replaced": {"EMAIL": 0, "NUMBER": 2, "PLACE": 0}}) == [
        "replaced.EMAIL",
        "replaced.PLACE",
    ]
    assert items.scrubbing_block({"NUMBER": 2}, ["b", "a", "a"]) == {
        "version": 2,
        "replaced": [{"kind": "EMAIL", "count": 0}, {"kind": "NUMBER", "count": 2}],
        "nulled": ["a", "b"],
    }
    assert items.scrubbing_block({"NUMBER": 2}, [], {"NUMBER": 1, "EMAIL": 1})["replaced"] == [
        {"kind": "EMAIL", "count": 1}, {"kind": "NUMBER", "count": 3},
    ]


def test_revision_is_the_sha256_of_the_sorted_compact_item_without_the_volatile_fields() -> None:
    item = {"state": "LIVE", "revision": "x", "updatedAt": "y", "id": "cm1", "b": [1, 2], "a": "é",
            "image": {"url": "u", "expiresAt": "e", "sha256": "s"}}
    expected = hashlib.sha256('{"a":"é","b":[1,2],"id":"cm1","image":{"sha256":"s"}}'.encode()).hexdigest()[:32]
    assert ledger.revision(item) == expected
    assert ledger.revision({**item, "state": "GONE", "updatedAt": "z", "image": {"sha256": "s", "url": "v"}}) == expected


def test_digest_is_one_sorted_line_per_live_item() -> None:
    expected = hashlib.sha256(b"a 1\nb 2\n").hexdigest()
    assert ledger.digest([("b", "2"), ("a", "1")]) == expected
    assert ledger.digest([]) == hashlib.sha256(b"").hexdigest()


def test_a_cursor_is_signed_base64url_json_and_round_trips() -> None:
    cursor = ledger.encode_cursor(TOKEN, stream="images", view="all", stamp="2026-11-17T12:00:00.000001Z", item_id="cm9")
    body, mac = cursor.rsplit(".", 1)
    raw = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))
    assert json.loads(raw) == {"s": "images", "v": "all", "u": "2026-11-17T12:00:00.000001Z", "i": "cm9"}
    expected = hmac.new(TOKEN.encode(), ("field-voices-cursor\n" + body).encode(), hashlib.sha256).hexdigest()[:32]
    assert mac == expected
    assert ledger.decode_cursor(TOKEN, cursor, stream="images", view="all") == ("2026-11-17T12:00:00.000001Z", "cm9")


@pytest.mark.parametrize(
    "mutate",
    [
        lambda c: c[:-1] + ("0" if c[-1] != "0" else "1"),
        lambda c: "x" + c,
        lambda c: c.split(".")[0],
        lambda c: "",
        lambda c: c + "a" * 600,
    ],
)
def test_a_cursor_the_portal_did_not_issue_is_refused(mutate: Any) -> None:
    cursor = ledger.encode_cursor(TOKEN, stream="transcripts", view="all", stamp="2026-11-17T12:00:00.000001Z", item_id="a")
    with pytest.raises(ledger.UnknownCursor):
        ledger.decode_cursor(TOKEN, mutate(cursor), stream="transcripts", view="all")


def test_a_cursor_for_another_stream_view_or_token_is_refused() -> None:
    cursor = ledger.encode_cursor(TOKEN, stream="transcripts", view="all", stamp="2026-11-17T12:00:00.000001Z", item_id="a")
    for stream, view, token in (("images", "all", TOKEN), ("transcripts", "gone", TOKEN), ("transcripts", "all", "u" * 40)):
        with pytest.raises(ledger.UnknownCursor):
            ledger.decode_cursor(token, cursor, stream=stream, view=view)


def test_views() -> None:
    assert pages.views_for(None) == ("all", ["LIVE", "GONE"])
    assert pages.views_for("gone") == ("gone", ["GONE"])
    with pytest.raises(pages.BadView):
        pages.views_for("live")


def _row(i: int, state: str = "LIVE", payload: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "itemId": f"cm{i:03d}", "state": state, "revision": "r" * 32 if state == "LIVE" else None,
        "reason": None if state == "LIVE" else "DELETED", "successorId": None,
        "payload": json.dumps(payload or {"id": f"cm{i:03d}", "text": "x"}) if state == "LIVE" else None,
        "copyKey": None, "stamp": f"2026-11-17T12:00:00.{i:06d}Z",
    }


def test_a_live_change_is_the_payload_with_its_stamp_and_a_gone_change_is_a_tombstone() -> None:
    live = pages.change_of(_row(1), image_url=None)
    assert live == {"state": "LIVE", "id": "cm001", "revision": "r" * 32, "updatedAt": "2026-11-17T12:00:00.000001Z", "text": "x"}
    gone = pages.change_of({**_row(2, "GONE"), "reason": "REPLACED", "successorId": "cm9"}, image_url=None)
    assert gone == {"state": "GONE", "id": "cm002", "updatedAt": "2026-11-17T12:00:00.000002Z", "reason": "REPLACED", "successorId": "cm9"}


def test_a_transcript_page_holds_at_most_twenty_changes() -> None:
    changes, more = pages.assemble_page([_row(i) for i in range(21)], stream="transcripts", image_url=None)
    assert [c["id"] for c in changes] == [f"cm{i:03d}" for i in range(20)]
    assert more is True
    changes, more = pages.assemble_page([_row(i) for i in range(20)], stream="transcripts", image_url=None)
    assert (len(changes), more) == (20, False)


def test_a_transcript_page_stops_before_its_byte_budget_but_holds_one_change(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setitem(contract.PAGE_BYTES, "transcripts", 300)
    rows = [_row(i, payload={"id": f"cm{i:03d}", "text": "y" * 150}) for i in range(3)]
    changes, more = pages.assemble_page(rows, stream="transcripts", image_url=None)
    assert ([c["id"] for c in changes], more) == (["cm000"], True)
    monkeypatch.setitem(contract.PAGE_BYTES, "transcripts", 10)
    changes, more = pages.assemble_page(rows[:1], stream="transcripts", image_url=None)
    assert ([c["id"] for c in changes], more) == (["cm000"], False)


def test_an_image_page_holds_a_hundred_and_signs_each_url() -> None:
    meta = {"mimeType": "image/jpeg", "width": 2, "height": 1, "bytes": 3, "sha256": "ab" * 32}
    rows = [{**_row(i, payload={"id": f"cm{i:03d}", "image": meta}), "copyKey": f"field-voices/{'ab' * 32}.jpg"} for i in range(101)]
    seen: list[str] = []

    def url(key: str, image: dict[str, Any]) -> tuple[str, str]:
        seen.append(key)
        return f"https://bucket.example/{key}?sig", "2026-11-18T02:15:03Z"

    changes, more = pages.assemble_page(rows, stream="images", image_url=url)
    assert (len(changes), more) == (100, True)
    assert changes[0]["image"] == {**meta, "url": f"https://bucket.example/field-voices/{'ab' * 32}.jpg?sig", "expiresAt": "2026-11-18T02:15:03Z"}
    assert len(seen) == 100


class _Client:
    def __init__(self, rows: list[dict[str, Any]]) -> None:
        self.rows = rows
        self.calls: list[tuple[Any, ...]] = []

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        self.calls.append((sql, *args))
        return self.rows


def test_a_page_echoes_its_stream_view_and_cursor_and_names_the_next() -> None:
    client = _Client([_row(1), _row(2)])
    body = asyncio.run(pages.page(client, portal="DESIGNER", token=TOKEN, stream="transcripts", view=None, cursor=None))
    assert (body["schemaVersion"], body["portal"], body["stream"], body["view"], body["cursor"]) == (2, "DESIGNER", "transcripts", "all", None)
    assert body["hasMore"] is False
    assert ledger.decode_cursor(TOKEN, body["nextCursor"], stream="transcripts", view="all") == ("2026-11-17T12:00:00.000002Z", "cm002")
    _sql, stream, states, after, item, limit = client.calls[0]
    assert (stream, states, after, item, limit) == ("transcripts", ["LIVE", "GONE"], None, "", 21)
    again = asyncio.run(pages.page(_Client([]), portal="DESIGNER", token=TOKEN, stream="transcripts", view="all", cursor=body["nextCursor"]))
    assert (again["cursor"], again["nextCursor"], again["changes"], again["hasMore"]) == (body["nextCursor"], body["nextCursor"], [], False)


def test_a_page_with_a_never_sent_key_is_refused_before_it_is_signed() -> None:
    client = _Client([_row(1, payload={"id": "cm001", "village": "x"})])
    with pytest.raises(contract.NeverSent):
        asyncio.run(pages.page(client, portal="DESIGNER", token=TOKEN, stream="transcripts", view=None, cursor=None))


def test_the_rate_window_admits_six_hundred_calls_in_ten_minutes() -> None:
    now = [0.0]
    window = pages.RateWindow(clock=lambda: now[0])
    assert all(window.admit("kalavigyan-field-voices") is None for _ in range(600))
    assert window.admit("kalavigyan-field-voices") == 600
    assert window.admit("someone-else") is None
    now[0] = 599.0
    assert window.admit("kalavigyan-field-voices") == 1
    now[0] = 600.0
    assert window.admit("kalavigyan-field-voices") is None


def test_the_presigned_url_expires_half_a_window_after_its_reuse_ends(monkeypatch: pytest.MonkeyPatch) -> None:
    from datetime import UTC, datetime

    from app.services import s3

    asked: dict[str, Any] = {}

    def aged(key: str, **kwargs: Any) -> tuple[str, int]:
        asked.update(kwargs, key=key)
        return "https://bucket/field-voices/ab.jpg?X-Amz-Expires=900", 300

    monkeypatch.setattr(s3, "presign_get_url_aged", aged)
    now = datetime(2026, 11, 18, 2, 0, 0, tzinfo=UTC)
    url, expires = pages.presigned("field-voices/ab.jpg", {"mimeType": "image/jpeg"}, now=now)
    assert url.startswith("https://bucket/field-voices/ab.jpg")
    assert expires == "2026-11-18T02:12:30Z"
    assert asked == {"key": "field-voices/ab.jpg", "filename": "ab.jpg", "mime_type": "image/jpeg", "expires_in": 900, "disposition": "inline"}


@pytest.mark.parametrize("row", GOLDEN["fieldVoiceKeys"])
def test_the_copy_key_is_the_one_naming_exception(row: dict[str, Any]) -> None:
    assert naming.field_voice_copy_key(row["input"]["sha256"], row["input"]["ext"]) == row["key"]


@pytest.mark.parametrize("row", GOLDEN["fieldVoiceKeyRefusals"])
def test_a_copy_key_names_nothing_but_its_own_sha256(row: dict[str, Any]) -> None:
    with pytest.raises(ValueError):
        naming.field_voice_copy_key(row["sha256"], row["ext"])


def _jpeg_with_metadata() -> bytes:
    from PIL import Image

    image = Image.new("RGB", (3000, 1000), (200, 30, 30))
    exif = Image.Exif()
    exif[0x0112] = 6
    exif[0x010F] = "Camera Maker"
    exif[0x8825] = {1: "N", 2: (26.0, 55.0, 12.0)}
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", exif=exif, icc_profile=b"\x00" * 128, comment=b"Bagru 302012")
    return buffer.getvalue()


def test_the_neutral_copy_is_upright_small_and_carries_no_metadata() -> None:
    from PIL import Image

    made = images.neutral_copy(_jpeg_with_metadata())
    assert (made.ext, made.mime_type) == ("jpg", "image/jpeg")
    assert (made.width, made.height) == (800, 2400)
    assert len(made.data) <= images.MAX_COPY_BYTES
    assert made.sha256 == hashlib.sha256(made.data).hexdigest()
    with Image.open(io.BytesIO(made.data)) as copy:
        assert copy.size == (800, 2400)
        assert not copy.getexif()
        assert "icc_profile" not in copy.info
        assert "exif" not in copy.info
        assert "comment" not in copy.info
        assert not any(key.lower().startswith(("xmp", "iptc")) for key in copy.info)
    assert b"Camera Maker" not in made.data and b"Bagru" not in made.data
    assert made.meta() == {"mimeType": "image/jpeg", "width": 800, "height": 2400, "bytes": len(made.data), "sha256": made.sha256}


def test_a_transparent_source_stays_png_without_text_chunks() -> None:
    from PIL import Image, PngImagePlugin

    image = Image.new("RGBA", (40, 20), (0, 0, 0, 0))
    info = PngImagePlugin.PngInfo()
    info.add_text("Author", "Ramesh")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", pnginfo=info)
    made = images.neutral_copy(buffer.getvalue())
    assert (made.ext, made.mime_type, made.width, made.height) == ("png", "image/png", 40, 20)
    assert b"Ramesh" not in made.data


def test_an_opaque_png_becomes_a_jpeg() -> None:
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGBA", (10, 10), (1, 2, 3, 255)).save(buffer, format="PNG")
    assert images.neutral_copy(buffer.getvalue()).ext == "jpg"


def test_a_source_pillow_cannot_open_or_too_large_is_not_listed(monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(images.Undecodable):
        images.neutral_copy(b"not an image")
    monkeypatch.setattr(images, "MAX_SOURCE_PIXELS", 100)
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (20, 20)).save(buffer, format="JPEG")
    with pytest.raises(images.Undecodable):
        images.neutral_copy(buffer.getvalue())


def test_the_copier_stores_once_per_original_and_stops_at_its_budget() -> None:
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (30, 30), (5, 5, 5)).save(buffer, format="JPEG")
    stored: list[str] = []
    copier = images.Copier(budget=1, fetch=lambda key: buffer.getvalue(), store=lambda key, data, mime: stored.append(key))
    first = {"id": "m1", "objectKey": "media/u/x/a.jpg", "contentSha256": "f" * 64, "sizeBytes": "9"}
    key, meta = asyncio.run(copier.copy(first))
    assert key == f"field-voices/{meta['sha256']}.jpg" and stored == [key]
    assert asyncio.run(copier.copy(dict(first, id="m2"))) == (key, meta)
    assert asyncio.run(copier.copy({**first, "objectKey": "media/u/y/b.jpg"})) == images.DEFERRED
    assert (copier.stored, copier.skipped, stored) == (1, 1, [key])
    bad = images.Copier(fetch=lambda key: b"nope", store=lambda *a: None)
    assert asyncio.run(bad.copy(first)) == images.UNDECODABLE
    assert asyncio.run(bad.copy(first)) == images.UNDECODABLE
    assert bad.made == 1


def test_copy_of_names_the_original_and_its_content() -> None:
    assert images.copy_of({"objectKey": "media/u/x/a.jpg", "contentSha256": "abc", "sizeBytes": "9"}) == "media/u/x/a.jpg abc"
    assert images.copy_of({"objectKey": "media/u/x/a.jpg", "contentSha256": None, "sizeBytes": "9"}) == "media/u/x/a.jpg 9"


def test_images_with_the_same_verified_content_are_one_item_under_the_earliest() -> None:
    rows = [
        {"id": "b", "createdAt": "2026-01-02T00:00:00", "sha": "s"},
        {"id": "a", "createdAt": "2026-01-03T00:00:00", "sha": "s"},
        {"id": "c", "createdAt": "2026-01-01T00:00:00", "sha": None},
    ]
    assert images.groups(rows) == [["b", "a"], ["c"]]


@pytest.mark.parametrize(
    ("value", "tag"),
    [("hin", "hi"), ("guj", "gu"), ("gu", "gu"), ("en-IN", "en"), ("multi", "und"), ("", "und"), (None, "und"),
     ("mni", "mni"), ("??", "und")],
)
def test_language_tags(value: Any, tag: str) -> None:
    assert transcripts.language_tag(value) == tag


def test_a_long_turn_is_cut_at_sentence_ends() -> None:
    sentence = "a" * 9_000 + ". "
    parts = transcripts._split_long(sentence * 3)
    assert all(len(p) <= transcripts.MAX_SEGMENT_CHARACTERS for p in parts)
    assert len(parts) == 2
    assert transcripts._split_long("short") == ["short"]


def _directory() -> people.Directory:
    directory = people.Directory(portal="DESIGNER", survivors={"old": "a1"})
    directory.artisans["a1"] = people.ArtisanFacts(
        id="a1", name="Ismail Khatri", local_name=None, craft_id="c1", state="Gujarat", district="Kachchh",
        craft_start="1995-03-01T00:00:00+00:00", experience_years=None,
        numbers=("9876501234",), emails=("ismail@example.net",),
    )
    directory.users["u1"] = people.UserFacts(id="u1", name="Researcher One", local_name=None, role="RESEARCHER")
    return directory


def test_turns_become_segments_with_their_sources_times_and_scrubbing() -> None:
    rows = transcripts.TranscriptRows()
    rows.versions["m1"] = {"id": "v1", "version": 2, "languages": ["gu", "hin"], "detectedLanguage": "guj", "durationMs": 30_000}
    rows.turns["m1"] = [
        {"id": "t0", "position": 0, "speakerKey": "speaker_1", "startMs": 0, "endMs": 6100, "language": "hi",
         "machineText": "machine", "editedText": "How do you prepare it?", "machineEnglish": None,
         "editedEnglish": None, "translationStatus": "ALREADY_ENGLISH"},
        {"id": "t1", "position": 1, "speakerKey": "speaker_0", "startMs": 6400, "endMs": 40_000, "language": "gu",
         "machineText": "Call 9876501234 in Dhamadka", "editedText": None,
         "machineEnglish": "Call 9876501234 in Dhamadka", "editedEnglish": None, "translationStatus": "TRANSLATED"},
        {"id": "t2", "position": 2, "speakerKey": "speaker_2", "startMs": 5, "endMs": 5, "language": "gu",
         "machineText": "ok", "editedText": None, "machineEnglish": "ok", "editedEnglish": None,
         "translationStatus": "PENDING"},
    ]
    rows.speakers["m1"] = {
        "speaker_0": {"speakerKey": "speaker_0", "name": "Ismail", "role": "ARTISAN", "personId": "old"},
        "speaker_1": {"speakerKey": "speaker_1", "name": "Researcher One", "role": "RESEARCHER", "personId": "u1"},
    }
    directory = _directory()
    held = scrub.held_back(numbers=["9876501234"])
    labels = items.Labels(held)
    found = transcripts._turns_transcript("m1", rows, 30_000, directory, held, labels)
    assert (found.source, found.version, found.languages, found.detected) == ("TURNS", 2, ["gu", "hi"], "gu")
    first, second, third = found.segments
    assert (first["text"], first["textSource"], first["english"], first["englishSource"]) == ("How do you prepare it?", "EDITED", "How do you prepare it?", "ORIGINAL")
    assert (second["text"], second["textSource"], second["english"], second["englishSource"]) == ("Call [number] in Dhamadka", "MACHINE", "Call [number] in Dhamadka", "MACHINE")
    assert second["scrubbed"] == ["NUMBER"] and (second["startMs"], second["endMs"]) == (6400, 30_000)
    assert (third["startMs"], third["endMs"], third["english"], third["englishSource"]) == (None, None, None, None)
    assert found.counts == {"EMAIL": 0, "NUMBER": 2}
    assert found.speakers == [
        {"key": "speaker_1", "label": "Researcher One", "role": "RESEARCHER", "personId": "u1"},
        {"key": "speaker_0", "label": "Ismail", "role": "ARTISAN", "personId": "a1"},
        {"key": "speaker_2", "label": "Speaker 3", "role": None, "personId": None},
    ]


def test_a_markdown_transcript_has_lettered_speakers_and_untimed_segments() -> None:
    media = {"id": "m2", "transcriptText": "**Interviewer:** How long?\n\n**Ismail:** Thirty years.", "transcriptLanguage": "gu", "transcriptEditedAt": None}
    rows = transcripts.TranscriptRows()
    rows.insights["m2"] = {"translationStatus": "COMPLETED", "translationText": "Thirty years.", "editedAt": "2026-01-01"}
    held = scrub.HeldBack()
    found = transcripts._text_transcript(media, rows, held, items.Labels(held))
    assert [s["id"] for s in found.segments] == ["m2.0", "m2.1"]
    assert [s["speakerKey"] for s in found.segments] == ["L1", "L2"]
    assert all(s["startMs"] is None and s["textSource"] == "MACHINE" for s in found.segments)
    assert [s["label"] for s in found.speakers] == ["Interviewer", "Ismail"]
    assert found.english == {"text": "Thirty years.", "source": "EDITED"}


def test_a_typed_transcript_is_plain_paragraphs_without_speakers() -> None:
    found = transcripts._typed_transcript("m3", "First line.\n\nSecond line.", scrub.HeldBack())
    assert [(s["id"], s["text"], s["textSource"], s["speakerKey"]) for s in found.segments] == [
        ("m3.0", "First line.", "TYPED", None),
        ("m3.1", "Second line.", "TYPED", None),
    ]
    assert (found.languages, found.speakers) == (["und"], [])


def _session_take(sources: Sources, media_id: str, text: str, **media: Any) -> None:
    sources.media[media_id] = {
        "id": media_id, "mediaType": "AUDIO", "status": "SUBMITTED", "removedAt": None,
        "transcriptText": text, "transcriptLanguage": "gu", "transcriptEditedAt": None, "linkedRecordType": None,
        "linkedRecordId": None, "craftId": "c3", "artisanId": None, "workshopId": None, "productId": None,
        "toolId": None, "questionnaireInterviewId": "i1", "recordedAt": "2026-03-14T04:52:00+00:00",
        "recordedTimezone": None, **media,
    }
    sources.sessions["i1"] = {"id": "i1", "title": "Interview_2026-03-14_Dhamadka-Kachchh_Ajrakh",
                              "sessionDate": "2026-03-14", "workshopId": None, "questionnaireId": "f1",
                              "questionnaireTitle": "Craft documentation", "deletedAt": None, "mergedIntoId": None,
                              "state": "Gujarat", "district": "Kachchh"}


def test_village_and_place_names_stay_in_the_text_and_the_titles_while_numbers_go() -> None:
    sources = _sources()
    _session_take(sources, "m5", "**Ismail:** My cousin from Khavda brings card PEH-77-31. We dye at Ludiya, 370510.")
    sources.session_artisans = {"i1": ["a1", "p1"]}
    directory = _directory()
    directory.artisans["p1"] = people.ArtisanFacts(
        id="p1", name="Participant of Khavda", local_name=None, craft_id=None, state="Gujarat", district="Kachchh",
        craft_start=None, experience_years=None, numbers=("PEH-77-31",), emails=(),
    )
    batch = transcripts.Batch(sources, transcripts.TranscriptRows(), directory, {}, {}, set())
    outcome = transcripts.build(batch, "m5")
    assert outcome.payload is not None
    segments = outcome.payload["transcript"]["segments"]
    assert [s["text"] for s in segments] == ["My cousin from Khavda brings card [number]. We dye at Ludiya, 370510."]
    assert segments[0]["scrubbed"] == ["NUMBER"]
    assert outcome.payload["session"]["title"] == "Interview_2026-03-14_Dhamadka-Kachchh_Ajrakh"
    assert outcome.payload["scrubbing"] == {
        "version": 2, "replaced": [{"kind": "EMAIL", "count": 0}, {"kind": "NUMBER", "count": 1}], "nulled": [],
    }
    assert outcome.payload["people"] == []


def test_a_take_that_answers_an_identity_question_is_listed_with_its_numbers_scrubbed() -> None:
    sources = _sources()
    _session_take(sources, "m6", "**Ismail:** My number is 98765 01234 and my Aadhaar is 1234 5678 9012.")
    rows = transcripts.TranscriptRows()
    rows.recordings["m6"] = {"id": "r6", "mediaFileId": "m6", "capturedAt": "2026-03-14T04:52:00+00:00",
                             "durationMs": 40_000, "removedAt": None, "successor": None}
    rows.targets["r6"] = [{"recordingId": "r6", "sectionId": "s9", "questionId": "q9", "sectionCode": "RESP",
                           "sectionTitle": "Respondent Information", "prompt": "Your phone number",
                           "displayCode": "RESP-Q08", "questionCode": "CTW2.RESP.08"}]
    rows.subjects["r6"] = ["a1"]
    batch = transcripts.Batch(sources, rows, _directory(), {}, {}, set())
    outcome = transcripts.build(batch, "m6")
    assert outcome.payload is not None, outcome
    assert [s["text"] for s in outcome.payload["transcript"]["segments"]] == [
        "My number is [number] and my Aadhaar is [number]."
    ]
    assert outcome.payload["topics"][0]["question"] == "Your phone number"
    assert outcome.payload["topics"][0]["sectionCode"] == "RESP"
    assert [p["name"] for p in outcome.payload["people"]] == ["Ismail Khatri"]
    assert outcome.payload["people"][0]["craft"] == {"id": "c2", "slug": "New", "code": "new", "name": "New"}


def test_whole_session_takes_dictation_and_consent_clips_are_listed() -> None:
    sources = _sources()
    _session_take(sources, "m7", "**A:** The whole sitting, name, village and all.")
    sources.media["m8"] = {**sources.media["m7"], "id": "m8", "linkedRecordType": "dictation", "linkedRecordId": "x",
                           "questionnaireInterviewId": None, "transcriptText": "**A:** dictated words"}
    sources.media["m9"] = {**sources.media["m8"], "id": "m9", "linkedRecordType": None, "linkedRecordId": None,
                           "transcriptText": "**A:** I agree to the recording"}
    sources.evidence = {"m9"}
    batch = transcripts.Batch(sources, transcripts.TranscriptRows(), _directory(), {}, {}, set())
    for media_id in ("m7", "m8", "m9"):
        assert transcripts.build(batch, media_id).live, media_id


def test_a_take_filed_on_a_product_and_a_tool_holds_back_the_records_artisans_numbers() -> None:
    sources = _sources()
    _session_take(sources, "m10", "**A:** The tool maker's card is TL-88-12 and the printer's is PR-71-45.",
                  questionnaireInterviewId=None, productId="p1", toolId="t1")
    directory = _directory()
    for artisan_id, number in (("a2", "TL-88-12"), ("a3", "PR-71-45")):
        directory.artisans[artisan_id] = people.ArtisanFacts(
            id=artisan_id, name=f"Artisan {artisan_id}", local_name=None, craft_id=None, state="Gujarat",
            district="Kachchh", craft_start=None, experience_years=None, numbers=(number,), emails=(),
        )
    rows = transcripts.TranscriptRows()
    facts = transcripts._facts(sources, rows, "m10", {})
    assert transcripts._linked_artisans(sources, rows, facts) == ([], ["a1", "a2", "a3"])
    batch = transcripts.Batch(sources, rows, directory, {}, {}, set())
    outcome = transcripts.build(batch, "m10")
    assert outcome.payload is not None, outcome
    assert [s["text"] for s in outcome.payload["transcript"]["segments"]] == [
        "The tool maker's card is [number] and the printer's is [number]."
    ]
    assert outcome.payload["record"]["kind"] == "PRODUCT"
    subjects = transcripts.subjects_of(sources, rows, "m10", {}, directory)
    assert {"a1", "a2", "a3"} <= subjects.artisans


def _sources() -> Sources:
    found = Sources()
    found.crafts = {
        "c1": {"id": "c1", "slug": "Old", "craftCode": None, "name": "Old", "localName": None, "extraMetadata": None, "mergedIntoId": "c2"},
        "c2": {"id": "c2", "slug": "New", "craftCode": "new", "name": "New", "localName": None, "extraMetadata": None, "mergedIntoId": None},
        "c3": {"id": "c3", "slug": "Other", "craftCode": None, "name": "Other", "localName": None, "extraMetadata": None, "mergedIntoId": None},
    }
    found.products = {"p1": {"id": "p1", "productName": "Stole", "localName": None, "productType": "FINISHED_GOOD", "craftId": "c3", "artisanId": "a1", "workshopId": None}}
    found.tools = {"t1": {"id": "t1", "toolkitName": "Block", "localName": None, "englishName": None, "craftId": None, "artisanId": "a2", "workshopId": None}}
    found.tool_artisans = {"t1": ["a3"]}
    found.tool_crafts = {"t1": ["c1"]}
    return found


def test_crafts_follow_merges_and_take_the_first_tier_that_has_any() -> None:
    sources = _sources()
    assert sources.craft_end("c1") == "c2"
    assert items.crafts_for(sources, own=[None], records=["c1", "c3"], context=[], artisans=["c3"]) == ["c2", "c3"]
    assert items.crafts_for(sources, own=["c1"], records=["c3"], context=[], artisans=[]) == ["c2"]
    assert items.crafts_for(sources, own=[], records=[], context=[["c1", "c3"], ["c3"]], artisans=["c1"]) == ["c3"]
    assert items.crafts_for(sources, own=[], records=[], context=[["c1", "c3"]], artisans=["c1"]) == ["c2"]
    assert items.crafts_for(sources, own=["missing"], records=[], context=[], artisans=[]) == []


def _placement(field: str, *, listed: bool, number: int = 13) -> StagePlacement:
    return StagePlacement(
        media_id="m", design_workshop_id="d", stage_key="PROTOTYPE_DEVELOPMENT", stage_title="Prototype Development",
        stage_number=number, entity_key="prototype", entity_title="Prototypes", row_id="r", row_label="Runner",
        field_key=field, field_label="Prototype photographs", listed=listed, artisan_ids=("a9",),
    )


def test_image_placements_and_the_never_rules() -> None:
    sources = _sources()
    product_photo = {"id": "m", "productId": "p1", "toolId": None, "linkedRecordType": None, "linkedRecordId": None, "craftId": None, "artisanId": "a1"}
    staged = _placement("prototypePhotos", listed=True)
    found = images.own_placements(sources, product_photo, [staged])
    assert [(p.kind, p.record_id) for p in found] == [("PRODUCT", "p1"), ("DESIGN_STAGE", "r")]
    craft_only = {"id": "m3", "productId": None, "toolId": None, "linkedRecordType": None, "linkedRecordId": None, "craftId": "c3", "artisanId": None}
    assert [p.kind for p in images.own_placements(sources, craft_only, [])] == ["CRAFT"]
    assert images.never_listed(sources, product_photo, [staged], set()) is False
    assert images.never_listed(sources, product_photo, [staged, _placement("designerSignature", listed=False)], set()) is True
    assert images.never_listed(sources, {**product_photo, "extraMetadata": {"identityPhotograph": {"decision": "STORE"}}}, [], set()) is True
    assert images.never_listed(sources, product_photo, [], {"m"}) is True
    assert images.never_listed(sources, {**product_photo, "caption": "PAN card"}, [], set()) is True
    sources.evidence = {"m"}
    assert images.never_listed(sources, product_photo, [], set()) is True


def _participant_photo(media_id: str, caption: str | None = None) -> StagePlacement:
    return StagePlacement(
        media_id=media_id, design_workshop_id="d", stage_key="WORKSHOP_PLAN_PARTICIPANTS_OPENING",
        stage_title="Opening", stage_number=3, entity_key="participant", entity_title="Participants", row_id="r",
        row_label="Ramesh", field_key="photo", field_label="Photograph", listed=True, caption=caption,
        artisan_ids=("a1",),
    )


def test_a_participant_photo_read_as_a_card_and_not_yet_decided_is_never_listed() -> None:
    sources = _sources()
    card = {**_filed("m1"), "caption": None, "originalFilename": "IMG_2041.jpg",
            "extraMetadata": {"identityPhotograph": {"decision": "PENDING", "readById": "u1"}}}
    assert images.never_listed(sources, card, [_participant_photo("m1")], set()) is True
    portrait = {**card, "extraMetadata": None}
    assert images.never_listed(sources, portrait, [_participant_photo("m1")], set()) is False


def test_a_stage_caption_naming_an_identity_document_withholds_the_image() -> None:
    sources = _sources()
    sources.design_workshops = {"d": {"id": "d", "title": "Design workshop", "deletedAt": None, "craftId": "c3"}}
    sources.media["m1"] = {**_filed("m1"), "mediaType": "IMAGE", "status": "SUBMITTED", "removedAt": None,
                           "caption": None, "recordedAt": None, "createdAt": None, "extraMetadata": None,
                           "originalFilename": "IMG_2041.jpg"}
    staged = _participant_photo("m1", "Ramesh Aadhaar card front")
    assert images.never_listed(sources, sources.media["m1"], [staged], set()) is True
    batch = _image_batch(sources, _directory(), placements={"m1": [staged]})
    outcome, media = images.build(batch, images._group(batch, ["m1"]))
    assert media is None and outcome.payload is None
    assert (outcome.reason, outcome.withheld) == (contract.UNLISTED, items.WITHHELD_IDENTITY)
    plain = _participant_photo("m1", "Ramesh at the opening")
    batch = _image_batch(sources, _directory(), placements={"m1": [plain]})
    outcome, _media = images.build(batch, images._group(batch, ["m1"]))
    assert outcome.payload is not None and outcome.payload["caption"] == "Ramesh at the opening"


def _filed(media_id: str, **links: Any) -> dict[str, Any]:
    base = {"id": media_id, "productId": None, "toolId": None, "linkedRecordType": None, "linkedRecordId": None,
            "craftId": None, "artisanId": None, "workshopId": None, "questionnaireInterviewId": None}
    return {**base, **links}


def test_an_image_filed_on_a_person_a_session_or_a_workshop_is_placed_there() -> None:
    sources = _sources()
    sources.sessions = {
        "i1": {"id": "i1", "title": "Sitting at Dhamadka", "workshopId": "w1", "deletedAt": None, "mergedIntoId": "i2"},
        "i2": {"id": "i2", "title": "Merged sitting", "workshopId": "w1", "deletedAt": None, "mergedIntoId": None},
        "i3": {"id": "i3", "title": "Deleted sitting", "workshopId": None, "deletedAt": "2026-01-01", "mergedIntoId": None},
    }
    sources.session_artisans = {"i2": ["a1", "a4"]}
    sources.workshops = {"w1": {"id": "w1", "title": "Ajrakh documentation visit"}}
    sources.design_workshops = {"d1": {"id": "d1", "title": "Design workshop", "deletedAt": None}}
    sources.media_design_workshops = {"m5": "d1"}

    def kinds(media: dict[str, Any], photos: dict[str, str] | None = None) -> list[tuple[str, str | None]]:
        return [(p.kind, p.record_id) for p in images.own_placements(sources, media, [], photos)]

    assert kinds(_filed("m1", linkedRecordType="artisan", linkedRecordId="a1", artisanId="a1", craftId="c3")) == [("ARTISAN", "a1")]
    assert kinds(_filed("m2", questionnaireInterviewId="i1", artisanId="a1")) == [("ARTISAN", "a1"), ("SESSION", "i2")]
    assert kinds(_filed("m3", workshopId="w1")) == [("WORKSHOP", "w1")]
    assert kinds(_filed("m4", linkedRecordType="workshop", linkedRecordId="w1")) == [("WORKSHOP", "w1")]
    assert kinds(_filed("m5")) == [("DESIGN_WORKSHOP", "d1")]
    assert kinds(_filed("m6"), {"m6": "u1"}) == [("DESIGNER", "u1")]
    assert kinds(_filed("m7", questionnaireInterviewId="i3", craftId="c3")) == []
    assert kinds(_filed("m8", productId="p1", artisanId="a1")) == [("PRODUCT", "p1")]
    session = images.Placement("SESSION", "i2")
    assert images.placement_artisans(sources, session) == ["a1", "a4"]
    assert images.placement_workshops(sources, session) == ["w1"]
    assert images.placement_artisans(sources, images.Placement("ARTISAN", "a1")) == ["a1"]


def test_an_image_on_an_artisan_or_a_participant_field_is_marked_artisan_linked_whatever_else_it_links_to() -> None:
    assert images.artisan_linked(_filed("m1", artisanId="a1"), []) is True
    assert images.artisan_linked(_filed("m2", productId="p1", artisanId="a1"), []) is True
    assert images.artisan_linked(_filed("m3", linkedRecordType="Artisan", linkedRecordId="a1"), []) is True
    participant = StagePlacement(
        media_id="m4", design_workshop_id="d", stage_key="WORKSHOP_PLAN_PARTICIPANTS_OPENING", stage_title="Opening",
        stage_number=3, entity_key="participant", entity_title="Participants", row_id="r", row_label=None,
        field_key="photo", field_label="Photograph", listed=True,
    )
    assert images.artisan_linked(_filed("m4"), [participant]) is True
    assert images.artisan_linked(_filed("m7", questionnaireInterviewId="i1", productId="p1"), []) is True
    assert images.artisan_linked(_filed("m8", linkedRecordType="questionnaire_session", linkedRecordId="i1"), []) is True
    assert images.artisan_linked(_filed("m5", productId="p1"), [_placement("prototypePhotos", listed=True)]) is False
    assert images.artisan_linked(_filed("m6", craftId="c3"), []) is False


def _image_batch(sources: Sources, directory: people.Directory, **over: Any) -> images.ImageBatch:
    base: dict[str, Any] = {"placements": {}, "designers": {}, "never_media": set(), "refused": set(),
                            "rights_holder": "IIT", "designer_photos": {}}
    base.update(over)
    return images.ImageBatch(sources, directory, **base)


def test_a_portrait_comes_across_credited_to_the_person_it_is_filed_on() -> None:
    sources = _sources()
    sources.media["m1"] = {**_filed("m1", artisanId="old", linkedRecordType="artisan", linkedRecordId="old"),
                           "mediaType": "IMAGE", "status": "SUBMITTED", "removedAt": None,
                           "caption": "Ismail at home in Dhamadka, call 9876501234", "recordedAt": None,
                           "createdAt": "2026-03-14T04:52:00+00:00", "extraMetadata": None, "originalFilename": "IMG_1.jpg"}
    batch = _image_batch(sources, _directory())
    group = images._group(batch, ["m1"])
    outcome, media = images.build(batch, group)
    assert media is not None and outcome.payload is not None
    payload = outcome.payload
    assert payload["caption"] == "Ismail at home in Dhamadka, call [number]"
    assert payload["artisanLinked"] is True
    assert payload["belongsTo"] == [{"kind": "ARTISAN", "id": "a1", "name": "Ismail Khatri", "localName": None}]
    assert payload["credits"] == [{"personId": "a1", "as": "ARTISAN"}]
    assert [p["id"] for p in payload["people"]] == ["a1"]
    assert payload["people"][0]["craft"] == {"id": "c2", "slug": "New", "code": "new", "name": "New"}
    assert payload["crafts"][0]["id"] == "c2"
    assert payload["scrubbing"]["replaced"] == [{"kind": "EMAIL", "count": 0}, {"kind": "NUMBER", "count": 1}]
    assert contract.forbidden_keys(payload) == []


def test_a_designers_profile_photo_comes_across_credited_to_the_designer() -> None:
    sources = _sources()
    sources.media["m2"] = {**_filed("m2", craftId="c3"), "mediaType": "IMAGE", "status": "SUBMITTED",
                           "removedAt": None, "caption": None, "recordedAt": None, "createdAt": None,
                           "extraMetadata": None, "originalFilename": "photo.jpg"}
    directory = _directory()
    directory.users["u2"] = people.UserFacts(id="u2", name="Meera Rao", local_name=None, role="DESIGNER")
    batch = _image_batch(sources, directory, designer_photos={"m2": "u2"})
    outcome, _media = images.build(batch, images._group(batch, ["m2"]))
    assert outcome.payload is not None
    assert outcome.payload["belongsTo"] == [{"kind": "DESIGNER", "id": "u2", "name": "Meera Rao"}]
    assert outcome.payload["artisanLinked"] is False
    assert outcome.payload["credits"] == [{"personId": "u2", "as": "DESIGNER"}]
    assert [(p["id"], p["kind"], p["craft"]) for p in outcome.payload["people"]] == [("u2", "DESIGNER", None)]


def test_image_people_come_from_the_records_and_stage_rows() -> None:
    sources = _sources()
    tool = images.Placement("TOOL", "t1")
    assert images.placement_artisans(sources, tool) == ["a2", "a3"]
    assert images.placement_artisans(sources, images.Placement("PRODUCT", "p1")) == ["a1"]
    assert images.placement_artisans(sources, images.Placement("DESIGN_STAGE", "r", _placement("prototypePhotos", listed=True))) == ["a9"]
    assert images.placement_crafts(sources, tool) == [None, "c1"]
    assert _placement("x", listed=True, number=10).credits_designers is True
    assert _placement("x", listed=True, number=17).credits_designers is True
    assert _placement("x", listed=True, number=18).credits_designers is False
    assert _placement("x", listed=True, number=9).credits_designers is False


def test_a_person_is_credited_by_the_name_the_portal_records() -> None:
    directory = _directory()
    labels = items.Labels(scrub.held_back(numbers=["9876501234"]))
    person = people.artisan_person(directory, "a1", on=ist_day("2026-03-14"), labels=labels, path="people[0]")
    assert person["name"] == "Ismail Khatri"
    assert person["yearsInCraft"] == 31
    assert person["mergedIds"] == ["old"]
    assert person["samePersonIn"] == []
    assert person["area"] is not None and person["area"]["state"] == "Gujarat"
    assert set(person) == {"id", "kind", "craft", "name", "localName", "yearsInCraft", "area", "samePersonIn", "mergedIds"}
    assert person["craft"] is None
    renamed = people.Directory(portal="FIELD_REPOSITORY")
    renamed.artisans["a1"] = people.ArtisanFacts(id="a1", name="Ramesh of Dhamadka 9876501234", local_name=None, craft_id=None, state=None, district=None, craft_start=None, experience_years=40, remote_ids=("r1",))
    other = people.artisan_person(renamed, "a1", on=None, labels=labels, path="people[1]")
    assert (other["name"], other["yearsInCraft"], other["area"]) == ("Ramesh of Dhamadka [number]", 40, None)
    assert other["samePersonIn"] == [{"portal": "DESIGNER", "id": "r1"}]
    assert labels.nulled == []


def test_the_directory_reads_no_place_finer_than_district_and_no_date_of_birth() -> None:
    for column in ("dateOfBirth", "village", "placeName", "pincode", '"place"', '"address"'):
        assert column not in people.ARTISAN_SQL
    assert not hasattr(people, "under_age")


def test_designers_are_only_a_designer_portal_kind() -> None:
    directory = people.Directory(portal="DESIGNER")
    directory.users["u"] = people.UserFacts(id="u", name="D", local_name=None, role="RESEARCHER")
    directory.users["v"] = people.UserFacts(id="v", name="E", local_name=None, role="DESIGNER")
    assert people.user_kind(directory, "u", ["u"]) == "DESIGNER"
    assert people.user_kind(directory, "v", []) == "DESIGNER"
    assert people.user_kind(directory, "u", []) == "RESEARCHER"
    directory.portal = "FIELD_REPOSITORY"
    assert people.user_kind(directory, "v", ["v"]) == "RESEARCHER"


def test_an_unknown_district_is_sent_as_null() -> None:
    found = people.area("Rajasthan", "No Such District")
    assert found is not None and found["state"] == "Rajasthan" and found["district"] is None and found["districtLgd"] is None
    assert people.area(None, "Jaipur") is None


def test_moments_keep_their_offset_and_days_are_india_days() -> None:
    assert iso_moment("2026-03-14T04:52:00+00:00", "Asia/Kolkata") == "2026-03-14T10:22:00+05:30"
    assert iso_moment(None) is None
    assert str(ist_day("2026-03-13T20:00:00+00:00")) == "2026-03-14"


def test_the_ledger_writes_only_what_changed() -> None:
    live = items.Outcome(item_id="a", payload={"id": "a", "x": 1})
    rev = ledger.revision(live.payload or {})
    assert ledger.planned(live, None) == ("LIVE", rev)
    assert ledger.planned(live, {"state": "LIVE", "revision": rev, "copyKey": None, "copyOf": None}) is None
    assert ledger.planned(live, {"state": "GONE", "revision": None}) == ("LIVE", rev)
    gone = items.Outcome(item_id="a", reason="WITHDRAWN")
    assert ledger.planned(gone, None) is None
    assert ledger.planned(gone, {"state": "GONE"}) is None
    assert ledger.planned(gone, {"state": "LIVE"}) == ("GONE", "WITHDRAWN")
    assert ledger.planned(items.Outcome(item_id="a", withheld="deferred"), {"state": "LIVE"}) is None


def test_a_tombstone_written_after_the_batch_was_read_is_never_turned_back_live() -> None:
    live = items.Outcome(item_id="a", payload={"id": "a", "x": 1})
    rev = ledger.revision(live.payload or {})
    since = "2026-11-18T10:00:00.000500Z"
    newer = {"state": "GONE", "revision": None, "stamp": "2026-11-18T10:00:00.000501Z"}
    older = {"state": "GONE", "revision": None, "stamp": "2026-11-18T09:59:59.999999Z"}
    assert ledger.planned(live, newer, since=since) is None
    assert ledger.planned(live, older, since=since) == ("LIVE", rev)
    assert ledger.planned(live, newer) == ("LIVE", rev)
    assert ledger.planned(items.Outcome(item_id="a", reason="WITHDRAWN"), {**newer, "state": "LIVE"}, since=since) == ("GONE", "WITHDRAWN")


def test_a_changed_subject_set_alone_rewrites_the_subjects_and_not_the_stamp() -> None:
    subjects = items.Subjects(media={"a"}, artisans={"x", "y"}, workshops={"w"}, users=set()).stored()
    assert subjects == {"media": ["a"], "artisans": ["x", "y"], "workshops": ["w"], "users": []}
    assert items.Subjects.from_stored(subjects) == items.Subjects({"a"}, {"x", "y"}, {"w"}, set())
    assert items.Subjects.from_stored(None) == items.Subjects()
    live = items.Outcome(item_id="a", payload={"id": "a", "x": 1}, subjects=subjects)
    rev = ledger.revision(live.payload or {})
    same = {"state": "LIVE", "revision": rev, "copyKey": None, "copyOf": None, "subjects": subjects}
    assert ledger.planned(live, same) is None
    assert ledger.planned(live, {**same, "subjects": {**subjects, "artisans": ["x"]}}) == ("SUBJECTS",)
    assert ledger.planned(live, {**same, "subjects": None}) == ("SUBJECTS",)


def test_the_gone_pass_drops_an_image_once_every_record_it_belongs_to_is_deleted() -> None:
    from app.services.field_voices import gone

    image = {"belongsTo": [{"kind": "PRODUCT", "id": "p1"}, {"kind": "DESIGN_STAGE", "row": {"id": "r1"}}],
             "workshop": {"kind": "DESIGN_WORKSHOP", "id": "d1"}}
    alive = gone.GoneFacts(designs={"d1": {"id": "d1", "deletedAt": None}}, present={("PRODUCT", "p1")})
    assert gone._deleted("images", image, alive) is False
    product_gone = gone.GoneFacts(designs={"d1": {"id": "d1", "deletedAt": None}})
    assert gone._deleted("images", image, product_gone) is False
    assert gone._deleted("images", image, gone.GoneFacts(designs=product_gone.designs, rows_gone={"r1"})) is True
    assert gone._deleted("images", image, gone.GoneFacts()) is True
    assert gone._deleted("images", {"belongsTo": [{"kind": "CRAFT"}]}, gone.GoneFacts()) is False
    transcript = {"workshop": {"kind": "DESIGN_WORKSHOP", "id": "d1"}, "stage": None}
    assert gone._deleted("transcripts", transcript, alive) is False
    assert gone._deleted("transcripts", transcript, gone.GoneFacts()) is True
    staged = {"workshop": {"kind": "WORKSHOP", "id": "w1"}, "stage": {"rowId": "r1"}}
    assert gone._deleted("transcripts", staged, gone.GoneFacts(rows_gone={"r1"})) is True


def test_the_gone_pass_drops_an_image_filed_on_a_person_session_or_workshop_once_that_is_deleted() -> None:
    from app.services.field_voices import gone

    portrait = {"belongsTo": [{"kind": "ARTISAN", "id": "a1"}]}
    alive = gone.GoneFacts(resolved={"a1": "a1"}, artisans_present={"a1"})
    assert gone._deleted("images", portrait, alive) is False
    assert gone._deleted("images", portrait, gone.GoneFacts(resolved={"a1": "a1"})) is True
    merged = gone.GoneFacts(resolved={"a1": "a9"}, artisans_present={"a9"})
    assert gone._deleted("images", portrait, merged) is False
    session = {"belongsTo": [{"kind": "SESSION", "id": "i1"}]}
    assert gone._deleted("images", session, gone.GoneFacts()) is False
    assert gone._deleted("images", session, gone.GoneFacts(sessions_gone={"i1"})) is True
    workshop = {"belongsTo": [{"kind": "WORKSHOP", "id": "w1"}, {"kind": "DESIGNER", "id": "u1"}]}
    assert gone._deleted("images", workshop, gone.GoneFacts(present={("DESIGNER", "u1")})) is False
    assert gone._deleted("images", workshop, gone.GoneFacts()) is True
    design = {"belongsTo": [{"kind": "DESIGN_WORKSHOP", "id": "d1"}]}
    assert gone._deleted("images", design, gone.GoneFacts(designs={"d1": {"id": "d1", "deletedAt": None}})) is False
    assert gone._deleted("images", design, gone.GoneFacts(designs={"d1": {"id": "d1", "deletedAt": "x"}})) is True


def test_the_gone_pass_checks_the_stored_subjects_as_well_as_the_payload() -> None:
    from app.services.field_voices import gone

    payload = {"people": [{"id": "a1", "kind": "ARTISAN", "mergedIds": []}], "workshop": {"kind": "DESIGN_WORKSHOP", "id": "d1"}}
    stored = {"media": ["m1"], "artisans": ["p1"], "workshops": ["w1"], "users": ["u9"]}
    found = gone._subjects("m1", payload, stored, {"p1": "p2"})
    assert found == items.Subjects({"m1"}, {"a1", "p1", "p2"}, {"d1", "w1"}, {"u9"})


def test_terraform_grants_the_api_key_put_get_and_delete_on_the_copy_prefix_only() -> None:
    text = (REPO / "infra" / "terraform" / "main.tf").read_text(encoding="utf-8")
    match = re.search(r'Sid\s*=\s*"FieldVoiceCopies"(.*?)\n\s*\}', text, re.DOTALL)
    assert match, "no FieldVoiceCopies statement in infra/terraform/main.tf"
    body = match.group(1)
    assert f'"${{aws_s3_bucket.media.arn}}/{naming.FIELD_VOICE_PREFIX}/*"' in body
    assert all(f'"s3:{action}"' in body for action in ("PutObject", "GetObject", "DeleteObject"))
    assert "ListBucket" not in body and "s3:*" not in body
    assert naming.field_voice_copy_key("a" * 64, "jpg").startswith(f"{naming.FIELD_VOICE_PREFIX}/")


def test_stamps_carry_microseconds() -> None:
    from datetime import UTC, datetime

    assert ledger.stamp_text(datetime(2026, 11, 17, 12, 0, 0, 1, tzinfo=UTC)) == "2026-11-17T12:00:00.000001Z"


def test_the_gates_are_off_by_default_and_the_images_route_needs_a_rights_holder() -> None:
    from app.core.config import Settings

    assert Settings.model_fields["cross_portal_field_voices_enabled"].default is False
    assert Settings.model_fields["cross_portal_field_voices_enabled"].alias == "CROSS_PORTAL_FIELD_VOICES_ENABLED"
    assert Settings.model_fields["field_voices_rights_holder"].default is None
    assert Settings.model_fields["field_voices_rights_holder"].alias == "FIELD_VOICES_RIGHTS_HOLDER"
    on = SimpleNamespace(cross_portal_field_voices_enabled=True, cross_portal_token="t" * 32, field_voices_rights_holder=None)
    assert cross_portal.field_voices_active(on) is True
    assert cross_portal.field_voices_images_active(on) is False
    assert cross_portal.field_voices_images_active(SimpleNamespace(**{**vars(on), "field_voices_rights_holder": " IIT  Kharagpur "})) is True
    assert cross_portal.field_voices_rights_holder(SimpleNamespace(field_voices_rights_holder=" IIT  Kharagpur ")) == "IIT Kharagpur"
    assert cross_portal.field_voices_rights_holder(SimpleNamespace(field_voices_rights_holder="x" * 201)) is None
    assert cross_portal.field_voices_active(SimpleNamespace(**{**vars(on), "cross_portal_token": "t" * 31})) is False
    assert cross_portal.field_voices_active(SimpleNamespace(**{**vars(on), "cross_portal_field_voices_enabled": False})) is False
    assert cross_portal.active(on) is False
    assert cross_portal.craft_feed_active(on) is False


def test_an_answer_is_signed_the_way_kalavigyan_checks_it() -> None:
    body = b'{"schemaVersion":2}'
    nonce = "0123456789abcdef0123456789abcdef"
    path = "/api/cross-portal/peer/field-voices/transcripts"
    head = "\n".join(("GET", "/cross-portal/peer/field-voices/transcripts", nonce, "")).encode()
    expected = hmac.new(TOKEN.encode(), head + body, hashlib.sha256).hexdigest()
    assert cross_portal.sign(TOKEN, body, method="GET", path=path, nonce=nonce) == expected


def test_this_portal_names_itself() -> None:
    assert field_voices_portal.PORTAL in contract.PORTALS
    assert contract.other_portal(field_voices_portal.PORTAL) != field_voices_portal.PORTAL
