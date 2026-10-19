"""Download names of media files (NAMING 2.1), rebuilt at read time from the row.

Two files of one folder must never answer to the same name, and each must answer to the SAME name
on every request. Re-recording an answer several times inside one minute is ordinary practice, so
the numbering that separates them (``_2``, ``_3``) is not a nicety, and the stability half of it is
tested the way it actually breaks: the same folder is named twice with the rows fed in a DIFFERENT
order, because the order rows come back from Postgres is exactly what varies between two requests.

This file is identical in designer-portal and documentation-portal.
"""

from datetime import UTC, datetime, timedelta

import pytest

from app.services import naming
from app.services.media_naming import (
    clip,
    display_filename,
    folder_order,
    transcript_filename,
    unique_display_filename,
    unique_display_stem,
    unique_name,
)
from app.services.naming_portal import PORTAL


class Row:
    """The handful of columns a display name is read from, as a media row exposes them."""

    def __init__(self, **fields):
        self.id = fields.pop("id", "m1")
        self.originalFilename = fields.pop("originalFilename", "AUD_1_190620261703.m4a")
        self.createdAt = fields.pop("createdAt", datetime(2026, 6, 19, 11, 33, tzinfo=UTC))
        self.recordedAt = fields.pop("recordedAt", None)
        self.recordedTimezone = fields.pop("recordedTimezone", None)
        self.mediaType = fields.pop("mediaType", "AUDIO")
        self.caption = fields.pop("caption", None)
        self.linkedRecordType = fields.pop("linkedRecordType", None)
        self.questionnaireInterviewId = fields.pop("questionnaireInterviewId", None)
        self.extraMetadata = fields.pop("extraMetadata", None)
        for attr in (
            "artisan",
            "craft",
            "workshop",
            "product",
            "tool",
            "questionnaireInterview",
            "location",
        ):
            setattr(self, attr, fields.pop(attr, None))
        assert not fields, f"unknown row fields: {sorted(fields)}"


class Obj:
    def __init__(self, **kw):
        self.__dict__.update(kw)


def take(n, *, minute=33, second=0, **overrides):
    """One of several recordings of the same answer, distinguishable only by when it was saved."""
    return Row(
        id=f"take{n}",
        originalFilename=f"K_1_ANSWER_00104{n}_1906202611{minute:02d}{second:02d}.m4a",
        createdAt=datetime(2026, 6, 19, 11, minute, second, n, tzinfo=UTC),
        caption="Question audio: K1 What types of waste does the craft produce?",
        linkedRecordType="questionnaire",
        questionnaireInterviewId="iv1",
        **overrides,
    )


def name_folder(rows, **kwargs):
    """One folder's files named the way ``_media_entries`` names them: ordered, then numbered."""
    used: set[str] = set()
    return {
        row.id: unique_display_filename(row, used, position=position, fallback=row.id, **kwargs)
        for position, row in enumerate(folder_order(rows), start=1)
    }


# --------------------------------------------------------------------------------------------
# The grammar
# --------------------------------------------------------------------------------------------


def test_an_old_capture_code_reads_as_the_convention():
    name = display_filename(take(1), record_type="Interview", record_name="Om Prakash")
    assert name == f"{PORTAL}_Interview_2026-06-19_1133_Om-Prakash_Recording_K-Q01.m4a"


def test_a_section_recording_claims_no_question():
    row = Row(
        originalFilename="D_SEC_GIRIRAJ_001046_010720261824.wav",
        caption="Section audio: D RAW MATERIALS",
        linkedRecordType="questionnaire",
        questionnaireInterviewId="iv1",
    )
    name = display_filename(row, record_type="Interview", record_name="Giriraj")
    assert name == f"{PORTAL}_Interview_2026-07-01_1824_Giriraj_Recording_D-All.wav"


def test_a_group_interview_is_named_for_its_head_count():
    interview = Obj(
        title="t",
        artisans=[Obj(artisan=Obj(name=n)) for n in ("A", "B", "C")],
        location=None,
        place=None,
    )
    row = take(1)
    row.questionnaireInterview = interview
    assert display_filename(row) == (
        f"{PORTAL}_Group-Interview_2026-06-19_1133_3-Artisans_Recording_K-Q01.m4a"
    )


def test_place_and_craft_come_from_loaded_relations():
    row = Row(
        originalFilename="photo.jpg",
        mediaType="IMAGE",
        recordedAt=datetime(2026, 6, 22, 5, 13, tzinfo=UTC),
        product=Obj(
            productName="Indigo Dabu Saree",
            location=Obj(village="Bagru", district="Jaipur", state="Rajasthan"),
            craft=Obj(name="Hand block printing"),
        ),
    )
    assert display_filename(row, position=3) == (
        f"{PORTAL}_Product_2026-06-22_1043_Bagru-Jaipur_Hand-Block-Printing_Indigo-Dabu-Saree"
        "_Photo_03.jpg"
    )


def test_an_upload_time_never_states_a_capture_time():
    """``createdAt`` is when the row was written, so it gives the day and never a time."""
    row = Row(originalFilename="scan.pdf", mediaType="PDF")
    name = display_filename(row, record_type="Artisan", record_name="Om")
    assert name == f"{PORTAL}_Artisan_2026-06-19_Om_Document_01.pdf"


def test_a_name_already_in_the_convention_is_kept():
    minted = f"{PORTAL}_Tool_2026-06-19_1017_Tray-Attachment_Step-3-Photo_01.jpg"
    row = Row(originalFilename=minted, mediaType="IMAGE", linkedRecordType="tool")
    assert display_filename(row, record_type="Tool", record_name="anything") == minted
    assert display_filename(row, suffix=2).endswith("_Step-3-Photo_01_2.jpg")


def test_a_transcript_is_the_clip_with_what_transcript():
    name = transcript_filename(take(1), record_type="Interview", record_name="Om")
    assert name == f"{PORTAL}_Interview_2026-06-19_1133_Om_Transcript_K-Q01.md"


def test_every_name_is_an_ascii_s3_safe_leaf():
    row = Row(originalFilename="गिरीराज.m4a", linkedRecordType="artisan")
    for name in (
        display_filename(row, record_type="Artisan", record_name="गिरीराज प्रसाद छीपा"),
        display_filename(Row(originalFilename="", mediaType="OTHER")),
    ):
        assert name.isascii() and len(name) <= naming.MAX_FILE_NAME, name
        assert " " not in name and "·" not in name


# --------------------------------------------------------------------------------------------
# The suffix itself
# --------------------------------------------------------------------------------------------


def test_first_of_a_colliding_group_is_not_numbered():
    names = name_folder([take(1), take(2), take(3)], record_type="Artisan", record_name="Om")
    ordered = [names[f"take{n}"] for n in (1, 2, 3)]
    base = f"{PORTAL}_Artisan_2026-06-19_1133_Om_Recording_K-Q01"
    assert ordered == [f"{base}.m4a", f"{base}_2.m4a", f"{base}_3.m4a"]


def test_numbering_closes_every_collision_and_ignores_case():
    names = name_folder([take(n) for n in range(1, 7)], record_type="Artisan", record_name="Om")
    assert len({n.lower() for n in names.values()}) == 6
    used: set[str] = set()
    first = unique_name("A_Rekha_Photo_01", ".jpg", used)
    second = unique_name("a_rekha_photo_01", ".jpg", used)
    assert first != second and second.endswith("_2.jpg")


def test_the_same_folder_names_the_same_way_whatever_order_the_rows_arrive_in():
    rows = [take(n) for n in range(1, 7)]
    forward = name_folder(rows, record_type="Artisan", record_name="Om Prakash")
    backward = name_folder(list(reversed(rows)), record_type="Artisan", record_name="Om Prakash")
    assert forward == backward


def test_rows_tied_to_the_microsecond_are_still_ordered():
    stamp = datetime(2026, 6, 19, 11, 33, 6, 176000, tzinfo=UTC)
    a = take(1, second=6)
    b = take(2, second=6)
    a.createdAt = b.createdAt = stamp
    a.id, b.id = "zzz", "aaa"
    forward = name_folder([a, b], record_type="Artisan", record_name="Om")
    backward = name_folder([b, a], record_type="Artisan", record_name="Om")
    assert forward == backward
    assert not forward["aaa"].endswith("_2.m4a")
    assert forward["zzz"].endswith("_2.m4a")


def test_naive_or_missing_createdat_does_not_crash_the_ordering():
    a, b = take(1), take(2)
    b.createdAt = b.createdAt.replace(tzinfo=None)
    assert len(name_folder([a, b], record_type="Artisan", record_name="Om")) == 2
    a.createdAt = None
    assert len(name_folder([a, b], record_type="Artisan", record_name="Om")) == 2


def test_a_transcript_is_numbered_against_its_own_folder():
    used: set[str] = set()
    names = [
        unique_display_stem(
            row, used, extension=".transcript.md", record_type="Artisan", record_name="Om"
        )
        for row in folder_order([take(1), take(2)])
    ]
    assert names[0].endswith("_Transcript_K-Q01.md")
    assert names[1].endswith("_Transcript_K-Q01_2.md")


def test_a_long_name_keeps_its_tail_and_its_number():
    long = "गिरीराज प्रसाद छीपा " * 12
    names = name_folder([take(n) for n in range(1, 4)], record_type="Artisan", record_name=long)
    for name in names.values():
        assert len(name) <= naming.MAX_FILE_NAME
        assert "_Recording_K-Q01" in name
    assert names["take2"].endswith("_2.m4a") and names["take3"].endswith("_3.m4a")


@pytest.mark.parametrize("count", [2, 3, 10, 26])
def test_a_group_of_any_size_is_fully_separated(count):
    names = name_folder(
        [take(n) for n in range(1, count + 1)], record_type="Artisan", record_name="Om"
    )
    assert len({n.lower() for n in names.values()}) == count


def test_a_row_with_nothing_nameable_still_gets_a_numbered_name():
    rows = [
        Row(id="x", originalFilename="", createdAt=datetime(2026, 6, 1, tzinfo=UTC)),
        Row(id="y", originalFilename="", createdAt=datetime(2026, 6, 2, tzinfo=UTC)),
    ]
    names = name_folder(rows)
    assert all(names.values())
    assert len(set(names.values())) == 2


def test_numbering_survives_a_gap_left_by_a_name_already_taken():
    used: set[str] = set()
    assert unique_name("Clip_2", ".m4a", used) == "Clip_2.m4a"
    assert unique_name("Clip", ".m4a", used) == "Clip.m4a"
    assert unique_name("Clip", ".m4a", used) == "Clip_3.m4a"


def test_recorded_at_is_read_in_ist_to_the_minute():
    row = Row(
        originalFilename="photo.jpg",
        mediaType="IMAGE",
        recordedAt=datetime(2026, 6, 19, 6, 3, 44, tzinfo=UTC),
        recordedTimezone="Mars/Olympus",
    )
    name = display_filename(row, record_type="Artisan", record_name="Om")
    assert "_2026-06-19_1133_" in name, name


def test_clip_stops_at_the_empty_string_and_not_at_the_budget():
    assert clip("abc", 10, 0) == ""
    assert clip("abc", 10, -5) == ""
    assert clip("abc", 10, 100) == "abc"


def test_a_dot_in_a_name_is_not_mistaken_for_an_extension():
    row = Row(originalFilename="Interview 2026.06 " + "गिरीराज प्रसाद छीपा साक्षात्कार " * 3)
    name = display_filename(row, record_type="Artisan", record_name="Giriraj")
    assert name == f"{PORTAL}_Artisan_2026-06-19_Giriraj_Recording_01"


def test_a_real_extension_is_kept_and_lower_cased():
    for filename, ext in (("clip.m4a", ".m4a"), ("photo.jpeg", ".jpeg"), ("scan.PDF", ".pdf")):
        row = Row(originalFilename=filename, mediaType="IMAGE")
        assert display_filename(row, record_type="Artisan", record_name="Om").endswith(ext)


def test_folder_order_is_by_created_at_then_id():
    early = take(1)
    late = take(2)
    late.createdAt = early.createdAt + timedelta(seconds=30)
    assert [r.id for r in folder_order([late, early])] == ["take1", "take2"]


def test_a_plain_dict_row_is_named_like_a_model_row():
    """``datasets`` signs rows that may arrive as dicts; they must not lose their name."""
    row = {
        "id": "d1",
        "originalFilename": "K_1_ANSWER_001041_190620261133.m4a",
        "mediaType": "AUDIO",
        "caption": "Question audio: K1 What types of waste does the craft produce?",
        "linkedRecordType": "questionnaire",
        "questionnaireInterviewId": "iv1",
        "createdAt": datetime(2026, 6, 19, 11, 33, tzinfo=UTC),
    }
    assert display_filename(row, record_type="Interview", record_name="Om") == (
        f"{PORTAL}_Interview_2026-06-19_1133_Om_Recording_K-Q01.m4a"
    )
