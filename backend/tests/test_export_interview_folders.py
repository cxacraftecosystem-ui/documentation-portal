"""Two sittings of one artisan set must unpack into two NAMED folders, not "… (2)".

================================================================================================
WHAT THE MIGRATION OF 2026-09-13 DID TO THE ZIP
================================================================================================

``20260913100000_questionnaire_instruments`` dropped the global ``@unique`` on
``QuestionnaireInterview.artisanSetKey`` and replaced it with
``@@unique([questionnaireId, artisanSetKey])``. The same artisan set sitting twice — once for the
2nd Craft Toolkit Workshop's instrument, once for the 3rd — stopped being a duplicate and became
two legitimate rows. That is the whole point of the migration.

``record_fields.interview_label`` was updated with it: an interview is named after the artisans it
covers, and the instrument is appended in parentheses **when the ``questionnaire`` relation is
actually loaded** (record_fields.py:167). That last clause is load-bearing and it is invisible —
``getattr(getattr(interview, "questionnaire", None), "title", None)`` answers None just as quietly
for a relation nobody asked for as for an interview with no instrument, and there is no instrument
that could be missing, because the column is NOT NULL.

``/export/dataset`` did not ask for it. So the second sitting's folder was named by ``_uniq``
instead — ``"Kanhu Charan Sahu, Sanjukta Devi (2)"``. Nothing was overwritten; ``_uniq`` was doing
exactly its job. But "(2)" is not a fact about the fieldwork. It tells a researcher unpacking a
ministry deliverable that there were two folders, not WHICH WORKSHOP each one holds, and both
answers.txt files open with the same artisan names. The two instruments do not even agree on what
their sections mean — section V is "International Exposure and Overseas Travel" in one and
"NETWORK / ECOSYSTEM MAPPING" in the other — so a reader who guesses wrong reads one workshop's
answers as the other's, in a zip that is the deliverable.

WHY THIS IS TESTED THROUGH ``dataset_manifest`` AND NOT AGAINST ``interview_label``. The label
function is already correct and already tested; the defect was entirely in what the export ASKED
THE DATABASE FOR. A test that calls ``interview_label`` with a hand-built row carrying a
``questionnaire`` attribute proves nothing at all about ``_INTERVIEW_INCLUDE`` — it passes just as
happily with the include removed. So the fake database here REFUSES TO HYDRATE A RELATION THAT WAS
NOT REQUESTED, exactly as Prisma does, which is what makes the include itself the thing under test.

WHAT IS NOT ASSERTED HERE: the scope/permission rules of ``/export/dataset``, which
``test_permission_matrix.py`` owns. Every call below is made as a user who may download everything.
"""

import asyncio
from typing import Any

import pytest

import app.api.routes.export as export

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"

W2_TITLE = "2nd Craft Toolkit Workshop"
W3_TITLE = "3rd Craft Toolkit Workshop"


class _Row:
    """A database row: EVERY COLUMN EXISTS, and the ones nobody named are NULL.

    A ``SimpleNamespace`` is the wrong shape for this test and fails loudly at the wrong place. The
    shared field registry reads ``w.place``, ``w.startDate`` and two dozen more off every record it
    describes (record_fields.py:217) because a Prisma model always has them; a namespace built from
    the handful of fields this test cares about raises ``AttributeError`` on the first one it did
    not think of, and the suite then reports a missing attribute instead of a folder name. Answering
    None for an un-named column is what a real row does.

    Private names still raise, so pytest's own introspection and ``copy``/``pickle`` probing behave
    normally rather than being handed a None that means nothing.
    """

    def __init__(self, **fields: Any) -> None:
        self.__dict__.update(fields)

    def __getattr__(self, name: str) -> Any:
        if name.startswith("_"):
            raise AttributeError(name)
        return None


class _Delegate:
    """One Prisma delegate over canned rows, which HIDES ANY RELATION THE CALLER DID NOT INCLUDE.

    That hiding is the point of the whole file. Prisma returns a model whose un-included relation
    attribute is None, and ``interview_label`` reads exactly that attribute through ``getattr``. A
    fake that handed back a fully-populated object regardless would make this suite pass with
    ``_INTERVIEW_INCLUDE`` reverted, which is the one outcome it must not have.
    """

    #: Relation attributes this delegate will strip unless the query includes them by name.
    RELATIONS: tuple[str, ...] = ()

    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_many(self, where=None, include=None, take=None, order=None, **_kwargs):
        included = set(include or {})
        out = []
        for row in self.rows:
            fields = dict(vars(row))
            for relation in self.RELATIONS:
                if relation not in included:
                    fields[relation] = None
            out.append(_Row(**fields))
        return out


class _InterviewDelegate(_Delegate):
    RELATIONS = ("questionnaire", "artisans", "responses")


class _Db:
    def __init__(self, *, workshops, artisans, interviews) -> None:
        self.workshop = _Delegate(workshops)
        self.artisan = _Delegate(artisans)
        self.productdocumentation = _Delegate([])
        self.tooldocumentation = _Delegate([])
        self.questionnaireinterview = _InterviewDelegate(interviews)
        self.process = _Delegate([])
        self.mediafile = _Delegate([])


ARTISAN = _Row(
    id="artisan-1",
    name="Kanhu Charan Sahu",
    craftId=None,
    craft=None,
    workshopId="ws-1",
    workshops=[],
    location=None,
    extraMetadata=None,
    status="APPROVED",
    aadhaarNumber=None,
    phone=None,
    gender=None,
    age=None,
    address=None,
    notes=None,
    createdAt=None,
    updatedAt=None,
    createdById="user-1",
    createdBy=None,
)

WORKSHOP = _Row(
    id="ws-1",
    title="Bhubaneswar",
    crafts=[],
    artisans=[],
    extraMetadata=None,
    status="APPROVED",
    notes=None,
    location=None,
    createdAt=None,
    updatedAt=None,
    createdById="user-1",
    createdBy=None,
)


def _interview(iv_id: str, questionnaire_id: str, title: str) -> "_Row":
    """One sitting, with its instrument relation POPULATED — the fake strips it when not included."""
    return _Row(
        id=iv_id,
        title="Questionnaire",
        questionnaireId=questionnaire_id,
        questionnaire=_Row(id=questionnaire_id, title=title),
        artisanSetKey="artisan-1",
        artisans=[_Row(artisanId="artisan-1", artisan=ARTISAN)],
        responses=[],
        notes=None,
        place=None,
        language=None,
        status="APPROVED",
        interviewDate=None,
        recordedAt=None,
        recordedTimezone="Asia/Kolkata",
        extraMetadata=None,
        workshopId="ws-1",
        workshop=None,
        location=None,
        createdAt=None,
        updatedAt=None,
        createdById="user-1",
        createdBy=None,
    )


@pytest.fixture
def manifest(monkeypatch: pytest.MonkeyPatch):
    """Run ``/export/dataset`` over a fake repository and hand back the list of file paths."""

    async def _no_where(*_args, **_kwargs):
        return {}

    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)
    monkeypatch.setattr(export, "owned_or_granted_where", _no_where)

    def run(interviews: list[Any]) -> list[str]:
        monkeypatch.setattr(
            export,
            "db",
            _Db(workshops=[WORKSHOP], artisans=[ARTISAN], interviews=interviews),
        )
        payload = asyncio.run(
            export.dataset_manifest(current_user=_Row(id="user-1", role="ADMIN"))
        )
        return [entry["path"] for entry in payload["files"]]

    return run


def _questionnaire_folders(paths: list[str]) -> set[str]:
    """The folder each ``Questionnaires/<folder>/answers.txt`` was written into."""
    return {
        path.split("/Questionnaires/", 1)[1].rsplit("/", 1)[0]
        for path in paths
        if "/Questionnaires/" in path
    }


def test_two_sittings_of_one_artisan_set_get_two_folders_named_by_instrument(manifest):
    """THE DEFECT. Both folders exist either way — ``_uniq`` guarantees that — so the assertion
    that matters is that each one NAMES ITS WORKSHOP. A researcher unpacking the deliverable has to
    be able to tell which sitting they are reading without opening both and comparing dates that
    may not be filled in."""
    folders = _questionnaire_folders(
        manifest([_interview("iv-w2", W2, W2_TITLE), _interview("iv-w3", W3, W3_TITLE)])
    )
    assert folders == {
        f"Kanhu Charan Sahu ({W2_TITLE})",
        f"Kanhu Charan Sahu ({W3_TITLE})",
    }


def test_no_interview_folder_is_disambiguated_by_a_bare_numeral(manifest):
    """THE REGRESSION SHAPE, STATED DIRECTLY. Reverting ``_INTERVIEW_INCLUDE`` leaves both folders
    in the zip and both answers.txt intact — ``_uniq`` never loses a file — so a test that only
    counted paths would go on passing. What breaks is the NAME: the second sitting becomes
    "Kanhu Charan Sahu (2)", which says there were two of something and nothing about which."""
    folders = _questionnaire_folders(
        manifest([_interview("iv-w2", W2, W2_TITLE), _interview("iv-w3", W3, W3_TITLE)])
    )
    assert not any(folder.endswith("(2)") for folder in folders), folders


def test_each_sittings_answers_land_in_its_own_instrument_folder(manifest):
    """Two answers.txt, one per folder, and no path written twice. The zip is built client-side by
    writing every manifest entry in order, so two entries at one path is the second silently
    replacing the first — which for a questionnaire export means one workshop's answers being
    served as the other's."""
    paths = manifest([_interview("iv-w2", W2, W2_TITLE), _interview("iv-w3", W3, W3_TITLE)])
    answers = [p for p in paths if p.endswith("/answers.txt")]
    assert len(answers) == 2
    assert len(set(answers)) == 2


def test_a_single_instrument_repository_still_reads_exactly_as_it_always_did(manifest):
    """THE COMPATIBILITY HALF. Every repository that existed before 2026-09-13 has one instrument,
    and the suffix must not turn every folder in those zips into a rename. The parenthetical is
    added by ``interview_label`` whenever the instrument is known — which it now always is — so this
    pins what a one-sitting export looks like rather than pretending nothing changed: ONE folder,
    named for the artisan, carrying the instrument it was taken on."""
    folders = _questionnaire_folders(manifest([_interview("iv-w2", W2, W2_TITLE)]))
    assert folders == {f"Kanhu Charan Sahu ({W2_TITLE})"}


def test_the_export_asks_for_the_instrument_relation_it_reads(manifest):
    """The include, asserted as a fact rather than only through its effect. ``interview_label``
    reads ``interview.questionnaire.title`` through ``getattr`` and answers None just as quietly for
    a relation nobody requested as for a genuinely absent one — so the request itself is worth
    pinning, in the file that explains why."""
    assert export._INTERVIEW_INCLUDE.get("questionnaire") is True
