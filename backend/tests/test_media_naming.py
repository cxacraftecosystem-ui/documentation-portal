"""Two files of one folder must never answer to the same name, and each must answer to the SAME
name on every request.

The display scheme stamps a file to the minute, and re-recording an artisan's answer four times
inside one minute is ordinary practice in this repository — 231 of the 925 live files share a
twelve-digit name with a sibling. So the numbering that separates them is not a nicety, and neither
half of it is: if it were dropped, a zip would hold six files called
``…-Interview-Section-E-Answer-15-190620261703.m4a`` and five of them would be gone on extraction;
if it were unstable, a researcher who downloaded the same folder twice would get two names for one
recording and every note they had written against a filename would point at nothing.

The stability half is the one that cannot be caught by eye, so it is tested the way it actually
breaks: the same folder is named twice with the rows fed in a DIFFERENT order, because the order
rows come back from Postgres is exactly what varies between two requests.
"""

from datetime import datetime, timedelta, timezone

import pytest

from app.services.media_naming import (
    MAX_NAME_BYTES,
    NameParts,
    _assemble,
    clip,
    display_filename,
    folder_order,
    unique_display_filename,
    unique_display_stem,
    unique_name,
)

UTC = timezone.utc


class Row:
    """The handful of columns a display name is read from, as a media row exposes them.

    ``media_naming`` reads a Prisma model purely by ``getattr``, so a plain object with the right
    attribute names is the same thing to it as the real row — and lets a test state the one column
    it is about instead of constructing a database.
    """

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
        for attr in ("artisan", "craft", "workshop", "product", "tool", "questionnaireInterview"):
            setattr(self, attr, fields.pop(attr, None))
        assert not fields, f"unknown row fields: {sorted(fields)}"


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
# The suffix itself
# --------------------------------------------------------------------------------------------


def test_first_of_a_colliding_group_is_not_numbered():
    """The common case must not pay for the rare one.

    754 of the 925 live files have no sibling to be confused with, and a scheme that numbered every
    file "-1" would put an apology on all of them to solve a problem 171 of them have.
    """
    names = name_folder([take(1), take(2), take(3)], record_type="Artisan", record_name="Om Prakash")
    ordered = [names[f"take{n}"] for n in (1, 2, 3)]

    assert ordered[0] == "Artisan-Om-Prakash-Interview-Section-K-Answer-1-190620261133.m4a"
    assert ordered[1] == "Artisan-Om-Prakash-Interview-Section-K-Answer-1-190620261133-2.m4a"
    assert ordered[2] == "Artisan-Om-Prakash-Interview-Section-K-Answer-1-190620261133-3.m4a"


def test_a_file_with_no_sibling_is_never_numbered():
    only = name_folder([take(1)], record_type="Artisan", record_name="Om Prakash")
    assert only["take1"].endswith("-190620261133.m4a")


def test_suffix_goes_before_the_extension():
    """``recording.m4a-2`` is not an m4a to the operating system that has to open it."""
    names = name_folder([take(1), take(2)], record_type="Artisan", record_name="Om Prakash")
    for name in names.values():
        assert name.endswith(".m4a")
    assert "-2.m4a" in names["take2"]
    assert ".m4a-2" not in names["take2"]


def test_numbering_closes_every_collision():
    names = name_folder([take(n) for n in range(1, 7)], record_type="Artisan", record_name="Om")
    assert len({n.lower() for n in names.values()}) == 6


def test_numbering_is_case_insensitive():
    """Extracting a zip on Windows is case-insensitive: two names differing only in case are one
    file there, and the second would silently overwrite the first."""
    used: set[str] = set()
    first = unique_name("Artisan-Rekha-Photo-1-190620261133", ".jpg", used)
    second = unique_name("artisan-rekha-photo-1-190620261133", ".jpg", used)
    assert first != second
    assert second.endswith("-2.jpg")


# --------------------------------------------------------------------------------------------
# Stability — the half that cannot be caught by eye
# --------------------------------------------------------------------------------------------


def test_the_same_folder_names_the_same_way_whatever_order_the_rows_arrive_in():
    """The bug this exists to prevent: two downloads of one folder disagreeing about which take is
    the plain name and which is "-2".

    Postgres is free to hand back rows tied on ``createdAt`` in whatever order it finds them, so the
    input order is fed in reversed on the second pass. Anything that leaned on arrival order — or on
    a set's or dict's iteration order — renames the group here.
    """
    rows = [take(n) for n in range(1, 7)]
    forward = name_folder(rows, record_type="Artisan", record_name="Om Prakash")
    backward = name_folder(list(reversed(rows)), record_type="Artisan", record_name="Om Prakash")
    assert forward == backward


def test_rows_tied_to_the_microsecond_are_still_ordered():
    """A batch saved in one transaction shares ``createdAt`` exactly; ``id`` is what breaks the tie.

    Without the id the two would be ordered by whatever ``sorted`` was handed, and the pair would
    swap names between requests — the same defect as above, but reachable only through real batch
    uploads, which is how it would have escaped notice.
    """
    stamp = datetime(2026, 6, 19, 11, 33, 6, 176000, tzinfo=UTC)
    a = take(1, second=6)
    b = take(2, second=6)
    a.createdAt = b.createdAt = stamp
    a.id, b.id = "zzz", "aaa"

    forward = name_folder([a, b], record_type="Artisan", record_name="Om")
    backward = name_folder([b, a], record_type="Artisan", record_name="Om")
    assert forward == backward
    # The lower id sorts first, so it is the one that keeps the unnumbered name.
    assert not forward["aaa"].endswith("-2.m4a")
    assert forward["zzz"].endswith("-2.m4a")


def test_a_naive_createdat_does_not_crash_the_ordering():
    """Comparing an aware datetime against a naive one raises; the column is UTC either way."""
    a = take(1)
    b = take(2)
    b.createdAt = b.createdAt.replace(tzinfo=None)
    assert len(name_folder([a, b], record_type="Artisan", record_name="Om")) == 2


def test_a_missing_createdat_does_not_crash_the_ordering():
    a = take(1)
    a.createdAt = None
    assert len(name_folder([a, take(2)], record_type="Artisan", record_name="Om")) == 2


# --------------------------------------------------------------------------------------------
# Scope: one folder, and only one folder
# --------------------------------------------------------------------------------------------


def test_the_same_name_in_two_folders_is_not_a_collision():
    """Nothing ever writes them side by side, so numbering them would put a suffix on hundreds of
    files to fix a problem none of them have."""
    here = name_folder([take(1)], record_type="Artisan", record_name="Om Prakash")
    there = name_folder([take(1)], record_type="Artisan", record_name="Om Prakash")
    assert here["take1"] == there["take1"]
    assert not here["take1"].endswith("-2.m4a")


def test_a_transcript_is_numbered_against_its_own_folder_not_the_audio():
    """A ``.transcript.md`` is a file of its own in the folder it lands in, and the compound
    extension must not be split at the last dot into ``…transcript-2.md``."""
    used: set[str] = set()
    rows = [take(1), take(2)]
    names = [
        unique_display_stem(
            row, used, extension=".transcript.md", record_type="Artisan", record_name="Om"
        )
        for row in folder_order(rows)
    ]
    assert names[0].endswith("-190620261133.transcript.md")
    assert names[1].endswith("-190620261133-2.transcript.md")


# --------------------------------------------------------------------------------------------
# The byte budget: the suffix is what must not be clipped
# --------------------------------------------------------------------------------------------

# A Devanagari character costs three bytes, so a long name in it exhausts the budget on its own.
LONG_DEVANAGARI = "गिरीराज प्रसाद छीपा " * 12


def test_a_name_at_the_limit_keeps_its_suffix():
    """Clipping the suffix would put the two files it exists to separate back onto one name.

    Which is why the number is charged to the byte budget during assembly rather than glued onto a
    finished name that has already been trimmed to the limit.
    """
    names = name_folder(
        [take(n) for n in range(1, 4)], record_type="Artisan", record_name=LONG_DEVANAGARI
    )
    ordered = [names[f"take{n}"] for n in (1, 2, 3)]

    for name in ordered:
        assert len(name.encode("utf-8")) <= MAX_NAME_BYTES
    assert ordered[1].endswith("-2.m4a")
    assert ordered[2].endswith("-3.m4a")
    assert len({n.lower() for n in ordered}) == 3


def test_clipping_takes_the_record_name_and_never_the_stamp():
    """The stamp and the descriptor are precisely what tells two files of one artisan apart, so the
    record name absorbs the whole shortfall."""
    name = display_filename(
        take(1), record_type="Artisan", record_name=LONG_DEVANAGARI, suffix=2
    )
    assert name.endswith("Interview-Section-K-Answer-1-190620261133-2.m4a")
    assert len(name.encode("utf-8")) <= MAX_NAME_BYTES


# --------------------------------------------------------------------------------------------
# Guarantees the numbering must not have cost
# --------------------------------------------------------------------------------------------


def test_devanagari_survives_and_so_does_its_suffix():
    """Names are the data in this repository; an artisan named in Devanagari must not come back as
    a row of underscores, numbered or not."""
    names = name_folder(
        [take(1), take(2)], record_type="Artisan", record_name="गिरीराज प्रसाद छीपा"
    )
    for name in names.values():
        assert "गिरीराज" in name
    assert names["take2"].endswith("-2.m4a")


def test_zero_width_joiners_are_kept():
    """Invisible, but in Indic scripts they select conjunct and half forms — dropping them
    misspells the very names the Unicode rule exists to preserve."""
    name = display_filename(take(1), record_type="Artisan", record_name="क‍ष‌त")
    assert "‍" in name and "‌" in name


def test_a_windows_reserved_name_keeps_its_underscore_when_numbered():
    """Windows refuses CON, PRN, AUX, LPT1 … with or without an extension.

    The underscore is applied before the number, so a reserved name and its duplicate are spelled
    the same way and still sort next to each other once extracted.
    """
    parts = NameParts(
        record_type="", record_name="AUX", descriptor="", stamp="", extension=".jpg"
    )
    assert _assemble(parts) == "AUX_.jpg"
    assert _assemble(parts, "-2") == "AUX_-2.jpg"


def test_the_stamp_is_always_twelve_digits():
    """Seconds are cut, never padded: one precision for every file is what makes the scheme
    predictable, and padding the other third would state a second the app never recorded."""
    with_seconds = Row(originalFilename="D_SEC_GIRIRAJ_001046_010720261824" + "31" + ".wav")
    without = Row(originalFilename="D_SEC_GIRIRAJ_001046_010720261824.wav")
    for row in (with_seconds, without):
        name = display_filename(row, record_type="Artisan", record_name="Giriraj")
        assert name.endswith("-010720261824.wav"), name


def test_a_row_with_nothing_nameable_still_gets_a_numbered_name():
    """A file listed with a blank name is worse than one listed under its upload code."""
    rows = [
        Row(id="x", originalFilename="", createdAt=datetime(2026, 6, 1, tzinfo=UTC)),
        Row(id="y", originalFilename="", createdAt=datetime(2026, 6, 2, tzinfo=UTC)),
    ]
    names = name_folder(rows)
    assert all(names.values())
    assert len(set(names.values())) == 2


@pytest.mark.parametrize("count", [2, 3, 10, 26])
def test_a_group_of_any_size_is_fully_separated(count):
    names = name_folder(
        [take(n) for n in range(1, count + 1)], record_type="Artisan", record_name="Om"
    )
    assert len({n.lower() for n in names.values()}) == count


def test_numbering_survives_a_gap_left_by_a_name_already_taken():
    """``unique_name`` hands out the first FREE number, so a stem that already ends in "-2" — as one
    arriving from the tree, already numbered, does — does not collide with the number about to be
    minted for a different file."""
    used: set[str] = set()
    assert unique_name("Clip-190620261133-2", ".m4a", used) == "Clip-190620261133-2.m4a"
    assert unique_name("Clip-190620261133", ".m4a", used) == "Clip-190620261133.m4a"
    # "-2" is already spoken for by the entry that arrived carrying it, so the next free number is 3.
    assert unique_name("Clip-190620261133", ".m4a", used) == "Clip-190620261133-3.m4a"


def test_recorded_at_falls_back_to_minute_precision():
    """A derived stamp is minute-precision too: ``recordedAt``'s seconds are the upload beat rather
    than the capture, so stating them would be inventing detail."""
    row = Row(
        originalFilename="photo.jpg",
        mediaType="IMAGE",
        recordedAt=datetime(2026, 6, 19, 6, 3, 44, tzinfo=UTC),
        recordedTimezone="Asia/Kolkata",
    )
    name = display_filename(row, record_type="Artisan", record_name="Om")
    # 06:03:44 UTC is 11:33 IST, to the minute.
    assert name.endswith("-190620261133.jpg"), name


def test_an_unknown_timezone_does_not_cost_the_file_its_timestamp():
    row = Row(
        originalFilename="photo.jpg",
        mediaType="IMAGE",
        recordedAt=datetime(2026, 6, 19, 6, 3, tzinfo=UTC),
        recordedTimezone="Mars/Olympus",
    )
    assert display_filename(row, record_type="Artisan", record_name="Om").endswith(
        "-190620261133.jpg"
    )


def test_clip_stops_at_the_empty_string_and_not_at_the_budget():
    """A budget of zero or less is reachable: callers subtract the parts they have promised to keep
    from the total before asking for the rest.

    On a negative budget "shorter than the budget" is never true, so a loop watching only the byte
    count spins on the empty string forever — in the single-worker web process, for one row.
    """
    assert clip("abc", 10, 0) == ""
    assert clip("abc", 10, -5) == ""
    assert clip("abc", 10, 100) == "abc"


def test_a_dot_in_a_name_is_not_mistaken_for_an_extension():
    """``splitext`` splits on the LAST dot wherever it is, so a recording saved with no extension
    but a dot in a date claims a 268-byte one — long enough to consume the budget the stamp and the
    suffix are promised out of, and (before the guard in :func:`clip`) to hang the request outright.
    """
    row = Row(originalFilename="Interview 2026.06 " + "गिरीराज प्रसाद छीपा साक्षात्कार " * 3)
    plain = display_filename(row, record_type="Artisan", record_name="Giriraj")
    numbered_name = display_filename(row, record_type="Artisan", record_name="Giriraj", suffix=2)

    # The bogus tail is dropped whole — not carried, and not truncated into a nonsense extension.
    assert plain == "Artisan-Giriraj-Audio-Note-1-190620261703"
    assert numbered_name == "Artisan-Giriraj-Audio-Note-1-190620261703-2"
    assert len(numbered_name.encode("utf-8")) <= MAX_NAME_BYTES


def test_a_real_extension_is_still_kept():
    for filename, ext in (("clip.m4a", ".m4a"), ("photo.jpeg", ".jpeg"), ("scan.PDF", ".PDF")):
        row = Row(originalFilename=filename, mediaType="IMAGE")
        assert display_filename(row, record_type="Artisan", record_name="Om").endswith(ext)


def test_the_fallback_name_is_clipped_and_keeps_its_number():
    """The fallback hands back the uploaded name, and an upload is free to be longer than a
    filesystem will accept."""
    long_upload = "क" * 400
    rows = [
        Row(id="a", originalFilename=long_upload, createdAt=datetime(2026, 6, 1, tzinfo=UTC)),
        Row(id="b", originalFilename=long_upload, createdAt=datetime(2026, 6, 2, tzinfo=UTC)),
    ]
    names = name_folder(rows)
    for name in names.values():
        assert len(name.encode("utf-8")) <= MAX_NAME_BYTES
    assert len(set(names.values())) == 2


def test_folder_order_is_by_created_at_then_id():
    early = take(1)
    late = take(2)
    late.createdAt = early.createdAt + timedelta(seconds=30)
    assert [r.id for r in folder_order([late, early])] == ["take1", "take2"]
