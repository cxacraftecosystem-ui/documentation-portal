"""The shared questionnaire package's pure rules, the same in both portals (UNIFY_2026-10-21.md U0).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (shared/questionnaire.sha256). No
database: the author rule, the answer lock, the subject key, the form resolution order, the kinds and
section states, the guest respondent's fields, and the shared vocabularies against their JSON.
"""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.questionnaire import answer_lock, authoring, bindings, kinds, subjects

SHARED = Path(__file__).resolve().parents[2] / "shared"


def user(uid: str, role: str = "RESEARCHER", name: str = "Nandini") -> SimpleNamespace:
    return SimpleNamespace(id=uid, role=role, name=name)


# ── the author rule (owner, 22 Oct 2026) ──────────────────────────────────────────────────────
@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"])
def test_a_content_admin_changes_any_form_and_restores(role: str) -> None:
    form = {"ownerId": "someone"}
    assert authoring.may_change_form(user("a", role), form)
    assert authoring.may_restore_form(user("a", role))
    assert authoring.may_delete_session(user("a", role)) and authoring.may_delete_recording(user("a", role))
    assert authoring.may_admin_act(user("a", role))


@pytest.mark.parametrize("role", ["RESEARCHER", "PROFESSOR", "DESIGNER", "VOLUNTEER", "REGIONAL_DIRECTOR"])
def test_the_author_and_only_the_author_changes_their_form(role: str) -> None:
    mine = {"ownerId": "me"}
    fr_form = {"createdById": "me"}
    theirs = SimpleNamespace(ownerId="other", createdById=None)
    assert authoring.may_change_form(user("me", role), mine)
    assert authoring.may_change_form(user("me", role), fr_form), "FR's createdById is the author"
    assert not authoring.may_change_form(user("me", role), theirs)
    assert not authoring.may_change_form(user("me", role), {"ownerId": None}), "no recorded author: content admins only"
    assert not authoring.may_restore_form(user("me", role))
    assert not authoring.may_delete_session(user("me", role)), "sessions: content admins only, the author included"
    assert not authoring.may_delete_recording(user("me", role))
    assert not authoring.may_admin_act(user("me", role)), "sharing, defaults and bindings stay admin acts"


def test_the_role_may_be_an_enum() -> None:
    role = SimpleNamespace(value="MINISTRY_ADMIN")
    assert authoring.is_content_admin(user("x", role))  # type: ignore[arg-type]


def test_ministry_admin_authoring_is_withheld_on_designer_portal_android_only() -> None:
    for act in ("share", "default", "type-default", "bind", "edit-others"):
        assert not authoring.offered_on(authoring.PORTAL_DR, "android", act)
        assert authoring.offered_on(authoring.PORTAL_DR, "web", act)
        assert authoring.offered_on(authoring.PORTAL_FR, "android", act), "Field Repository serves it on Android"
    assert authoring.offered_on(authoring.PORTAL_DR, "android", "delete")


def test_the_content_admins_are_the_portals_own() -> None:
    try:
        from app.core.roles import CONTENT_ADMIN_ROLES, CONTENT_DELETE_REFUSAL
    except ImportError:
        from app.core.deps import CONTENT_ADMIN_ROLES, CONTENT_DELETE_REFUSAL
    assert set(CONTENT_ADMIN_ROLES) == set(authoring.CONTENT_ADMIN_ROLES)
    assert CONTENT_DELETE_REFUSAL == authoring.CONTENT_DELETE_REFUSAL


# ── the one answer-lock rule (owner decision 2) ───────────────────────────────────────────────
def test_an_answer_with_text_is_locked_to_others() -> None:
    current = {"text": "Wool from Bikaner", "actorId": "author"}
    assert answer_lock.answer_locked(current, user("stranger"))
    assert not answer_lock.answer_locked(current, user("author")), "its author"
    assert not answer_lock.answer_locked(current, user("lead"), session_leads={"lead"}), "a session lead"
    assert not answer_lock.answer_locked(current, user("x", "ADMIN")), "an admin"
    assert not answer_lock.answer_locked(current, user("x", "MINISTRY_ADMIN"))
    assert answer_lock.answer_locked(current, user("x", "PROFESSOR")), "rank alone is not authorship"


def test_an_empty_answer_is_never_locked() -> None:
    assert not answer_lock.answer_locked({"text": "   ", "actorId": "author"}, user("stranger"))
    assert not answer_lock.answer_locked(None, user("stranger"))


def test_the_lock_is_off_until_switched_and_says_who() -> None:
    assert not answer_lock.locks_enabled(SimpleNamespace())
    assert answer_lock.locks_enabled(SimpleNamespace(questionnaire_answer_locks=True))
    assert answer_lock.lock_message("Nandini") == "Answered by Nandini. Only they or a lead can change it."
    from app.core.config import Settings

    assert Settings.model_fields["questionnaire_answer_locks"].default is False


# ── the subject key ───────────────────────────────────────────────────────────────────────────
def test_the_subject_key_is_exactly_one_owner() -> None:
    assert subjects.subject_key(artisan_id="a1") == "A:a1"
    assert subjects.subject_key(respondent_id="r1") == "R:r1"
    for bad in ({}, {"artisan_id": "a", "respondent_id": "r"}):
        with pytest.raises(ValueError):
            subjects.subject_key(**bad)
    assert subjects.parse_subject_key("R:r1") == ("R", "r1")
    assert subjects.subject_of({"artisanId": None, "respondentId": "r9"}) == "R:r9"
    assert subjects.artisan_ids(["A:1", "R:2", "A:3"]) == ["1", "3"]
    assert subjects.respondent_ids(["A:1", "R:2"]) == ["2"]
    for bad in ("", "X:1", "A:", "a1"):
        assert not subjects.is_subject_key(bad)


# ── the form a session starts on (owner, amendment 2 point 2) ─────────────────────────────────
B = bindings.Binding


def test_the_resolution_order() -> None:
    both = [B("f1", False, "WORKSHOP_INTERVIEW"), B("f2", True, "MARKET_SURVEY")]
    assert bindings.resolve(explicit="fx", bindings=both, type_default="t", global_default="g").reason == bindings.EXPLICIT
    r = bindings.resolve(bindings=both, type_default="t", global_default="g")
    assert (r.questionnaireId, r.reason) == ("f2", bindings.PRIMARY_BINDING)
    r = bindings.resolve(bindings=[B("f1")], type_default="t", global_default="g")
    assert (r.questionnaireId, r.reason) == ("f1", bindings.ONLY_BINDING)
    r = bindings.resolve(bindings=[], type_default="t", global_default="g")
    assert (r.questionnaireId, r.reason) == ("t", bindings.TYPE_DEFAULT)
    r = bindings.resolve(global_default="g")
    assert (r.questionnaireId, r.reason) == ("g", bindings.GLOBAL_DEFAULT)
    assert bindings.resolve().reason == bindings.PICKER


def test_several_bindings_and_no_primary_show_the_picker_grouped_by_kind() -> None:
    r = bindings.resolve(bindings=[B("f1", False, "MARKET_SURVEY"), B("f2", False, "WORKSHOP_INTERVIEW"), B("f3", False, "MARKET_SURVEY")],
                         type_default="t", global_default="g")
    assert r.questionnaireId is None and r.reason == bindings.PICKER
    groups = {g["kind"]: g["questionnaireIds"] for g in r.out()["boundByKind"]}
    assert groups == {"MARKET_SURVEY": ["f1", "f3"], "WORKSHOP_INTERVIEW": ["f2"]}


def test_a_form_that_may_not_be_offered_is_skipped() -> None:
    r = bindings.resolve(explicit="gone", bindings=[B("deleted", True)], type_default="t", global_default="g", live={"t", "g"})
    assert (r.questionnaireId, r.reason) == ("t", bindings.TYPE_DEFAULT)


# ── kinds and the section override's three states (amendment 2 point 1) ───────────────────────
def test_kinds_match_the_shared_json() -> None:
    shared = json.loads((SHARED / "questionnaire-kinds.json").read_text(encoding="utf-8"))
    assert {k: v["label"] for k, v in shared["kinds"].items()} == kinds.KIND_LABELS
    for portal in ("designer-portal", "documentation-portal"):
        assert {k: v["stage"][portal] for k, v in shared["kinds"].items()} == kinds.STAGE_KEYS[portal]
    assert shared["notStatedLabel"] == kinds.NOT_STATED_LABEL
    assert kinds.coerce_kind(" market_survey ") == "MARKET_SURVEY" and kinds.coerce_kind("") is None
    with pytest.raises(ValueError):
        kinds.coerce_kind("OTHER")
    assert kinds.label_for(None) == "Kind not stated" and kinds.label_for("NEWER") == "NEWER"


def test_the_section_override_keeps_three_states() -> None:
    assert kinds.SECTION_STATUSES == ("COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO")
    assert kinds.SECTION_STATUS_LABELS["NEEDS_REVIEW"] == "Needs review"
    assert kinds.coerce_section_status("needs_redo") == "NEEDS_REDO"
    with pytest.raises(ValueError):
        kinds.coerce_section_status("DONE")


def test_the_unify_vocabularies_match_the_shared_json() -> None:
    from app.questionnaire import common, coverage, schemas

    shared = json.loads((SHARED / "questionnaire-enums.json").read_text(encoding="utf-8"))
    enums = shared["enums"]
    assert enums["QuestionnaireKind"] == list(kinds.KIND_LABELS)
    assert enums["SectionStatus"] == list(kinds.SECTION_STATUSES)
    assert enums["RespondentKind"] == list(schemas.RESPONDENT_KINDS)
    assert enums["SessionSource"] == list(schemas.SESSION_SOURCES)
    assert enums["CoverageSource"] == [coverage.SOURCE_RECORDED, coverage.SOURCE_TYPED]
    assert enums["SubjectKeyPrefix"] == list(subjects.PREFIXES)
    assert enums["FormResolution"] == list(bindings.ORDER)
    assert enums["WorkshopType"] == list(bindings.WORKSHOP_TYPES)
    assert set(shared["pending"]["errorCodes"]["add"]) == set(common.UNIFY_MESSAGES) == set(common.UNIFY_STATUS)
    assert not set(common.UNIFY_MESSAGES) & set(common.MESSAGES), "a pending code is already in the frozen list"
    assert shared["pending"]["AnswerPutStatus"]["add"] == [answer_lock.STATUS]


# ── guest respondents (owner decision 1) ──────────────────────────────────────────────────────
def test_a_guest_needs_a_name_and_self_is_the_caller() -> None:
    from fastapi import HTTPException

    from app.questionnaire import respondents

    me = user("u1", name="Asha Rao")
    assert respondents.clean("  Ravi   Kumar ", "guest", None, me) == {"name": "Ravi Kumar", "kind": "GUEST", "notes": None, "userId": None}
    assert respondents.clean(None, "SELF", " owns a shop ", me) == {"name": "Asha Rao", "kind": "SELF", "notes": "owns a shop", "userId": "u1"}
    for name, kind in (("", "GUEST"), ("x" * 121, "GUEST"), ("Ravi", "FRIEND")):
        with pytest.raises(HTTPException) as refused:
            respondents.clean(name, kind, None, me)
        assert refused.value.status_code == 422


def test_an_answer_belongs_to_exactly_one_owner() -> None:
    from pydantic import ValidationError

    from app.questionnaire.schemas import AnswerItem, SessionCreate

    base = {"questionLineageId": "q1", "text": "t", "sessionId": "s1"}
    assert AnswerItem.model_validate({**base, "respondentId": "r1", "note": "said twice"}).respondentId == "r1"
    for bad in ({}, {"artisanId": "a1", "respondentId": "r1"}):
        with pytest.raises(ValidationError):
            AnswerItem.model_validate({**base, **bad})
    session = {"clientKey": "k", "questionnaireId": "q", "sessionDate": "2026-10-22"}
    assert SessionCreate.model_validate({**session, "respondents": [{"name": "Ravi"}]}).participants == []
    with pytest.raises(ValidationError):
        SessionCreate.model_validate(session)
