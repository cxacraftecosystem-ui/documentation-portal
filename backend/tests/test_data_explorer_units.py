from __future__ import annotations

import asyncio
import csv
import io
import tracemalloc
import zipfile
from types import SimpleNamespace
from typing import Any

import pytest

from app.services.data_explorer import (
    browse,
    convert,
    downloads,
    report_tools,
    signing,
    stats,
    transcript_zip,
    tree,
    walk,
    zipstream,
)


class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


def _media_entry(mid: str, media_type: str, name: str, size: int | None = 10, **extra: Any) -> dict[str, Any]:
    entry = {
        "name": name,
        "path": name,
        "kind": "file",
        "mediaId": mid,
        "mediaType": media_type,
        "sizeBytes": size,
        "url": f"https://bucket.example/{mid}",
        "originalFilename": name,
        "transcriptAvailable": False,
    }
    entry.update(extra)
    return entry


def test_totals_count_files_and_bytes_per_type_and_flag_unknown_sizes():
    files = [
        {"mediaId": "a", "mediaType": "IMAGE", "sizeBytes": 100},
        {"mediaId": "b", "mediaType": "VIDEO", "sizeBytes": 3000},
        {"mediaId": "c", "mediaType": "AUDIO", "sizeBytes": None},
        {"content": "héllo"},
        {"mediaId": "d", "content": "words"},
        {"mediaId": "e", "_fill": "e", "sizeBytes": 7},
    ]
    stats.annotate(files)
    out = stats.totals(files, truncated=False)
    assert out["files"] == 6
    assert out["bytes"] == 100 + 3000 + len("héllo".encode()) + 5 + 7
    assert out["unknownSizeFiles"] == 1
    assert out["byType"]["images"] == {"files": 1, "bytes": 100}
    assert out["byType"]["videos"] == {"files": 1, "bytes": 3000}
    assert out["byType"]["text"]["files"] == 1
    assert out["byType"]["transcripts"] == {"files": 2, "bytes": 12}
    assert set(stats.TYPE_TOKENS) <= set(out["byType"])


def test_include_tokens_parse_from_a_query_string_or_a_list():
    assert stats.parse_include(None) is None
    assert stats.parse_include("") is None
    assert stats.parse_include("Images, videos") == {"images", "videos"}
    assert stats.parse_include(["audios"]) == {"audios"}


def test_a_signed_link_is_reused_inside_its_window_and_renewed_after():
    signing.forget()
    made: list[int] = []

    def sign() -> str:
        made.append(1)
        return f"https://signed.example/{len(made)}"

    first, age = signing.cached(("k", "x"), 3600, sign, now=1000.0)
    again, left = signing.cached(("k", "x"), 3600, sign, now=1000.0 + 600)
    later, _ = signing.cached(("k", "x"), 3600, sign, now=1000.0 + signing.REUSE_SECONDS + 1)
    assert first == again != later
    assert age == signing.REUSE_SECONDS
    assert left == signing.REUSE_SECONDS - 600
    assert len(made) == 2
    _short, short_age = signing.cached(("k", "short"), 900, sign, now=5.0)
    assert short_age == 450
    assert signing.redirect_headers(left)["Cache-Control"] == f"private, max-age={left}"
    signing.forget()
    assert signing.size() == 0


def test_the_signing_cache_never_grows_past_its_bound():
    signing.forget()
    for n in range(signing.CACHE_ENTRIES + 50):
        signing.cached(("key", n), 3600, lambda n=n: f"u{n}", now=1.0)
    assert signing.size() == signing.CACHE_ENTRIES
    signing.forget()


def test_table_search_matches_every_word_across_all_rows_not_the_preview():
    rows = [[f"row {i}", "plain"] for i in range(500)]
    rows.append(["Indigo dabu", "Bagru block"])
    sheet = {"name": "Products", "columns": ["Name", "Place"], "rows": rows, "prose": []}
    (found,) = report_tools.search([sheet], "dabu BAGRU")
    assert found["rows"] == [["Indigo dabu", "Bagru block"]]
    assert found["matchedRows"] == 1 and found["totalRows"] == 501
    (many,) = report_tools.search([sheet], "plain")
    assert many["matchedRows"] == 500 and len(many["rows"]) == report_tools.SEARCH_MAX_ROWS
    assert many["truncated"] is True


def test_a_capped_sheet_note_row_is_not_searched():
    sheet = {"name": "S", "columns": ["a"], "rows": [["x"], ["Note: capped"]], "noteRow": True, "prose": []}
    (found,) = report_tools.search([sheet], "capped")
    assert found["rows"] == [] and found["totalRows"] == 1


def test_preview_transcript_cells_are_cut_and_other_cells_are_not():
    long = "w" * (report_tools.PREVIEW_TRANSCRIPT_CHARS + 500)
    sheets = [{"rows": [[long, long]], "prose": [1]}]
    report_tools.cap_prose(sheets)
    kept, cut = sheets[0]["rows"][0]
    assert kept == long
    assert len(cut) == report_tools.PREVIEW_TRANSCRIPT_CHARS and cut.endswith("…")


def test_a_sheet_downloads_as_csv_with_a_bom_in_bounded_chunks():
    rows = [[f"r{i}", "x" * 300] for i in range(2000)]
    sheet = {"name": "Big", "columns": ["Id", "Text"], "rows": rows, "prose": []}
    chunks = list(report_tools.csv_chunks(sheet))
    assert len(chunks) > 1
    assert max(len(c) for c in chunks) < 600_000
    text = b"".join(chunks).decode("utf-8")
    assert text.startswith("﻿")
    parsed = list(csv.reader(io.StringIO(text.lstrip("﻿"))))
    assert parsed[0] == ["Id", "Text"] and len(parsed) == 2001


def test_sheets_are_picked_by_name_ignoring_case():
    sheets = [{"name": "Artisans"}, {"name": "Tools"}]
    assert report_tools.pick(sheets, "tools") == [{"name": "Tools"}]
    assert report_tools.pick(sheets, None) == sheets


def test_the_streaming_zip_is_readable_and_names_collisions():
    archive = zipstream.StreamingZip()
    out = bytearray()
    out += archive.add_bytes("a/one.txt", b"first")
    out += archive.add_bytes("a/one.txt", b"second")
    for part in archive.add_chunks("big.bin", (b"z" * 1000 for _ in range(50))):
        out += part
    out += archive.close()
    with zipfile.ZipFile(io.BytesIO(bytes(out))) as handle:
        names = handle.namelist()
        assert names == ["a/one.txt", "a/one (2).txt", "big.bin"]
        assert handle.read("a/one (2).txt") == b"second"
        assert len(handle.read("big.bin")) == 50_000
        assert handle.testzip() is None


def test_zip_entry_names_cannot_climb_out_of_the_archive():
    archive = zipstream.StreamingZip()
    assert archive.unique("../../etc/passwd") == "etc/passwd"
    assert archive.unique("") == "file"


def test_the_zip_writer_holds_one_file_at_a_time():
    archive = zipstream.StreamingZip()
    tracemalloc.start()
    try:
        total = 0
        for index in range(200):
            total += len(archive.add_bytes(f"f{index}.bin", bytes(64 * 1024)))
        _current, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    total += len(archive.close())
    assert total > 0
    assert peak < 4 * 1024 * 1024, peak


def _walker(include: set[str] | None = None) -> walk.Walk:
    async def no_level(_path: str, _scope: Any) -> Any:
        return [], None

    return walk.Walk(no_level, SimpleNamespace(), include)


def test_audio_is_listed_as_mp4_with_its_real_name_kept_for_the_fallback():
    walker = _walker()
    walker.emit_entries([_media_entry("m1", "AUDIO", "Talk.m4a", 99)], "Folder", set())
    (entry,) = walker.files
    assert entry["path"] == "Folder/Talk.mp4"
    assert entry["originalPath"] == "Folder/Talk.m4a"
    assert entry["convertToMp4"] is True and entry["sizeBytes"] == 99


def test_a_media_file_reached_twice_is_listed_once_and_include_filters_types():
    walker = _walker({"images"})
    entries = [
        _media_entry("m1", "IMAGE", "Loom.jpg"),
        _media_entry("m1", "IMAGE", "Loom.jpg"),
        _media_entry("m2", "VIDEO", "Clip.mp4"),
        {"name": "details.txt", "path": "details.txt", "kind": "file", "content": "about"},
    ]
    walker.emit_entries(entries, "", set())
    assert [f["path"] for f in walker.files] == ["Loom.jpg"]


def test_a_transcript_without_its_text_is_marked_to_be_filled_later():
    walker = _walker({"transcripts"})
    walker.emit_entries(
        [_media_entry("m1", "AUDIO", "Talk.m4a", transcriptAvailable=True, _transcriptName="Talk.md")],
        "",
        set(),
    )
    (entry,) = walker.files
    assert entry["_fill"] == "m1" and entry["content"] is None
    assert walk.media_count(walker.files) == 0
    stats.annotate(walker.files)
    assert entry["typeToken"] == "transcripts"


def test_the_walk_stops_at_the_file_cap_and_says_so(monkeypatch):
    monkeypatch.setattr(walk, "MAX_MANIFEST_FILES", 5)
    walker = _walker()
    walker.emit_entries([_media_entry(f"m{i}", "IMAGE", f"p{i}.jpg") for i in range(9)], "", set())
    assert len(walker.files) == 5 and walker.truncated is True


def test_a_deep_tree_is_walked_level_by_level_and_cut_at_the_depth_cap(monkeypatch):
    monkeypatch.setattr(walk, "MAX_WALK_DEPTH", 3)
    calls: list[str] = []

    async def level(path: str, _scope: Any) -> Any:
        calls.append(path)
        child = f"{path}/d" if path else "d"
        return [
            {"name": "d", "path": child, "kind": "folder"},
            _media_entry(f"m-{len(calls)}", "IMAGE", "p.jpg"),
        ], None

    walker = walk.Walk(level, SimpleNamespace(), None)
    asyncio.run(walker.run(""))
    assert len(calls) == 4
    assert walker.truncated is True
    assert [f["path"] for f in walker.files][:2] == ["p.jpg", "d/p.jpg"]


def test_one_walk_loads_a_shared_lookup_once():
    loads: list[str] = []

    async def load() -> str:
        loads.append("x")
        await asyncio.sleep(0)
        return "value"

    async def run() -> list[str]:
        token = walk.begin()
        try:
            got = await asyncio.gather(*(walk.memo("key", load) for _ in range(22)))
        finally:
            walk.end(token)
        outside = [await walk.memo("key", load), await walk.memo("key", load)]
        return [*got, *outside]

    values = asyncio.run(run())
    assert set(values) == {"value"}
    assert len(loads) == 3


def test_a_failed_lookup_is_not_remembered():
    attempts: list[int] = []

    async def flaky() -> str:
        attempts.append(1)
        if len(attempts) == 1:
            raise RuntimeError("boom")
        return "ok"

    async def run() -> str:
        token = walk.begin()
        try:
            with pytest.raises(RuntimeError):
                await walk.memo("k", flaky)
            return await walk.memo("k", flaky)
        finally:
            walk.end(token)

    assert asyncio.run(run()) == "ok"


def test_tree_pages_carry_links_and_leave_transcript_text_out_when_lean():
    entries = [
        {"name": "B", "path": "x/B", "kind": "folder"},
        _media_entry("img", "IMAGE", "a.jpg", _media=SimpleNamespace(mimeType="image/jpeg")),
        _media_entry("vid", "VIDEO", "c.mp4", transcriptAvailable=True),
        {"name": "t.transcript.md", "path": "t", "kind": "file", "content": "words", "mediaId": "aud", "_txMedia": "aud"},
        {"name": "details.txt", "path": "d", "kind": "file", "content": "about"},
    ]
    page, total, cursor = asyncio.run(tree.shape(entries, lean=True, limit=3))
    assert total == 5 and cursor == "3"
    assert [e["name"] for e in page] == ["B", "a.jpg", "c.mp4"]
    image = page[1]
    assert image["thumbnailPath"] == "/data/media/img/thumbnail"
    assert image["previewPath"] == "/data/media/img/preview"
    assert image["downloadPath"] == "/data/media/img/download"
    assert page[2]["transcriptPath"] == "/data/media/vid/transcript"
    rest, _total, done = asyncio.run(tree.shape(entries, lean=True, cursor=cursor, limit=3))
    assert done is None
    by_name = {e["name"]: e for e in rest}
    assert "content" not in by_name["t.transcript.md"]
    assert by_name["t.transcript.md"]["transcriptPath"] == "/data/media/aud/transcript"
    assert by_name["details.txt"]["content"] == "about"
    assert all(not k.startswith("_") for e in [*page, *rest] for k in e)


def test_svg_and_unknown_types_get_no_inline_preview():
    assert "previewPath" not in downloads.entry_links("s", "IMAGE", "image/svg+xml")
    assert "thumbnailPath" not in downloads.entry_links("s", "IMAGE", "image/svg+xml")
    assert set(downloads.entry_links("d", "OTHER")) == {"downloadPath"}
    assert "previewPath" in downloads.entry_links("p", "PDF", "application/pdf")
    pdf = SimpleNamespace(mediaType="PDF", mimeType="application/pdf")
    html = SimpleNamespace(mediaType="DOCUMENT", mimeType="text/html")
    assert downloads.previewable(pdf) and not downloads.previewable(html)


def test_a_thumbnail_is_a_small_webp_and_never_the_original():
    from PIL import Image

    source = io.BytesIO()
    Image.new("RGB", (2400, 1600), (120, 40, 20)).save(source, format="JPEG", quality=90)
    out = downloads.render_thumbnail(source.getvalue(), 256)
    with Image.open(io.BytesIO(out)) as small:
        assert small.format == "WEBP"
        assert max(small.size) == 256
    assert len(out) < len(source.getvalue())
    assert downloads.thumbnail_key("m1", 256) == "thumbnails/256/m1.webp"


def test_aac_audio_is_copied_and_anything_else_is_encoded():
    copied = convert.ffmpeg_args("in.m4a", "aac")
    encoded = convert.ffmpeg_args("in.opus", "opus")
    assert copied[copied.index("-c:a") + 1] == "copy"
    assert encoded[encoded.index("-c:a") + 1] == "aac"
    assert copied[-1] == "pipe:1" and "frag_keyframe+empty_moov+default_base_moof" in copied


def test_a_failed_conversion_sends_the_original_under_its_real_name(monkeypatch):
    signing.forget()
    monkeypatch.setattr(convert, "available", lambda: False)
    monkeypatch.setattr(
        signing, "signed_get", lambda key, **kw: (f"https://bucket.example/{key}?n={kw['filename']}", 900)
    )
    media = Row(
        id="m1",
        mediaType="AUDIO",
        mimeType="audio/ogg",
        sizeBytes=10,
        objectKey="media/u/abc/Talk.ogg",
        originalFilename="Talk.ogg",
        url=None,
        recordedAt=None,
        createdAt=None,
    )
    before = convert.snapshot().get("fallback", 0)
    response = asyncio.run(downloads.media_download(media, fmt="mp4", fallback=True))
    assert response.status_code == 307
    assert response.headers["X-Conversion"] == "original"
    assert response.headers["Cache-Control"] == "private, max-age=900"
    assert ".ogg" in response.headers["location"]
    assert convert.snapshot()["fallback"] == before + 1
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as refused:
        asyncio.run(downloads.media_download(media, fmt="mp4", fallback=False))
    assert refused.value.status_code == 503


def test_folder_transcript_zip_writes_each_format_and_skips_unreadable_ones():
    files = [
        {"path": "A/Talk.md", "mediaId": "m1", "content": "legacy words"},
        {"path": "A/Talk.srt", "mediaId": "m2", "transcriptFile": {"format": "srt", "track": "original", "timestamps": True}},
        {"path": "A/Gone.md", "mediaId": "m3", "transcriptFile": {"format": "md", "track": "both", "timestamps": True}},
    ]
    doc = SimpleNamespace(turns=[SimpleNamespace(end_ms=1000, english="")], english_text=None)

    async def load_doc(media_id: str) -> Any:
        return doc if media_id == "m2" else None

    rendered: list[tuple[str, str]] = []

    def fake_render(_doc: Any, fmt: str, track: str, _stamps: bool) -> bytes:
        rendered.append((fmt, track))
        return b"1\n00:00:00,000 --> 00:00:01,000\nhi\n"

    original = transcript_zip.render_bytes
    transcript_zip.render_bytes = fake_render
    try:
        async def collect() -> bytes:
            return b"".join([part async for part in transcript_zip.folder_zip(files, load_doc)])

        data = asyncio.run(collect())
    finally:
        transcript_zip.render_bytes = original
    with zipfile.ZipFile(io.BytesIO(data)) as handle:
        assert sorted(handle.namelist()) == ["A/Talk.md", "A/Talk.srt"]
        assert handle.read("A/Talk.md") == b"legacy words"
    assert rendered == [("srt", "original")]


def test_an_empty_folder_bundle_says_so():
    async def collect() -> bytes:
        async def none(_mid: str) -> Any:
            return None

        return b"".join([part async for part in transcript_zip.folder_zip([], none)])

    with zipfile.ZipFile(io.BytesIO(asyncio.run(collect()))) as handle:
        assert handle.namelist() == ["No transcripts.txt"]


def test_browse_pages_flag_a_cut_list_and_search_every_row():
    assert browse.envelope(120, 1, 100) == {"total": 120, "page": 1, "pageSize": 100, "pages": 2, "truncated": True}
    assert browse.envelope(120, 2, 100)["truncated"] is False
    assert browse.page_args(None, 500) == (1, browse.PAGE_SIZE_MAX)
    scope = SimpleNamespace(records={"createdById": "u1"}, media={"uploadedById": "u1"})
    where = browse.where_for(browse.KINDS["media"], scope, "loom  video", None)
    assert {"removedAt": None} in where["AND"]
    assert {"uploadedById": "u1"} in where["AND"]
    words = next(part for part in where["AND"] if "AND" in part)["AND"]
    assert len(words) == 2
    craft = browse.where_for(browse.KINDS["craft"], scope, None, None)
    assert craft == {"mergedIntoId": None}
    interviews = browse.where_for(browse.KINDS["interview"], scope, None, ["w1"])
    assert {"workshopId": {"in": ["w1"]}} in interviews["AND"]
    assert {"deletedAt": None, "mergedIntoId": None} in interviews["AND"]


def test_a_records_media_is_found_by_its_key_or_its_tag():
    where = browse.record_media_where("tool", "t1")
    assert {"toolId": "t1"} in where["OR"]
    assert browse.record_media_where("media", "x") is None
    process = browse.record_media_where("process", "p1")
    assert process == {"OR": [{"AND": [{"linkedRecordType": {"in": ["process"]}}, {"linkedRecordId": "p1"}]}]}


def test_browse_labels_match_the_web_rows():
    artisan = SimpleNamespace(name="Kamla", place="Bagru")
    assert browse.label("artisan", artisan) == "Kamla · Bagru"
    media = SimpleNamespace(originalFilename="", mediaType=SimpleNamespace(value="VIDEO"))
    assert browse.label("media", media) == "Media · VIDEO"


def test_a_recording_over_the_conversion_ceiling_is_refused_or_sent_as_it_is(monkeypatch):
    from fastapi import HTTPException

    monkeypatch.setattr(convert, "available", lambda: True)
    monkeypatch.setattr(signing, "signed_get", lambda key, **kw: (f"https://bucket.example/{key}", 60))
    media = Row(
        id="big",
        mediaType="AUDIO",
        mimeType="audio/wav",
        sizeBytes=convert.MAX_SOURCE_BYTES + 1,
        objectKey="media/u/abc/Big.wav",
        originalFilename="Big.wav",
    )
    with pytest.raises(HTTPException) as refused:
        asyncio.run(downloads.media_download(media, fmt="mp4", fallback=False))
    assert refused.value.status_code == 413
    sent = asyncio.run(downloads.media_download(media, fmt="mp4", fallback=True))
    assert sent.headers["X-Conversion"] == "original"
    plain = asyncio.run(downloads.media_download(media, fmt="original", fallback=False))
    assert plain.status_code == 307 and plain.headers["X-Conversion"] == "none"


def test_a_transcript_page_keeps_filling_until_it_is_full_for_a_narrow_viewer(monkeypatch):
    from app.api.routes import transcript_data

    ids = [f"m{n:02d}" for n in range(1, 31)]
    pages: list[str | None] = []

    async def select_page(f, cursor, *, limit, client=None):
        pages.append(cursor)
        start = 0 if cursor is None else ids.index(cursor) + 1
        return ids[start : start + limit]

    async def visible_fn(user):
        async def visible(page):
            return {mid for mid in page if int(mid[1:]) % 3 == 0}

        return visible

    async def load(shown, *, sees_spend=False, client=None):
        return {mid: {"mediaId": mid} for mid in shown}

    async def filters(request):
        return SimpleNamespace(echo=lambda: {})

    monkeypatch.setattr(transcript_data.transcript_export, "select_page", select_page)
    monkeypatch.setattr(transcript_data.transcript_export, "load", load)
    monkeypatch.setattr(transcript_data, "_visible_fn", visible_fn)
    monkeypatch.setattr(transcript_data, "_filters", filters)
    monkeypatch.setattr(transcript_data.transcript_portal, "may_see_spend", lambda user: False)

    first = asyncio.run(transcript_data.transcripts_page(request=None, cursor=None, limit=4, current_user=Row()))
    assert [i["mediaId"] for i in first["items"]] == ["m03", "m06", "m09", "m12"]
    assert first["nextCursor"] == "m12"
    second = asyncio.run(transcript_data.transcripts_page(request=None, cursor=first["nextCursor"], limit=4, current_user=Row()))
    assert [i["mediaId"] for i in second["items"]] == ["m15", "m18", "m21", "m24"]
    last = asyncio.run(transcript_data.transcripts_page(request=None, cursor=second["nextCursor"], limit=4, current_user=Row()))
    assert [i["mediaId"] for i in last["items"]] == ["m27", "m30"]
    assert last["nextCursor"] is None
    assert len(pages) == 8


def test_the_visibility_check_reads_ids_only(monkeypatch):
    from app.api.routes import data_browser, transcript_data

    seen: dict[str, Any] = {}

    async def group_by(**kwargs):
        seen.update(kwargs)
        return [{"id": "m1"}]

    async def scope_for(user):
        return SimpleNamespace(media={"createdById": "u1"})

    monkeypatch.setattr(data_browser, "_scope_for", scope_for)
    monkeypatch.setattr(transcript_data, "db", SimpleNamespace(mediafile=SimpleNamespace(group_by=group_by)))
    visible = asyncio.run(transcript_data._visible_fn(Row()))
    assert asyncio.run(visible(["m1", "m2"])) == {"m1"}
    assert seen["by"] == ["id"]
    assert seen["take"] == 2


def test_has_transcript_reads_the_cheap_flag_before_any_text():
    from app.services.data_explorer import slim

    assert slim.has_transcript(Row(hasTranscript=True)) is True
    assert slim.has_transcript(Row(hasTranscript=False, transcriptText="ignored")) is False
    assert slim.has_transcript(Row(transcriptText="  ")) is False
    assert slim.has_transcript(Row(transcriptText="Clay and gum")) is True
