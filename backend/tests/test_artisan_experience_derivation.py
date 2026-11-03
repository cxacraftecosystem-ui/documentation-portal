from datetime import datetime

from app.services.record_fields import ARTISAN, sheet_columns
from app.services.records import derive_experience_years


class _Artisan:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


def _cell(record, label: str):
    field = next(f for f in ARTISAN.fields if f.label == label)
    return field.get(record)


def test_years_are_derived_from_the_join_date_on_a_stated_day():
    assert derive_experience_years(datetime(2000, 6, 1), on=datetime(2026, 5, 31)) == 25
    assert derive_experience_years(datetime(2000, 6, 1), on=datetime(2026, 6, 1)) == 26


def test_a_future_date_is_none_and_never_zero():
    assert derive_experience_years(datetime(2030, 1, 1), on=datetime(2026, 6, 1)) is None
    assert derive_experience_years(datetime(2026, 1, 1), on=datetime(2026, 6, 1)) == 0


def test_out_of_band_is_none_so_the_stated_column_behind_it_still_gets_its_turn():
    assert derive_experience_years(datetime(1935, 1, 1), on=datetime(2026, 6, 1)) is None
    assert derive_experience_years(datetime(1936, 7, 1), on=datetime(2026, 6, 1)) == 89


def test_unparseable_and_absent_dates_are_none_rather_than_an_exception():
    assert derive_experience_years(None) is None
    assert derive_experience_years("") is None
    assert derive_experience_years("not a date") is None
    assert derive_experience_years("2000-06-01T00:00:00Z", on=datetime(2026, 6, 1)) == 26


def test_the_record_fields_row_prefers_the_date_then_the_column_then_the_metadata():
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
    apprentice = _Artisan(experienceYears=0, extraMetadata={"experienceYears": "30+"})

    assert _cell(apprentice, "Experience (years)") == 0


def test_practising_since_appears_in_every_export_surface():
    columns = sheet_columns("artisan")

    assert columns.count("Practising since") == 1
    assert columns.index("Practising since") < columns.index("Experience (years)")

    from app.services.csv_export import records_to_csv

    csv = records_to_csv("artisan", [_Artisan(id="art_1", craftStartDate=datetime(2000, 6, 1))])
    assert "Practising since" in csv.splitlines()[0]


def test_experience_months_reaches_no_export_surface_and_that_is_a_decision():
    for kind in ("artisan", "product", "tool", "process", "craft", "workshop", "interview"):
        assert not [c for c in sheet_columns(kind) if "month" in c.lower()]
