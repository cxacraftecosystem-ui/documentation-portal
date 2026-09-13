"""``services/uploads.read_upload_bounded`` — A30-10, the cap that used to be checked too late.

THE DEFECT, IN TWO ROUTES THAT STILL HAVE IT AND ONE NEW DOOR THAT DOES NOT::

    content = await file.read()
    if len(content) > SOME_MAX:
        raise HTTPException(413, ...)

``await file.read()`` with no argument reads to EOF. So the ceiling is enforced one line AFTER the
cost it exists to avoid has been paid in full: an 800 MB body is materialised in one contiguous
``bytes`` and only then measured and refused. The 413 is honest and useless.

``POST /media/transcribe`` (app/api/routes/media.py:228) and ``POST /media/analyze-measurement``
(:240) are still written that way at the time this file was added — both call into
``app/services/ai.py``, which does the unbounded read. Each already has a ``*_bytes`` twin beside it
(``ai.py:758``, ``ai.py:1101``), so converting each is a two-line route change; media.py was owned by
another workstream when this module landed. THE TESTS BELOW TEST THE HELPER, NOT THOSE ROUTES, and
they are what makes the conversion a two-line change rather than a rewrite.

**THE BOX IS THE ARGUMENT.** A single-worker uvicorn on 1 GiB (MEASURED, docs/SCALABILITY.md §5.1),
with no supervisor to respawn a worker that overcommits — ``--workers >1`` is what caused the outage
``app/worker.py``'s docstring describes. A body larger than the free heap does not degrade one
request; it takes every in-flight request on the box with it, including the sign-in of whoever is
trying to find out why. The cheapest attempt is one signed-in account, one request, one large file.

WHAT EACH HALF IS FOR, since the tests below are organised by it:

* The ``Content-Length`` pre-check skips the COPY out of the spooled part, so the ordinary honest
  mistake — a 400 MB video attached to an 8 MB field — costs one integer comparison rather than
  four hundred loop passes. NOT "before a byte leaves the socket": the framework parses the
  multipart form before the route function is entered, so the body has already arrived and been
  spooled. It is fast and forgeable.
* The chunked loop is the GUARANTEE. It bounds a body whose header lies or is absent (a chunked
  transfer sends none), and it is what makes ``max_bytes`` a real bound rather than a hint.

Neither is redundant and they fail in opposite directions, which is why there is a section for each.

THE FILE IS A STAND-IN, NOT AN ``UploadFile``. What is under test is which numbers the function
compares and how much it has read by the time it refuses, and a Starlette ``UploadFile`` wrapping a
``SpooledTemporaryFile`` would answer both questions identically while hiding the second one. The
stub COUNTS its reads, which is the only way to assert "it refused before reading" as a fact rather
than as a hope.
"""

import asyncio

import pytest
from fastapi import HTTPException

from app.services import uploads

MB = 1024 * 1024


class _CountingFile:
    """An ``UploadFile``-shaped source that records every read it is asked for.

    ``read(size)`` is the whole protocol ``read_upload_bounded`` uses. The recorded sizes are what
    let a test say "nothing was read" or "it stopped after two chunks" rather than inferring it.
    """

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
    """Just enough of a request for the header read: a ``headers`` mapping."""

    def __init__(self, content_length: object = None) -> None:
        self.headers = {} if content_length is None else {"content-length": str(content_length)}


def _read(file, max_bytes: int, **kwargs):
    return asyncio.run(uploads.read_upload_bounded(file, max_bytes, **kwargs))


# --------------------------------------------------------------------------------------
# 1. The ordinary path is unchanged
# --------------------------------------------------------------------------------------


def test_a_file_inside_the_limit_comes_back_whole():
    """The control. A bound that also truncated would be a far worse defect than the one being
    fixed — a grid photograph half-read is a measurement quietly taken from the wrong picture."""
    payload = b"x" * (3 * MB) + b"tail"
    got = _read(_CountingFile(payload), 8 * MB, request=_Request(len(payload)))
    assert got == payload
    assert isinstance(got, bytes)


def test_a_file_exactly_at_the_limit_is_accepted():
    """The boundary is ``>``, not ``>=``: a caller who sends precisely the documented limit did what
    the error message told them to and must not be refused for it."""
    payload = b"y" * (8 * MB)
    assert len(_read(_CountingFile(payload), 8 * MB)) == 8 * MB


def test_an_empty_upload_returns_empty_rather_than_raising():
    """"No file" is the CALLER'S problem to name — ``_read_workbook_upload`` answers 422 with a sentence
    telling the admin to attach the filled-in pro-forma — so this must hand back the empty bytes
    rather than inventing a refusal of its own and taking that message away."""
    assert _read(_CountingFile(b""), 8 * MB) == b""


# --------------------------------------------------------------------------------------
# 2. The pre-read refusal: nothing is read at all
# --------------------------------------------------------------------------------------


def test_a_content_length_over_the_limit_refuses_before_reading_a_byte():
    """THE DEFECT, AT ITS CHEAPEST. This is the whole point of taking a ``request``: an 800 MB body
    announced in the header is refused with the socket untouched, rather than after the box has
    already spent the 8 MB it was always going to spend before noticing."""
    file = _CountingFile(b"z" * (12 * MB))
    with pytest.raises(HTTPException) as excinfo:
        _read(file, 8 * MB, request=_Request(800 * MB))
    assert excinfo.value.status_code == 413
    assert file.reads == []
    assert file.bytes_read == 0


def test_the_multipart_envelope_does_not_cost_a_caller_their_upload():
    """``Content-Length`` on a ``multipart/form-data`` request measures the WHOLE envelope — part
    boundaries, ``Content-Disposition`` headers, the other form fields, the trailing delimiter — not
    the file. So a file of exactly ``max_bytes`` arrives with a Content-Length a few hundred bytes
    above it, and a strict comparison would 413 an upload sitting precisely at the documented limit.
    That is why ``CONTENT_LENGTH_SLACK_BYTES`` exists, and this is the case it exists for."""
    payload = b"m" * (8 * MB)
    declared = len(payload) + 512  # a realistic envelope for one file part and two small fields
    assert len(_read(_CountingFile(payload), 8 * MB, request=_Request(declared))) == 8 * MB


def test_the_slack_is_not_a_loophole():
    """The slack admits an envelope, not a second file. Anything past it is refused on the header,
    and everything inside it still falls through to the loop — which is the real bound."""
    file = _CountingFile(b"m" * (9 * MB))
    with pytest.raises(HTTPException):
        _read(file, 8 * MB, request=_Request(8 * MB + uploads.CONTENT_LENGTH_SLACK_BYTES + 1))
    assert file.reads == []


@pytest.mark.parametrize("header", [None, "", "  ", "not-a-number", "-1", "1e9"])
def test_an_unusable_content_length_falls_through_to_the_loop(header):
    """A missing header (every chunked transfer sends none), a blank one, a negative one and a
    non-numeric one all mean the same thing: this pre-check could not be made. The same shape
    ``s3.head_object`` uses, and for the same reason — the caller then falls back to a bound it can
    actually enforce. It must never become a 500 on a path whose real answer is one line down."""
    payload = b"q" * (2 * MB)
    got = _read(_CountingFile(payload), 8 * MB, request=_Request(header))
    assert got == payload


def test_a_request_object_with_no_headers_at_all_is_simply_no_evidence():
    """Defensive because this helper is called from three routes by three lanes, and a caller that
    passes something request-shaped but header-less must get the loop, not an AttributeError."""
    assert _read(_CountingFile(b"a" * 100), 8 * MB, request=object()) == b"a" * 100


# --------------------------------------------------------------------------------------
# 3. The chunked read: the guarantee, with or without a header
# --------------------------------------------------------------------------------------


def test_a_lying_content_length_is_caught_by_the_loop():
    """THE REASON THE LOOP IS THE GUARANTEE AND THE HEADER IS THE OPTIMISATION. ``Content-Length``
    is written by the client; a caller who wants to send 40 MB past an 8 MB gate simply declares
    1024. The bound has to be enforced on what actually arrives."""
    file = _CountingFile(b"w" * (40 * MB))
    with pytest.raises(HTTPException) as excinfo:
        _read(file, 8 * MB, request=_Request(1024))
    assert excinfo.value.status_code == 413


def test_the_loop_stops_within_one_chunk_of_the_limit():
    """The peak this function will ever hold is ``max_bytes`` plus one chunk — that IS the fix. If
    it read to EOF and then measured, this assertion would read 40 MB and the defect would be back
    with a passing test suite in front of it."""
    file = _CountingFile(b"w" * (40 * MB))
    with pytest.raises(HTTPException):
        _read(file, 8 * MB)
    assert file.bytes_read <= 8 * MB + uploads.CHUNK_BYTES


def test_no_request_is_a_supported_call_and_still_bounded():
    """*request* is optional because not every caller has one to hand. Omitting it loses the cheap
    early refusal and nothing that is load-bearing."""
    file = _CountingFile(b"w" * (20 * MB))
    with pytest.raises(HTTPException) as excinfo:
        _read(file, 6 * MB)
    assert excinfo.value.status_code == 413


def test_the_read_is_chunked_rather_than_one_read_to_eof():
    """The mechanism, asserted directly. ``read()`` with no argument — or with ``-1`` — is the exact
    call this module exists to stop making, so a "simplification" back to it must fail here."""
    file = _CountingFile(b"c" * (3 * MB))
    _read(file, 8 * MB)
    assert file.reads
    assert all(size == uploads.CHUNK_BYTES for size in file.reads)


# --------------------------------------------------------------------------------------
# 4. What the caller is told
# --------------------------------------------------------------------------------------


def test_the_refusal_is_one_sentence_naming_the_limit_in_mb():
    """Owner's standing instruction: one line, state-fact plus action, professional. The argument
    for the number belongs in the comment above the constant that sets it, never on screen."""
    with pytest.raises(HTTPException) as excinfo:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB)
    detail = excinfo.value.detail
    assert "8 MB" in detail
    assert detail.count(".") == 1
    assert len(detail) < 120


def test_the_purpose_names_the_thing_the_route_asked_for():
    """One helper speaks for three routes — a grid photograph, a dictation clip, a questionnaire
    workbook — and none of them should sound like a generic file server."""
    with pytest.raises(HTTPException) as excinfo:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="image")
    assert "image" in excinfo.value.detail


def test_both_refusals_read_identically():
    """A caller must not be able to tell from the wording whether the server read their body or not,
    and two spellings of one limit is two places for the number to go stale."""
    with pytest.raises(HTTPException) as early:
        _read(_CountingFile(b"x" * MB), 8 * MB, request=_Request(900 * MB), purpose="image")
    with pytest.raises(HTTPException) as late:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="image")
    assert early.value.detail == late.value.detail


# --------------------------------------------------------------------------------------
# 5. The remedy — the per-site advice a shared 413 sentence would otherwise delete
#
# Two of this repository's upload routes have a second clause that is NOT a restatement of "send a
# smaller file": the grid-sheet reader ("photograph the grid sheet alone rather than the whole
# workbench") and dictation ("upload a longer recording as workshop audio instead"). The first is the
# action that clears the refusal from a phone camera; the second names the ONLY route that accepts
# what the researcher is holding, which is the documented reason a dictation ceiling is a low number
# rather than an arbitrary one. Moving those routes onto the shared sentence WITHOUT the remedy
# parameter would silently delete both, so the parameter and these tests exist before the move.
# --------------------------------------------------------------------------------------

#: The two sentences the MEDIA routes will pass when they are converted onto this helper, written
#: down here so the conversion is a two-line change and the shape is already asserted.
#:
#: NO ROUTE IN THIS REPOSITORY PASSES EITHER OF THEM TODAY, and that is stated rather than left to be
#: inferred: the questionnaire workbook route — the only caller of ``read_upload_bounded`` that
#: exists so far — deliberately passes no remedy at all, which is what
#: ``test_a_site_that_passes_no_remedy_is_unchanged`` below pins. These are the sentences
#: ``media.py`` must use, kept as literals AT THE CALL SITES rather than imported from here, because
#: each belongs to one route and a shared constant would invite a third route to reach for one.
CARD_REMEDY = "Photograph the grid sheet alone rather than the whole workbench."
DICTATION_REMEDY = (
    "Upload a longer recording as workshop audio instead — it is transcribed in the background."
)


def test_a_remedy_is_appended_as_a_second_sentence_and_does_not_replace_the_first():
    """THE NUMBER STILL COMES FIRST. A remedy that swallowed the shared sentence would take the
    limit off the screen, which is the one fact a caller cannot work out for themselves."""
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
    """THE COMPATIBILITY HALF, AND IT IS NOT THEORETICAL. Both clients print ``detail`` verbatim and
    the questionnaire workbook route — the only caller today — passes no remedy, so the parameter
    being optional has to mean byte-identical and not merely similar: a fielded build must not start
    reading a different sentence because a sibling route gained one."""
    with pytest.raises(HTTPException) as without:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="spreadsheet")
    with pytest.raises(HTTPException) as explicit_none:
        _read(_CountingFile(b"x" * (20 * MB)), 8 * MB, purpose="spreadsheet", remedy=None)
    assert without.value.detail == explicit_none.value.detail
    assert without.value.detail == "That spreadsheet is over the 8 MB limit; send a smaller file."


def test_the_remedy_reaches_the_header_refusal_too():
    """The cheap refusal and the mid-read one must still be indistinguishable. A remedy that only
    the loop carried would mean an admin who declared a Content-Length got less help than one who
    did not — the same asymmetry ``test_both_refusals_read_identically`` rules out for the number."""
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
    """Owner's standing instruction, applied to the clause that was reinstated rather than only to
    the one that was already there: one line, state-fact plus action, professional. This is read on
    a phone in a courtyard, so a remedy that grew into a paragraph is a regression even though every
    word of it would be true."""
    assert remedy.endswith(".")
    assert remedy.count(".") == 1
    assert "\n" not in remedy
    with pytest.raises(HTTPException) as excinfo:
        _read(_CountingFile(b"x" * (20 * MB)), 6 * MB, purpose="dictated clip", remedy=remedy)
    assert len(excinfo.value.detail) < 200
