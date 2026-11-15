from __future__ import annotations

import hashlib
import json
import math
import struct
import sys
import uuid
import urllib.error
import urllib.request
from datetime import UTC, date, datetime

API = "http://127.0.0.1:8000/api"

TYPED = {
    "C1": "Moved from Puri to Bhubaneswar for work",
    "D1": "Sandstone from Lalitgiri",
    "D2": "Bought through a trader",
    "D3": "Prices rose this year",
    "D4": "Khondalite for temple work",
    "D5": "Stored in the yard",
    "D6": "Quarry permits are slow",
    "E1": "Rough cutting takes about four days, then shaping with the chisel",
}


def call(token: str, method: str, path: str, body: object | None = None, key: str | None = None) -> object:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(f"{API}{path}", data=data, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Content-Type", "application/json")
    request.add_header("X-App-Platform", "web")
    request.add_header("X-App-Version", "web-ci")
    request.add_header("X-Client-Time", datetime.now(UTC).isoformat())
    if key:
        request.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise SystemExit(f"{method} {path} -> {error.code}: {error.read().decode(errors='replace')}") from error


def wav(seconds: int) -> bytes:
    rate = 8000
    salt = uuid.uuid4().int % 997
    frames = b"".join(struct.pack("<h", int(math.sin((i + salt) / 9) * 900)) for i in range(rate * seconds))
    header = b"RIFF" + struct.pack("<I", 36 + len(frames)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    return header + b"data" + struct.pack("<I", len(frames)) + frames


def create(seed: dict, artisans: list[str], record_by: str, note: str) -> dict:
    body = {
        "clientKey": f"ci-{uuid.uuid4().hex[:16]}",
        "questionnaireId": seed["questionnaireId"],
        "workshopId": seed["workshopId"] if seed["workshopField"] == "workshopId" else None,
        "designWorkshopId": seed["workshopId"] if seed["workshopField"] == "designWorkshopId" else None,
        "sessionDate": date.today().isoformat(),
        "startedAt": datetime.now(UTC).isoformat(),
        "purpose": "FIRST_SITTING",
        "place": seed["place"],
        "languageCode": "or",
        "note": note,
        "participants": [{"artisanId": seed["artisans"][name]} for name in artisans],
        "recordBy": record_by,
    }
    return call(seed["tokens"]["lead"], "POST", "/questionnaire/sessions", body, body["clientKey"])


def take(seed: dict, session: dict, target: dict | None, artisans: list[str], duration_ms: int = 60000) -> None:
    token = seed["tokens"]["lead"]
    payload = wav(1)
    sha = hashlib.sha256(payload).hexdigest()
    clip = f"clip-{uuid.uuid4().hex[:16]}"
    at = datetime.now(UTC).isoformat()
    targets = [target] if target else []
    ids = [seed["artisans"][name] for name in artisans]
    signed = call(token, "POST", "/media/presign", {
        "linkedRecordType": "questionnaire_session", "sessionId": session["id"], "clientClipKey": clip, "sizeBytes": len(payload),
        "contentType": "audio/wav", "extension": "wav", "contentSha256": sha, "targets": targets, "subjectArtisanIds": ids,
        "deviceCapturedAt": at, "clockOffsetMs": 0,
    })
    put = urllib.request.Request(signed["url"], data=payload, method="PUT")
    for name, value in (signed.get("headers") or {}).items():
        put.add_header(name, value)
    if not any(k.lower() == "content-type" for k in (signed.get("headers") or {})):
        put.add_header("Content-Type", "audio/wav")
    with urllib.request.urlopen(put, timeout=60):
        pass
    call(token, "POST", "/media/complete", {
        "objectKey": signed["objectKey"], "clientClipKey": clip, "linkedRecordType": "questionnaire_session", "linkedRecordId": session["id"],
        "recording": {
            "targets": targets,
            "subjects": [{"artisanId": a, "role": "ANSWERED", "named": True, "repeatReason": None} for a in ids],
            "deviceCapturedAt": at, "clockOffsetMs": 0, "capturedAtSource": "DEVICE", "durationMs": duration_ms,
            "rosterCount": len(ids), "contentSha256": sha, "continuationOfId": None, "replacesRecordingId": None, "ackedRecordingIds": [],
        },
    }, clip)


def board(seed: dict, record_by: str, note: str) -> dict:
    meera = "Meera Devi"
    session = create(seed, [meera], record_by, note)
    take(seed, session, {"sectionId": seed["sections"]["A"]}, [meera])
    take(seed, session, {"sectionId": seed["sections"]["B"]}, [meera])
    items = [
        {"artisanId": seed["artisans"][meera], "questionLineageId": seed["questions"][code], "text": text, "origin": "TYPED",
         "attribution": "INDIVIDUAL", "sessionId": session["id"], "sourceRecordingId": None}
        for code, text in TYPED.items()
    ]
    call(seed["tokens"]["lead"], "PUT", "/questionnaire/answers", {"items": items})
    take(seed, session, {"questionLineageId": seed["questions"]["E2"]}, [meera], 192000)
    return session


def main(seed_path: str, out_path: str) -> None:
    with open(seed_path, encoding="utf-8") as handle:
        seed = json.load(handle)
    group = ["Ramesh Maharana", "Sushila Sahoo", "Bhagirathi Nayak"]
    by_section = board(seed, "SECTION", "CI board by section")
    by_question = board(seed, "QUESTION", "CI board by question")
    finished = board(seed, "SECTION", "CI board finished")
    call(seed["tokens"]["lead"], "POST", f"/questionnaire/sessions/{finished['id']}/finish", {})
    fresh = create(seed, ["Meera Devi"], "SECTION", "CI fresh")
    grouped = create(seed, group, "SECTION", "CI group")
    out = {
        "bySection": by_section["id"],
        "byQuestion": by_question["id"],
        "finished": finished["id"],
        "fresh": fresh["id"],
        "group": grouped["id"],
    }
    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump(out, handle)
    print(f"prepared {len(out)} interview sessions")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
