import asyncio
from typing import Any

import pytest

import app.api.routes.export as export

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"

W2_TITLE = "2nd Craft Toolkit Workshop"
W3_TITLE = "3rd Craft Toolkit Workshop"


class _Row:
    def __init__(self, **fields: Any) -> None:
        self.__dict__.update(fields)

    def __getattr__(self, name: str) -> Any:
        if name.startswith("_"):
            raise AttributeError(name)
        return None


class _Delegate:
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
    return {
        path.split("/Questionnaires/", 1)[1].rsplit("/", 1)[0]
        for path in paths
        if "/Questionnaires/" in path
    }


def test_two_sittings_of_one_artisan_set_get_two_folders_named_by_instrument(manifest):
    folders = _questionnaire_folders(
        manifest([_interview("iv-w2", W2, W2_TITLE), _interview("iv-w3", W3, W3_TITLE)])
    )
    assert folders == {
        f"Kanhu Charan Sahu ({W2_TITLE})",
        f"Kanhu Charan Sahu ({W3_TITLE})",
    }


def test_no_interview_folder_is_disambiguated_by_a_bare_numeral(manifest):
    folders = _questionnaire_folders(
        manifest([_interview("iv-w2", W2, W2_TITLE), _interview("iv-w3", W3, W3_TITLE)])
    )
    assert not any(folder.endswith("(2)") for folder in folders), folders


def test_each_sittings_answers_land_in_its_own_instrument_folder(manifest):
    paths = manifest([_interview("iv-w2", W2, W2_TITLE), _interview("iv-w3", W3, W3_TITLE)])
    answers = [p for p in paths if p.endswith("/answers.txt")]
    assert len(answers) == 2
    assert len(set(answers)) == 2


def test_a_single_instrument_repository_still_reads_exactly_as_it_always_did(manifest):
    folders = _questionnaire_folders(manifest([_interview("iv-w2", W2, W2_TITLE)]))
    assert folders == {f"Kanhu Charan Sahu ({W2_TITLE})"}


def test_the_export_asks_for_the_instrument_relation_it_reads(manifest):
    assert export._INTERVIEW_INCLUDE.get("questionnaire") is True
