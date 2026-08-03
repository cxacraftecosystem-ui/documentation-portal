"""The bulk dataset API: the whole repository, for a machine, behind admin credentials.

WHAT THIS IS FOR, AND WHY IT IS NOT /export. Everything under ``/api/export`` and ``/api/data``
answers a BROWSER: it builds one artefact for one click — a .xlsx a researcher opens, a manifest the
web client zips in the tab that asked for it. All of it is bounded by what a page can hold, all of it
buffers the answer in the web process before sending a byte, and all of it authenticates as a person
with a session. None of that suits the job this module exists for: a scheduled mirror, an archival
snapshot, a statistics pipeline — a caller with no browser, no session and no upper bound on how much
it wants.

So this module is built on three commitments the browser surfaces cannot make:

1. **It streams.** ``/{dataset}.ndjson`` and ``/{dataset}.csv`` hold one batch of rows in memory at a
   time, however many rows the table has, and start emitting immediately. The web box is a
   single-worker t3.micro behind a CloudFront origin with a read timeout, so an endpoint that
   assembles a 200 MB answer before its first byte is an endpoint that reports a gateway timeout
   after doing all of the work. Streaming also means these routes have NO row cap, which is the one
   thing an archival download must not have — and why they are worth adding rather than raising
   ``EXPORT_TAKE``.
2. **It is paged by KEYSET, not by OFFSET.** See ``_stream_rows``.
3. **It says how much there is.** Every stream sends ``X-Dataset-Total`` before the body, so a client
   can compare it against what arrived instead of assuming. A bulk download that stops early and
   looks complete is the failure mode this repository has hit most often.

   Read it precisely: it is the row count AT THE MOMENT THE EXPORT BEGAN, taken by a separate query
   from the walk that follows, so it is a tripwire and not a checksum. A mismatch means either a
   truncated transfer or a concurrent write, and the two are worth distinguishing — the keyset walk
   picks up rows inserted mid-export (a new cuid sorts after the cursor) and misses rows deleted
   ahead of it, so on a live repository ±a few is normal and a large shortfall is not. Nothing here
   takes a snapshot: doing so would mean holding a repeatable-read transaction open for the length
   of a multi-gigabyte download, against a pooler this deployment has already exhausted twice.

AUTHENTICATION IS ADMIN, and by either of two credentials — see ``deps.require_dataset_admin``:

* an ordinary admin session token, so a signed-in admin can simply curl it; or
* a ``dataset:read`` token from ``POST /api/datasets/token``, minted from admin email + password,
  which is the credential a cron job holds. It can reach THIS router and nothing else in the API:
  the containment is enforced in ``deps._user_from_bearer``, not here, so it cannot be lost by a
  route that forgets about it.

WHAT IT DELIBERATELY DOES NOT DO. It does not apply ``owned_or_granted_where``. That filter answers
"which rows may this PERSON take out", and every caller here is already an admin, for whom it
resolves to the empty filter anyway — spelling it out would suggest a narrowing that is not
happening. Row selection here is the caller's explicit ``workshopIds`` / ``createdBy`` /
``updatedSince`` filters and nothing implicit.

IDENTITY NUMBERS ARE MASKED BY DEFAULT, exactly as they are on every other exported surface, and are
released only to a MASTER admin who asks for them by name (``includeIdentityNumbers=true``). An
endpoint that hands over every artisan in one request is the easiest possible way to take a copy of
several thousand Aadhaar numbers, so it must not be the one place in the codebase where they come
unmasked as a side effect of being convenient.
"""

import csv
import io
import json
from datetime import datetime
from typing import Any, AsyncIterator

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    DATASET_READ_SCOPE,
    get_value,
    is_admin,
    is_master_admin,
    require_dataset_admin,
)
from app.core.security import create_access_token, verify_password
from app.schemas.auth import LoginRequest
from app.services.concurrency import gather_reads
from app.services.csv_export import record_media
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_fields import sheet_columns, sheet_row
from app.services.record_filters import (
    artisan_workshop_clause,
    craft_workshop_clause,
    resolve_workshop_ids,
    workshop_clause,
)
from app.services.records import ALL_MEDIA_URLS, public_encode
from app.services.s3 import presign_get_url

router = APIRouter(prefix="/datasets", tags=["datasets"])

# Rows fetched per database round trip while streaming. The database is in another region and a
# round trip costs far more than the rows themselves, so this wants to be large; the includes on an
# artisan or an interview are wide enough that "large" still has to fit comfortably in the memory of
# a t3.micro serving other requests at the same time. 200 is the compromise, and it is the ONLY
# thing that bounds memory here — the batch is encoded, yielded and dropped before the next is read.
STREAM_BATCH = 200

# How long a presigned media URL stays valid. Long enough for a mirror job to work through a large
# media table after reading the index, short enough that a leaked line out of an .ndjson file is not
# a permanent public link to a recording. The client is expected to re-read the index, not to keep
# these.
PRESIGN_TTL_SECONDS = 6 * 60 * 60


class Dataset:
    """One downloadable table: where its rows live, what they carry, and how to narrow them."""

    def __init__(
        self,
        *,
        name: str,
        delegate: str,
        label: str,
        description: str,
        kind: str | None,
        include: dict[str, Any],
        owner_field: str = "createdById",
        workshop_filter: str = "column",
        media_tags: tuple[str, ...] = (),
    ) -> None:
        self.name = name
        #: Attribute on the Prisma client — the model name lowercased, e.g. ``productdocumentation``.
        self.delegate = delegate
        self.label = label
        self.description = description
        #: Key into ``services.record_fields.SPECS``, or None for a table the registry does not
        #: describe (media). A dataset with no kind has no ``.csv`` form; see ``_csv_columns``.
        self.kind = kind
        self.include = include
        #: The row's owner column — ``uploadedById`` on media, ``createdById`` everywhere else.
        self.owner_field = owner_field
        #: How ``workshopIds`` narrows this table:
        #:   "column"   — the row carries a workshopId FK (the common case);
        #:   "self"     — the row IS the workshop, so the predicate is on its primary key;
        #:   "artisan"  — an artisan reaches a workshop three different ways;
        #:   "craft"    — a craft reaches it two ways (column OR the WorkshopCraft join).
        #: The last two defer to the shared clause builders rather than testing a column, because
        #: on the live repository the readings disagree and a column-only test returns nothing.
        self.workshop_filter = workshop_filter
        #: ``MediaFile.linkedRecordType`` values that attach a file to a row of THIS table, for a
        #: model with no ``media`` back-relation to load. Empty for every table that has one.
        self.media_tags = media_tags

    @property
    def model(self) -> Any:
        return getattr(db, self.delegate)


# The registry. Adding a table here gives it a paged JSON route, an .ndjson stream, a .csv stream
# and a line in the catalogue at once — there is deliberately no per-dataset route to write.
DATASETS: dict[str, Dataset] = {
    "workshops": Dataset(
        name="workshops",
        delegate="workshop",
        label="Workshops",
        description="Field workshops: dates, place, linked artisans and crafts.",
        kind="workshop",
        include={
            "location": True,
            "createdBy": True,
            "media": True,
            "artisans": {"include": {"artisan": True}},
            "crafts": {"include": {"craft": True}},
        },
        workshop_filter="self",
    ),
    "crafts": Dataset(
        name="crafts",
        delegate="craft",
        label="Crafts",
        description="The craft vocabulary artisans, products and tools are classified against.",
        kind="craft",
        # BOTH spellings of the workshop link are loaded, and the plural one is load-bearing rather
        # than belt-and-braces: the registry's CRAFT spec renders its "Workshops" column with
        # `_workshop_titles(c)`, which reads `c.workshops` — the WorkshopCraft join with its nested
        # workshop — NOT the singular `workshop` FK. Loading only the FK left that column silently
        # empty in crafts.csv for every craft, which reads as "this craft belongs to no workshop"
        # rather than as a missing include. (It is the same include the artisans entry below needs,
        # for the same getter.)
        include={
            "workshop": True,
            "workshops": {"include": {"workshop": True}},
            "createdBy": True,
            "media": True,
        },
        workshop_filter="craft",
    ),
    "artisans": Dataset(
        name="artisans",
        delegate="artisan",
        label="Artisans",
        description="Artisan records, their craft, their workshops and their stated address.",
        kind="artisan",
        include={
            "craft": True,
            "location": True,
            "createdBy": True,
            "media": True,
            "workshops": {"include": {"workshop": True}},
        },
        workshop_filter="artisan",
    ),
    "products": Dataset(
        name="products",
        delegate="productdocumentation",
        label="Products",
        description="Product documentation: materials, dimensions, pricing and process links.",
        kind="product",
        include={"workshop": True, "createdBy": True, "media": True, "location": True},
    ),
    "tools": Dataset(
        name="tools",
        delegate="tooldocumentation",
        label="Tools",
        description="Tool and toolkit documentation.",
        kind="tool",
        include={"workshop": True, "createdBy": True, "media": True, "location": True},
    ),
    "processes": Dataset(
        name="processes",
        delegate="process",
        label="Processes",
        description="Step-by-step making processes, with their ordered steps.",
        kind="process",
        # Process has no `media` back-relation at all — its attachments are carried by the MediaFile
        # TAG PAIR (linkedRecordType/linkedRecordId), with no FK column to include. So its media
        # columns are resolved by tag instead; see `media_tags` and `_media_by_tag`. Without that
        # this dataset's CSV printed "Media count: 0" on every row while /api/data/report printed
        # the real filenames for the same processes — the same record described two ways.
        #
        # "process" only, NOT "processstep": the workbook files step attachments on its separate
        # Process steps sheet (keyed by step id, via data_browser._media_owner_id), so folding them
        # into the parent process row here would make this export disagree with it in the other
        # direction. Step media is in the `media` dataset under linkedRecordType "processstep".
        include={"steps": True, "product": True, "createdBy": True},
        media_tags=("process",),
    ),
    "interviews": Dataset(
        name="interviews",
        delegate="questionnaireinterview",
        label="Questionnaire interviews",
        description="Interviews with their answers and the artisans they cover.",
        kind="interview",
        include={
            "createdBy": True,
            "media": True,
            "artisans": {"include": {"artisan": True}},
            "responses": {"include": {"question": True}},
        },
    ),
    "media": Dataset(
        name="media",
        delegate="mediafile",
        label="Media files",
        description=(
            "Every uploaded photograph, recording, video and document, with the record it is "
            "attached to. Pass presign=true for time-limited direct download URLs."
        ),
        # The field registry describes RECORDS, and a media file is not one — it has no spec, so it
        # has no .csv form. Its JSON rows carry every column, which is what a mirror job needs.
        kind=None,
        include={"uploadedBy": True},
        owner_field="uploadedById",
    ),
}


def _dataset_or_404(name: str) -> Dataset:
    dataset = DATASETS.get(name)
    if dataset is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Unknown dataset '{name}'. Available: {', '.join(sorted(DATASETS))}.",
        )
    return dataset


def _build_where(
    dataset: Dataset,
    *,
    workshop_ids: list[str] | None,
    created_by: str | None,
    updated_since: datetime | None,
    created_since: datetime | None,
) -> dict[str, Any]:
    """The row filter for one dataset, composed the way every other list route composes one.

    Everything is nested under ``AND`` rather than written as top-level keys. That is not tidiness:
    ``artisan_workshop_clause`` returns an ``OR`` (an artisan reaches a workshop three ways), and a
    second filter written as a bare ``OR`` key would silently replace it — which would widen the
    scope to the whole table while looking like it had narrowed it.
    """
    clauses: list[dict[str, Any]] = []

    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        if dataset.workshop_filter == "artisan":
            clauses.append(artisan_workshop_clause(ids, include_unassigned))
        elif dataset.workshop_filter == "craft":
            clauses.append(craft_workshop_clause(ids, include_unassigned))
        else:
            clause = workshop_clause(
                ids, include_unassigned, is_workshop_table=(dataset.workshop_filter == "self")
            )
            # None means the selection cannot narrow this table at all — "unassigned only" against
            # the workshops table is the real case. An impossible predicate is the honest answer;
            # leaving the table unfiltered would hand over every row under a scope excluding them.
            clauses.append(clause if clause is not None else {"id": {"in": []}})

    if created_by:
        clauses.append({dataset.owner_field: created_by})
    if created_since:
        clauses.append({"createdAt": {"gte": created_since}})
    if updated_since:
        # For an incremental mirror: "everything that changed since my last run". updatedAt is
        # maintained by Prisma's @updatedAt on every model in this registry.
        clauses.append({"updatedAt": {"gte": updated_since}})

    return {"AND": clauses} if clauses else {}


def _presign_media_row(row: Any) -> dict[str, Any] | None:
    """A ``{downloadUrl, expiresIn}`` patch for one media row, or None when it cannot be signed."""
    object_key = get_value(row, "objectKey")
    if not object_key:
        return None
    filename = get_value(row, "originalFilename") or f"{get_value(row, 'id')}"
    mime_type = get_value(row, "mimeType") or "application/octet-stream"
    try:
        url = presign_get_url(
            object_key,
            filename=str(filename),
            mime_type=str(mime_type),
            expires_in=PRESIGN_TTL_SECONDS,
        )
    except Exception:
        # Signing is a local computation, but it needs credentials to be configured. A storage
        # misconfiguration must not turn the whole media index into a 500 — the rows are still
        # worth having, and `downloadUrl` being absent says plainly that this one cannot be fetched.
        return None
    return {"downloadUrl": url, "downloadUrlExpiresIn": PRESIGN_TTL_SECONDS}


def _encode_rows(
    rows: list[Any], *, unmasked_viewer: Any | None, presign: bool
) -> list[dict[str, Any]]:
    """Rows as JSON-ready dicts, with identity masking and media URLs decided once for the batch.

    ``media_urls=ALL_MEDIA_URLS`` is passed explicitly: this is an already-gated download surface, so
    withholding the stored URL would be withholding it from a caller entitled to the bytes. Passing
    it explicitly is also the difference between deciding and defaulting — ``public_encode``'s
    default is the cheapest SAFE answer, and a bulk export route is exactly the kind of place that
    should not inherit a default it never thought about.
    """
    encoded = public_encode(rows, unmasked_viewer, media_urls=ALL_MEDIA_URLS)
    if presign:
        for row, payload in zip(rows, encoded):
            patch = _presign_media_row(row)
            if patch:
                payload.update(patch)
    return encoded


async def _stream_rows(
    dataset: Dataset, where: dict[str, Any]
) -> AsyncIterator[list[Any]]:
    """Every matching row, in batches, by KEYSET — never OFFSET.

    ``skip``/``take`` re-walks and discards the rows it skips, so page N costs O(N x pageSize) and a
    full-table export is quadratic; on a table of any size the last pages are where an export stops
    finishing. Worse, OFFSET paging is not stable: a row inserted or deleted mid-export shifts every
    later page, silently duplicating or dropping records — in an ARCHIVAL snapshot, which is what
    this endpoint produces, that is a corrupt answer that looks like a clean one.

    Keyset paging asks for ``id > last-id-seen`` on an ordered unique column, so each batch is an
    index seek regardless of depth, and a concurrent write can only ever be included or missed
    whole — never counted twice.
    """
    last_id: str | None = None
    while True:
        page_where = where
        if last_id is not None:
            page_where = {"AND": [where, {"id": {"gt": last_id}}]} if where else {"id": {"gt": last_id}}
        rows = await dataset.model.find_many(
            where=page_where,
            include=dataset.include,
            take=STREAM_BATCH,
            order={"id": "asc"},
        )
        if not rows:
            return
        yield rows
        if len(rows) < STREAM_BATCH:
            return
        last_id = get_value(rows[-1], "id")


async def _media_by_tag(dataset: Dataset, rows: list[Any]) -> dict[str, list[Any]]:
    """``row id -> its media``, for a table whose files are attached by TAG rather than by an FK.

    One extra query per batch, and only for a dataset that declares ``media_tags`` — so every table
    with a real ``media`` relation pays nothing. Keyed on ``linkedRecordId`` exactly as
    ``data_browser._media_owner_id`` keys it, which is what makes this export and the .xlsx report
    agree about which files belong to which process.
    """
    if not dataset.media_tags or not rows:
        return {}
    ids = [rid for rid in (get_value(r, "id") for r in rows) if rid]
    if not ids:
        return {}
    media = await db.mediafile.find_many(
        where={
            "AND": [
                {"linkedRecordType": {"in": list(dataset.media_tags)}},
                {"linkedRecordId": {"in": ids}},
            ]
        }
    )
    index: dict[str, list[Any]] = {}
    for item in media:
        owner = get_value(item, "linkedRecordId")
        if owner:
            index.setdefault(owner, []).append(item)
    return index


def _csv_columns(kind: str) -> list[str]:
    """The CSV header for a registry kind. THE ONE DEFINITION — the catalogue and the ``.csv`` route
    must both read it.

    "ID" is prepended for the same reason ``services/csv_export`` does it: it is the only stable key
    a downstream sheet can join these tables on, and the registry has no opinion about it. That extra
    column is exactly why this is a shared function and not two expressions: the catalogue used to
    advertise a bare ``sheet_columns(kind)`` while the route emitted this, so every column a client
    read from the plan was one to the left of the value under it — a mirror job doing
    ``dict(zip(columns, row))`` filed the record's ID under "Craft", the name under "Local name", and
    dropped the last column entirely. Every kind was short by exactly one, and both lists looked
    perfectly reasonable on their own.
    """
    return ["ID", *sheet_columns(kind)]


def _csv_shape(dataset: Dataset) -> tuple[str, list[str]]:
    """``(registry kind, column list)`` for a dataset's CSV form, or 422 when it has none.

    Returns the kind alongside the columns rather than letting the caller re-read
    ``dataset.kind``: that field is ``str | None``, so a caller reading it again would need an
    assertion to convince a type checker that the 422 above had already ruled None out.
    """
    if dataset.kind is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"The '{dataset.name}' dataset has no CSV form: it is not a record type the shared "
                f"field registry describes. Use /api/datasets/{dataset.name}.ndjson instead."
            ),
        )
    return dataset.kind, _csv_columns(dataset.kind)


# =================================================================================================
# Routes.
#
# ORDER IS LOAD-BEARING. FastAPI matches in declaration order and a path parameter happily swallows
# a literal, so `/{dataset}` must come LAST — otherwise `/datasets/token` is read as a dataset named
# "token". For the same reason `/{dataset}.ndjson` and `/{dataset}.csv` are declared above the bare
# `/{dataset}`: `[^/]+` matches a dot, so the bare route would otherwise answer for
# "artisans.ndjson" as a dataset of that name. (`workshops.py` carries the same warning for the same
# reason — see the note above its `/{workshop_id}`.)
# =================================================================================================


@router.post("/token")
async def mint_dataset_token(payload: LoginRequest) -> dict[str, Any]:
    """Exchange ADMIN credentials for a long-lived, read-only token for this API.

    This is how a machine authenticates. It is a separate endpoint from ``POST /api/auth/login``
    rather than a flag on it because the two mint genuinely different credentials, and conflating
    them is how a script ends up holding a token that can also delete records: the token issued here
    carries ``scope: dataset:read``, which ``deps._user_from_bearer`` refuses everywhere except this
    router.

    A non-admin with perfectly valid credentials is refused HERE, at issue time, rather than being
    handed a token that 403s on first use — the operator wiring up a cron job finds out while they
    are looking at the terminal.

    The response deliberately reports ``expiresInMinutes`` and the account it names, because the two
    questions asked about a stored machine credential are always "when does this stop working" and
    "whose is it".
    """
    if payload.googleIdToken:
        # Google sign-in is an interactive, browser-only flow (it needs a redirect and a consent
        # screen), and the credential this endpoint exists to issue is for a process with no
        # browser. Refusing plainly beats accepting a token that a cron job could never obtain.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Dataset tokens are issued from an email and password, not a Google ID token.",
        )

    user = await db.user.find_unique(where={"email": (payload.email or "").lower()})
    # One message for "no such account" and "wrong password", the same as POST /auth/login: telling
    # them apart turns this endpoint into an oracle for which admin addresses exist.
    if not user or not verify_password(payload.password or "", user.passwordHash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid email or password"
        )
    if not is_admin(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access required to issue a dataset token.",
        )

    minutes = get_settings().dataset_token_expires_minutes
    token = create_access_token(
        subject=user.id,
        extra_claims={"scope": DATASET_READ_SCOPE, "email": user.email},
        expires_minutes=minutes,
    )
    return {
        "accessToken": token,
        "tokenType": "bearer",
        "scope": DATASET_READ_SCOPE,
        "expiresInMinutes": minutes,
        "account": {"id": user.id, "email": user.email, "role": str(getattr(user.role, "value", user.role))},
        "usage": "Send as: Authorization: Bearer <accessToken>",
    }


@router.get("")
async def dataset_catalogue(
    request: Request,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    current_user: Any = Depends(require_dataset_admin),
) -> dict[str, Any]:
    """What there is to download, how much of it, and the exact URLs that fetch it.

    Self-describing on purpose: a client should be able to mirror the whole repository from this one
    response without a human reading documentation first, and the counts are computed under the SAME
    filters passed here, so ``?workshopIds=abc`` returns the plan for that workshop rather than a
    plan for everything with a filter the caller has to remember to re-apply.

    The counts are gathered concurrently rather than in sequence: they are eight independent reads
    against a database in another region, where the round trip dominates everything else.
    """
    names = sorted(DATASETS)
    wheres = {
        name: _build_where(
            DATASETS[name],
            workshop_ids=workshopIds,
            created_by=createdBy,
            updated_since=updatedSince,
            created_since=createdSince,
        )
        for name in names
    }
    counts = await gather_reads(
        *(DATASETS[name].model.count(where=wheres[name]) for name in names)
    )

    # Echo the caller's filters back onto every generated URL, so following a link from this
    # response cannot silently widen the scope the plan was counted under.
    #
    # ROOT-RELATIVE, not absolute. Building an absolute URL would mean trusting request.url's scheme
    # and host, and this API is served behind CloudFront: unless uvicorn is started with
    # --proxy-headers the origin sees plain http on an internal hostname, so every link in the plan
    # would come back as an http:// URL to a box the client cannot reach. A relative path resolves
    # against whatever host the client actually used and cannot be wrong.
    query = request.url.query
    suffix = f"?{query}" if query else ""
    base = request.url.path.rstrip("/")

    entries = []
    for name, count in zip(names, counts):
        dataset = DATASETS[name]
        entry: dict[str, Any] = {
            "name": name,
            "label": dataset.label,
            "description": dataset.description,
            "rows": count,
            "json": f"{base}/{name}{suffix}",
            "ndjson": f"{base}/{name}.ndjson{suffix}",
            "csv": f"{base}/{name}.csv{suffix}" if dataset.kind else None,
            # The SAME list the .csv route emits as its header — see _csv_columns for what happened
            # when these were two expressions.
            "columns": _csv_columns(dataset.kind) if dataset.kind else None,
        }
        entries.append(entry)

    return {
        "datasets": entries,
        "totalRows": sum(counts),
        "filters": {
            "workshopIds": (
                "Repeated or comma-joined workshop ids. Absent means every workshop; the reserved "
                "word 'none' means records not linked to any workshop."
            ),
            "createdBy": "A user id. Narrows to the rows that account created (uploaded, for media).",
            "createdSince": "ISO-8601 timestamp. Rows created at or after it.",
            "updatedSince": "ISO-8601 timestamp. Rows changed at or after it — for incremental mirrors.",
            "presign": "media only. true swaps in time-limited direct download URLs.",
            "includeIdentityNumbers": (
                "Master admin only. true returns unmasked artisan identity numbers; they are masked "
                "on every surface by default."
            ),
        },
        "applied": {
            "workshopIds": workshopIds,
            "createdBy": createdBy,
            "createdSince": createdSince,
            "updatedSince": updatedSince,
        },
        "notes": [
            "Every stream sends X-Dataset-Total before the body: compare it with what you received "
            "to prove the download is complete.",
            "The .ndjson and .csv routes have NO row cap and stream in constant memory; the paged "
            "JSON route is capped at 100 rows a page like every other list in this API.",
        ],
        "authenticatedAs": get_value(current_user, "email"),
    }


def _identity_viewer(current_user: Any, include_identity_numbers: bool) -> Any | None:
    """The ``viewer`` handed to ``public_encode`` — and therefore whether identity numbers are raw.

    ``None`` means masked, which is the default and the answer for every caller who did not ask.
    Only a MASTER admin who explicitly asks gets the account passed through (``public_encode``
    unmasks for Professor and above), so the unmasking is both a rank decision and a deliberate one:
    an ordinary admin script that mirrors the artisan table nightly stores masked numbers, and
    taking a bulk copy of regulated identifiers requires the top of the ladder to say so per request.
    """
    if not include_identity_numbers:
        return None
    if not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Master admin access is required to export unmasked identity numbers. Omit "
                "includeIdentityNumbers to receive the masked values."
            ),
        )
    return current_user


def _stream_headers(total: int, filename: str) -> dict[str, str]:
    return {
        # The honest count, sent before the body — see this module's docstring.
        "X-Dataset-Total": str(total),
        "Content-Disposition": f'attachment; filename="{filename}"',
    }


@router.get("/{dataset_name}.ndjson")
async def stream_dataset_ndjson(
    dataset_name: str,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    presign: bool = False,
    includeIdentityNumbers: bool = False,
    current_user: Any = Depends(require_dataset_admin),
) -> StreamingResponse:
    """The whole dataset as newline-delimited JSON — one complete record per line, no row cap.

    NDJSON rather than a JSON array because an array has to be closed: a consumer cannot begin
    parsing until the last byte arrives, and a truncated array is invalid JSON that yields nothing,
    whereas a truncated NDJSON file is every line that did arrive. For a multi-gigabyte archival
    download over a link that may drop, "what arrived is usable" is the whole difference.
    """
    dataset = _dataset_or_404(dataset_name)
    viewer = _identity_viewer(current_user, includeIdentityNumbers)
    where = _build_where(
        dataset,
        workshop_ids=workshopIds,
        created_by=createdBy,
        updated_since=updatedSince,
        created_since=createdSince,
    )
    sign = presign and dataset.name == "media"
    total = await dataset.model.count(where=where)

    async def body() -> AsyncIterator[bytes]:
        async for rows in _stream_rows(dataset, where):
            chunk = "".join(
                json.dumps(payload, ensure_ascii=False, default=str) + "\n"
                for payload in _encode_rows(rows, unmasked_viewer=viewer, presign=sign)
            )
            yield chunk.encode("utf-8")

    return StreamingResponse(
        body(),
        media_type="application/x-ndjson",
        headers=_stream_headers(total, f"{dataset.name}.ndjson"),
    )


@router.get("/{dataset_name}.csv")
async def stream_dataset_csv(
    dataset_name: str,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    current_user: Any = Depends(require_dataset_admin),
) -> StreamingResponse:
    """The whole dataset as CSV, streamed, with no row cap.

    The columns come from the shared field registry — the same one the .xlsx report, the data
    browser's cards and ``/api/export/*.csv`` are built from — so this download cannot describe a
    record differently from the surfaces a researcher checks it against, and a column added to the
    registry appears here without anyone remembering to add it.

    Note that the registry MASKS regulated identity numbers and there is no override on this route.
    A CSV is the format that ends up in a shared drive, and the unmasked path (``.ndjson`` with
    ``includeIdentityNumbers``) is the one that should have to be asked for by name.
    """
    dataset = _dataset_or_404(dataset_name)
    kind, columns = _csv_shape(dataset)
    where = _build_where(
        dataset,
        workshop_ids=workshopIds,
        created_by=createdBy,
        updated_since=updatedSince,
        created_since=createdSince,
    )
    total = await dataset.model.count(where=where)

    async def body() -> AsyncIterator[bytes]:
        buffer = io.StringIO()
        writer = csv.writer(buffer)

        def drain() -> bytes:
            """Hand back what the writer has produced and reset the buffer.

            The buffer is truncated rather than replaced with a new StringIO so the csv.writer keeps
            writing into the same object — and it is what keeps memory flat: without the drain the
            buffer would grow to hold the entire table, which is precisely what streaming is for.
            """
            chunk = buffer.getvalue()
            buffer.seek(0)
            buffer.truncate(0)
            return chunk.encode("utf-8")

        writer.writerow(columns)
        yield drain()
        # Which of the two ways this table's media is found, decided ONCE from the registry rather
        # than per row from whether the index came back empty: a batch of processes that genuinely
        # has no attachments would otherwise fall through to `record_media`, which is the very read
        # that returns nothing here — correct by accident, and only until someone changed it.
        uses_tags = bool(dataset.media_tags)
        async for rows in _stream_rows(dataset, where):
            tagged = await _media_by_tag(dataset, rows) if uses_tags else {}
            for record in rows:
                row_id = get_value(record, "id")
                media = tagged.get(row_id, []) if uses_tags else record_media(record)
                writer.writerow([row_id, *sheet_row(kind, record, media)])
            yield drain()

    return StreamingResponse(
        body(),
        media_type="text/csv; charset=utf-8",
        headers=_stream_headers(total, f"{dataset.name}.csv"),
    )


@router.get("/{dataset_name}")
async def read_dataset_page(
    dataset_name: str,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    presign: bool = False,
    includeIdentityNumbers: bool = False,
    page: int = Query(1, ge=1),
    pageSize: int = Query(100, ge=1, le=100),
    current_user: Any = Depends(require_dataset_admin),
) -> dict[str, Any]:
    """One page of a dataset, in the same ``{items,total,page,pageSize,pages}`` envelope every list
    route in this API returns.

    For browsing, sampling and "show me what a row of this looks like". For taking the table, use
    ``.ndjson`` — this route is OFFSET-paged (as every paged route here is) and walking a large table
    through it is both quadratic and unstable against concurrent writes; ``_stream_rows`` explains
    why in full.
    """
    dataset = _dataset_or_404(dataset_name)
    viewer = _identity_viewer(current_user, includeIdentityNumbers)
    clean_page, page_size, skip = normalize_pagination(page, pageSize)
    where = _build_where(
        dataset,
        workshop_ids=workshopIds,
        created_by=createdBy,
        updated_since=updatedSince,
        created_since=createdSince,
    )
    total, rows = await gather_reads(
        dataset.model.count(where=where),
        dataset.model.find_many(
            where=where, include=dataset.include, skip=skip, take=page_size, order={"id": "asc"}
        ),
    )
    items = _encode_rows(
        rows, unmasked_viewer=viewer, presign=presign and dataset.name == "media"
    )
    return page_payload(items, total, clean_page, page_size)
