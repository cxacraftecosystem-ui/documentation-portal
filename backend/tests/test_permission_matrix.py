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
    for last in "0123456789":
        candidate = f"22345678901{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no Verhoeff-valid Aadhaar in the candidate range")


LOCATION = {
    "latitude": 22.57,
    "longitude": 88.36,
    "state": "West Bengal",
    "district": "Kolkata",
}
CRAFT_BODY = {"name": "Test craft", "subCategoryId": "sub_1", "giStatus": "NON_GI"}
WORKSHOP_BODY = {"title": "Test workshop", "date": "2026-10-20", "place": "Kolkata", "location": LOCATION}
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
    pass


class _Tripwire:
    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def preload(self, name: str, delegate: Any) -> None:
        self._preloaded[name] = delegate

    def reset(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def __getattr__(self, name: str) -> Any:
        preloaded = object.__getattribute__(self, "_preloaded")
        if name in preloaded:
            return preloaded[name]
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    def __init__(self, *, reached: bool, status_code: int | None = None, detail: Any = "") -> None:
        self.reached = reached
        self.status_code = status_code
        self.detail = str(detail)

    @property
    def refused(self) -> bool:
        return self.status_code == 403

    def __repr__(self) -> str:  # pragma: no cover
        return "reached-handler" if self.reached else f"HTTP {self.status_code}: {self.detail}"


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _Api:
    def __init__(self, tripwire: _Tripwire) -> None:
        self.tripwire = tripwire

    def as_(self, user: Any) -> "_Api":
        _CURRENT["user"] = user
        return self

    def preload(self, name: str, delegate: Any) -> "_Api":
        self.tripwire.preload(name, delegate)
        return self

    def call(self, method: str, path: str, body: dict[str, Any] | None = None) -> _Outcome:
        async def run() -> _Outcome:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://matrix.test") as client:
                response = await client.request(method, f"/api{path}", json=body)
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
    tripwire = _Tripwire()
    tripwire.preload("artisanmerge", SimpleNamespace(find_many=_returning([]), find_first=_returning(None)))
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



@pytest.mark.parametrize("path,body", TAXONOMY)
def test_a_researcher_cannot_create_or_update_a_craft_or_workshop(api: _Api, path: str, body: dict) -> None:
    caller = api.as_(_user("RESEARCHER"))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)

    assert (created.refused, updated.refused) == (True, True), (created, updated)
    assert api.tripwire.touched is False


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_the_old_per_user_grant_no_longer_opens_that_door(api: _Api, path: str, body: dict) -> None:
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


def test_a_typed_craft_name_never_mints_a_craft(api: _Api, monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import craft_master

    real_resolve = craft_master.resolve_record_craft
    resolved: list[dict[str, Any]] = []

    async def _spy(*args: Any, **kwargs: Any) -> dict[str, Any]:
        data = await real_resolve(*args, **kwargs)
        resolved.append(dict(data))
        return data

    monkeypatch.setattr(craft_master, "resolve_record_craft", _spy)
    caller = api.as_(_user("RESEARCHER", canManageCrafts=True))
    minted: list[Any] = []

    async def _mint(**kwargs: Any) -> None:
        minted.append(kwargs)

    caller.preload(
        "craft", SimpleNamespace(find_unique=_returning(None), find_many=_returning([]), create=_mint)
    )
    caller.preload("craftspelling", SimpleNamespace(find_unique=_returning(None), find_many=_returning([])))

    body = {**ARTISAN_BODY, "craftName": "A craft nobody added yet"}
    body.pop("craftId")
    outcome = caller.call("POST", "/artisans", body)

    assert minted == [], "a typed craft name created a craft"
    assert outcome.reached, outcome
    assert resolved, "the typed craft name was never resolved"
    assert not resolved[-1].get("craftId")
    assert "craftName" not in resolved[-1]
    kept = resolved[-1]["extraMetadata"][craft_master.LEGACY_TEXT_KEY]
    assert craft_master.normalise(kept) == craft_master.normalise("A craft nobody added yet")



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



@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_attaches_media_to_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
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
    folded: list[str] = []

    async def fold(existing, payload, current_user, check=None):
        folded.append(current_user.role)
        return {"id": existing.id}

    monkeypatch.setattr(questionnaire, "merge_into_interview", fold)
    api.preload("questionnaire", SimpleNamespace(find_first=_returning(SimpleNamespace(id="qnr-1"))))
    api.preload("questionnaireinterview", SimpleNamespace(find_first=_returning(SimpleNamespace(id="i1"))))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.status_code == 201, outcome
    assert folded == [role]


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_may_not_open_an_interview_for_an_artisan_set_that_has_none(api: _Api, role: str) -> None:
    api.preload("questionnaire", SimpleNamespace(find_first=_returning(SimpleNamespace(id="qnr-1"))))
    api.preload("questionnaireinterview", SimpleNamespace(find_first=_returning(None)))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.refused, outcome
    assert "Researcher access or above" in outcome.detail


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_comments_on_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    monkeypatch.setattr(data_access, "_resolve_record_owner", _returning("someone-else"))
    monkeypatch.setattr(data_access, "effective_tier_for_record", _returning("COMMENT"))

    outcome = api.as_(_user(role)).call(
        "POST", "/data-access/comments", {"recordType": "artisan", "recordId": "a1", "body": "Seen at the fair."}
    )

    assert outcome.reached, outcome


QUESTIONNAIRE_MANAGER_ROUTES = [
    ("POST", "/questionnaires", {"title": "A third instrument"}),
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


def test_the_admin_narrowing_did_not_leak_onto_the_authors_section_builder(api: _Api) -> None:
    outcome = _authored_by_u1(api).as_(_user("PROFESSOR", "u1")).call(
        "POST", "/questionnaire/sections", {"questionnaireId": "f1", "code": "Z", "title": "A new section"}
    )

    assert outcome.reached, outcome


WORKBOOK_ROUTES = [
    ("GET", "/questionnaires/pro-forma"),
    ("GET", "/questionnaires/q1/question-set.xlsx"),
    ("POST", "/questionnaires/upload"),
]

BELOW_QUESTIONNAIRE_MANAGER = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER")


@pytest.mark.parametrize("method,path", WORKBOOK_ROUTES)
@pytest.mark.parametrize("role", BELOW_QUESTIONNAIRE_MANAGER)
def test_nobody_below_a_questionnaire_manager_reaches_a_workbook_route(
    api: _Api, method: str, path: str, role: str
) -> None:
    outcome = api.as_(_user(role)).call(method, path)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False


@pytest.mark.parametrize("role", ("PROFESSOR", "ADMIN", "MASTER_ADMIN"))
def test_a_form_author_reaches_the_workbook_upload_handler(api: _Api, role: str) -> None:
    outcome = api.as_(_user(role)).call("POST", "/questionnaires/upload")

    assert outcome.status_code == 422, outcome


def test_the_questionnaire_grant_opens_the_workbook_door(api: _Api) -> None:
    granted = api.as_(_user("RESEARCHER", canManageQuestionnaire=True))
    assert granted.call("GET", "/questionnaires/pro-forma").status_code == 200
    assert granted.call("POST", "/questionnaires/upload").status_code == 422


def test_the_pro_forma_path_is_not_swallowed_by_an_id_route(api: _Api) -> None:
    outcome = api.as_(_user("ADMIN")).call("GET", "/questionnaires/pro-forma")

    assert outcome.status_code == 200, outcome
    assert outcome.reached is False
    assert api.tripwire.touched is False


def test_a_ministry_admin_edits_and_reuploads_any_form_but_does_not_author_new_ones(
    api: _Api,
) -> None:
    assert api.as_(_user("MINISTRY_ADMIN")).call("POST", "/questionnaires/upload").refused

    api.tripwire.reset()
    ministry = _authored_by_u1(api).as_(_user("MINISTRY_ADMIN", "u2"))
    assert ministry.call("POST", "/questionnaire/sections", {"questionnaireId": "f1", "code": "Z", "title": "T"}).reached
    assert ministry.call("POST", "/questionnaires/f1/upload").status_code == 422



def test_a_professor_edits_the_work_of_everyone_ranked_below_them() -> None:
    professor = _user("PROFESSOR")

    assert deps.can_edit_others_record(professor, "RESEARCHER") is True
    assert deps.can_edit_others_record(professor, "FIELD_CONTRIBUTOR") is True
    assert deps.can_edit_others_record(professor, "CROWDSOURCE_VOLUNTEER") is True
    assert deps.can_edit_others_record(professor, "PROFESSOR") is False
    assert deps.can_edit_others_record(professor, "ADMIN") is False
    assert deps.can_edit_others_record(professor, None) is True


def test_the_edit_ladder_is_the_review_ladder_and_not_a_second_reading_of_it() -> None:
    for role in ("PROFESSOR", "ADMIN", "MASTER_ADMIN"):
        editor = _user(role)
        for creator_role in ALL_ROLES:
            assert deps.can_edit_others_record(editor, creator_role) == deps.can_review_record(editor, creator_role), (
                role,
                creator_role,
            )


def test_editing_is_narrower_than_reviewing_below_professor() -> None:
    for role in ("FIELD_CONTRIBUTOR", "RESEARCHER"):
        reviewer = _user(role)
        assert deps.can_review_record(reviewer, "CROWDSOURCE_VOLUNTEER") is True
        assert deps.can_edit_others_record(reviewer, "CROWDSOURCE_VOLUNTEER") is False


def test_a_professor_is_privileged_over_a_lower_ranked_authors_record(monkeypatch: pytest.MonkeyPatch) -> None:
    lookups: list[str] = []

    async def find_unique(where: dict, **_: Any) -> Any:
        lookups.append(where["id"])
        return _user("RESEARCHER", user_id=where["id"])

    monkeypatch.setattr(deps, "db", SimpleNamespace(user=SimpleNamespace(find_unique=find_unique)))

    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), "author")) is True
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("RESEARCHER"), "author")) is False
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), None)) is False
    assert lookups == ["author"]


def test_the_craft_and_workshop_predicates_read_rank_alone() -> None:
    for role in ALL_ROLES:
        granted = _user(role, canManageCrafts=True, canManageWorkshops=True)
        expected = deps.has_rank(granted, "PROFESSOR")
        assert deps.can_manage_crafts(granted) is expected, role
        assert deps.can_manage_workshops(granted) is expected, role


CONTENT_ADMINS = ("ADMIN", "MASTER_ADMIN")
NOT_CONTENT_ADMINS = tuple(role for role in ALL_ROLES if role not in CONTENT_ADMINS)

CONTENT_DELETES = [
    ("DELETE", "/artisans/x1"),
    ("DELETE", "/products/x1"),
    ("DELETE", "/tools/x1"),
    ("DELETE", "/processes/x1"),
    ("DELETE", "/crafts/x1"),
    ("DELETE", "/workshops/x1"),
    ("DELETE", "/media/x1"),
    ("DELETE", "/data-access/comments/x1"),
    ("DELETE", "/questionnaire/interviews/x1"),
    ("POST", "/questionnaire/interviews/x1/merge-into/x2"),
    ("DELETE", "/tasks/x1"),
    ("DELETE", "/tasks/batch/b1"),
    ("DELETE", "/map/coverage/aliases/x1"),
]


def test_the_content_admin_set_names_the_three_admin_tiers() -> None:
    assert frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"}) == deps.CONTENT_ADMIN_ROLES
    assert not deps.is_admin(_user("MINISTRY_ADMIN"))
    assert deps.is_content_admin(_user("MINISTRY_ADMIN"))
    for role in ALL_ROLES:
        assert deps.is_content_admin(_user(role)) is (role in CONTENT_ADMINS), role


@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", CONTENT_DELETES)
def test_no_role_below_the_content_admins_deletes(api: _Api, method: str, path: str, role: str) -> None:
    outcome = api.as_(_user(role)).call(method, path)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False, "the delete was refused before reading a row"


@pytest.mark.parametrize("role", CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", CONTENT_DELETES)
def test_every_content_admin_deletes(api: _Api, method: str, path: str, role: str) -> None:
    outcome = api.as_(_user(role)).call(method, path)

    assert outcome.reached, outcome


@pytest.mark.parametrize("call", ["delete_session", "merge_into", "remove"])
@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
def test_no_role_below_the_content_admins_deletes_a_session_or_a_take(call: str, role: str) -> None:
    from fastapi import HTTPException

    from app.questionnaire import recordings, sessions

    work = {
        "delete_session": lambda user: sessions.delete_session("s1", user, client=_Tripwire()),
        "merge_into": lambda user: sessions.merge_into("s1", "s2", user, client=_Tripwire()),
        "remove": lambda user: recordings.remove("r1", user, client=_Tripwire()),
    }[call]
    with pytest.raises(HTTPException) as refusal:
        asyncio.run(work(_user(role)))
    assert refusal.value.status_code == 403



AUTHOR_OR_ADMIN_DELETES = [
    ("DELETE", "/questionnaire/sections/x1"),
    ("DELETE", "/questionnaire/questions/x1"),
    ("DELETE", "/questionnaires/f1"),
]


class _Row:
    def __init__(self, name: str, row: Any) -> None:
        object.__setattr__(self, "_name", name)
        object.__setattr__(self, "find_unique", _returning(row))
        object.__setattr__(self, "find_many", _returning([row]))

    def __getattr__(self, attr: str) -> Any:
        raise _DatabaseTouched(f"{object.__getattribute__(self, '_name')}.{attr}")


def _authored_by_u1(api: _Api) -> _Api:
    return (
        api.preload("questionnaire", _Row("questionnaire", _Form(id="f1", createdById="u1", isDefault=False, isActive=True)))
        .preload("questionnairesection", _Row("questionnairesection", _SECTION))
        .preload("questionnairequestion", _Row("questionnairequestion", _QUESTION))
    )


class _Form(SimpleNamespace):
    @property
    def title(self) -> str:
        raise _DatabaseTouched("questionnaire.title")


_SECTION = SimpleNamespace(id="x1", questionnaireId="f1", isActive=True, code="A", title="About the craft", sortOrder=1)
_QUESTION = SimpleNamespace(
    id="x1", questionnaireId="f1", isActive=True, sectionId="x1", sectionCode="A", prompt="How long?", sortOrder=1
)


@pytest.mark.parametrize("method,path", AUTHOR_OR_ADMIN_DELETES)
def test_the_forms_author_deletes_from_it(api: _Api, method: str, path: str) -> None:
    outcome = _authored_by_u1(api).as_(_user("PROFESSOR", "u1")).call(method, path)

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", AUTHOR_OR_ADMIN_DELETES)
def test_a_content_admin_deletes_from_somebody_elses_form(api: _Api, method: str, path: str, role: str) -> None:
    outcome = _authored_by_u1(api).as_(_user(role, "u2")).call(method, path)

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", AUTHOR_OR_ADMIN_DELETES)
def test_nobody_else_deletes_from_a_form(api: _Api, method: str, path: str, role: str) -> None:
    outcome = _authored_by_u1(api).as_(_user(role, "u2", canManageQuestionnaire=True)).call(method, path)

    assert outcome.refused, outcome


AUTHOR_OR_ADMIN_EDITS = [
    ("PATCH", "/questionnaires/f1", {"title": "Renamed"}),
    ("POST", "/questionnaire/sections", {"questionnaireId": "f1", "code": "Z", "title": "A new section"}),
    ("PATCH", "/questionnaire/sections/x1", {"title": "Renamed"}),
    ("POST", "/questionnaire/sections/reorder", {"sectionIds": ["x1"]}),
    ("POST", "/questionnaire/questions", {"sectionId": "x1", "prompt": "A new question"}),
    ("PATCH", "/questionnaire/questions/x1", {"prompt": "Reworded"}),
    ("POST", "/questionnaire/questions/reorder", {"sectionId": "x1", "questionIds": ["x1"]}),
    ("GET", "/questionnaires/f1/xlsx", None),
]


@pytest.mark.parametrize("method,path,body", AUTHOR_OR_ADMIN_EDITS)
def test_the_forms_author_edits_it(api: _Api, method: str, path: str, body: Any) -> None:
    outcome = _authored_by_u1(api).as_(_user("PROFESSOR", "u1")).call(method, path, body)

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", CONTENT_ADMINS + ("MINISTRY_ADMIN",))
@pytest.mark.parametrize("method,path,body", AUTHOR_OR_ADMIN_EDITS)
def test_a_content_admin_edits_somebody_elses_form(api: _Api, method: str, path: str, body: Any, role: str) -> None:
    outcome = _authored_by_u1(api).as_(_user(role, "u2")).call(method, path, body)

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
@pytest.mark.parametrize("method,path,body", AUTHOR_OR_ADMIN_EDITS)
def test_another_questionnaire_manager_is_refused_the_edit(api: _Api, method: str, path: str, body: Any, role: str) -> None:
    outcome = _authored_by_u1(api).as_(_user(role, "u2", canManageQuestionnaire=True)).call(method, path, body)

    assert outcome.refused, outcome
    assert "author or an admin" in outcome.detail


@pytest.mark.parametrize("method,path,body", AUTHOR_OR_ADMIN_EDITS)
def test_a_form_with_no_recorded_author_is_edited_by_a_content_admin_alone(
    api: _Api, method: str, path: str, body: Any
) -> None:
    api.preload("questionnaire", _Row("questionnaire", _Form(id="f1", createdById=None, isDefault=False, isActive=True)))
    api.preload("questionnairesection", _Row("questionnairesection", _SECTION))
    api.preload("questionnairequestion", _Row("questionnairequestion", _QUESTION))
    assert api.as_(_user("PROFESSOR", "u1", canManageQuestionnaire=True)).call(method, path, body).refused
    assert api.as_(_user("ADMIN", "u2")).call(method, path, body).reached


def test_the_author_and_a_content_admin_reupload_the_workbook_and_nobody_else(api: _Api) -> None:
    _authored_by_u1(api)
    assert api.as_(_user("PROFESSOR", "u1")).call("POST", "/questionnaires/f1/upload").status_code == 422
    for role in CONTENT_ADMINS + ("MINISTRY_ADMIN",):
        assert api.as_(_user(role, "u2")).call("POST", "/questionnaires/f1/upload").status_code == 422, role
    for role in NOT_CONTENT_ADMINS:
        outcome = api.as_(_user(role, "u2", canManageQuestionnaire=True)).call("POST", "/questionnaires/f1/upload")
        assert outcome.refused, (role, outcome)
    assert api.tripwire.touched is False


class _Comments:
    def __init__(self) -> None:
        self.find_unique = _returning(SimpleNamespace(id="c1", authorId="u1"))

    async def update(self, *_args: Any, **_kwargs: Any) -> Any:
        raise _DatabaseTouched("entrycomment.update")


def test_the_author_edits_their_own_comment(api: _Api) -> None:
    outcome = api.preload("entrycomment", _Comments()).as_(_user("RESEARCHER", "u1")).call(
        "PATCH", "/data-access/comments/c1", {"body": "Corrected."}
    )

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", ["RESEARCHER", "ADMIN", "MASTER_ADMIN"])
def test_nobody_else_edits_a_comment(api: _Api, role: str) -> None:
    outcome = api.preload("entrycomment", _Comments()).as_(_user(role, "u2")).call(
        "PATCH", "/data-access/comments/c1", {"body": "Rewritten."}
    )

    assert outcome.refused, outcome


def test_an_author_no_longer_deletes_their_own_comment(api: _Api) -> None:
    outcome = api.preload("entrycomment", _Comments()).as_(_user("RESEARCHER", "u1")).call(
        "DELETE", "/data-access/comments/c1"
    )

    assert outcome.refused, outcome
