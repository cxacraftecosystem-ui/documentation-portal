from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.services.datareport.contract import ReportContext, ReportOptions
from app.services.datareport.sheets import extras_common, extras_coverage

NOW = datetime(2026, 11, 17, 12, 0, tzinfo=UTC)


class Links:
    def open_record(self, kind: str, record_id: str) -> str:
        return f"https://portal.example/r/{kind}/{record_id}"

    def section(self, interview_id: str, section_code: str) -> str:
        return f"https://portal.example/s/{interview_id}/{section_code}"


def _artisan(aid: str, name: str) -> Any:
    return SimpleNamespace(id=aid, name=name, craft=None)


def _link(aid: str, *, nested_only: bool = False) -> Any:
    if nested_only:
        return SimpleNamespace(artisan=SimpleNamespace(id=aid))
    return SimpleNamespace(artisanId=aid, artisan=SimpleNamespace(id=aid))


def _media(mid: str, size: int, *, tool: str | None = None, linked: str | None = None, talk_ms: int = 0) -> Any:
    return SimpleNamespace(
        id=mid, sizeBytes=size, artisanId=None, productId=None, toolId=tool,
        linkedRecordType="tool" if linked else None, linkedRecordId=linked,
        questionnaireInterviewId=None, mediaType="AUDIO", contentSha256=None, transcriptStatus=None,
        durationMs=talk_ms,
    )


def _material() -> dict[str, Any]:
    tools = [
        SimpleNamespace(id="t-head-only", artisanId="a-head", artisanLinks=[]),
        SimpleNamespace(id="t-shared", artisanId="a-head", artisanLinks=[_link("a-linked"), _link("a-head")]),
        SimpleNamespace(id="t-links-only", artisanId=None, artisanLinks=[_link("a-nested", nested_only=True)]),
    ]
    media = [
        _media("m-head-only", 100, tool="t-head-only", talk_ms=1000),
        _media("m-shared", 20, tool="t-shared", talk_ms=500),
        _media("m-by-link", 3, linked="t-shared"),
        _media("m-links-only", 7, tool="t-links-only"),
    ]
    artisans = [_artisan("a-head", "Head"), _artisan("a-linked", "Linked"), _artisan("a-nested", "Nested")]
    return {"artisans": artisans, "interviews": [], "media": media, "tools": tools, "products": []}


def _ctx() -> ReportContext:
    return ReportContext(
        portal="designer",
        user=SimpleNamespace(id="u1", email="ravi@example.org", role="ADMIN"),
        scope=None,
        path="by-workshop/w1",
        folder_name="Bagru",
        material={"records": _material()},
        options=ReportOptions(),
        links=Links(),
        t=lambda _key, default: default,
        data_version="v1",
        generated_at=NOW.isoformat(),
    )


@pytest.fixture
def no_db(monkeypatch: pytest.MonkeyPatch) -> None:
    async def query_raw(_sql: str, *_args: Any) -> list[dict[str, Any]]:
        return []

    async def find_many(**_kwargs: Any) -> list[Any]:
        return []

    fake = SimpleNamespace(query_raw=query_raw, questionnairequestion=SimpleNamespace(find_many=find_many))
    monkeypatch.setattr(extras_common, "db", fake)
    monkeypatch.setattr(extras_coverage, "db", fake)


def test_a_tool_names_its_head_then_its_links_once_each() -> None:
    head_only, shared, links_only = _material()["tools"]
    assert extras_common.tool_artisans(head_only) == ["a-head"]
    assert extras_common.tool_artisans(shared) == ["a-head", "a-linked"]
    assert extras_common.tool_artisans(links_only) == ["a-nested"]


def test_a_tools_media_belongs_to_its_head_and_every_linked_artisan() -> None:
    owners = extras_common.media_owner_artisans(_ctx())
    assert owners["m-head-only"] == {"a-head"}, "a tool whose head has no link row still counts for its head"
    assert owners["m-shared"] == {"a-head", "a-linked"}
    assert owners["m-by-link"] == {"a-head", "a-linked"}
    assert owners["m-links-only"] == {"a-nested"}


def test_the_completeness_sheet_counts_tool_files_for_the_head_and_the_links(no_db: None) -> None:
    [sheet] = asyncio.run(extras_coverage.build_completeness(_ctx()))
    rows = {row["artisanId"]: row for row in sheet.rows}
    assert (rows["a-head"]["files"], rows["a-head"]["bytes"], rows["a-head"]["talkMs"]) == (3, 123, 1500)
    assert (rows["a-linked"]["files"], rows["a-linked"]["bytes"], rows["a-linked"]["talkMs"]) == (2, 23, 500)
    assert (rows["a-nested"]["files"], rows["a-nested"]["bytes"], rows["a-nested"]["talkMs"]) == (1, 7, 0)
