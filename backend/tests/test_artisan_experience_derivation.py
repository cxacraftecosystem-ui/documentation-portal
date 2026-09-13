"""Experience is worked out from a date, and the number behind it still gets its turn.

``Artisan.experienceYears`` holds a number somebody stated, and its own schema comment records the
cost of that: the value "ages with the record rather than with the artisan ... an artisan documented
in 2024 with 30 years reads 30 in 2030". That decay PRINTS — ``record_fields``' "Experience (years)"
row feeds the data browser card, the /data/report workbook, ``details.txt`` inside the dataset zip
and both /export CSVs. ``craftStartDate`` is the date the number can be derived from instead, on
every read, with nobody editing anything.

WHAT IS NOT BEING CLAIMED. The stated number is not deprecated and is not backfilled from. An artisan
who says "about thirty years" and cannot name a year must stay recordable, and the rows whose legacy
metadata reads "30+" or "about 30" are the oldest and best documented in the repository — so the
precedence has three sources and the last of them is that metadata.
"""

from datetime import datetime

from app.services.record_fields import ARTISAN, sheet_columns
from app.services.records import derive_experience_years


class _Artisan:
    """An artisan row answering ``None`` for every column a test did not set."""

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


def _cell(record, label: str):
    field = next(f for f in ARTISAN.fields if f.label == label)
    return field.get(record)


def test_years_are_derived_from_the_join_date_on_a_stated_day():
    """``on`` exists so this asks what the function returns on a STATED day rather than on the day
    the suite happens to run — a derivation tested against ``now()`` passes in March and fails in
    September, on the anniversary of whatever fixture it uses.

    The two dates either side of the anniversary are the assertion: a naive
    ``(today - started).days // 365`` drifts a day every four years and reports somebody as a year
    further on than they are for a few days each year, which is exactly the kind of wrongness nobody
    checks.
    """
    assert derive_experience_years(datetime(2000, 6, 1), on=datetime(2026, 5, 31)) == 25
    assert derive_experience_years(datetime(2000, 6, 1), on=datetime(2026, 6, 1)) == 26


def test_a_future_date_is_none_and_never_zero():
    """A BLANK BOX AND "PRACTISING FOR ZERO YEARS" ARE DIFFERENT STATEMENTS, and the second is one
    this repository would be making up. Zero itself IS reachable and IS kept — an apprentice who
    started this month is a real answer — which is why every reader tests ``is not None`` rather than
    truthiness."""
    assert derive_experience_years(datetime(2030, 1, 1), on=datetime(2026, 6, 1)) is None
    assert derive_experience_years(datetime(2026, 1, 1), on=datetime(2026, 6, 1)) == 0


def test_out_of_band_is_none_so_the_stated_column_behind_it_still_gets_its_turn():
    """``ArtisanCreate.experienceYears`` declares ``ge=0, le=90`` to match the sibling repository's
    registry exactly, so a number outside that range is not a value this system can carry. A date
    that derives to 91 therefore returns None rather than a figure no schema in either product would
    accept — and the cell falls through to the stated number instead of printing a refusal."""
    assert derive_experience_years(datetime(1935, 1, 1), on=datetime(2026, 6, 1)) is None
    assert derive_experience_years(datetime(1936, 7, 1), on=datetime(2026, 6, 1)) == 89


def test_unparseable_and_absent_dates_are_none_rather_than_an_exception():
    """The value arrives from Prisma, from a JSON blob or from a client, and an export that raised on
    a malformed one would take out the whole workbook for one bad row."""
    assert derive_experience_years(None) is None
    assert derive_experience_years("") is None
    assert derive_experience_years("not a date") is None
    assert derive_experience_years("2000-06-01T00:00:00Z", on=datetime(2026, 6, 1)) == 26


def test_the_record_fields_row_prefers_the_date_then_the_column_then_the_metadata():
    """THREE SOURCES, IN ORDER, IN ONE LAMBDA — because four export surfaces read it and they would
    otherwise disagree about one artisan."""
    all_three = _Artisan(
        craftStartDate=datetime(2000, 6, 1),
        experienceYears=12,
        extraMetadata={"experienceYears": "30+"},
    )
    derived = derive_experience_years(datetime(2000, 6, 1))
    assert _cell(all_three, "Experience (years)") == derived

    stated_only = _Artisan(experienceYears=12, extraMetadata={"experienceYears": "30+"})
    assert _cell(stated_only, "Experience (years)") == 12

    legacy_only = _Artisan(extraMetadata={"experienceYears": "30+"})
    assert _cell(legacy_only, "Experience (years)") == "30+"


def test_a_stated_zero_is_printed_rather_than_skipped():
    """``_first_answer`` AND NOT ``or``: zero years is a real answer — a first-month apprentice — and
    ``or`` would read it as absent and print the staler legacy value instead. This is the trap the
    "Age" row two lines up already carries a comment about."""
    apprentice = _Artisan(experienceYears=0, extraMetadata={"experienceYears": "30+"})

    assert _cell(apprentice, "Experience (years)") == 0


def test_practising_since_appears_in_every_export_surface():
    """The artisan CSV header is a public dataset contract and this change widens it. Deliberately:
    the date is what the artisan said and does not change, the number beside it is a fact about today
    that the sheet works out each time it is drawn, and a reader has to be able to see both."""
    columns = sheet_columns("artisan")

    assert columns.count("Practising since") == 1
    assert columns.index("Practising since") < columns.index("Experience (years)")

    from app.services.csv_export import records_to_csv

    csv = records_to_csv("artisan", [_Artisan(id="art_1", craftStartDate=datetime(2000, 6, 1))])
    assert "Practising since" in csv.splitlines()[0]


def test_experience_months_reaches_no_export_surface_and_that_is_a_decision():
    """A months column in a ministry-facing header is an owner call with its own blast radius, and
    the sibling repository made the same choice. Recorded as a test rather than as a comment so that
    adding the row later is a deliberate edit here, with whoever owns the header in the room."""
    for kind in ("artisan", "product", "tool", "process", "craft", "workshop", "interview"):
        assert not [c for c in sheet_columns(kind) if "month" in c.lower()]
