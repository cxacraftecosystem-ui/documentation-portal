from __future__ import annotations

UPLOAD_MIME_DENIED = frozenset(
    {
        "text/html",
        "text/html-sandboxed",
        "application/xhtml+xml",
        "application/javascript",
        "application/x-javascript",
        "application/ecmascript",
        "text/javascript",
        "text/ecmascript",
        "application/x-msdownload",
        "application/hta",
        "application/xml",
        "text/xml",
        "text/xsl",
        "application/xslt+xml",
        "application/xml-dtd",
        "text/xml-external-parsed-entity",
        "application/xml-external-parsed-entity",
    }
)

UPLOAD_MIME_FAMILIES: tuple[str, ...] = (
    "image/",
    "video/",
    "audio/",
    "text/",
    "model/",
    "font/",
    "application/vnd.",
)

UPLOAD_MIME_ALLOWED = frozenset(
    {
        "application/pdf",
        "application/msword",
        "application/rtf",
        "application/json",
        "application/zip",
        "application/x-zip-compressed",
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
        "application/octet-stream",
    }
)

INLINE_SAFE_MIME_FAMILIES: tuple[str, ...] = ("image/", "audio/", "video/")

INLINE_SAFE_MIME = frozenset({"application/pdf"})


def normalized_mime(raw: str | None) -> str:
    return (raw or "").split(";")[0].strip().lower()


def is_xml_document(mime_type: str | None) -> bool:
    normalized = normalized_mime(mime_type)
    if not normalized:
        return False
    subtype = normalized.split("/", 1)[-1]
    return subtype == "xml" or subtype.endswith("+xml") or subtype.startswith("xml-") or subtype in {"xsl", "xslt"}


def is_uploadable_mime(mime_type: str | None) -> bool:
    normalized = normalized_mime(mime_type)
    if normalized in UPLOAD_MIME_DENIED or is_xml_document(normalized):
        return False
    return normalized in UPLOAD_MIME_ALLOWED or any(
        normalized.startswith(family) for family in UPLOAD_MIME_FAMILIES
    )


def is_inline_safe(mime_type: str | None) -> bool:
    normalized = normalized_mime(mime_type)
    if not is_uploadable_mime(normalized):
        return False
    return normalized in INLINE_SAFE_MIME or any(
        normalized.startswith(family) for family in INLINE_SAFE_MIME_FAMILIES
    )


def signed_read_headers(mime_type: str | None) -> tuple[str, str]:
    normalized = normalized_mime(mime_type)
    if not is_uploadable_mime(normalized):
        return "application/octet-stream", "attachment"
    return normalized, "inline" if is_inline_safe(normalized) else "attachment"


def upload_ceiling(settings: object) -> int:
    return int(getattr(settings, "media_upload_max_bytes", 0) or 0)


def over_ceiling(size_bytes: int | None, settings: object) -> bool:
    ceiling = upload_ceiling(settings)
    return bool(ceiling) and size_bytes is not None and int(size_bytes) > ceiling


MIN_PART_BYTES = 5 * 1024 * 1024
MAX_PARTS = 10_000


def max_part_number(settings: object) -> int:
    ceiling = upload_ceiling(settings)
    if not ceiling:
        return MAX_PARTS
    return max(1, min(MAX_PARTS, -(-ceiling // MIN_PART_BYTES)))
