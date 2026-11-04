import csv
from io import StringIO
from typing import Any

from app.core.deps import get_value
from app.services.record_fields import sheet_columns, sheet_row


def record_media(record: Any) -> list[Any]:
    return list(get_value(record, "media") or [])


def records_to_csv(kind: str, records: list[Any], truncated: bool = False) -> str:
    output = StringIO()
    writer = csv.writer(output)
    columns = ["ID", *sheet_columns(kind)]
    writer.writerow(columns)
    for record in records:
        writer.writerow([get_value(record, "id"), *sheet_row(kind, record, record_media(record))])
    if truncated:
        note = f"Note: this file shows only the newest {len(records)} entries; there are more."
        writer.writerow([note] + [""] * (len(columns) - 1))
    return output.getvalue()
