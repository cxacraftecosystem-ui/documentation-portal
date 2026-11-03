import asyncio

import pytest
from fastapi import HTTPException

from app.services import uploads

MB = 1024 * 1024


class _CountingFile:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload
        self._offset = 0
        self.reads: list[int] = []

    async def read(self, size: int = -1) -> bytes:
        self.reads.append(size)
        if size is None or size < 0:
            chunk = self._payload[self._offset :]
            self._offset = len(self._payload)
            return chunk
        chunk = self._payload[self._offset : self._offset + size]
        self._offset += len(chunk)
        return chunk

    @property
    def bytes_read(self) -> int:
        return self._offset


class _Request:
    def __init__(self, content_length: object = None) -> None:
        self.headers = {} if content_length is None else {"content-length": str(content_length)}


def _read(file, max_bytes: int, **kwargs):
    return asyncio.run(uploads.read_upload_bounded(file, max_bytes, **kwargs))



def test_a_file_inside_the_limit_comes_back_whole():
    payload = b"x" * (3 * MB) + b"tail"
    got = _read(_CountingFile(payload), 8 * MB, request=_Request(len(payload)))
    assert got == payload
    assert isinstance(got, bytes)


def test_a_file_exactly_at_the_limit_is_accepted():
    payload = b"y" * (8 * MB)
    assert len(_read(_CountingFile(payload), 8 * MB)) == 8 * MB


def test_an_empty_upload_returns_empty_rather_than_raising():
    assert _read(_CountingFile(b""), 8 * MB) == b""



def test_a_content_length_over_the_limit_refuses_before_reading_a_byte():
    file = _CountingFile(b"z" * (12 * MB))
    with pytest.raises(HTTPException) as excinfo:
        _read(file, 8 * MB, request=_Request(800 * MB))
    assert excinfo.value.status_code == 413
    assert file.reads == []
    assert file.bytes_read == 0


def test_the_multipart_envelope_does_not_cost_a_caller_their_upload():
    payload = b"m" * (8 * MB)
    declared = len(payload) + 512
    assert len(_read(_CountingFile(payload), 8 * MB, request=_Request(declared))) == 8 * MB


def test_the_slack_is_not_a_loophole():
    file = _CountingFile(b"m" * (9 * MB))
    with pytest.raises(HTTPException):
        _read(file, 8 * MB, request=_Request(8 * MB + uploads.CONTENT_LENGTH_SLACK_BYTES + 1))
    assert file.reads == []


@pytest.mark.parametrize("header", [None, "", "  ", "not-a-number", "-1", "1e9"])
def test_an_unusable_content_length_falls_through_to_the_loop(header):
    payload = b"q" * (2 * MB)
    got = _read(_CountingFile(payload), 8 * MB, request=_Request(header))
    assert got == payload


def test_a_request_object_with_no_headers_at_all_is_simply_no_evidence():
    assert _read(_CountingFile(b"a" * 100), 8 * MB, request=object()) == b"a" * 100



def test_a_lying_content_length_is_caught_by_the_loop():
    file = _CountingFile(b"w" * (40 * MB))
    with pytest.raises(HTTPException) as excinfo:
        _read(file, 8 * MB, request=_Request(1024))
    assert excinfo.value.status_code == 413


def test_the_loop_stops_within_one_chunk_of_the_limit():
    file = _CountingFile(b"w" * (40 * MB))
    with pytest.raises(HTTPException):
        _read(file, 8 * MB)
    assert file.bytes_read <= 8 * MB + uploads.CHUNK_BYTES


def test_no_request_is_a_supported_call_and_still_bounded():
    file = _CountingFile(b"w" * (20 * MB))
    with pytest.raises(HTTPException) as excinfo:
        _read(file, 6 * MB)
    assert excinfo.value.status_code == 413


def test_the_read_is_chunked_rather_than_one_read_to_eof():
    file = _CountingFile(b"c" * (3 * MB))
    _read(file, 8 * MB)
    assert file.reads
    assert all(size == uploads.CHUNK_BYTES for size in file.reads)



def test_the_refusal_is_one_sentence_naming_the_limit_in_mb():
    with pytest.raises(HTTPException) as excinfo:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB)
    detail = excinfo.value.detail
    assert "8 MB" in detail
    assert detail.count(".") == 1
    assert len(detail) < 120


def test_the_purpose_names_the_thing_the_route_asked_for():
    with pytest.raises(HTTPException) as excinfo:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="image")
    assert "image" in excinfo.value.detail


def test_both_refusals_read_identically():
    with pytest.raises(HTTPException) as early:
        _read(_CountingFile(b"x" * MB), 8 * MB, request=_Request(900 * MB), purpose="image")
    with pytest.raises(HTTPException) as late:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="image")
    assert early.value.detail == late.value.detail


CARD_REMEDY = "Photograph the grid sheet alone rather than the whole workbench."
DICTATION_REMEDY = (
    "Upload a longer recording as workshop audio instead — it is transcribed in the background."
)


def test_a_remedy_is_appended_as_a_second_sentence_and_does_not_replace_the_first():
    with pytest.raises(HTTPException) as excinfo:
        _read(
            _CountingFile(b"x" * (20 * MB)),
            8 * MB,
            purpose="identity card photograph",
            remedy=CARD_REMEDY,
        )
    detail = excinfo.value.detail
    assert detail.startswith("That identity card photograph is over the 8 MB limit;")
    assert detail.endswith(CARD_REMEDY)


def test_a_site_that_passes_no_remedy_is_unchanged():
    with pytest.raises(HTTPException) as without:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="spreadsheet")
    with pytest.raises(HTTPException) as explicit_none:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="spreadsheet", remedy=None)
    assert without.value.detail == explicit_none.value.detail
    assert without.value.detail == "That spreadsheet is over the 8 MB limit; send a smaller file."


def test_the_remedy_reaches_the_header_refusal_too():
    with pytest.raises(HTTPException) as early:
        _read(
            _CountingFile(b"x" * MB),
            6 * MB,
            request=_Request(900 * MB),
            purpose="dictated clip",
            remedy=DICTATION_REMEDY,
        )
    with pytest.raises(HTTPException) as late:
        _read(
            _CountingFile(b"x" * (20 * MB)),
            6 * MB,
            purpose="dictated clip",
            remedy=DICTATION_REMEDY,
        )
    assert early.value.detail == late.value.detail
    assert DICTATION_REMEDY in early.value.detail


@pytest.mark.parametrize("remedy", [CARD_REMEDY, DICTATION_REMEDY])
def test_a_remedy_stays_one_short_line(remedy: str):
    assert remedy.endswith(".")
    assert remedy.count(".") == 1
    assert "\n" not in remedy
    with pytest.raises(HTTPException) as excinfo:
        _read(_CountingFile(b"x" * (20 * MB)), 6 * MB, purpose="dictated clip", remedy=remedy)
    assert len(excinfo.value.detail) < 200
