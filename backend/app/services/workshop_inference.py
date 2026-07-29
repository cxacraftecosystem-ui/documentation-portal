"""Which workshop a record belongs to, when the record never said.

WHY THIS EXISTS
---------------
``workshopId`` arrived on the record types AFTER a workshop's worth of fieldwork had already been
recorded. Every screen that narrows by workshop reads that column — the search box, the map, the data
browser, the consolidated questionnaire, the XLSX export, and the completion matrix's derived green —
so a row with a NULL ``workshopId`` is not merely untagged. It is INVISIBLE under every workshop
scope, including the one the app opens on (the most recent workshop), while sitting in plain sight
under "All records".

That is the worst kind of failure, because it looks like an empty result rather than a broken filter.
The live corpus showed it exactly: 25 questionnaire interviews and 924 media files, every one of them
recorded at the single workshop in the repository, none of them carrying its id. The completion matrix
answered "nothing was covered at this workshop" while twenty-five interviews and five hundred and
sixty-six recordings sat right there.

WHAT THIS MODULE IS, AND WHAT IT REFUSES TO BE
----------------------------------------------
An EVIDENCE LADDER, not a guess. Every rung is a fact already stored on the record; a row whose
evidence is absent or ambiguous is left alone and REPORTED, never assigned to whichever workshop looked
likely. "Only one workshop exists, so everything must be its" is deliberately NOT a rung: it would
sweep up the rows that are correctly unassigned — anything recorded before the workshop existed — and
afterwards there would be no way to tell them back apart.

THE RUNGS, strongest first
--------------------------
``PARENT``    The row hangs off another record that already names a workshop. A questionnaire clip
              belongs where its interview belongs; a process belongs where its product does. This is
              not inference at all — it is reading the same fact off the parent — which is why it
              outranks the rest.
``ARTISANS``  Every artisan the row covers points at the SAME single workshop, through either
              ``Artisan.workshopId`` or the ``WorkshopArtisan`` roster. Both routes count, for the
              same reason ``record_filters.artisan_workshop_clause`` honours both.
``WINDOW``    The moment the row was captured falls inside exactly ONE workshop's dates. A workshop is
              a physical event with a start and an end; something recorded during it, by a researcher
              who was there, is its.

AMBIGUITY ALWAYS LOSES, AND IT STOPS THE LADDER. A rung that names two workshops does not fall
through to the next one. An interview whose artisans span two workshops genuinely needs a person: the
date window would pick one of the two, which is a wrong answer dressed as a decision, and the row
would then look mapped. So the first rung with ANY evidence decides — one candidate resolves it, more
than one reports it as ``AMBIGUOUS`` with every candidate named. A rung with no evidence at all falls
through, because "this record has no artisans" says nothing about its dates.

IDEMPOTENCE
-----------
:func:`apply_workshop_mapping` only ever writes NULL -> id, and re-asserts ``workshopId: None`` in the
``where`` of every write, so running it twice is a no-op and a row somebody assigned by hand in
between is never overwritten. Nothing here clears or moves an existing link: a record that names its
workshop is the authority on its own workshop.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Iterable

from app.core.db import db
from app.services.concurrency import gather_reads

# The rung names, as they travel to the client. Constants because both platforms render them, and a
# typo'd string would silently become an unrecognised rung with no label rather than an error.
RUNG_PARENT = "PARENT"
RUNG_ARTISANS = "ARTISANS"
RUNG_WINDOW = "WINDOW"

RUNGS: tuple[str, ...] = (RUNG_PARENT, RUNG_ARTISANS, RUNG_WINDOW)

# How a rung is described to a human, on both clients. Here rather than in two UIs, so the web and
# Android cannot describe the same decision differently.
RUNG_COPY: dict[str, str] = {
    RUNG_PARENT: "the record it belongs to already names that workshop",
    RUNG_ARTISANS: "every artisan in it belongs to that one workshop",
    RUNG_WINDOW: "it was recorded inside that workshop's dates",
}

# Why a row could not be mapped. Reported per row, because "24 of 25" is never the whole answer.
REASON_NO_EVIDENCE = "NO_EVIDENCE"
REASON_AMBIGUOUS = "AMBIGUOUS"

REASON_COPY: dict[str, str] = {
    REASON_NO_EVIDENCE: "nothing on the record points at a workshop",
    REASON_AMBIGUOUS: "the evidence points at more than one workshop",
}

# A workshop with a start but no end covers this much. One day, because a workshop row whose only date
# is the legacy ``date`` IS a single-day event as far as the schema can say — and a record stamped at
# 18:40 on that day has to fall inside it, which a window ending at 00:00 would exclude.
_SINGLE_DAY = timedelta(days=1)

# The record types this module maps: the key on the wire, the Prisma delegate, and the singular/plural
# nouns both clients print. Order is the order the report renders in — interviews first, because they
# are what the completion matrix reads, then the media that hangs off them.
#
# Craft is deliberately ABSENT. A craft is taxonomy — "Dabu hand block printing" is not something that
# happened at a workshop — and while the column exists for the roster join, no screen narrows crafts by
# workshop. Assigning them would be inventing a fact to fill a column.
BUCKETS: tuple[tuple[str, str, str, str], ...] = (
    ("interviews", "questionnaireinterview", "questionnaire interview", "questionnaire interviews"),
    ("media", "mediafile", "media file", "media files"),
    ("products", "productdocumentation", "product record", "product records"),
    ("tools", "tooldocumentation", "tool record", "tool records"),
    ("processes", "process", "process record", "process records"),
    ("artisans", "artisan", "artisan", "artisans"),
)

BUCKET_KEYS: tuple[str, ...] = tuple(bucket for bucket, _, _, _ in BUCKETS)

# How many rows of per-row detail travel per bucket. The plan renders on a phone as well as a laptop,
# and 924 filenames is a payload nobody reads: the COUNTS are the answer, the rows are the evidence for
# spot-checking it. Truncation is stated in the payload rather than left silent — and the WRITES are
# driven off the un-truncated plan, never off this list.
_MAX_ROWS_PER_BUCKET = 40

# How many ids go into one ``id IN (...)`` write. Postgres copes with far more, but the statement goes
# through a pooled connection and 566 ids is already a 20 KB parameter list; chunking keeps every
# statement small enough to log and to retry.
_WRITE_CHUNK = 200


@dataclass(frozen=True)
class WorkshopWindow:
    """One workshop's identity and the span of time it occupied. ``end`` is EXCLUSIVE — see
    :func:`build_windows` for why, and for why it is not the same arithmetic as the late-submission
    check."""

    id: str
    title: str
    start: datetime
    end: datetime

    def contains(self, moment: datetime) -> bool:
        return self.start <= moment < self.end


@dataclass
class RowPlan:
    """What the ladder would do to one row, and why."""

    id: str
    title: str
    workshopId: str | None = None
    rung: str | None = None
    reason: str | None = None
    # Every workshop the deciding rung pointed at. One entry means resolved; more than one is what
    # AMBIGUOUS means, and naming them is what lets an admin go and correct the record by hand.
    candidates: list[str] = field(default_factory=list)


@dataclass
class BucketPlan:
    bucket: str
    unassigned: int = 0
    rows: list[RowPlan] = field(default_factory=list)

    @property
    def resolved(self) -> list[RowPlan]:
        return [row for row in self.rows if row.workshopId]

    @property
    def unresolved(self) -> list[RowPlan]:
        return [row for row in self.rows if not row.workshopId]


@dataclass
class LadderRun:
    """One complete pass of the ladder: the raw per-row plans plus what they were decided against."""

    plans: dict[str, BucketPlan]
    workshopTitles: dict[str, str]
    windows: list[WorkshopWindow]


# ---------------------------------------------------------------------------------------------
# The rungs, as pure functions over values. Tested directly; no database in sight.
# ---------------------------------------------------------------------------------------------


def _aware(value: Any) -> datetime | None:
    """A comparable, timezone-aware datetime, or None.

    Prisma hands back aware datetimes, but a naive one is not a shape worth crashing on: comparing an
    aware datetime with a naive one raises ``TypeError``, and a single legacy row with a naive stamp
    would take the whole report down. A naive value is read as UTC, which is what every stamp in this
    database is.
    """
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def build_windows(workshops: Iterable[Any]) -> list[WorkshopWindow]:
    """Every workshop as a half-open span ``[start, end)`` covering WHOLE DAYS.

    ``startDate`` is the column the workshop list sorts and filters by; ``date`` is the legacy single
    date that rows created before ``startDate`` existed still carry, so it is the FALLBACK rather than
    an alternative. A workshop with neither is skipped: a window with no start cannot contain anything,
    and pretending it starts at the epoch would make it contain EVERYTHING.

    WHY THE END IS THE START OF THE DAY AFTER ``endDate``, and not ``endDate`` itself. An end date is a
    DAY, not an instant, and how that day is stored depends entirely on which client wrote the workshop:

      * the web form sends the last millisecond of the day (``…T23:59:59.999``);
      * Android sends MIDNIGHT of that day (``toIsoInstant()`` on a ``LocalDate``);
      * and ``workshops.normalize_workshop_dates`` copies ``startDate`` into ``endDate`` whenever the
        payload omits one — so a single-day workshop very often has ``endDate == startDate``, at
        midnight.

    Reading ``endDate`` as an instant therefore had two failure modes, both silent. A midnight end
    excluded the workshop's entire final day; and ``endDate == startDate`` produced a ZERO-LENGTH
    window, so the WINDOW rung could never fire for a single-day workshop at all — for Android-created
    workshops, the rung simply did not exist. Truncating to the date and adding a day fixes both by
    construction, whichever client wrote the row.

    IT IS DELIBERATELY NOT THE SAME ARITHMETIC AS ``workshop_access.describe_workshop_submission``,
    which adds a day to the raw instant. That one answers "is this submission LATE?", where being
    generous is the point — nobody should be flagged for saving at 23:59. This one answers "was this
    recorded DURING the workshop?", where exactness is the point: an extra 24 hours of slack would
    silently claim the next day's records, and where two workshops really do touch, the honest outcome
    is an overlap this ladder reports as AMBIGUOUS rather than a row it quietly assigns to one of them.
    """
    windows: list[WorkshopWindow] = []
    for workshop in workshops:
        start = _aware(getattr(workshop, "startDate", None)) or _aware(getattr(workshop, "date", None))
        if start is None:
            continue
        last_day = _aware(getattr(workshop, "endDate", None)) or start
        # Midnight of the day AFTER the last day: exclusive, so the whole of the last day is inside the
        # window and none of the next one is.
        end = last_day.replace(hour=0, minute=0, second=0, microsecond=0) + _SINGLE_DAY
        # An endDate before its startDate is a typo, not a window. Reversing it silently would invent a
        # span nobody entered; treating it as the start day alone keeps the workshop findable by its
        # start without claiming the days in between.
        if end <= start:
            end = start.replace(hour=0, minute=0, second=0, microsecond=0) + _SINGLE_DAY
        windows.append(
            WorkshopWindow(
                id=workshop.id,
                title=str(getattr(workshop, "title", "") or "Untitled workshop"),
                start=start,
                end=end,
            )
        )
    return windows


def distinct(values: Iterable[str | None]) -> list[str]:
    """Every distinct non-empty value, in first-seen order.

    Order is stable rather than sorted so that a single-candidate answer is reproducible and a
    multi-candidate report reads in the order the evidence was found.
    """
    seen: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.append(value)
    return seen


def windows_containing(moment: datetime | None, windows: list[WorkshopWindow]) -> list[str]:
    """Which workshops were running at ``moment``. Empty when the stamp is missing."""
    if moment is None:
        return []
    return [window.id for window in windows if window.contains(moment)]


def stamp_of(row: Any, *columns: str) -> datetime | None:
    """The first present stamp among ``columns``, as an aware datetime.

    The column ORDER encodes which fact is wanted. ``recordedAt`` is when the work actually happened —
    the client stamps it at capture — so it is asked for first. ``createdAt`` is when the row reached
    the database, which for anything captured offline and synced later is a different day, so it is the
    last resort rather than the answer.
    """
    for column in columns:
        stamp = _aware(getattr(row, column, None))
        if stamp is not None:
            return stamp
    return None


def decide(row_id: str, title: str, rungs: list[tuple[str, list[str]]]) -> RowPlan:
    """Walk the rungs in order and return the row's plan.

    The FIRST rung with any evidence decides — see the module header for why an ambiguous rung stops
    the ladder instead of falling through to a weaker one that would break the tie arbitrarily.
    """
    for rung, candidates in rungs:
        found = distinct(candidates)
        if not found:
            continue
        if len(found) == 1:
            return RowPlan(id=row_id, title=title, workshopId=found[0], rung=rung, candidates=found)
        return RowPlan(
            id=row_id,
            title=title,
            reason=REASON_AMBIGUOUS,
            candidates=found,
        )
    return RowPlan(id=row_id, title=title, reason=REASON_NO_EVIDENCE)


def title_of(row: Any, *columns: str) -> str:
    """The first non-empty display column, or an honest placeholder."""
    for column in columns:
        value = getattr(row, column, None)
        if value:
            return str(value)
    return "Untitled record"


# ---------------------------------------------------------------------------------------------
# The ladder, run against the database
# ---------------------------------------------------------------------------------------------


async def _empty() -> list[Any]:
    """A read that costs no round trip, so a conditional query keeps its slot in the wave.

    ``gather_reads`` returns positionally; omitting a skipped read would renumber every unpack after
    it. Mirrors ``map_points._none`` and exists for the same reason.
    """
    return []


async def run_ladder() -> LadderRun:
    """Read every unassigned row and decide each one. A pure READ — nothing here writes.

    THE READS GO OUT IN TWO WAVES, and the split is forced rather than chosen. Everything in the first
    wave is independent. The second cannot be issued until the first has said which artisans and which
    parent records matter. The database is in another AWS region, so what decides whether an admin
    screen is usable is purely how many round trips wait on each other — two, here, whatever the corpus
    size.
    """
    (
        workshops,
        interviews,
        media,
        products,
        tools,
        processes,
        loose_artisans,
    ) = await gather_reads(
        db.workshop.find_many(),
        db.questionnaireinterview.find_many(
            where={"workshopId": None}, include={"artisans": True}, order={"createdAt": "asc"}
        ),
        db.mediafile.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.productdocumentation.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.tooldocumentation.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.process.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.artisan.find_many(
            where={"workshopId": None}, include={"workshops": True}, order={"createdAt": "asc"}
        ),
    )

    windows = build_windows(workshops)
    workshop_titles: dict[str, str] = {
        workshop.id: str(getattr(workshop, "title", "") or "Untitled workshop")
        for workshop in workshops
    }

    # WHICH ARTISANS MATTER: the ones sitting in an unassigned interview, plus the ones a product, a
    # tool or a media file names. Gathered as one set so the lookup is a single query however many
    # buckets need it.
    artisan_ids: set[str] = {
        link.artisanId
        for interview in interviews
        for link in (interview.artisans or [])
        if link.artisanId
    }
    for row in (*products, *tools, *media):
        parent = getattr(row, "artisanId", None)
        if parent:
            artisan_ids.add(parent)

    # WHICH PARENT RECORDS MATTER for the unassigned media, per typed foreign key. Each is read for its
    # own ``workshopId`` only.
    media_parent_ids: dict[str, set[str]] = {
        "questionnaireInterviewId": set(),
        "productId": set(),
        "toolId": set(),
        "craftId": set(),
    }
    for row in media:
        for column in media_parent_ids:
            value = getattr(row, column, None)
            if value:
                media_parent_ids[column].add(value)
    # A process names its parent product, which is where its workshop comes from when it has one.
    process_product_ids = {row.productId for row in processes if getattr(row, "productId", None)}
    # THE ONE PARENT TYPE WITH A WORKSHOP AND NO COLUMN ON MediaFile. A clip attached to a Process
    # carries only the string tags (``linkedRecordType``/``linkedRecordId``) — ``media_relation_data`` has
    # no ``processId`` to set, because the model has none — so the only way to read its parent's workshop
    # is by the tag. Read here so the backfill agrees with ``media.create``'s own inheritance, which
    # covers the same case through ``_tagged_parent``.
    tagged_process_ids = {
        row.linkedRecordId
        for row in media
        if str(getattr(row, "linkedRecordType", "") or "").strip().lower() == "process"
        and getattr(row, "linkedRecordId", None)
    }

    (
        artisan_rows,
        roster_rows,
        parent_interviews,
        parent_products,
        parent_tools,
        parent_crafts,
        tagged_processes,
    ) = await gather_reads(
        db.artisan.find_many(where={"id": {"in": sorted(artisan_ids)}}) if artisan_ids else _empty(),
        db.workshopartisan.find_many(where={"artisanId": {"in": sorted(artisan_ids)}})
        if artisan_ids
        else _empty(),
        db.questionnaireinterview.find_many(
            where={"id": {"in": sorted(media_parent_ids["questionnaireInterviewId"])}}
        )
        if media_parent_ids["questionnaireInterviewId"]
        else _empty(),
        db.productdocumentation.find_many(
            where={"id": {"in": sorted(media_parent_ids["productId"] | process_product_ids)}}
        )
        if (media_parent_ids["productId"] or process_product_ids)
        else _empty(),
        db.tooldocumentation.find_many(where={"id": {"in": sorted(media_parent_ids["toolId"])}})
        if media_parent_ids["toolId"]
        else _empty(),
        db.craft.find_many(where={"id": {"in": sorted(media_parent_ids["craftId"])}})
        if media_parent_ids["craftId"]
        else _empty(),
        db.process.find_many(where={"id": {"in": sorted(tagged_process_ids)}})
        if tagged_process_ids
        else _empty(),
    )

    # artisanId -> every workshop that artisan belongs to, by EITHER route. Both count, for the same
    # reason ``record_filters.artisan_workshop_clause`` honours both: the column arrived after the
    # roster, so a record predating it has only the roster, and a screen reading one of the two would
    # disagree with the screen reading the other about who was at a workshop.
    artisan_workshops: dict[str, list[str]] = {}
    for row in artisan_rows or []:
        if row.workshopId:
            artisan_workshops.setdefault(row.id, []).append(row.workshopId)
    for row in roster_rows or []:
        bucket = artisan_workshops.setdefault(row.artisanId, [])
        if row.workshopId not in bucket:
            bucket.append(row.workshopId)

    # recordId -> the workshop that record already names. The PARENT rung reads only this map, so a
    # parent that is itself unassigned contributes nothing and the child falls to its next rung.
    parent_workshop: dict[str, str] = {}
    for row in (
        *(parent_interviews or []),
        *(parent_products or []),
        *(parent_tools or []),
        *(parent_crafts or []),
        *(artisan_rows or []),
        *(tagged_processes or []),
    ):
        workshop_id = getattr(row, "workshopId", None)
        if workshop_id:
            parent_workshop[row.id] = workshop_id

    plans: dict[str, BucketPlan] = {bucket: BucketPlan(bucket=bucket) for bucket in BUCKET_KEYS}

    def rung_window(row: Any, *columns: str) -> tuple[str, list[str]]:
        return RUNG_WINDOW, windows_containing(stamp_of(row, *columns), windows)

    def first_parent_workshop(row: Any, *columns: str) -> str | None:
        """The workshop named by the FIRST of ``columns`` whose target has one.

        Narrowest parent first, and only the first that actually answers: a questionnaire clip carries
        an interview id and sometimes also the artisan it was tagged with, and the interview is the
        sitting the clip was recorded in, so it is the truer owner of the two. Because exactly one
        parent is consulted, the PARENT rung can never produce two candidates and can never report
        AMBIGUOUS — which is right: a parent naming a workshop is not evidence to weigh, it is the
        answer.
        """
        for column in columns:
            value = getattr(row, column, None)
            if value and parent_workshop.get(value):
                return parent_workshop[value]
        return None

    def record(bucket: str, rows: list[Any], plan_row: Any) -> None:
        """Run one bucket and FEED ITS RESULT FORWARD.

        The cascade is what makes one pass equal to running the tool repeatedly: an interview mapped in
        this pass immediately becomes a valid parent for its 566 clips, and a product mapped here becomes
        one for its processes. Only RESOLVED rows are fed forward, so a child whose parent stayed
        unresolved falls through to its own date window rather than inheriting a decision nobody made.
        """
        bucket_plan = plans[bucket]
        bucket_plan.unassigned = len(rows)
        for row in rows:
            result = plan_row(row)
            bucket_plan.rows.append(result)
            if result.workshopId:
                parent_workshop[row.id] = result.workshopId

    # THE CASCADE ORDER, and it is a correctness order rather than a tidiness one. Each bucket may be a
    # parent of the ones below it, so a bucket must be decided before anything that could inherit from
    # it: artisans are parents of products, tools and media; products are parents of processes and
    # media; interviews are parents of media. ``backend/prisma/migrations/20260729120000_*`` runs its
    # statements in EXACTLY this order for exactly this reason — the migration and this service must not
    # disagree about a row, and the only way they could is by cascading differently.

    # --- Artisans: their roster row, then WINDOW. ------------------------------------------------
    #
    # The roster is an artisan's PARENT rung: a ``WorkshopArtisan`` row is a workshop saying "this person
    # was here", which is the same class of fact as a product naming its workshop. Two roster rows for
    # two workshops is a person who attended both, and which workshop "their" record belongs to is then
    # genuinely a question for a person.
    record(
        "artisans",
        list(loose_artisans),
        lambda row: decide(
            row.id,
            title_of(row, "name", "localName"),
            [
                (RUNG_PARENT, [link.workshopId for link in (getattr(row, "workshops", None) or [])]),
                rung_window(row, "recordedAt", "createdAt"),
            ],
        ),
    )
    # An artisan mapped a moment ago is now a parent for the products, tools and media below, and is
    # also new evidence for an interview they sat in.
    for artisan_row in loose_artisans:
        mapped = parent_workshop.get(artisan_row.id)
        if mapped and mapped not in artisan_workshops.setdefault(artisan_row.id, []):
            artisan_workshops[artisan_row.id].append(mapped)

    # --- Interviews: ARTISANS, then WINDOW. -----------------------------------------------------
    record(
        "interviews",
        list(interviews),
        lambda row: decide(
            row.id,
            title_of(row, "title"),
            [
                (
                    RUNG_ARTISANS,
                    [
                        workshop
                        for link in (row.artisans or [])
                        if link.artisanId
                        for workshop in artisan_workshops.get(link.artisanId, [])
                    ],
                ),
                rung_window(row, "recordedAt", "interviewDate", "createdAt"),
            ],
        ),
    )

    # --- Products and tools: PARENT (the artisan they document), then WINDOW. ---------------------
    for bucket, rows in (("products", products), ("tools", tools)):
        record(
            bucket,
            list(rows),
            lambda row: decide(
                row.id,
                title_of(row, "productName", "toolkitName", "englishName", "craftName"),
                [
                    (RUNG_PARENT, [first_parent_workshop(row, "artisanId")]),
                    rung_window(row, "recordedAt", "createdAt"),
                ],
            ),
        )

    # --- Processes: PARENT (their product), then WINDOW. -----------------------------------------
    #
    # A Process names a product, never an artisan — its ``productId`` is NOT NULL — so the parent rung
    # reads the product. That is also the OLDER of the two readings of "which workshop was this process
    # documented at": before the column existed a process reached a workshop only through its product,
    # and this keeps the two answers identical.
    record(
        "processes",
        list(processes),
        lambda row: decide(
            row.id,
            title_of(row, "name"),
            [
                (RUNG_PARENT, [first_parent_workshop(row, "productId")]),
                rung_window(row, "recordedAt", "createdAt"),
            ],
        ),
    )

    # --- Media: PARENT, then WINDOW. Last, so every possible parent is already decided. -----------
    record(
        "media",
        list(media),
        lambda row: decide(
            row.id,
            title_of(row, "originalFilename", "caption"),
            [
                (
                    RUNG_PARENT,
                    [
                        first_parent_workshop(
                            row,
                            "questionnaireInterviewId",
                            "productId",
                            "toolId",
                            "artisanId",
                            "craftId",
                            # LAST, and it is the string tag rather than a column: a clip attached to a
                            # Process has no typed foreign key to read (MediaFile has no ``processId``),
                            # so its parent is reachable only through ``linkedRecordId``. Only ids that
                            # are actually IN ``parent_workshop`` answer, so a tag pointing at something
                            # this pass never read — another media row, a deleted record — contributes
                            # nothing and the row falls through to its own date window.
                            "linkedRecordId",
                        )
                    ],
                ),
                rung_window(row, "recordedAt", "createdAt"),
            ],
        ),
    )

    return LadderRun(plans=plans, workshopTitles=workshop_titles, windows=windows)


def payload_for(run: LadderRun, applied: dict[str, int] | None = None) -> dict[str, Any]:
    """The plan as the wire shape both clients render, optionally with what was actually written."""
    titles = run.workshopTitles
    buckets: list[dict[str, Any]] = []
    total_unassigned = 0
    total_resolved = 0
    for bucket, _delegate, singular, plural in BUCKETS:
        plan = run.plans[bucket]
        resolved = plan.resolved
        unresolved = plan.unresolved
        total_unassigned += plan.unassigned
        total_resolved += len(resolved)

        by_rung: dict[str, int] = {}
        by_workshop: dict[str, int] = {}
        for row in resolved:
            if row.rung:
                by_rung[row.rung] = by_rung.get(row.rung, 0) + 1
            if row.workshopId:
                by_workshop[row.workshopId] = by_workshop.get(row.workshopId, 0) + 1
        by_reason: dict[str, int] = {}
        for row in unresolved:
            reason = row.reason or REASON_NO_EVIDENCE
            by_reason[reason] = by_reason.get(reason, 0) + 1

        buckets.append(
            {
                "bucket": bucket,
                "singular": singular,
                "plural": plural,
                "unassigned": plan.unassigned,
                "resolved": len(resolved),
                "unresolved": len(unresolved),
                "byRung": [
                    {"rung": rung, "copy": RUNG_COPY.get(rung, rung), "count": by_rung[rung]}
                    for rung in RUNGS
                    if by_rung.get(rung)
                ],
                "byReason": [
                    {"reason": reason, "copy": REASON_COPY.get(reason, reason), "count": count}
                    for reason, count in sorted(by_reason.items(), key=lambda item: -item[1])
                ],
                "byWorkshop": [
                    {
                        "workshopId": workshop_id,
                        "title": titles.get(workshop_id, "Unknown workshop"),
                        "count": count,
                    }
                    for workshop_id, count in sorted(by_workshop.items(), key=lambda item: -item[1])
                ],
                # Resolved rows first, then the ones needing a person — the second group is what an
                # admin actually has to act on, and it is never the group that gets truncated away
                # while the first fills the cap.
                "rows": [
                    {
                        "id": row.id,
                        "title": row.title,
                        "workshopId": row.workshopId,
                        "workshopTitle": titles.get(row.workshopId, "Unknown workshop")
                        if row.workshopId
                        else None,
                        "rung": row.rung,
                        "rungCopy": RUNG_COPY.get(row.rung or ""),
                        "reason": row.reason,
                        "reasonCopy": REASON_COPY.get(row.reason or ""),
                        "candidateTitles": [
                            titles.get(candidate, "Unknown workshop") for candidate in row.candidates
                        ],
                    }
                    for row in (*unresolved, *resolved)[:_MAX_ROWS_PER_BUCKET]
                ],
                "rowsTruncated": len(plan.rows) > _MAX_ROWS_PER_BUCKET,
                "applied": (applied or {}).get(bucket) if applied is not None else None,
            }
        )

    return {
        "workshops": [
            {
                "id": window.id,
                "title": window.title,
                "start": window.start.isoformat(),
                "end": window.end.isoformat(),
            }
            for window in run.windows
        ],
        "buckets": buckets,
        "totals": {
            "unassigned": total_unassigned,
            "resolved": total_resolved,
            "unresolved": total_unassigned - total_resolved,
            "applied": sum((applied or {}).values()) if applied is not None else None,
        },
    }


async def plan_workshop_mapping() -> dict[str, Any]:
    """What WOULD be mapped, per record type, per row, with the rung that decided it. Read-only."""
    return payload_for(await run_ladder())


def _chunks(values: list[str], size: int) -> list[list[str]]:
    return [values[index : index + size] for index in range(0, len(values), size)]


async def apply_workshop_mapping() -> dict[str, Any]:
    """Stamp every row the ladder resolved, and return the plan that was applied.

    RE-DERIVES the plan rather than accepting one from the client. A client-supplied plan is a
    client-supplied list of "set this row's workshop to that id", which is a much wider power than
    "close the gap the server itself found" — and it would already be stale between the screen
    rendering and the button being pressed.

    Every write is one ``update_many`` per (bucket, workshop) pair with ``workshopId: None`` still in
    the ``where``. That is what makes this idempotent AND safe to race: a row somebody assigned by hand
    while the admin was reading the report simply is not matched, so it keeps the answer a person gave
    it. The counts returned are what the DATABASE reports it changed, not what the plan hoped for, so a
    row that slipped out from under a write shows up as a shortfall instead of being hidden.

    The writes are driven off the UN-TRUNCATED plan, never off the ``rows`` list in the payload — that
    list is capped for the wire, and driving writes from it would silently stop at forty rows a bucket.
    """
    run = await run_ladder()
    applied: dict[str, int] = {}
    for bucket, delegate_name, _singular, _plural in BUCKETS:
        delegate = getattr(db, delegate_name)
        grouped: dict[str, list[str]] = {}
        for row in run.plans[bucket].resolved:
            grouped.setdefault(str(row.workshopId), []).append(row.id)
        changed = 0
        for workshop_id, ids in grouped.items():
            for chunk in _chunks(ids, _WRITE_CHUNK):
                result = await delegate.update_many(
                    where={"id": {"in": chunk}, "workshopId": None},
                    data={"workshopId": workshop_id},
                )
                changed += int(result or 0)
        applied[bucket] = changed
    return payload_for(run, applied)


async def count_unassigned_interviews(artisan_ids: Iterable[str] | None = None) -> int:
    """How many questionnaire interviews carry no workshop at all.

    Read by the completion matrix so the invisible-data failure mode this module exists to fix can
    never be silent again: a matrix scoped to a workshop says, on screen, that N interviews are
    excluded from every workshop scope because nothing on them names one.

    ``artisan_ids`` narrows it to the interviews the artisans ON SCREEN sat in, because that is the
    only shortfall that explains THIS matrix. An empty (but not None) collection means "no artisans in
    scope", for which the honest answer is zero rather than every unassigned interview in the corpus.
    """
    where: dict[str, Any] = {"workshopId": None}
    if artisan_ids is not None:
        ids = [value for value in artisan_ids if value]
        if not ids:
            return 0
        where["artisans"] = {"some": {"artisanId": {"in": ids}}}
    return await db.questionnaireinterview.count(where=where)
