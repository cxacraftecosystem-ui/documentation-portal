from math import ceil
from typing import Any

MAX_PAGE_SIZE = 100


def normalize_pagination(page: int, page_size: int) -> tuple[int, int, int]:
    clean_page = max(1, page)
    clean_page_size = max(1, min(page_size, MAX_PAGE_SIZE))
    skip = (clean_page - 1) * clean_page_size
    return clean_page, clean_page_size, skip


def page_payload(items: list[Any], total: int, page: int, page_size: int) -> dict[str, Any]:
    return {
        "items": items,
        "total": total,
        "page": page,
        "pageSize": page_size,
        "pages": ceil(total / page_size) if total else 0,
    }
