from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Reason:
    code: str
    label: str
    fixable: bool
    needs_note: bool = False


REASONS: tuple[Reason, ...] = (
    Reason("MISSING_DETAILS", "Missing details", fixable=True),
    Reason("WRONG_DETAILS", "Details are wrong", fixable=True),
    Reason("MEDIA_UNUSABLE", "Photos or recordings unusable", fixable=True),
    Reason("NO_CONSENT", "No consent on file", fixable=True),
    Reason("DUPLICATE", "Duplicate of another record", fixable=False),
    Reason("OUT_OF_SCOPE", "Not part of this documentation", fixable=False),
    Reason("OTHER", "Other", fixable=False, needs_note=True),
)

_BY_CODE = {reason.code: reason for reason in REASONS}


def reason_for(code: str | None) -> Reason | None:
    if not code:
        return None
    return _BY_CODE.get(str(code).strip().upper())


def reason_label(code: str | None) -> str | None:
    reason = reason_for(code)
    return reason.label if reason else None


def as_payload() -> list[dict[str, object]]:
    return [
        {"code": r.code, "label": r.label, "fixable": r.fixable, "needsNote": r.needs_note} for r in REASONS
    ]
