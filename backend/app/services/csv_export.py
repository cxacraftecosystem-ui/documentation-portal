"""CSV extracts of a record table (the /export/products.csv and /export/tools.csv downloads).

The columns are NOT written out here: they come from the shared field registry
(:mod:`app.services.record_fields`), the same one the data browser's info panels and every sheet of
the .xlsx report are built from. Until this module was migrated it carried its own hand-maintained
``PRODUCT_FIELDS`` / ``TOOL_FIELDS`` lists, which meant a column added to the registry reached the
browser and the workbook but silently never reached the CSVs — and it emitted raw column values,
bypassing the coercion (Decimals without trailing zeros, enums as plain words) and, crucially, the
masking the registry applies to regulated personal data such as an artisan's Aadhaar number.

A CSV is a data extract rather than a reading surface, so one column the registry has no opinion
about is prepended: the record's ``ID``, the only stable key a downstream sheet can join on.
"""

import csv
from io import StringIO
from typing import Any

from app.core.deps import get_value
from app.services.record_fields import sheet_columns, sheet_row


def record_media(record: Any) -> list[Any]:
    """The media rows loaded alongside a record (``include={"media": True}``)."""
    return list(get_value(record, "media") or [])


def records_to_csv(kind: str, records: list[Any], truncated: bool = False) -> str:
    """CSV text for records of one registry ``kind`` ("product", "tool", ...).

    ``truncated`` appends the same full-width note row the .xlsx report uses when a sheet hits its
    row cap: a download that silently stops short is worse than one that says so, and keeping the
    note rectangular means a spreadsheet or a parser still reads the file as a clean table.
    """
    output = StringIO()
    writer = csv.writer(output)
    columns = ["ID", *sheet_columns(kind)]
    writer.writerow(columns)
    for record in records:
        writer.writerow([get_value(record, "id"), *sheet_row(kind, record, record_media(record))])
    if truncated:
        note = f"Note: capped at {len(records)} rows — the full data set has more."
        writer.writerow([note] + [""] * (len(columns) - 1))
    return output.getvalue()
