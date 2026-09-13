"""Reading a multipart upload without first agreeing to hold all of it.

================================================================================================
A30-10: EVERY UPLOAD CAP IN THIS API WAS CHECKED AFTER THE BODY WAS ALREADY IN THE HEAP
================================================================================================

Three routes take a file straight from a caller — ``POST /media/transcribe``
(app/api/routes/media.py:228), ``POST /media/analyze-measurement`` (:240) and the questionnaire
workbook import added beside this module — and the first two are still written the same way::

    content = await file.read()
    if len(content) > SOME_MAX:
        raise HTTPException(413, ...)

``await file.read()`` with no argument reads to EOF. So the ceiling is enforced one line after the
cost it exists to avoid has already been paid in full: an 800 MB body is materialised, in one
contiguous ``bytes``, and only then measured and refused. The 413 is honest and useless.

**THE TWO MEDIA ROUTES ARE NOT YET CONVERTED, AND THAT IS RECORDED HERE RATHER THAN LEFT TO BE
DISCOVERED.** Both already have a ``*_bytes`` twin sitting beside them — ``app/services/ai.py:758
transcribe_audio_bytes`` and ``:1101 analyze_measurement_image_bytes`` — so converting each is a
two-line route change: read the bytes through this module, hand them to the twin. It was not done in
the change that introduced this file because ``app/api/routes/media.py`` was owned by another
workstream at the time. Anyone reading this module because they are about to add a THIRD upload door
should convert those two on the way past; this docstring is the argument for doing it, and it is
about their shape, not about the questionnaire.

**THE BOX IS THE ARGUMENT.** This deployment is a single-worker uvicorn on 1 GiB (MEASURED,
docs/SCALABILITY.md §5.1 — the same document that records the 668.44 MiB live object, at
docs/SCALABILITY.md:292). There is no supervisor to kill and respawn a worker that overcommits,
because ``--workers >1`` is what caused the outage ``app/worker.py``'s docstring describes; so a body
larger than the free heap does not degrade one request, it takes every in-flight request on the box
down with it, including the sign-in of whoever is trying to find out why. And no authentication gate
helps: the cheapest possible attempt is one signed-in account, one request, one large file.

**WHAT THIS MODULE BOUNDS, STATED CORRECTLY.** It bounds the HEAP and the work. It does not bound the
network, and it does not bound the disk.

It is tempting to write that the ``Content-Length`` check "answers before a single byte of the body
has been pulled off the socket". That is not how a FastAPI multipart handler is reached. Binding a
``file: UploadFile`` parameter makes the framework parse the form BEFORE the route function is
entered, so by the time any line below runs the whole body has already been read off the socket and
written into a ``SpooledTemporaryFile`` — MEASURED IN THIS REPOSITORY'S OWN VIRTUALENV ON
2026-09-13 against starlette 1.6.0, ``formparsers.py:147`` (``spool_max_size = 1024 * 1024``) and
``:230`` (``SpooledTemporaryFile(max_size=self.spool_max_size)``), so a part over 1 MiB is on DISK
before this module is consulted. The date is on that sentence deliberately: this repository has no
lockfile (backend/pyproject.toml pins ``>=`` floors, not versions), so the measurement has a shelf
life and a reader is entitled to know how old it is. No check written in Python here can stop those
bytes arriving; the socket read is finished before the first one executes.

**SO THE CHUNKED LOOP IS STILL THE POINT, AND THE HARM IT PREVENTS IS THE REAL ONE.**
``await file.read()`` with no argument copies that entire spooled part into ONE CONTIGUOUS ``bytes``
in the process heap. That is the A30-10 cost on a 1 GiB single-worker box: not the transfer, which
has already happened either way, but the resident copy — and it is the copy that takes every
in-flight request down with it. The loop caps what is ever resident at *max_bytes* plus one chunk,
whatever the body turned out to be.

**AND THE ``Content-Length`` CHECK IS STILL WORTH ITS LINE, FOR A SMALLER AND HONEST REASON.** It
skips the pointless copy: an 800 MB spool that is going to be refused is refused without being read
back into the heap at all, and the refusal is decided in one integer comparison instead of eight
hundred loop passes. It is an efficiency, not a shield. It is also advisory — absent on a chunked
transfer, and a claim rather than a fact on any transfer — which is why the loop, and not the
header, is what the guarantee rests on.

**WHAT DOES BOUND THE NETWORK AND THE DISK IS UPSTREAM, AND IT IS NOT IN THIS REPOSITORY'S PYTHON.**
``client_max_body_size 200M`` on the nginx site in ``infra/terraform/user_data.sh:27`` (mirrored as
the ingress annotation in ``infra/k8s/base/ingress.yaml:9-11``) is what refuses an oversized body
before the application ever sees it, and it is therefore the only ceiling that bounds what the spool
can write to the instance's disk. A change to the numbers in this module is a change to heap; a
change to what this box will ACCEPT is a change to that nginx directive.

**IT IS DELIBERATELY NOT A MIDDLEWARE.** A global body cap would need one number for every route in
the API, and the numbers here differ by an order of magnitude on purpose — 8 MB for a questionnaire
workbook, 8 MB for a grid-sheet photograph, and 25 MB for a dictation clip, which is a whole field
recording and is the largest thing this API should accept inline. A single ceiling would be either
useless at the top or a refusal at the bottom.
"""

from typing import Any

from fastapi import HTTPException, status

#: How much is copied out of the spooled part per iteration — NOT off the socket, which the
#: framework's form parser finished with before this module was reached (see the correction in the
#: module docstring). Small enough that the overshoot past the limit is bounded by one chunk, large
#: enough that an 8 MB workbook is eight loop passes and not eight thousand.
CHUNK_BYTES = 1024 * 1024

#: How far a ``Content-Length`` may exceed *max_bytes* before the pre-COPY refusal fires.
#:
#: THIS SLACK IS NOT TIMIDITY; WITHOUT IT THE HEADER CHECK REFUSES FILES THE LOOP WOULD ACCEPT.
#: ``Content-Length`` on a ``multipart/form-data`` request measures the WHOLE envelope — every part
#: boundary, every ``Content-Disposition`` header, the other form fields, the trailing delimiter —
#: not the file. So a file of exactly *max_bytes* arrives with a Content-Length of *max_bytes* plus
#: a few hundred bytes, and a strict comparison would 413 an upload that is precisely at the
#: documented limit. Refusing a caller who did what the error message told them to is the one
#: failure mode a size gate must not have. 64 KiB is far more than any envelope this API receives
#: (a handful of small fields) and far less than any body worth pre-refusing; anything inside it
#: falls through to the loop, which measures the file itself and is the actual bound.
CONTENT_LENGTH_SLACK_BYTES = 64 * 1024


def _limit_label(max_bytes: int) -> str:
    """The ceiling as a reader sees it. MB, because every caller's limit is single- or double-digit MB."""
    megabytes = max_bytes / (1024 * 1024)
    return f"{megabytes:.0f} MB" if megabytes >= 1 else f"{megabytes:.2f} MB"


def _too_large(max_bytes: int, purpose: str, remedy: str | None = None) -> HTTPException:
    """The one 413 this module raises, from both the header check and the loop.

    ONE SENTENCE, AND IT NAMES THE NUMBER. The argument for the ceiling belongs in the comment above
    the constant that sets it, never on a researcher's screen — so this says what happened and what
    to do, once, and stops. Both refusals share it deliberately: a caller must not be able to tell
    from the wording whether the server read their body or not, and two spellings of one limit is
    two places for the number to go stale.

    **AND A SECOND SENTENCE WHERE THE SITE HAS ONE, WHICH IS NOT A RETREAT FROM THE RULE ABOVE.**
    Some call sites have real advice that is not interchangeable with "send a smaller file": telling
    somebody to photograph the grid sheet alone rather than the whole workbench is the action that
    clears the refusal, and telling somebody that a longer recording belongs in workshop audio names
    the ONLY route that accepts it. Neither is the argument FOR the ceiling; both are what the person
    on the screen does next. The per-caller half stays per-caller, the number and its phrasing stay
    here, and a site with nothing useful to add passes nothing and gets exactly the sentence it would
    have got anyway.
    """
    detail = f"That {purpose} is over the {_limit_label(max_bytes)} limit; send a smaller file."
    return HTTPException(
        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        detail=f"{detail} {remedy}" if remedy else detail,
    )


async def read_upload_bounded(
    file: Any,
    max_bytes: int,
    *,
    request: Any = None,
    purpose: str = "upload",
    remedy: str | None = None,
) -> bytes:
    """Read an ``UploadFile`` into memory, refusing anything over *max_bytes*.

    Returns the bytes on success. Raises ``HTTPException(413)`` — carrying one terse sentence that
    names the limit in MB — the moment the upload is known to be too large: before the spooled body
    is copied into the heap at all when *request* was given and its ``Content-Length`` says so, and
    otherwise as soon as the running total passes the ceiling mid-read.

    NOT "before the body is read". The framework has already parsed the multipart form and spooled
    it by the time this function is called — see the correction in this module's docstring. What the
    header check skips is the COPY, not the transfer.

    *request* is optional because not every caller has one to hand and because the header is
    advisory: it is absent on a chunked transfer and can be wrong on any transfer. Passing it skips
    a pointless read; omitting it loses nothing that is load-bearing, since the loop is the
    guarantee either way.

    *purpose* is the noun the 413 uses — "image", "recording", "questionnaire workbook" — so one
    helper can speak for three routes without any of them sounding like a generic file server.

    *remedy* is one short sentence appended after it, for a site where "send a smaller file" is not
    the useful instruction. Optional and defaulted, so no existing caller and no fielded client
    changes shape; a site that passes nothing gets exactly the sentence it got before. Keep it to
    ONE line: it is read in a courtyard on a phone, and both clients print ``detail`` verbatim.

    THE PEAK COST IS THE BOUND, NOT THE BODY, and that is the whole difference from
    ``await file.read()``. Bytes accumulate in a ``bytearray`` that is checked after every chunk, so
    the most this can ever be holding when it refuses is *max_bytes* plus one chunk. The ``bytes()``
    conversion on the way out copies once — the callers here are bounded to tens of MB, and handing
    a mutable buffer to a provider request body would make every one of them reason about whether
    anything downstream mutates it.
    """
    if request is not None:
        declared = _declared_length(request)
        if declared is not None and declared > max_bytes + CONTENT_LENGTH_SLACK_BYTES:
            raise _too_large(max_bytes, purpose, remedy)

    buffer = bytearray()
    while True:
        chunk = await file.read(CHUNK_BYTES)
        if not chunk:
            break
        buffer += chunk
        if len(buffer) > max_bytes:
            # BOTH REFUSALS CARRY THE REMEDY, for the reason ``_too_large`` gives about the number:
            # a caller must not be able to tell from the wording whether the server read their body.
            raise _too_large(max_bytes, purpose, remedy)
    return bytes(buffer)


def _declared_length(request: Any) -> int | None:
    """``Content-Length`` as an int, or None for anything that is not one.

    A missing header, a non-numeric one, a negative one and a request object that has no headers at
    all all answer None — "this pre-check could not be made" — which is the same shape
    ``s3.head_object`` uses for the same reason: the caller then falls back to a bound it can
    actually enforce. Never raises, because a malformed header must not turn into a 500 on a path
    whose real answer is one line further down.
    """
    try:
        raw = request.headers.get("content-length")
    except Exception:  # noqa: BLE001 — an object without headers is simply no evidence
        return None
    if raw is None:
        return None
    try:
        declared = int(str(raw).strip())
    except (TypeError, ValueError):
        return None
    return declared if declared >= 0 else None
