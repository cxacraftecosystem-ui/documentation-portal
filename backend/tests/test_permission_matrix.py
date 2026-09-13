"""Who may CREATE, UPDATE and DELETE each record type — the confirmed permission matrix, as tests.

    ADMIN (50) / MASTER_ADMIN (60)  everything, everywhere.
    PROFESSOR (40)                  create + update crafts and workshops, but NOT delete them;
                                    edit the data of anyone ranked below them.
    RESEARCHER (30)                 create an artisan, product, tool, process or interview.
    FIELD_CONTRIBUTOR (20) /        create NOTHING. They populate what already exists: attach media
    CROWDSOURCE_VOLUNTEER (10)      to an artisan, answer an existing interview, comment on a record.

The last line is the one worth testing hardest. Tightening creation is a two-line change and it is
very easy to tighten it one endpoint too far — POST /questionnaire/interviews in particular is BOTH
the "open a new interview" call and, for an artisan set that already has one, the "answer the
existing interview" call. Refusing the whole endpoint would silently remove the contribution path
the two bottom tiers exist for, and every test below the divider is there to catch that.

NOTHING HERE TOUCHES A DATABASE. The real routers are mounted and driven over HTTP with ``db``
swapped for a tripwire that raises on the first delegate anybody reads off it. That makes the two
outcomes unambiguous and needs no schema: a refusal is an HTTP 403 with the tripwire never touched
(so the gate fired before any work), and an authorisation is the tripwire raising (so every guard
passed and the handler body began). Assertions phrased as "not 403" would also pass for a route
that 404s or 422s for an unrelated reason; these two cannot. A test that needs the handler to get
PAST a particular read hands that one delegate over with ``preload``.
"""

import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.api.routes import data_access, media, questionnaire
from app.core import deps
from app.services.artisan_identity import verhoeff_ok

ALL_ROLES = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR", "ADMIN", "MASTER_ADMIN")
LOWER_TIERS = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR")


def _user(role: str, user_id: str = "u1", **grants: Any) -> SimpleNamespace:
    """A user row. Every grantable capability defaults to False; pass one to turn it on."""
    flags = {
        "canManageCrafts": False,
        "canManageWorkshops": False,
        "canManageQuestionnaire": False,
        "canDownloadDataset": False,
        "canReview": False,
        "canViewProvenance": False,
    }
    flags.update(grants)
    return SimpleNamespace(id=user_id, email=f"{user_id}@example.test", name="Test", role=role, **flags)


def _valid_aadhaar() -> str:
    """A 12-digit number the create schema accepts, checked with the app's own Verhoeff routine
    rather than hard-coded — a literal here would rot the day the validator is tightened."""
    for last in "0123456789":
        candidate = f"22345678901{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no Verhoeff-valid Aadhaar in the candidate range")


# A create carries both halves of a location: the coordinate the device reported, and the state and
# district the researcher stated about the subject. The second pair is not decoration here — since
# 20260727120000_location_stated_address it is required on create (see require_location in
# app/schemas/common.py), because a nullable column nobody has to fill is how `district` stayed empty
# on every record for as long as it has been a shipped export column.
LOCATION = {
    "latitude": 22.57,
    "longitude": 88.36,
    "state": "West Bengal",
    "district": "Kolkata",
}
CRAFT_BODY = {"name": "Test craft"}
WORKSHOP_BODY = {"title": "Test workshop", "place": "Kolkata", "location": LOCATION}
ARTISAN_BODY = {
    "name": "Test artisan",
    "place": "Kolkata",
    "aadhaarNumber": _valid_aadhaar(),
    "dos": "Greet the artisan first.",
    "donts": "Do not photograph without asking.",
    "craftId": "c1",
    "location": LOCATION,
}
PRODUCT_BODY = {
    "craftName": "Test craft",
    "place": "Kolkata",
    "artisanName": "Test artisan",
    "productName": "Test product",
    "location": LOCATION,
}
TOOL_BODY = {
    "craftName": "Test craft",
    "place": "Kolkata",
    "artisanName": "Test artisan",
    "toolkitName": "Test toolkit",
    "location": LOCATION,
}
PROCESS_BODY = {"name": "Test process", "productId": "p1"}
INTERVIEW_BODY = {"title": "Test interview", "location": LOCATION, "artisanIds": ["a1"]}

CREATE_BODIES = [
    ("/artisans", ARTISAN_BODY),
    ("/products", PRODUCT_BODY),
    ("/tools", TOOL_BODY),
    ("/processes", PROCESS_BODY),
]
TAXONOMY = [("/crafts", CRAFT_BODY), ("/workshops", WORKSHOP_BODY)]


class _DatabaseTouched(Exception):
    """The route's guards all passed and its body started working — the whole "allowed" assertion."""


class _Tripwire:
    """Stands in for ``db``. Reading any delegate off it means the handler got past every guard,
    unless the test explicitly handed that delegate over with ``preload``."""

    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def preload(self, name: str, delegate: Any) -> None:
        self._preloaded[name] = delegate

    def reset(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def __getattr__(self, name: str) -> Any:
        # __getattr__, not __getattribute__, so the real attributes above stay readable.
        preloaded = object.__getattribute__(self, "_preloaded")
        if name in preloaded:
            return preloaded[name]
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    """Either "the gate refused it" or "the handler ran" — never a bare status code, so a 404 or a
    422 from an unrelated cause can never be mistaken for either one."""

    def __init__(self, *, reached: bool, status_code: int | None = None, detail: Any = "") -> None:
        self.reached = reached
        self.status_code = status_code
        self.detail = str(detail)

    @property
    def refused(self) -> bool:
        return self.status_code == 403

    def __repr__(self) -> str:  # pragma: no cover - only ever read out of a failure message
        return "reached-handler" if self.reached else f"HTTP {self.status_code}: {self.detail}"


# The whole API, assembled ONCE. Twenty-eight routers with their response models cost a couple of
# seconds to build, and rebuilding them per test turned a fast suite into a two-minute one. Nothing
# request-scoped lives on it: the caller comes from ``_CURRENT`` and the database from the fixture.
_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _Api:
    """The real routers, driven as one caller at a time."""

    def __init__(self, tripwire: _Tripwire) -> None:
        self.tripwire = tripwire

    def as_(self, user: Any) -> "_Api":
        _CURRENT["user"] = user
        return self

    def preload(self, name: str, delegate: Any) -> "_Api":
        """Let the handler get past one specific database read, for the tests whose decision point
        is behind it."""
        self.tripwire.preload(name, delegate)
        return self

    def call(self, method: str, path: str, body: dict[str, Any] | None = None) -> _Outcome:
        async def run() -> _Outcome:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://matrix.test") as client:
                response = await client.request(method, f"/api{path}", json=body)
            # PARSED ONLY WHEN IT IS JSON. Every route in this matrix used to answer JSON or nothing,
            # so `response.json()` on any non-empty body was safe. `GET /questionnaires/pro-forma`
            # answers 200 with an .xlsx, and httpx raises JSONDecodeError on it — which is not
            # `_DatabaseTouched` and is not caught below, so the test ERRORS instead of asserting,
            # and the failure reads as a broken test rather than as a broken guard.
            # A non-JSON body carries no `detail` to report, so `{}` is the honest payload for it.
            content_type = response.headers.get("content-type", "")
            payload = (
                response.json()
                if response.content and content_type.startswith("application/json")
                else {}
            )
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return _Outcome(reached=False, status_code=response.status_code, detail=detail)

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return _Outcome(reached=True)


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch) -> _Api:
    """Mount the real API with every module's ``db`` replaced.

    The modules do ``from app.core.db import db``, so each holds its OWN reference and patching the
    source alone would miss all of them. Rebinding by identity finds every one already imported and
    keeps finding them when a new module is added.
    """
    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)
    yield _Api(tripwire)
    _CURRENT["user"] = None


def _returning(value: Any):
    async def call(*_args: Any, **_kwargs: Any) -> Any:
        return value

    return call


# --- Crafts and workshops: rank, and nothing but rank --------------------------------------------


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_a_researcher_cannot_create_or_update_a_craft_or_workshop(api: _Api, path: str, body: dict) -> None:
    caller = api.as_(_user("RESEARCHER"))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)

    assert (created.refused, updated.refused) == (True, True), (created, updated)
    # The refusal happened in the dependency, before the route read a single row.
    assert api.tripwire.touched is False


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_the_old_per_user_grant_no_longer_opens_that_door(api: _Api, path: str, body: dict) -> None:
    """THE HOLE THIS CLOSES. ``canManageCrafts`` / ``canManageWorkshops`` used to sit in an OR beside
    the rank floor, so a master admin could hand craft or workshop authorship to a researcher — or to
    a volunteer — without their role column ever changing. Rank is now the only criterion; the
    columns survive in the schema, unread, so restoring the old behaviour is one clause."""
    caller = api.as_(_user("RESEARCHER", canManageCrafts=True, canManageWorkshops=True))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)

    assert (created.refused, updated.refused) == (True, True), (created, updated)


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_a_professor_creates_and_updates_but_may_not_delete(api: _Api, path: str, body: dict) -> None:
    caller = api.as_(_user("PROFESSOR"))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)
    api.tripwire.reset()
    deleted = caller.call("DELETE", f"{path}/x1")

    assert created.reached and updated.reached, (created, updated)
    assert deleted.refused, deleted
    assert api.tripwire.touched is False, "the delete was refused before reading the record"


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
@pytest.mark.parametrize("path,body", TAXONOMY)
def test_admins_create_update_and_delete(api: _Api, role: str, path: str, body: dict) -> None:
    caller = api.as_(_user(role))

    outcomes = [
        caller.call("POST", path, body),
        caller.call("PATCH", f"{path}/x1", body),
        caller.call("DELETE", f"{path}/x1"),
    ]

    assert all(outcome.reached for outcome in outcomes), outcomes


def test_a_new_craft_cannot_be_minted_sideways_through_the_artisan_form(api: _Api) -> None:
    """``craftName`` on POST /artisans creates the craft when nothing matches it — the same write
    POST /crafts guards, reached from another route. Both ask ``can_manage_crafts``, so the grant
    that no longer opens the front door does not open this one either."""
    caller = api.as_(_user("RESEARCHER", canManageCrafts=True))
    # Let the name lookup answer "no such craft", which is the branch that decides.
    caller.preload("craft", SimpleNamespace(find_unique=_returning(None)))

    body = {**ARTISAN_BODY, "craftName": "A craft nobody added yet"}
    body.pop("craftId")
    outcome = caller.call("POST", "/artisans", body)

    assert outcome.refused, outcome
    assert "ask a professor or an admin to add it" in outcome.detail


# --- Creating a record: Researcher and above ------------------------------------------------------


@pytest.mark.parametrize("path,body", CREATE_BODIES)
@pytest.mark.parametrize("role", LOWER_TIERS)
def test_the_two_bottom_tiers_cannot_open_a_new_record(api: _Api, path: str, body: dict, role: str) -> None:
    outcome = api.as_(_user(role)).call("POST", path, body)

    assert outcome.refused, outcome
    assert "Researcher access or above" in outcome.detail
    assert api.tripwire.touched is False


@pytest.mark.parametrize("path,body", CREATE_BODIES)
def test_a_researcher_opens_all_four_record_types(api: _Api, path: str, body: dict) -> None:
    assert api.as_(_user("RESEARCHER")).call("POST", path, body).reached


# --- The lower tiers keep every path they exist for ----------------------------------------------
#
# Everything below this line is a REGRESSION guard, not a new rule. If one of these starts failing
# because creation was tightened, the tightening went too far and the crowdsourcing path is broken.


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_attaches_media_to_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    """Both halves of an upload: asking for the signed URL, then registering the finished object
    against an existing artisan. Neither is gated by record creation, and neither may become so."""
    monkeypatch.setattr(media, "presign_put_url", lambda key, mime: f"https://upload.test/{key}")
    monkeypatch.setattr(media, "public_url_for_key", lambda key: f"https://cdn.test/{key}")
    caller = api.as_(_user(role))

    presigned = caller.call(
        "POST",
        "/media/presign",
        {"filename": "loom.jpg", "mimeType": "image/jpeg", "mediaType": "IMAGE", "sizeBytes": 1024},
    )
    completed = caller.call(
        "POST",
        "/media/complete",
        {
            "originalFilename": "loom.jpg",
            "mediaType": "IMAGE",
            "mimeType": "image/jpeg",
            "sizeBytes": 1024,
            "objectKey": "media/u1/loom.jpg",
            "linkedRecordType": "artisan",
            "linkedRecordId": "a1",
        },
    )

    assert presigned.status_code == 200, presigned
    assert completed.reached, completed


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_answers_an_interview_that_already_exists(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    """POST /questionnaire/interviews for an artisan set that ALREADY has one folds into it — that is
    how both apps submit answers to a shared entry. The create gate sits after the fold for exactly
    this reason, and this is the test that says so."""
    folded: list[str] = []

    async def fold(existing, payload, current_user, check=None):
        folded.append(current_user.role)
        return {"id": existing.id}

    monkeypatch.setattr(questionnaire, "merge_into_interview", fold)
    # TWO preloads, and the second one is why this test still tests what it says it does. Since the
    # questionnaire-container change `create_interview` resolves an INSTRUMENT before it looks for
    # an existing sitting, which is a read of `db.questionnaire`; without handing that delegate over
    # the tripwire fires on the instrument lookup instead of on the fold, `_Outcome(reached=True)`
    # comes back, and the test passes while no longer exercising the lower-tier contribution path it
    # exists for. And the lookup itself is now `find_first` over (questionnaireId, artisanSetKey),
    # not `find_unique` over the set key alone — a `find_unique`-only stub would raise AttributeError
    # rather than fold.
    api.preload("questionnaire", SimpleNamespace(find_first=_returning(SimpleNamespace(id="qnr-1"))))
    api.preload("questionnaireinterview", SimpleNamespace(find_first=_returning(SimpleNamespace(id="i1"))))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.status_code == 201, outcome
    assert folded == [role]


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_may_not_open_an_interview_for_an_artisan_set_that_has_none(api: _Api, role: str) -> None:
    """The other half of the same endpoint: nothing to fold into means this really is a create."""
    # Same two preloads, same reason — see the test above. The refusal has to come from the create
    # gate AFTER both reads, not from the tripwire firing on either of them.
    api.preload("questionnaire", SimpleNamespace(find_first=_returning(SimpleNamespace(id="qnr-1"))))
    api.preload("questionnaireinterview", SimpleNamespace(find_first=_returning(None)))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.refused, outcome
    assert "Researcher access or above" in outcome.detail


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_comments_on_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    """Commenting is gated by the sharing tier (owner/admin/COMMENT grant) and by nothing about rank.
    Given the tier, the bottom two tiers reach the write exactly as anyone else does."""
    monkeypatch.setattr(data_access, "_resolve_record_owner", _returning("someone-else"))
    monkeypatch.setattr(data_access, "effective_tier_for_record", _returning("COMMENT"))

    outcome = api.as_(_user(role)).call(
        "POST", "/data-access/comments", {"recordType": "artisan", "recordId": "a1", "body": "Seen at the fair."}
    )

    assert outcome.reached, outcome


# --- The questionnaire CONTAINER: two tiers, and the line between them ----------------------------
#
# Building a FORM stays where it has always been (`require_questionnaire_manager` — Professor and
# above, or the `canManageQuestionnaire` grant). Two acts were narrowed to `require_admin` in the
# questionnaire-container change, and neither is an edit to a form:
#
#   * which instrument is the DEFAULT — where every client that names no instrument lands, including
#     Android builds that predate the field and offline payloads queued before them;
#   * which instrument a WORKSHOP uses — which questions every researcher at that event is shown.
#
# The row that matters most is the LAST one: it asserts the narrowing did not leak onto the builder.

QUESTIONNAIRE_MANAGER_ROUTES = [
    ("POST", "/questionnaires", {"title": "A third instrument"}),
    ("PATCH", "/questionnaires/q1", {"title": "Renamed"}),
    # The builder. Deliberately in the SAME list, because its tier did not change.
    ("POST", "/questionnaire/sections", {"code": "Z", "title": "A new section"}),
]

ADMIN_ONLY_ROUTES = [
    ("PUT", "/questionnaires/q1/default", {"isDefault": True}),
    ("PUT", "/workshops/w1/questionnaire", {"questionnaireId": "q1"}),
]


@pytest.mark.parametrize("method,path,body", QUESTIONNAIRE_MANAGER_ROUTES)
def test_a_researcher_cannot_build_a_questionnaire_without_the_grant(
    api: _Api, method: str, path: str, body: dict
) -> None:
    outcome = api.as_(_user("RESEARCHER")).call(method, path, body)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False


@pytest.mark.parametrize("method,path,body", QUESTIONNAIRE_MANAGER_ROUTES)
def test_a_professor_builds_a_questionnaire_and_so_does_a_granted_researcher(
    api: _Api, method: str, path: str, body: dict
) -> None:
    assert api.as_(_user("PROFESSOR")).call(method, path, body).reached
    api.tripwire.reset()
    granted = api.as_(_user("RESEARCHER", canManageQuestionnaire=True))
    assert granted.call(method, path, body).reached


@pytest.mark.parametrize("method,path,body", ADMIN_ONLY_ROUTES)
def test_choosing_the_default_and_binding_a_workshop_are_refused_below_admin(
    api: _Api, method: str, path: str, body: dict
) -> None:
    """THE TEST THAT PINS THE NARROWING. A Professor holding `canManageQuestionnaire` may build any
    form in the repository and still may not decide which one every unqualified client lands on."""
    professor = api.as_(_user("PROFESSOR", canManageQuestionnaire=True))

    outcome = professor.call(method, path, body)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False


@pytest.mark.parametrize("method,path,body", ADMIN_ONLY_ROUTES)
@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_an_admin_chooses_the_default_and_binds_a_workshop(
    api: _Api, role: str, method: str, path: str, body: dict
) -> None:
    assert api.as_(_user(role)).call(method, path, body).reached


def test_the_narrowing_did_not_leak_onto_the_section_builder(api: _Api) -> None:
    """Stated twice, on purpose. `critic-1-coverage.json` gap #9 names this exact accident: a
    narrowing applied to a neighbouring route by copy-paste, taking the form away from the people
    whose job is to build it. POST /questionnaire/sections is still Professor's."""
    outcome = api.as_(_user("PROFESSOR")).call(
        "POST", "/questionnaire/sections", {"code": "Z", "title": "A new section"}
    )

    assert outcome.reached, outcome


# --- The questionnaire WORKBOOK: admin only, and the builder beside it is NOT ---------------------
#
# Two tiers on one subject, which is new in this codebase and is the thing most likely to be
# "harmonised" by a later change. Both directions are asserted: widening the workbook routes hands
# the whole-instrument press to every questionnaire grant-holder, and narrowing the builder takes the
# section editor away from the professors who use it today.
#
# AND THE TWO GATES ARE DIFFERENT KINDS OF PREDICATE, which is what the director ranks below are
# doing in this file. `is_admin` (deps.py:59-60) is SET MEMBERSHIP over {"MASTER_ADMIN", "ADMIN"};
# `can_manage_questionnaire` (deps.py:67-70) is a RANK FLOOR at PROFESSOR. A rank inserted between 40
# and 50 therefore gains the editor automatically and is refused the workbook automatically —
# MINISTRY_ADMIN included, despite the name. Deliberate, documented in docs/PERMISSIONS.md §1.1, and
# asserted both ways here so that changing it has to be a decision rather than a side effect.

WORKBOOK_ROUTES = [
    ("GET", "/questionnaires/pro-forma"),
    ("GET", "/questionnaires/q1/xlsx"),
    ("GET", "/questionnaires/q1/question-set.xlsx"),
    ("POST", "/questionnaires/upload"),
    ("POST", "/questionnaires/q1/upload"),
]

# The four ladder roles below ADMIN, plus the three the role workstream inserts between PROFESSOR and
# ADMIN. Listing the three now means this file goes red the day they land if anybody widens the set
# literal in `is_admin` without saying so. A role absent from ROLE_RANK ranks 0 (deps.py:52), which
# is refused by both gates, so these rows pass today and stay meaningful tomorrow.
BELOW_ADMIN = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "PROFESSOR",
    "ASSISTANT_DIRECTOR",
    "REGIONAL_DIRECTOR",
    "MINISTRY_ADMIN",
)


@pytest.mark.parametrize("method,path", WORKBOOK_ROUTES)
@pytest.mark.parametrize("role", BELOW_ADMIN)
def test_nobody_below_admin_reaches_a_questionnaire_workbook_route(
    api: _Api, method: str, path: str, role: str
) -> None:
    outcome = api.as_(_user(role)).call(method, path)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False


@pytest.mark.parametrize("method,path", WORKBOOK_ROUTES)
def test_the_questionnaire_grant_does_not_open_the_workbook_door(
    api: _Api, method: str, path: str
) -> None:
    """`canManageQuestionnaire` is Professor-tier and opens the SECTION EDITOR. It must not open
    this: one spreadsheet re-states the whole instrument, and everything absent from it is removed by
    rule."""
    outcome = api.as_(_user("PROFESSOR", canManageQuestionnaire=True)).call(method, path)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False


@pytest.mark.parametrize("role", ("ADMIN", "MASTER_ADMIN"))
def test_an_admin_reaches_the_workbook_upload_handler(api: _Api, role: str) -> None:
    outcome = api.as_(_user(role)).call("POST", "/questionnaires/upload")

    # A multipart route with no file is a 422 from FastAPI's own body validation, which runs AFTER
    # the dependencies and therefore BEFORE the handler body — so the tripwire is the wrong
    # instrument here and the assertion is that the gate did not answer 403.
    assert outcome.status_code == 422, outcome


def test_the_pro_forma_path_is_not_swallowed_by_an_id_route(api: _Api) -> None:
    """ROUTE ORDER, PINNED. `/questionnaires/pro-forma` is a literal segment sharing a prefix with
    `/questionnaires/{questionnaire_id}`, and `[^/]+` matches "pro-forma" happily. Declared below the
    id route, this answers 404 "Record not found" — which reads as a broken database, not as a
    routing problem, and would send somebody looking in Prisma. The pro-forma touches no database at
    all, so a 200 carrying an .xlsx is proof the literal route won.

    Requires the content-type guard in `_Api.call`: this is the only route in this file that answers
    a non-JSON body, and without that guard this test ERRORS on `response.json()` instead of
    asserting anything."""
    outcome = api.as_(_user("ADMIN")).call("GET", "/questionnaires/pro-forma")

    assert outcome.status_code == 200, outcome
    assert outcome.reached is False  # no delegate was read; the workbook is built in memory
    assert api.tripwire.touched is False


# ONE BODY PER ROUTE, NOT ONE BODY FOR ALL FOUR. Every questionnaire schema inherits APIModel, which
# is extra="forbid" (app/schemas/common.py:13). A single {"code","title","sectionId","prompt"} body
# is rejected by all four with a 422 from body validation — `reached` is then False and the failure
# reads exactly like a permission regression, which is the one thing this file must never lie about.
EDITOR_ROUTES = [
    ("POST", "/questionnaire/sections", {"code": "A", "title": "T"}),
    ("PATCH", "/questionnaire/sections/s1", {"title": "T"}),
    ("POST", "/questionnaire/questions", {"sectionId": "s1", "prompt": "P"}),
    ("PATCH", "/questionnaire/questions/q1", {"prompt": "P"}),
]


@pytest.mark.parametrize("method,path,body", EDITOR_ROUTES)
def test_the_section_and_question_editor_is_still_professor_tier(
    api: _Api, method: str, path: str, body: dict
) -> None:
    """THE OTHER HALF OF THE SPLIT. A change that made the whole questionnaire admin-only would pass
    every test above and would silently remove the editor from every professor in the repository."""
    outcome = api.as_(_user("PROFESSOR")).call(method, path, body)

    assert outcome.reached, outcome


@pytest.mark.parametrize("method,path,body", EDITOR_ROUTES)
def test_the_questionnaire_grant_still_opens_the_editor(
    api: _Api, method: str, path: str, body: dict
) -> None:
    outcome = api.as_(_user("RESEARCHER", canManageQuestionnaire=True)).call(method, path, body)

    assert outcome.reached, outcome


def test_a_ministry_admin_is_refused_the_workbook_but_still_reaches_the_section_editor(
    api: _Api,
) -> None:
    """THE ASYMMETRY, WRITTEN DOWN AS A TEST because it is the one a reader will meet as a bug.

    `is_admin` is set membership over {"MASTER_ADMIN", "ADMIN"} (deps.py:59-60) and
    `can_manage_questionnaire` is a rank floor at PROFESSOR (deps.py:67-70). MINISTRY_ADMIN ranks 48
    in the role workstream's ladder — above the floor, outside the set — so it edits questions and
    cannot upload a workbook. If that is ever wrong, it is wrong deliberately: widen the set literal
    and change this test in the same commit.

    Written so it passes BEFORE the ladder lands too: an unknown role ranks 0 (deps.py:52), so the
    editor half is skipped rather than asserted false, and the refusal half holds either way."""
    assert api.as_(_user("MINISTRY_ADMIN")).call("POST", "/questionnaires/upload").refused

    if deps.ROLE_RANK.get("MINISTRY_ADMIN", 0) >= deps.ROLE_RANK["PROFESSOR"]:
        api.tripwire.reset()
        method, path, body = EDITOR_ROUTES[0]
        assert api.as_(_user("MINISTRY_ADMIN")).call(method, path, body).reached


# --- "Below them", decided once ------------------------------------------------------------------


def test_a_professor_edits_the_work_of_everyone_ranked_below_them() -> None:
    professor = _user("PROFESSOR")

    assert deps.can_edit_others_record(professor, "RESEARCHER") is True
    assert deps.can_edit_others_record(professor, "FIELD_CONTRIBUTOR") is True
    assert deps.can_edit_others_record(professor, "CROWDSOURCE_VOLUNTEER") is True
    # Strictly below: a peer's record is not theirs to overwrite.
    assert deps.can_edit_others_record(professor, "PROFESSOR") is False
    assert deps.can_edit_others_record(professor, "ADMIN") is False
    # A record with no creator role on file is treated as a researcher's, as the review ladder does.
    assert deps.can_edit_others_record(professor, None) is True


def test_the_edit_ladder_is_the_review_ladder_and_not_a_second_reading_of_it() -> None:
    """Two rank rules that disagree by one tier is how a privilege bug hides. Above the Professor
    floor the edit rule must BE ``can_review_record``, tier for tier, with no exceptions."""
    for role in ("PROFESSOR", "ADMIN", "MASTER_ADMIN"):
        editor = _user(role)
        for creator_role in ALL_ROLES:
            assert deps.can_edit_others_record(editor, creator_role) == deps.can_review_record(editor, creator_role), (
                role,
                creator_role,
            )


def test_editing_is_narrower_than_reviewing_below_professor() -> None:
    """The review ladder deliberately reaches further down than editing: a field contributor reviews
    a volunteer's submission and a researcher reviews a field contributor's. Neither of them gets to
    rewrite it — only Professor and above edit other people's work."""
    for role in ("FIELD_CONTRIBUTOR", "RESEARCHER"):
        reviewer = _user(role)
        assert deps.can_review_record(reviewer, "CROWDSOURCE_VOLUNTEER") is True
        assert deps.can_edit_others_record(reviewer, "CROWDSOURCE_VOLUNTEER") is False


def test_a_professor_is_privileged_over_a_lower_ranked_authors_record(monkeypatch: pytest.MonkeyPatch) -> None:
    """``may_edit_lower_ranked_record`` is the async half — the creator's role looked up once. It
    must cost that lookup ONLY for the ranks the rule can apply to."""
    lookups: list[str] = []

    async def find_unique(where: dict, **_: Any) -> Any:
        lookups.append(where["id"])
        return _user("RESEARCHER", user_id=where["id"])

    monkeypatch.setattr(deps, "db", SimpleNamespace(user=SimpleNamespace(find_unique=find_unique)))

    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), "author")) is True
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("RESEARCHER"), "author")) is False
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), None)) is False
    # One lookup, for the professor. The researcher and the ownerless record are refused on rank
    # alone, so no ordinary contributor's edit pays a query for a clause that cannot help them.
    assert lookups == ["author"]


def test_the_craft_and_workshop_predicates_read_rank_alone() -> None:
    for role in ALL_ROLES:
        granted = _user(role, canManageCrafts=True, canManageWorkshops=True)
        expected = deps.has_rank(granted, "PROFESSOR")
        assert deps.can_manage_crafts(granted) is expected, role
        assert deps.can_manage_workshops(granted) is expected, role
