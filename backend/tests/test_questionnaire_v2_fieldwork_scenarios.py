"""Questionnaire v2 against real fieldwork: the scenarios that decide whether it can be switched on.

Each test is one day (or several) in the field, driven through the REAL HTTP API with
``QUESTIONNAIRE_V2`` on: sessions (open-match, join, join by QR key, continue), presign and complete
with stand-in storage, coverage/check, answers, decide, the inbox, the artisan record and its CSV.
The assertions are on what people read: the artisan record (timeline, by-question, the coverage
line, the Open list), the workshop coverage grid and the inbox.

Design: C:/dev/IIT/docs/questionnaire/DESIGN_2026-10-18.md (sections 3.1-3.5, 3.8, 4.6, 4.7,
revisions 3 and 4). Owner rules: AUDIT_2026-10-21_RULES.md and UNIFY_2026-10-21.md (answer lock,
guest respondents, amendment 2).

TYPED ANSWERS AND COVERAGE. Amendment 2 (22 Oct 2026) makes a typed answer with text count as
coverage; that is built in the U0 shared package, not here. No assertion below depends on typed
answers NOT counting. The amendment's rules that are not true on this branch yet are their own
``xfail(strict=True, reason="U0 amendment 2")`` tests at the end, so they turn green (and must lose
the mark) when U0 merges.

Real Postgres (``QV2_TEST_DATABASE_URL``, a migrated throwaway database; never production). S3 and
every speech-to-text or AI provider are stand-ins that fail the test if called.
"""

from __future__ import annotations

import csv
import io
import json
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx
import pytest
from query_counter import counting
from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, run, seed, settings_v2, user

pytestmark = needs_db

SIX = {"A": 2, "B": 2, "C": 2, "D": 2, "E": 2, "F": 2}
DAY1, DAY2, DAY3, DAY4 = "2026-06-20", "2026-06-21", "2026-06-22", "2026-06-23"
_CURRENT: dict[str, Any] = {}


# ── the harness: the real API, one database client, a signed-in person per call ──────────────────
def _app() -> Any:
    from fastapi import FastAPI

    from app.api import router as api_router_module
    from app.core import deps

    app = FastAPI()
    app.include_router(api_router_module.api_router)
    app.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return app


class Api:
    def __init__(self, http: httpx.AsyncClient) -> None:
        self.http = http

    async def call(self, who: Any, method: str, path: str, **kw: Any) -> httpx.Response:
        _CURRENT["user"] = who
        return await self.http.request(method, path, **kw)

    async def ok(self, who: Any, method: str, path: str, *, expect: tuple[int, ...] = (200, 201), **kw: Any) -> Any:
        response = await self.call(who, method, path, **kw)
        assert response.status_code in expect, (method, path, response.status_code, response.text)
        return response.json() if response.content else None


def _scenario(monkeypatch: pytest.MonkeyPatch, body: Any, **seed_kw: Any) -> None:
    async def main() -> None:
        client = await connect()
        try:
            patch_db(monkeypatch, client)
            no_outside_calls(monkeypatch)
            settings_v2(monkeypatch, True)
            ids = await seed(client, **seed_kw)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=_app()), base_url="http://fieldwork.test") as http:
                await body(Api(http), client, ids)
        finally:
            await client.execute_raw(
                'UPDATE "AppSetting" SET "transcriptionPaused" = true WHERE "id" = \'singleton\''
            )
            await client.disconnect()

    run(main())


async def _researcher(client: Any, ids: Any, key: str = "third", name: str = "Nandini") -> Any:
    uid = f"u{key}{ids.tag}"
    await client.execute_raw(
        'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, \'RESEARCHER\'::"UserRole", now())',
        uid, f"{uid}@example.test", name,
    )
    return user("RESEARCHER", uid, name)


def _sec(ids: Any, code: str) -> dict[str, str]:
    return {"sectionId": ids.sections[code]}


def _q(ids: Any, code: str) -> dict[str, str]:
    return {"questionLineageId": ids.questions[code]}


def _at(day: str, hour: int, minute: int = 0) -> datetime:
    y, m, d = (int(x) for x in day.split("-"))
    return datetime(y, m, d, hour, minute, tzinfo=UTC)


def _answered(*artisans: str, reason: str | None = None) -> list[dict[str, Any]]:
    return [{"artisanId": a, "role": "ANSWERED", **({"repeatReason": reason} if reason else {})} for a in artisans]


async def _start(api: Api, who: Any, ids: Any, artisans: list[str], *, day: str, purpose: str = "FIRST_SITTING") -> dict[str, Any]:
    body = {
        "clientKey": f"c-{uuid.uuid4().hex[:12]}", "questionnaireId": ids.q, "workshopId": ids.workshop, "sessionDate": day,
        "startedAt": f"{day}T05:00:00Z", "purpose": purpose, "participants": [{"artisanId": a} for a in artisans],
    }
    return await api.ok(who, "POST", "/api/questionnaire/sessions", json=body, expect=(201,))


async def _take(api: Api, who: Any, session_id: str, *, targets: list[dict], at: datetime, subjects: list[dict] | None = None,
                acked: list[str] | tuple[str, ...] = (), **recording: Any) -> dict[str, Any]:
    """One clip through the real upload path: presign (an intent, a server-minted key), then complete."""
    clip = f"k-{uuid.uuid4().hex[:12]}"
    signed = await api.ok(who, "POST", "/api/media/presign", json={
        "linkedRecordType": "questionnaire_session", "sessionId": session_id, "clientClipKey": clip, "sizeBytes": 3_400_000,
        "contentType": "audio/mp4", "extension": "m4a", "targets": targets,
        "subjectArtisanIds": [s["artisanId"] for s in (subjects or []) if s.get("role", "ANSWERED") == "ANSWERED"],
        "deviceCapturedAt": at.isoformat(), "clockOffsetMs": 0,
    })
    assert signed["closed"] is False and signed["url"]
    return await api.ok(who, "POST", "/api/media/complete", expect=(201,), json={
        "objectKey": signed["objectKey"], "clientClipKey": clip, "linkedRecordType": "questionnaire_session",
        "linkedRecordId": session_id,
        "recording": {
            "targets": targets, "subjects": subjects or [], "deviceCapturedAt": at.isoformat(), "clockOffsetMs": 0,
            "capturedAtSource": "DEVICE", "durationMs": 600_000, "ackedRecordingIds": list(acked), **recording,
        },
    })


async def _check(api: Api, who: Any, ids: Any, artisans: list[str], targets: list[dict], session_id: str) -> dict[str, Any]:
    return await api.ok(who, "POST", "/api/questionnaire/coverage/check", json={
        "questionnaireId": ids.q, "artisanIds": artisans, "targets": targets, "sessionId": session_id,
    })


async def _record(api: Api, who: Any, ids: Any, artisan: str) -> dict[str, Any]:
    out = await api.ok(who, "GET", f"/api/artisans/{artisan}/record", params={"questionnaireId": ids.q})
    assert out["artisanId"] == artisan and len(out["instruments"]) == 1, out
    return out["instruments"][0]


def _covered(inst: dict[str, Any]) -> tuple[int, int, list[str]]:
    """The coverage line ("3 of 6 sections") and the Open list, as the record states them."""
    s = inst["summary"]
    return s["sectionsCovered"], s["sectionsTotal"], s["open"]


def _doc_entry(inst: dict[str, Any], ids: Any, code: str) -> dict[str, Any]:
    display = ids.codes[ids.questions[code]]
    for section in inst["document"]:
        for q in section["questions"]:
            if q["displayCode"] == display:
                return q
    raise AssertionError(f"{code} ({display}) is not in the by-question document")


async def _grid(api: Api, who: Any, ids: Any, **params: Any) -> dict[str, dict[str, dict[str, Any]]]:
    """``{sectionCode: {artisanId: cell}}``."""
    out = await api.ok(who, "GET", "/api/questionnaire/coverage", params={"questionnaireId": ids.q, **params})
    return {s["code"]: {c["artisanId"]: c for c in s["cells"]} for s in out["sections"]}


def _position(recording: dict[str, Any], artisan: str) -> int | None:
    return next(s["position"] for s in recording["subjects"] if s["artisanId"] == artisan)


def _subject(recording: dict[str, Any], artisan: str) -> dict[str, Any]:
    return next(s for s in recording["subjects"] if s["artisanId"] == artisan)


def _change(item: dict[str, Any]) -> dict[str, Any]:
    change = item.get("change") or {}
    return json.loads(change) if isinstance(change, str) else change


# ── 1 ─────────────────────────────────────────────────────────────────────────────────────────
def test_three_researchers_share_one_group_session_and_each_records_different_sections(monkeypatch) -> None:
    """One group of five, one shared session: the lead starts it, the second researcher is offered it
    by open-match and joins by code, the third joins from the QR. Each records a different section;
    everybody is ANSWERED by default, and one artisan is unticked on one recording (PRESENT)."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        third = await _researcher(client, ids)
        five = ids.artisans[:5]
        s = await _start(api, ids.lead, ids, five, day=DAY1)
        offered = await api.ok(ids.other, "GET", "/api/questionnaire/sessions/open-match", params={
            "questionnaireId": ids.q, "workshopId": ids.workshop, "artisanIds": ",".join(five[1:3]), "date": DAY1,
        })
        assert [m["id"] for m in offered["items"]] == [s["id"]]
        assert offered["items"][0]["researcher"]["id"] == ids.lead.id
        assert (await api.ok(ids.other, "POST", "/api/questionnaire/sessions/join", json={"joinCode": s["joinCode"]}))["id"] == s["id"]
        joined = await api.ok(third, "POST", "/api/questionnaire/sessions/join-by-key", json={"clientKey": s["clientKey"]})
        assert joined["id"] == s["id"]
        assert {r["userId"]: r["role"] for r in joined["researchers"]} == {
            ids.lead.id: "LEAD", ids.other.id: "CONTRIBUTOR", third.id: "CONTRIBUTOR"}

        a = await _take(api, ids.lead, s["id"], targets=[_sec(ids, "A")], at=_at(DAY1, 10, 0))
        b = await _take(api, ids.other, s["id"], targets=[_sec(ids, "B")], at=_at(DAY1, 10, 30))
        c = await _take(api, third, s["id"], targets=[_sec(ids, "C")], at=_at(DAY1, 11, 0),
                        subjects=_answered(*five[:4]) + [{"artisanId": five[4], "role": "PRESENT"}])
        for take in (a, b):
            assert take["filing"] == "FILED" and take["rosterCount"] == 5
            assert sorted(x["artisanId"] for x in take["subjects"] if x["role"] == "ANSWERED") == sorted(five)
        for take in (a, b, c):
            assert all(x["flag"] is None and x["repeatReason"] is None for x in take["subjects"]), take["subjects"]
        assert c["filing"] == "FILED" and _subject(c, five[4])["role"] == "PRESENT"
        assert _position(c, five[4]) is None, "PRESENT gains no take"

        for i, artisan in enumerate(five):
            inst = await _record(api, ids.lead, ids, artisan)
            answered_c = i < 4
            assert _covered(inst) == ((3, 6, ["D", "E", "F"]) if answered_c else (2, 6, ["C", "D", "E", "F"])), artisan
            assert inst["summary"]["sessions"] == 1 and inst["summary"]["repeats"] == 0
            [entry] = inst["sessions"]
            sitting = entry["session"]
            assert sitting["id"] == s["id"] and sitting["mode"] == "GROUP" and sitting["participantCount"] == 5  # "Group of 5"
            assert sorted(r["name"] for r in sitting["researchers"]) == ["Lead", "Nandini", "Other"]
            assert [r["id"] for r in entry["recordings"]] == [a["id"], b["id"], c["id"]] and entry["uncertain"] == []
            assert [r["recordedBy"]["name"] for r in entry["recordings"]] == ["Lead", "Other", "Nandini"]
            assert [r["targets"][0]["label"] for r in entry["recordings"]] == ["Section A", "Section B", "Section C"]
            assert _subject(entry["recordings"][2], artisan)["role"] == ("ANSWERED" if answered_c else "PRESENT")

        grid = await _grid(api, ids.lead, ids, workshopId=ids.workshop)
        assert all(set(cells) == set(five) for cells in grid.values())
        for code in "AB":
            assert all(cell["state"] == "RECORDED" and cell["takes"] == 1 for cell in grid[code].values()), code
        assert [grid["C"][x]["state"] for x in five] == ["RECORDED"] * 4 + ["NOT_RECORDED"]
        for code in "DEF":
            assert all(cell["state"] == "NOT_RECORDED" for cell in grid[code].values()), code
        session = await api.ok(ids.lead, "GET", f"/api/questionnaire/sessions/{s['id']}")
        assert session["counts"] == {"sections": 3, "takes": 3, "needsDecision": 0}

        exported = await api.call(ids.admin, "GET", f"/api/artisans/{five[4]}/record.csv", params={"questionnaireId": ids.q})
        assert exported.status_code == 200 and ".csv" in exported.headers["content-disposition"]
        rows = list(csv.reader(io.StringIO(exported.text)))
        assert rows[0][:3] == ["Questionnaire", "Section", "Question"] and len(rows) == 1 + 12

    _scenario(monkeypatch, go, sections=SIX, artisans=5)


# ── 2 ─────────────────────────────────────────────────────────────────────────────────────────
def test_three_researchers_each_start_their_own_session_the_same_day_and_nothing_is_counted_twice(monkeypatch) -> None:
    """The same group, the same day, but each researcher declines open-match and starts a session of
    their own. Every artisan's record shows three sittings, one section each, and nothing doubled."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        third = await _researcher(client, ids)
        five = ids.artisans[:5]
        s1 = await _start(api, ids.lead, ids, five, day=DAY1)
        params = {"questionnaireId": ids.q, "workshopId": ids.workshop, "artisanIds": ",".join(five), "date": DAY1}
        offered = await api.ok(ids.other, "GET", "/api/questionnaire/sessions/open-match", params=params)
        assert [m["id"] for m in offered["items"]] == [s1["id"]]
        s2 = await _start(api, ids.other, ids, five, day=DAY1)  # "New session": declined
        offered = await api.ok(third, "GET", "/api/questionnaire/sessions/open-match", params=params)
        assert {m["id"] for m in offered["items"]} == {s1["id"], s2["id"]}
        s3 = await _start(api, third, ids, five, day=DAY1)
        assert len({s1["id"], s2["id"], s3["id"]}) == 3

        a = await _take(api, ids.lead, s1["id"], targets=[_sec(ids, "A")], at=_at(DAY1, 10, 0))
        b = await _take(api, ids.other, s2["id"], targets=[_sec(ids, "B")], at=_at(DAY1, 10, 20))
        c = await _take(api, third, s3["id"], targets=[_sec(ids, "C")], at=_at(DAY1, 10, 40))
        for take in (a, b, c):
            assert take["filing"] == "FILED" and take["decisionKind"] is None
            assert all(x["flag"] is None and x["repeatReason"] is None and x["position"] == 1 for x in take["subjects"])

        for artisan in five:
            inst = await _record(api, ids.lead, ids, artisan)
            assert _covered(inst) == (3, 6, ["D", "E", "F"])
            assert inst["summary"]["sessions"] == 3 and inst["summary"]["repeats"] == 0
            # Same date: the newest-created sitting first.
            assert [e["session"]["id"] for e in inst["sessions"]] == [s3["id"], s2["id"], s1["id"]]
            assert [[r["id"] for r in e["recordings"]] for e in inst["sessions"]] == [[c["id"]], [b["id"]], [a["id"]]]
            assert [[r["name"] for r in e["session"]["researchers"]] for e in inst["sessions"]] == [["Nandini"], ["Other"], ["Lead"]]

        grid = await _grid(api, ids.lead, ids, workshopId=ids.workshop)
        for code in "ABC":
            assert all(cell["state"] == "RECORDED" and cell["takes"] == 1 for cell in grid[code].values()), code
        listed = await api.ok(ids.lead, "GET", "/api/questionnaire/sessions", params={"workshopId": ids.workshop})
        assert {s["id"] for s in listed["items"]} == {s1["id"], s2["id"], s3["id"]}
        assert all(s["counts"] == {"sections": 1, "takes": 1, "needsDecision": 0} for s in listed["items"])

    _scenario(monkeypatch, go, sections=SIX, artisans=5)


# ── 3 ─────────────────────────────────────────────────────────────────────────────────────────
def test_a_group_sitting_continues_as_solos_and_then_with_another_researcher(monkeypatch) -> None:
    """Day 1: a group of five, sections A-C (one artisan unticked on C). Day 2: "Continue with..."
    splits EACH into solo sittings, each starting at that artisan's own first gap. Day 3: another
    researcher continues one artisan. The coverage line and the Open list are right after every day,
    and the timeline is newest first by session date."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        five = ids.artisans[:5]
        day1 = await _start(api, ids.lead, ids, five, day=DAY1)
        await _take(api, ids.lead, day1["id"], targets=[_sec(ids, "A")], at=_at(DAY1, 9))
        await _take(api, ids.lead, day1["id"], targets=[_sec(ids, "B")], at=_at(DAY1, 10))
        await _take(api, ids.lead, day1["id"], targets=[_sec(ids, "C")], at=_at(DAY1, 11),
                    subjects=_answered(*five[:4]) + [{"artisanId": five[4], "role": "PRESENT"}])
        for artisan in five[:4]:
            assert _covered(await _record(api, ids.lead, ids, artisan)) == (3, 6, ["D", "E", "F"])
        assert _covered(await _record(api, ids.lead, ids, five[4])) == (2, 6, ["C", "D", "E", "F"])

        cont = await api.ok(ids.lead, "POST", "/api/questionnaire/sessions/continue", json={
            "fromSessionId": day1["id"], "artisanIds": five, "split": "EACH", "sessionDate": DAY2,
        })
        solos = cont["sessions"]
        assert [[p["artisanId"] for p in s["participants"]] for s in solos] == [[a] for a in five]
        assert all(s["mode"] == "INDIVIDUAL" and s["purpose"] == "FOLLOW_UP" and s["sessionDate"] == DAY2 for s in solos)
        assert {a: (g or {}).get("code") for a, g in cont["firstGap"].items()} == {**{a: "D" for a in five[:4]}, five[4]: "C"}
        # PRESENT on day 1 is no coverage: C is still a first take for the fifth artisan.
        chk = await _check(api, ids.lead, ids, [five[4]], [_sec(ids, "C")], solos[4]["id"])
        assert chk["targets"][0]["perArtisan"][0]["state"] == "NOT_RECORDED" and chk["suggestedReason"] is None
        for artisan, solo in zip(five, solos):
            gap = cont["firstGap"][artisan]["code"]
            take = await _take(api, ids.lead, solo["id"], targets=[_sec(ids, gap)], at=_at(DAY2, 10))
            assert take["filing"] == "FILED" and take["rosterCount"] == 1 and _subject(take, artisan)["repeatReason"] is None
        for artisan in five[:4]:
            assert _covered(await _record(api, ids.lead, ids, artisan)) == (4, 6, ["E", "F"])
        assert _covered(await _record(api, ids.lead, ids, five[4])) == (3, 6, ["D", "E", "F"])

        again = await api.ok(ids.other, "POST", "/api/questionnaire/sessions/continue", json={
            "fromSessionId": solos[0]["id"], "artisanIds": [five[0]], "split": "ONE", "sessionDate": DAY3,
        })
        [day3] = again["sessions"]
        assert again["firstGap"][five[0]]["code"] == "E"
        assert [r["userId"] for r in day3["researchers"] if r["role"] == "LEAD"] == [ids.other.id]
        e = await _take(api, ids.other, day3["id"], targets=[_sec(ids, "E")], at=_at(DAY3, 10))

        inst = await _record(api, ids.lead, ids, five[0])
        assert _covered(inst) == (5, 6, ["F"])
        assert inst["summary"]["sessions"] == 3 and inst["summary"]["repeats"] == 0
        timeline = inst["sessions"]
        assert [x["session"]["id"] for x in timeline] == [day3["id"], solos[0]["id"], day1["id"]]
        assert [x["session"]["sessionDate"] for x in timeline] == [DAY3, DAY2, DAY1]
        assert [[t["targets"][0]["label"] for t in x["recordings"]] for x in timeline] == [
            ["Section E"], ["Section D"], ["Section A", "Section B", "Section C"]]
        assert timeline[0]["recordings"][0]["id"] == e["id"] and timeline[0]["recordings"][0]["recordedBy"]["name"] == "Other"
        assert [x["session"]["mode"] for x in timeline] == ["INDIVIDUAL", "INDIVIDUAL", "GROUP"]
        for artisan in five[1:4]:
            assert _covered(await _record(api, ids.lead, ids, artisan)) == (4, 6, ["E", "F"])

        grid = await _grid(api, ids.lead, ids, workshopId=ids.workshop)
        assert {code: grid[code][five[0]]["state"] for code in "ABCDEF"} == {
            "A": "RECORDED", "B": "RECORDED", "C": "RECORDED", "D": "RECORDED", "E": "RECORDED", "F": "NOT_RECORDED"}
        assert {code: grid[code][five[4]]["state"] for code in "ABCDEF"} == {
            "A": "RECORDED", "B": "RECORDED", "C": "RECORDED", "D": "NOT_RECORDED", "E": "NOT_RECORDED", "F": "NOT_RECORDED"}

    _scenario(monkeypatch, go, sections=SIX, artisans=5)


# ── 4 ─────────────────────────────────────────────────────────────────────────────────────────
def test_a_question_recorded_again_on_later_days_is_reported_preselects_its_reason_and_keeps_every_take(monkeypatch) -> None:
    """B1 again and again for one artisan: group, then solo (New setting), a redo by the same
    researcher (Redo), another researcher (Re-interview), a Follow-up sitting (Follow-up). Every take
    is kept and ordered; the primary take follows DESIGN 3.5, and a Re-interview never demotes the
    earlier account, not even a group take."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        a0, a1, a2 = ids.artisans[:3]
        group = await _start(api, ids.lead, ids, [a0, a1, a2], day=DAY1)
        g1 = await _take(api, ids.lead, group["id"], targets=[_q(ids, "B1")], at=_at(DAY1, 9))

        async def primary(artisan: str) -> tuple[str | None, list[str]]:
            entry = _doc_entry(await _record(api, ids.lead, ids, artisan), ids, "B1")
            return entry["primaryRecordingId"], entry["otherRecordingIds"]

        assert await primary(a0) == (g1["id"], [])

        # New setting: group, then solo.
        solo = await _start(api, ids.lead, ids, [a0], day=DAY2)
        chk = await _check(api, ids.lead, ids, [a0], [_q(ids, "B1")], solo["id"])
        per = chk["targets"][0]["perArtisan"][0]
        assert per["state"] == "RECORDED" and [t["recordingId"] for t in per["takes"]] == [g1["id"]]
        assert per["takes"][0]["session"]["rosterCount"] == 3 and chk["suggestedReason"] == "NEW_SETTING"
        s1 = await _take(api, ids.lead, solo["id"], targets=[_q(ids, "B1")], at=_at(DAY2, 9),
                         subjects=_answered(a0, reason="NEW_SETTING"), acked=[g1["id"]])
        assert s1["filing"] == "FILED" and _subject(s1, a0)["repeatReason"] == "NEW_SETTING" and _subject(s1, a0)["flag"] is None
        assert await primary(a0) == (s1["id"], [g1["id"]]), "rule 4: the earliest solo take"

        # Redo: the same researcher, the same setting.
        chk = await _check(api, ids.lead, ids, [a0], [_q(ids, "B1")], solo["id"])
        assert chk["suggestedReason"] == "REDO"
        assert [t["recordingId"] for t in chk["targets"][0]["perArtisan"][0]["takes"]] == [g1["id"], s1["id"]]
        d1 = await _take(api, ids.lead, solo["id"], targets=[_q(ids, "B1")], at=_at(DAY2, 9, 40),
                         subjects=_answered(a0, reason="REDO"), acked=[g1["id"], s1["id"]])
        assert d1["filing"] == "FILED" and _subject(d1, a0)["repeatReason"] == "REDO"
        assert (await primary(a0))[0] == d1["id"], "rule 3: the same researcher's redo"

        # Re-interview: another researcher, the same setting. It never demotes the earlier account.
        other_solo = await _start(api, ids.other, ids, [a0], day=DAY3)
        chk = await _check(api, ids.other, ids, [a0], [_q(ids, "B1")], other_solo["id"])
        assert chk["suggestedReason"] == "RE_INTERVIEW"
        r1 = await _take(api, ids.other, other_solo["id"], targets=[_q(ids, "B1")], at=_at(DAY3, 9),
                         subjects=_answered(a0, reason="RE_INTERVIEW"), acked=[g1["id"], s1["id"], d1["id"]])
        assert _subject(r1, a0)["repeatReason"] == "RE_INTERVIEW"
        assert (await primary(a0))[0] == d1["id"]

        # Follow-up: the sitting's purpose decides first.
        cont = await api.ok(ids.other, "POST", "/api/questionnaire/sessions/continue", json={
            "fromSessionId": other_solo["id"], "artisanIds": [a0], "split": "ONE", "sessionDate": DAY4,
        })
        [follow] = cont["sessions"]
        assert follow["purpose"] == "FOLLOW_UP"
        chk = await _check(api, ids.other, ids, [a0], [_q(ids, "B1")], follow["id"])
        assert chk["suggestedReason"] == "FOLLOW_UP"
        f1 = await _take(api, ids.other, follow["id"], targets=[_q(ids, "B1")], at=_at(DAY4, 9),
                         subjects=_answered(a0, reason="FOLLOW_UP"), acked=[g1["id"], s1["id"], d1["id"], r1["id"]])
        assert _subject(f1, a0)["repeatReason"] == "FOLLOW_UP"

        # Both (all five) takes kept, ordered by capture time; the primary plays first.
        assert await primary(a0) == (d1["id"], sorted([g1["id"], s1["id"], r1["id"], f1["id"]]))
        inst = await _record(api, ids.lead, ids, a0)
        assert inst["summary"]["repeats"] == 4 and inst["summary"]["sessions"] == 4
        assert [e["session"]["id"] for e in inst["sessions"]] == [follow["id"], other_solo["id"], solo["id"], group["id"]]
        positions = {r["id"]: _position(r, a0) for e in inst["sessions"] for r in e["recordings"]}
        assert positions == {g1["id"]: 1, s1["id"]: 2, d1["id"]: 3, r1["id"]: 4, f1["id"]: 5}

        # A Re-interview after a GROUP take, solo, chosen over the suggested New setting: still a
        # second account, never the primary (DESIGN 3.5: "A Re-interview never demotes the earlier
        # account").
        other_a1 = await _start(api, ids.other, ids, [a1], day=DAY3)
        chk = await _check(api, ids.other, ids, [a1], [_q(ids, "B1")], other_a1["id"])
        assert chk["suggestedReason"] == "NEW_SETTING"
        r2 = await _take(api, ids.other, other_a1["id"], targets=[_q(ids, "B1")], at=_at(DAY3, 11),
                         subjects=_answered(a1, reason="RE_INTERVIEW"), acked=[g1["id"]])
        assert _position(r2, a1) == 2
        assert await primary(a1) == (g1["id"], [r2["id"]])
        assert await primary(a2) == (g1["id"], [])

    _scenario(monkeypatch, go, sections={"A": 2, "B": 2}, artisans=3)


# ── 5 ─────────────────────────────────────────────────────────────────────────────────────────
def test_two_researchers_recording_the_same_section_at_once_wait_for_a_decision(monkeypatch) -> None:
    """Two phones on one group, each recording the same section within two hours without seeing the
    other's take: the later one is CONCURRENT and waits in the inbox. Keep both; Keep one (a content
    admin only; a researcher gets 403); Part 2. Then the same across two sessions of the same day."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        a0, a1, a2 = ids.artisans[:3]
        s = await _start(api, ids.lead, ids, [a0, a1, a2], day=DAY1)
        await api.ok(ids.other, "POST", "/api/questionnaire/sessions/join", json={"joinCode": s["joinCode"]})

        async def pair(code: str, hour: int) -> tuple[dict, dict]:
            first = await _take(api, ids.lead, s["id"], targets=[_sec(ids, code)], at=_at(DAY1, hour, 0))
            second = await _take(api, ids.other, s["id"], targets=[_sec(ids, code)], at=_at(DAY1, hour, 20))
            assert first["filing"] == "FILED"
            assert second["filing"] == "NEEDS_DECISION" and second["decisionKind"] == "CONCURRENT"
            assert {x["flag"] for x in second["subjects"]} == {"CONCURRENT"}
            return first, second

        d_lead, d_other = await pair("D", 9)
        e_lead, e_other = await pair("E", 11)
        f_lead, f_other = await pair("F", 13)

        for who in (ids.other, ids.lead):
            rows = {r["recordingId"]: r for r in (await api.ok(who, "GET", "/api/questionnaire/inbox"))["needsDecision"]}
            for mine, theirs in ((d_other, d_lead), (e_other, e_lead), (f_other, f_lead)):
                assert rows[mine["id"]]["decisionKind"] == "CONCURRENT"
                assert rows[mine["id"]]["otherRecordingId"] == theirs["id"]
        grid = await _grid(api, ids.lead, ids, artisanIds=",".join([a0, a1, a2]))
        assert grid["D"][a0]["state"] == "RECORDED" and grid["D"][a0]["needsDecision"] is True and grid["D"][a0]["takes"] == 1
        assert (await api.ok(ids.lead, "GET", f"/api/questionnaire/sessions/{s['id']}"))["counts"]["needsDecision"] == 3

        # Keep both: the uploader decides; both are filed, ordered.
        kept = await api.ok(ids.other, "POST", f"/api/questionnaire/recordings/{d_other['id']}/decide",
                            json={"action": "KEEP_BOTH", "otherRecordingId": d_lead["id"]})
        assert kept["filing"] == "FILED" and kept["decisionKind"] is None and _position(kept, a0) == 2

        # Keep one removes the other take: content admins only.
        for who in (ids.other, ids.lead):
            refused = await api.call(who, "POST", f"/api/questionnaire/recordings/{e_other['id']}/decide",
                                     json={"action": "KEEP_THIS", "otherRecordingId": e_lead["id"]})
            assert refused.status_code == 403, refused.text
        one = await api.ok(ids.admin, "POST", f"/api/questionnaire/recordings/{e_other['id']}/decide",
                           json={"action": "KEEP_THIS", "otherRecordingId": e_lead["id"]})
        assert one["filing"] == "FILED" and _position(one, a0) == 1
        removed = await api.ok(ids.lead, "GET", "/api/questionnaire/recordings/removed", params={"sessionId": s["id"]})
        assert [r["id"] for r in removed["items"]] == [e_lead["id"]]

        # Part 2: the second take continues the first and shares its position; it is not a repeat.
        part2 = await api.ok(ids.other, "POST", f"/api/questionnaire/recordings/{f_other['id']}/decide",
                             json={"action": "CONTINUATION", "otherRecordingId": f_lead["id"]})
        assert part2["filing"] == "FILED" and part2["continuationOfId"] == f_lead["id"] and _position(part2, a0) == 1

        grid = await _grid(api, ids.lead, ids, artisanIds=",".join([a0, a1, a2]))
        assert [(grid[c][a0]["takes"], grid[c][a0]["needsDecision"]) for c in "DEF"] == [(2, False), (1, False), (2, False)]
        assert (await api.ok(ids.other, "GET", "/api/questionnaire/inbox"))["needsDecision"] == []
        inst = await _record(api, ids.lead, ids, a0)
        assert inst["summary"]["repeats"] == 1, "D's second take only: Keep one removed one, Part 2 is no repeat"

        # The same two researchers, two sessions of the same day with one artisan in both.
        mine = await _start(api, ids.lead, ids, [a0, a1], day=DAY2)
        theirs = await _start(api, ids.other, ids, [a1, a2], day=DAY2)
        b_lead = await _take(api, ids.lead, mine["id"], targets=[_sec(ids, "B")], at=_at(DAY2, 9))
        b_other = await _take(api, ids.other, theirs["id"], targets=[_sec(ids, "B")], at=_at(DAY2, 9, 30))
        assert b_other["filing"] == "NEEDS_DECISION" and b_other["decisionKind"] == "CONCURRENT"
        assert _subject(b_other, a1)["flag"] == "CONCURRENT" and _subject(b_other, a2)["flag"] is None
        rows = {r["recordingId"]: r for r in (await api.ok(ids.other, "GET", "/api/questionnaire/inbox"))["needsDecision"]}
        assert rows[b_other["id"]]["otherRecordingId"] == b_lead["id"], "Keep one needs to know which take it is choosing against"
        decided = await api.ok(ids.prof, "POST", f"/api/questionnaire/recordings/{b_other['id']}/decide",
                               json={"action": "KEEP_BOTH", "otherRecordingId": b_lead["id"]})
        assert decided["filing"] == "FILED"
        assert [_position(b_lead, a1), _position(decided, a1)] == [1, 2]

    _scenario(monkeypatch, go, sections={"B": 2, "D": 2, "E": 2, "F": 2}, artisans=3)


# ── 6 ─────────────────────────────────────────────────────────────────────────────────────────
def test_an_offline_take_that_arrives_days_late_is_placed_by_its_capture_time(monkeypatch) -> None:
    """A take recorded on day 1 reaches the server after another researcher's take of day 3: it is
    filed (LATE_ARRIVAL, no decision), placed first by its capture time, and, because it changes the
    artisan's primary take, a "Primary changed" note reaches both researchers. A late take that does
    not change the primary leaves no note."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        a0, a1 = ids.artisans[:2]
        early = await _start(api, ids.lead, ids, [a0], day=DAY1)
        later = await _start(api, ids.other, ids, [a0], day=DAY3)
        o1 = await _take(api, ids.other, later["id"], targets=[_q(ids, "D1")], at=_at(DAY3, 10))
        assert _doc_entry(await _record(api, ids.lead, ids, a0), ids, "D1")["primaryRecordingId"] == o1["id"]

        # The lead's phone comes back online: its clip of day 1 completes now, unaware of o1.
        late = await _take(api, ids.lead, early["id"], targets=[_q(ids, "D1")], at=_at(DAY1, 10))
        assert late["filing"] == "FILED" and late["decisionKind"] is None
        assert _subject(late, a0)["flag"] == "LATE_ARRIVAL"
        assert late["capturedAt"].startswith(DAY1) and late["capturedAtSource"] == "DEVICE"

        inst = await _record(api, ids.lead, ids, a0)
        assert [e["session"]["id"] for e in inst["sessions"]] == [later["id"], early["id"]]
        positions = {r["id"]: _position(r, a0) for e in inst["sessions"] for r in e["recordings"]}
        assert positions == {late["id"]: 1, o1["id"]: 2}
        entry = _doc_entry(inst, ids, "D1")
        assert entry["primaryRecordingId"] == late["id"] and entry["otherRecordingIds"] == [o1["id"]]

        for who in (ids.lead, ids.other):
            inbox = await api.ok(who, "GET", "/api/questionnaire/inbox")
            assert all(r["recordingId"] != late["id"] for r in inbox["needsDecision"])
            notes = [_change(i) for i in inbox["primaryChanged"] if _change(i).get("artisanId") == a0]
            assert [(n["from"], n["to"]) for n in notes] == [(o1["id"], late["id"])], (who.id, inbox["primaryChanged"])

        # A late take that does NOT change the primary: no note.
        first = await _start(api, ids.lead, ids, [a1], day=DAY1)
        kept = await _take(api, ids.lead, first["id"], targets=[_q(ids, "D1")], at=_at(DAY1, 11))
        second = await _start(api, ids.other, ids, [a1], day=DAY3)
        trailing = await _take(api, ids.other, second["id"], targets=[_q(ids, "D1")], at=_at(DAY3, 11))
        assert trailing["filing"] == "FILED" and _subject(trailing, a1)["flag"] == "LATE_ARRIVAL"
        assert _doc_entry(await _record(api, ids.lead, ids, a1), ids, "D1")["primaryRecordingId"] == kept["id"]
        for who in (ids.lead, ids.other):
            inbox = await api.ok(who, "GET", "/api/questionnaire/inbox")
            assert not [i for i in inbox["primaryChanged"] if _change(i).get("artisanId") == a1]

    _scenario(monkeypatch, go, sections={"D": 2}, artisans=2)


# ── 7 ─────────────────────────────────────────────────────────────────────────────────────────
def test_section_and_question_takes_follow_the_hierarchy_rule_and_a_group_answer_stays_a_group_answer(monkeypatch) -> None:
    """A section take after question takes asks nothing (the section was Partial); a question take
    after a section take prompts ("recorded whole", Follow-up preselected: DESIGN 8 E6). A typed
    answer for "All 3" is a group answer for each of them, never an individual account."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        three = ids.artisans[:3]
        s = await _start(api, ids.lead, ids, three, day=DAY1)
        d1 = await _take(api, ids.lead, s["id"], targets=[_q(ids, "D1")], at=_at(DAY1, 9))
        d2 = await _take(api, ids.lead, s["id"], targets=[_q(ids, "D2")], at=_at(DAY1, 9, 10))

        chk = await _check(api, ids.lead, ids, three, [_sec(ids, "D")], s["id"])
        assert [p["state"] for p in chk["targets"][0]["perArtisan"]] == ["PARTIAL"] * 3
        assert all(p["takes"] == [] and not p["viaSection"] for p in chk["targets"][0]["perArtisan"])
        assert chk["suggestedReason"] is None, "no prompt: a section after question takes"
        whole_d = await _take(api, ids.lead, s["id"], targets=[_sec(ids, "D")], at=_at(DAY1, 9, 30))
        assert whole_d["filing"] == "FILED"
        assert all(x["flag"] is None and x["repeatReason"] is None and x["position"] == 1 for x in whole_d["subjects"])

        whole_f = await _take(api, ids.lead, s["id"], targets=[_sec(ids, "F")], at=_at(DAY1, 10))
        chk = await _check(api, ids.lead, ids, three, [_q(ids, "F1")], s["id"])
        per = chk["targets"][0]["perArtisan"]
        assert [p["state"] for p in per] == ["VIA_SECTION"] * 3 and all(p["viaSection"] for p in per)
        assert all([t["recordingId"] for t in p["takes"]] == [whole_f["id"]] for p in per)
        assert chk["suggestedReason"] == "FOLLOW_UP", "E6: a question after its section's take is a follow-up"
        f1 = await _take(api, ids.lead, s["id"], targets=[_q(ids, "F1")], at=_at(DAY1, 10, 30),
                         subjects=_answered(*three, reason="FOLLOW_UP"), acked=[whole_f["id"]])
        assert f1["filing"] == "FILED" and all(x["flag"] is None for x in f1["subjects"])
        assert all(x["repeatReason"] == "FOLLOW_UP" and x["position"] == 1 for x in f1["subjects"])

        grid = await _grid(api, ids.lead, ids, artisanIds=",".join(three))
        assert all(grid["D"][a]["state"] == "RECORDED" and grid["D"][a]["takes"] == 3 for a in three)
        assert all(grid["F"][a]["state"] == "RECORDED" for a in three)

        # "Answer for: All 3".
        saved = await api.ok(ids.lead, "PUT", "/api/questionnaire/answers", json={"items": [
            {"artisanId": a, "questionLineageId": ids.questions["D2"], "text": "Teak from the forest office", "origin": "TYPED",
             "attribution": "GROUP_SHARED", "sessionId": s["id"]} for a in three
        ]})
        assert [r["status"] for r in saved["results"]] == ["SAVED"] * 3
        for artisan in three:
            entry = _doc_entry(await _record(api, ids.lead, ids, artisan), ids, "D2")
            assert entry["answer"]["attribution"] == "GROUP_SHARED" and entry["answer"]["text"] == "Teak from the forest office"
            assert entry["primaryRecordingId"] == d2["id"]
        assert _doc_entry(await _record(api, ids.lead, ids, three[0]), ids, "D1")["primaryRecordingId"] == d1["id"]
        exported = await api.call(ids.admin, "GET", f"/api/artisans/{three[1]}/record.csv", params={"questionnaireId": ids.q})
        rows = {r[2]: r for r in csv.reader(io.StringIO(exported.text))}
        assert rows[ids.codes[ids.questions["D2"]]][3:6] == ["Teak from the forest office", "TYPED", "GROUP_SHARED"]

    _scenario(monkeypatch, go, sections={"D": 2, "F": 2}, artisans=3)


# ── 8 ─────────────────────────────────────────────────────────────────────────────────────────
@pytest.mark.xfail(strict=True, reason="UNIFY U6: guest respondents are not built on Field Repository yet (U-M0 / U0)")
def test_a_guest_respondent_answers_in_a_group_and_their_history_moves_when_they_are_registered(monkeypatch) -> None:
    """A guest (R: subject) in a group appears on the session grid but has no artisan record;
    registering them as an artisan moves their history to that record (UNIFY 2.3 rule 1, 4.1)."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        a0, a1, newcomer = ids.artisans[:3]
        body = {
            "clientKey": f"c-{uuid.uuid4().hex[:12]}", "questionnaireId": ids.q, "workshopId": ids.workshop, "sessionDate": DAY1,
            "purpose": "FIRST_SITTING", "participants": [{"artisanId": a0}, {"artisanId": a1}],
            "respondents": [{"name": "Lakshmi Devi", "kind": "GUEST"}],
        }
        s = await api.ok(ids.lead, "POST", "/api/questionnaire/sessions", json=body, expect=(201,))
        [guest] = s["respondents"]
        take = await _take(api, ids.lead, s["id"], targets=[_sec(ids, "D")], at=_at(DAY1, 9),
                           subjects=_answered(a0, a1) + [{"respondentId": guest["id"], "role": "ANSWERED"}])
        session = await api.ok(ids.lead, "GET", f"/api/questionnaire/sessions/{s['id']}")
        assert any(f"R:{guest['id']}" in json.dumps(section) for section in session["grid"]["sections"])
        await api.ok(ids.lead, "POST", f"/api/questionnaire/respondents/{guest['id']}/register", json={"artisanId": newcomer})
        inst = await _record(api, ids.lead, ids, newcomer)
        assert [r["id"] for e in inst["sessions"] for r in e["recordings"]] == [take["id"]]

    _scenario(monkeypatch, go, sections={"D": 2}, artisans=3)


# ── 9 ─────────────────────────────────────────────────────────────────────────────────────────
def test_an_answer_with_text_is_locked_to_its_author_the_session_lead_and_admins(monkeypatch) -> None:
    """The owner's answer-lock rule (21 Oct 2026; UNIFY U9): a second researcher cannot change an
    answer another person wrote (ANSWER_LOCKED, per item; the rest of the batch saves). Its author,
    the session's LEAD and an admin can. Identical text is UNCHANGED, never locked."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        a0, a1, a2 = ids.artisans[:3]
        s = await _start(api, ids.lead, ids, [a0, a1, a2], day=DAY1)
        await api.ok(ids.other, "POST", "/api/questionnaire/sessions/join", json={"joinCode": s["joinCode"]})
        d1 = ids.questions["D1"]

        async def put(who: Any, *items: dict[str, Any]) -> list[dict[str, Any]]:
            out = await api.ok(who, "PUT", "/api/questionnaire/answers", json={"items": [
                {"questionLineageId": d1, "origin": "TYPED", "attribution": "INDIVIDUAL", "sessionId": s["id"], **i} for i in items
            ]})
            return out["results"]

        [mine] = await put(ids.lead, {"artisanId": a0, "text": "Teak"})
        assert mine["status"] == "SAVED"
        locked, fresh = await put(ids.other, {"artisanId": a0, "text": "Sal", "baseVersion": 1}, {"artisanId": a1, "text": "Neem"})
        assert locked["status"] == "FORBIDDEN" and locked.get("code") == "ANSWER_LOCKED", locked
        assert locked["current"]["text"] == "Teak" and locked["answer"] is None
        assert fresh["status"] == "SAVED", "one locked item never fails the batch"
        [same] = await put(ids.other, {"artisanId": a0, "text": "Teak", "baseVersion": 1})
        assert same["status"] == "UNCHANGED"

        [own] = await put(ids.other, {"artisanId": a1, "text": "Neem wood", "baseVersion": 1})
        assert own["status"] == "SAVED", "the author edits their own answer"
        [by_lead] = await put(ids.lead, {"artisanId": a1, "text": "Neem, seasoned", "baseVersion": 2})
        assert by_lead["status"] == "SAVED", "the session lead edits anyone's answer"
        [back] = await put(ids.other, {"artisanId": a1, "text": "Neem wood", "baseVersion": 3})
        assert back["status"] == "FORBIDDEN" and back.get("code") == "ANSWER_LOCKED", "now the lead's text"
        [by_admin] = await put(ids.admin, {"artisanId": a0, "text": "Teak, oiled", "baseVersion": 1})
        assert by_admin["status"] == "SAVED", "an admin edits anyone's answer"
        [lead_again] = await put(ids.lead, {"artisanId": a0, "text": "Teak", "baseVersion": 2})
        assert lead_again["status"] == "SAVED"

        texts = {a: _doc_entry(await _record(api, ids.lead, ids, a), ids, "D1")["answer"]["text"] for a in (a0, a1)}
        assert texts == {a0: "Teak", a1: "Neem, seasoned"}

    _scenario(monkeypatch, go, sections={"D": 2}, artisans=3)


# ── 10 ────────────────────────────────────────────────────────────────────────────────────────
def test_an_artisan_who_refused_consent_is_recorded_and_filed_but_never_sent_out(monkeypatch) -> None:
    """Consent is opt-out (revision 3). One artisan of three refused: a recording they are on is
    filed and shown, but nothing is sent for transcription. Recordings without them go out as
    usual (transcription switched ON here, so QUEUED is not just the pause talking)."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        from app.services import consent_gate

        a0, a1, a2 = ids.artisans[:3]
        refused = await api.ok(ids.admin, "POST", f"/api/artisans/{a2}/consent", json={"state": "REFUSED"}, expect=(201,))
        assert refused["state"] == "REFUSED"
        s = await _start(api, ids.lead, ids, [a0, a1, a2], day=DAY1)
        assert {p["artisanId"]: p["consent"]["state"] for p in s["participants"]} == {
            a0: "NOT_RECORDED", a1: "NOT_RECORDED", a2: "REFUSED"}

        with_them = await _take(api, ids.lead, s["id"], targets=[_sec(ids, "A")], at=_at(DAY1, 9))
        assert with_them["filing"] == "FILED" and {x["artisanId"] for x in with_them["subjects"]} == {a0, a1, a2}
        assert with_them["consent"]["verdict"] == consent_gate.REFUSED
        assert with_them["transcript"]["status"] == "REFUSED_CONSENT"

        await api.ok(ids.lead, "PATCH", f"/api/questionnaire/sessions/{s['id']}/participants/{a2}",
                     json={"leftAt": f"{DAY1}T09:30:00Z"})  # Away
        without = await _take(api, ids.lead, s["id"], targets=[_sec(ids, "B")], at=_at(DAY1, 10))
        assert {x["artisanId"] for x in without["subjects"]} == {a0, a1}
        assert without["consent"]["verdict"] == consent_gate.ALLOW
        assert without["transcript"] == {"status": "QUEUED", "paused": False, "text": None, "language": None}
        solo = await _take(api, ids.lead, s["id"], targets=[_sec(ids, "C")], at=_at(DAY1, 10, 30), subjects=_answered(a0))
        assert solo["consent"]["verdict"] == consent_gate.ALLOW and solo["transcript"]["status"] == "QUEUED"

        jobs = await client.query_raw(
            "SELECT \"mediaFileId\", \"status\"::text AS status FROM \"MediaProcessingJob\" "
            "WHERE \"jobType\" = 'TRANSCRIPTION' AND \"mediaFileId\" = ANY($1::text[])",
            [with_them["mediaFileId"], without["mediaFileId"], solo["mediaFileId"]],
        )
        assert sorted((j["mediaFileId"], j["status"]) for j in jobs) == sorted(
            [(without["mediaFileId"], "QUEUED"), (solo["mediaFileId"], "QUEUED")]), "nothing queued to go out for the refused take"
        retry = await api.ok(ids.lead, "POST", f"/api/questionnaire/recordings/{with_them['id']}/transcribe", expect=(202,))
        assert retry["status"] == "REFUSED_CONSENT"
        assert not await client.query_raw(
            "SELECT 1 FROM \"MediaProcessingJob\" WHERE \"jobType\" = 'TRANSCRIPTION' AND \"mediaFileId\" = $1", with_them["mediaFileId"]
        )

        refuser = await _record(api, ids.lead, ids, a2)
        [entry] = refuser["sessions"]
        assert [r["id"] for r in entry["recordings"]] == [with_them["id"]], "filed and shown"
        assert entry["recordings"][0]["consent"]["verdict"] == consent_gate.REFUSED
        assert _covered(refuser)[0] == 1
        others = await _record(api, ids.lead, ids, a0)
        assert [r["id"] for r in others["sessions"][0]["recordings"]] == [with_them["id"], without["id"], solo["id"]]
        assert _covered(others)[0] == 3

    _scenario(monkeypatch, go, sections={"A": 2, "B": 2, "C": 2}, artisans=3, paused=False)


# ── 11 ────────────────────────────────────────────────────────────────────────────────────────
RECORD_BUDGET = 33
GRID_BUDGET = 7


async def _scenario_three_costs(monkeypatch: pytest.MonkeyPatch, extra_days: int) -> tuple[int, int]:
    """Scenario 3's shape (a group of five on day 1, then solo sittings), with ``extra_days`` solo
    sittings for the first artisan; the statements one artisan record and one workshop grid cost."""
    client = await connect()
    try:
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        settings_v2(monkeypatch, True)
        ids = await seed(client, sections=SIX, artisans=5)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=_app()), base_url="http://fieldwork.test") as http:
            api = Api(http)
            five = ids.artisans[:5]
            day1 = await _start(api, ids.lead, ids, five, day=DAY1)
            for hour, code in ((9, "A"), (10, "B"), (11, "C")):
                await _take(api, ids.lead, day1["id"], targets=[_sec(ids, code)], at=_at(DAY1, hour))
            acked: list[str] = []
            for n in range(extra_days):
                day = f"2026-06-{21 + n}"
                solo = await _start(api, ids.lead, ids, [five[0]], day=day)
                take = await _take(api, ids.lead, solo["id"], targets=[_sec(ids, "D")], at=_at(day, 10),
                                   subjects=_answered(five[0], reason="REDO" if acked else None), acked=acked)
                acked.append(take["id"])
            with counting(client) as seen:
                inst = await _record(api, ids.lead, ids, five[0])
            record_cost = seen.count
            assert inst["summary"]["sessions"] == 1 + extra_days and len(inst["sessions"]) == 1 + extra_days
            with counting(client) as seen:
                grid = await _grid(api, ids.lead, ids, workshopId=ids.workshop)
            assert grid["D"][five[0]]["takes"] == extra_days
            return record_cost, seen.count
    finally:
        await client.disconnect()


def test_the_record_and_the_workshop_grid_cost_a_fixed_number_of_statements_however_many_sessions(monkeypatch) -> None:
    small = run(_scenario_three_costs(monkeypatch, 1))
    large = run(_scenario_three_costs(monkeypatch, 4))
    assert small == large, f"N+1: record and grid cost {small} statements with 2 sessions, {large} with 5"
    assert small[0] <= RECORD_BUDGET and small[1] <= GRID_BUDGET, small


# ── Amendment 2 (22 Oct 2026, owner) ──────────────────────────────────────────────────────────
def test_amendment_2_rule_1_an_admin_section_override_has_three_states(monkeypatch) -> None:
    """Already true on Field Repository: Marked complete, Needs review and Needs redo, set by a
    content admin, are shown on the v2 grid as they are; nobody else may set them."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        a0 = ids.artisans[0]
        await _start(api, ids.lead, ids, [a0], day=DAY1)
        cell = {"artisanId": a0, "sectionId": ids.sections["D"]}
        refused = await api.call(ids.lead, "PUT", "/api/questionnaire/completion", json={**cell, "status": "COMPLETED"})
        assert refused.status_code == 403
        for state in ("COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"):
            await api.ok(ids.admin, "PUT", "/api/questionnaire/completion", json={**cell, "status": state})
            assert (await _grid(api, ids.lead, ids, artisanIds=a0))["D"][a0]["override"] == state

    _scenario(monkeypatch, go, sections={"D": 2}, artisans=1)


@pytest.mark.xfail(strict=True, reason="U0 amendment 2")
def test_amendment_2_rule_2_a_workshop_without_a_binding_gets_its_types_default_form(monkeypatch) -> None:
    """A default form per workshop type: an unbound DESIGN_PROTOTYPE workshop resolves to the
    form set for that type, ahead of the global default."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        await client.execute_raw('UPDATE "Workshop" SET "workshopType" = \'DESIGN_PROTOTYPE\', "questionnaireId" = NULL WHERE "id" = $1',
                                 ids.workshop)
        columns = {r["column_name"] for r in await client.query_raw(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'QuestionnaireTypeDefault'")}
        assert columns, "QuestionnaireTypeDefault does not exist"
        await client.execute_raw('DELETE FROM "QuestionnaireTypeDefault" WHERE "workshopType" = \'DESIGN_PROTOTYPE\'')
        values = {"id": uuid.uuid4().hex, "questionnaireId": ids.q, "workshopType": "DESIGN_PROTOTYPE", "setById": ids.admin.id}
        names = [c for c in values if c in columns]
        quoted = ", ".join(f'"{c}"' for c in names)
        slots = ", ".join(f'${i + 1}::"WorkshopType"' if c == "workshopType" else f"${i + 1}" for i, c in enumerate(names))
        await client.execute_raw(f'INSERT INTO "QuestionnaireTypeDefault" ({quoted}) VALUES ({slots})', *[values[c] for c in names])
        sections = await api.ok(ids.lead, "GET", "/api/questionnaire/sections", params={"workshopId": ids.workshop})
        assert {s["id"] for s in sections} == set(ids.sections.values())

    _scenario(monkeypatch, go, sections={"D": 2}, artisans=1)


def test_amendment_2_rule_3_a_professor_makes_their_own_form_and_uses_it_but_only_admins_bind(monkeypatch) -> None:
    """Already true on Field Repository: Professor and above create forms and may start a session
    on their own form at any workshop; binding a form to a workshop stays admin-only."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        await client.execute_raw('UPDATE "Workshop" SET "questionnaireId" = $2 WHERE "id" = $1', ids.workshop, ids.q)
        refused = await api.call(ids.lead, "POST", "/api/questionnaires", json={"title": f"Lead's own form {ids.tag}"})
        assert refused.status_code == 403
        form = await api.ok(ids.prof, "POST", "/api/questionnaires", json={"title": f"Prof's own form {ids.tag}"}, expect=(201,))
        body = {
            "clientKey": f"c-{uuid.uuid4().hex[:12]}", "questionnaireId": form["id"], "workshopId": ids.workshop, "sessionDate": DAY1,
            "purpose": "FIRST_SITTING", "participants": [{"artisanId": ids.artisans[0]}],
        }
        s = await api.ok(ids.prof, "POST", "/api/questionnaire/sessions", json=body, expect=(201,))
        assert s["questionnaire"]["id"] == form["id"]
        bind = await api.call(ids.prof, "PUT", f"/api/workshops/{ids.workshop}/questionnaire", json={"questionnaireId": form["id"]})
        assert bind.status_code == 403

    _scenario(monkeypatch, go, sections={"D": 2}, artisans=1)


@pytest.mark.xfail(strict=True, reason="U0 amendment 2")
def test_amendment_2_rule_4_a_typed_answer_with_text_covers_its_question_and_section(monkeypatch) -> None:
    """Typed or recorded, either counts (a group answer counts for each of its artisans); recording
    over a typed answer asks nothing."""

    async def go(api: Api, client: Any, ids: Any) -> None:
        a0, a1 = ids.artisans[:2]
        s = await _start(api, ids.lead, ids, [a0, a1], day=DAY1)
        await api.ok(ids.lead, "PUT", "/api/questionnaire/answers", json={"items": [
            {"artisanId": a0, "questionLineageId": ids.questions["D1"], "text": "Chisels", "sessionId": s["id"]},
            {"artisanId": a0, "questionLineageId": ids.questions["D2"], "text": "Teak", "sessionId": s["id"]},
            *[{"artisanId": a, "questionLineageId": ids.questions["F1"], "text": "From the elders", "attribution": "GROUP_SHARED",
               "sessionId": s["id"]} for a in (a0, a1)],
        ]})
        chk = await _check(api, ids.lead, ids, [a0], [_sec(ids, "D")], s["id"])
        assert chk["suggestedReason"] is None, "recording over a typed answer asks nothing"
        assert _covered(await _record(api, ids.lead, ids, a0)) == (2, 2, [])
        assert _covered(await _record(api, ids.lead, ids, a1)) == (1, 2, ["D"])

    _scenario(monkeypatch, go, sections={"D": 2, "F": 1}, artisans=2)
