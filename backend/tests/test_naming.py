"""Golden names for services/naming.py (docs/conventions/NAMING_2026-10-18.md). Shared by both portals."""

from datetime import UTC, datetime

import pytest

from app.services import naming


@pytest.mark.parametrize(
    ("kwargs", "expected"),
    [
        (
            dict(
                portal=naming.PORTAL_DESIGN_WORKSHOP, entity="Artisan", what="Artisan-Card", ext="pdf",
                day="2026-10-18", place="Bagru, Jaipur", craft="hand block printing", subject="Giriraj Prasad Chhipa",
            ),
            "DesignWorkshop_Artisan_2026-10-18_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Artisan-Card.pdf",
        ),
        (
            dict(portal=naming.PORTAL_FIELD_REPOSITORY, entity="Artisan", what="Artisan-Cards", ext="pdf", day="2026-10-18"),
            "FieldRepository_Artisan_2026-10-18_Artisan-Cards.pdf",
        ),
        (
            # Unknown fields are left out; a name in another script with no romanised form drops out.
            dict(
                portal=naming.PORTAL_DESIGN_WORKSHOP, entity="Artisan", what="Artisan-Card", ext="pdf",
                day="2026-10-18", place=None, craft="", subject="सीता देवी",
            ),
            "DesignWorkshop_Artisan_2026-10-18_Artisan-Card.pdf",
        ),
        (
            dict(
                portal=naming.PORTAL_DESIGN_WORKSHOP, entity="Workshop", what="Report", ext="pdf",
                day="2026-10-18", place="Bagru-Jaipur", craft="Hand-Block-Printing", subject="DW-RJ-0012",
            ),
            "DesignWorkshop_Workshop_2026-10-18_Bagru-Jaipur_Hand-Block-Printing_DW-RJ-0012_Report.pdf",
        ),
    ],
)
def test_golden_names(kwargs, expected):
    assert naming.file_name(**kwargs) == expected


def test_accents_fold_and_separators_are_only_underscore_and_hyphen():
    assert naming.field("Śrī  Rāmā / O'Neil & co.") == "Sri-Rama-O-Neil-Co"


def test_long_names_cut_subject_first_and_stay_within_150():
    name = naming.file_name(
        naming.PORTAL_DESIGN_WORKSHOP, "Artisan", "Artisan-Card", "pdf",
        day="2026-10-18", place="A Very Long Locality Name " * 3, craft="Craft " * 10, subject="Name " * 30,
    )
    assert len(name) <= naming.MAX_LENGTH
    assert name.startswith("DesignWorkshop_Artisan_2026-10-18_")
    assert name.endswith("_Artisan-Card.pdf")


def test_ist_date_crosses_midnight():
    assert naming.ist_date(datetime(2026, 10, 17, 19, 0, tzinfo=UTC)) == "2026-10-18"
