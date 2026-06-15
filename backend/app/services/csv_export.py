import csv
from io import StringIO
from typing import Any

from app.core.deps import get_value


PRODUCT_FIELDS = [
    "id",
    "craftName",
    "place",
    "artisanName",
    "productName",
    "localName",
    "productType",
    "timeTakenToCompleteProduct",
    "size",
    "costOfMaking",
    "sellingPrice",
    "marketDemand",
    "rawMaterialsUsed",
    "mainToolsUsed",
    "productFunctionUse",
    "remarks",
    "status",
    "mediaLinks",
    "createdAt",
    "updatedAt",
]

TOOL_FIELDS = [
    "id",
    "craftName",
    "place",
    "artisanName",
    "toolkitName",
    "localName",
    "englishName",
    "processUsedIn",
    "material",
    "yearsInUse",
    "height",
    "width",
    "thickness",
    "weight",
    "radius",
    "maker",
    "traditionType",
    "replacementCost",
    "suggestionsForToolImprovement",
    "remarks",
    "status",
    "mediaLinks",
    "createdAt",
    "updatedAt",
]


def media_links(record: Any) -> str:
    media = get_value(record, "media") or []
    links: list[str] = []
    for item in media:
        links.append(get_value(item, "url") or get_value(item, "objectKey") or "")
    return " | ".join(link for link in links if link)


def records_to_csv(records: list[Any], fields: list[str]) -> str:
    output = StringIO()
    writer = csv.DictWriter(output, fieldnames=fields)
    writer.writeheader()
    for record in records:
        row = {}
        for field in fields:
            row[field] = media_links(record) if field == "mediaLinks" else get_value(record, field)
        writer.writerow(row)
    return output.getvalue()
