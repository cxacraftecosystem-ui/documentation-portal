"""The artisan form contract: which answers a save must carry, and how an older shape still lands.

── WHAT THE CONTRACT IS (owner decision, 2026-10-17) ─────────────────────────────────────────────

The artisan form is the same in the designer portal and the documentation portal, web and Android.
Under it, **Date of birth** and **Craft start date** are required on every NEW save — a create, and
an edit made from the artisan form. Experience stays two optional boxes, years (0..90) and months
(0..11, a remainder), stored in ``Artisan.experienceYears`` / ``Artisan.experienceMonths``; that
representation was already the same in both portals, so the contract needed no data migration.

── HOW A CLIENT OPTS IN, AND WHY IT IS A BODY KEY ───────────────────────────────────────────────

A client that collects the form under this contract sends ``"formContract": 2`` in the body. Only
then does the server refuse a save that leaves either date empty. It is a BODY key and not a header
on purpose: both outboxes store the body when an entry is queued and replay it later, possibly from a
newer build. A header would be stamped at REPLAY time and would hold an entry queued by an older
build to a rule it never saw; a body key is fixed at QUEUE time, so an old entry replays exactly as
it was written.

── THE TRANSITION ───────────────────────────────────────────────────────────────────────────────

A body WITHOUT the key is the old shape and is accepted unchanged: an Android outbox entry queued by
a build that predates this contract, a cached web bundle's IndexedDB queue, the spreadsheet import,
the review queue's edit and any other partial PATCH. That acceptance is the transition period. It
ends with a later change that removes this branch once no build older than the contract is in the
field — not with a date in code, because a clock-driven refusal would also start refusing the
spreadsheet import and the review queue, which are not the artisan form.

── WHAT A STORED RECORD WITHOUT THE DATES DOES ──────────────────────────────────────────────────

It still loads and still displays ("Not stated" on the form's experience months box, an empty date
box). An edit of it from the artisan form asks for the missing dates before it saves, and a PATCH
carrying the key is checked against the row as it will be AFTER the write — the stored value counts
where the body does not mention the date, an explicit null does not.

The documentation portal carries the same module under the same name; change both.
"""

from typing import Any

from fastapi import HTTPException, status

#: The body key, spelled once. Popped before anything reads the body as columns.
FORM_CONTRACT_KEY = "formContract"

#: The contract under which both dates are required. A client sends this value.
DATES_REQUIRED_CONTRACT = 2

#: The required dates, in form order, with the words a refusal uses for each.
REQUIRED_DATES: tuple[tuple[str, str], ...] = (
    ("dateOfBirth", "date of birth"),
    ("craftStartDate", "craft start date"),
)


def take_form_contract(data: dict[str, Any]) -> int | None:
    """Remove the contract key from a cleaned body and return it (``None`` for the old shape)."""
    value = data.pop(FORM_CONTRACT_KEY, None)
    return value if isinstance(value, int) else None


def missing_required_dates(data: dict[str, Any], stored: Any | None = None) -> list[str]:
    """The required dates this save would leave empty, as their spoken names, in form order.

    ``stored`` is the row an edit is applied to. A date the body does not mention keeps the stored
    value; a date the body sends as null (or blank) is empty whatever the row holds.
    """
    missing: list[str] = []
    for field, words in REQUIRED_DATES:
        if field in data:
            value = data[field]
        else:
            value = getattr(stored, field, None) if stored is not None else None
        if value is None or (isinstance(value, str) and not value.strip()):
            missing.append(words)
    return missing


def refusal_sentence(missing: list[str]) -> str:
    """ "Enter the date of birth." / "Enter the date of birth and the craft start date." """
    return "Enter the " + " and the ".join(missing) + "."


def assert_required_dates(
    contract: int | None, data: dict[str, Any], stored: Any | None = None
) -> None:
    """422 when a save made under the contract leaves a required date empty; nothing otherwise."""
    if contract is None or contract < DATES_REQUIRED_CONTRACT:
        return
    missing = missing_required_dates(data, stored)
    if missing:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=refusal_sentence(missing),
        )
