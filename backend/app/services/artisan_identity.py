"""Artisan identity: Aadhaar and Artisan Pehchan Card handling.

The Aadhaar number is the repository's **deduplication key** for artisans — the same person
documented at two workshops by two researchers must resolve to one record, and a UNIQUE index on
``Artisan.aadhaarNumber`` is what enforces that.

Required to CREATE an artisan, nullable in the database
-------------------------------------------------------
A dedup key that may be omitted only deduplicates the records that happened to fill it in, so
:func:`require_aadhaar` demands one when the artisan is first entered — while the researcher still
has the card in front of them. That rule lives HERE, in the application, and the column stays
``String?``. The reason is the rows already in production, recorded before the field existed:

- ``SET NOT NULL`` cannot be applied to them at all. The ALTER aborts on the first NULL, so the
  migration would simply fail to deploy.
- The two ways to make it apply are to invent numbers or to delete the rows. Inventing a national
  identity number is not a data-migration technique; it would also have to be unique, so it would
  poison the very index this column exists for, and the fabricated number would be masked to
  "XXXX XXXX 9012" and read as real by everyone downstream. Deleting the rows destroys field
  research that cannot be re-collected.
- ``CHECK (aadhaarNumber IS NOT NULL) NOT VALID`` looks like the escape hatch — existing rows go
  unchecked — but Postgres still enforces a NOT VALID constraint on every subsequent INSERT **and
  UPDATE**, and an UPDATE re-checks the whole new row version. A legacy artisan with no Aadhaar
  could then never be edited again: a researcher correcting that artisan's phone number would be
  refused until they produced an Aadhaar they do not have. That is the exact outcome the constraint
  was supposed to be worth having, and it is worse than the problem.

So: pre-existing artisans keep NULL. They stay readable, stay editable, stay exempt from the unique
index (Postgres allows unlimited NULLs under one), and can be completed whenever someone actually
obtains the number. Only NEW artisans must carry one. This is the same shape as ``dos``/``donts``,
required by ``ArtisanCreate`` and nullable in the schema for exactly the same reason.

Editing keeps :func:`validate_aadhaar` rather than :func:`require_aadhaar`, for two reasons: a PATCH
that does not mention the field must not be forced to supply one, and a number entered against the
WRONG artisan has to be retractable — leaving it stranded there would also block the artisan who
really holds it from ever being created.

That job only works if the stored value is trustworthy, which is why validation here is strict
rather than advisory. A mistyped Aadhaar is *worse* than no Aadhaar: it passes a uniqueness check
against a number nobody owns and silently creates exactly the duplicate the field exists to
prevent. So a number is accepted only when it is 12 digits, starts 2–9 (UIDAI never issues a number
beginning 0 or 1), and satisfies the **Verhoeff** checksum that UIDAI computes over the first 11
digits. Those three rules reject the overwhelming majority of transcription slips.

Aadhaar is also regulated personal data, so this module is deliberately the only place it is
formatted for output:

- :func:`normalize_aadhaar` strips the spacing people naturally type ("1234 5678 9012") down to the
  12 digits actually stored, so the same person cannot be entered twice under two spellings.
- :func:`mask_aadhaar` renders it as ``XXXX XXXX 9012`` for every general-purpose surface — the data
  browser, the .xlsx report, CSV exports. Only the last four digits are shown, which is enough for a
  researcher to confirm they have the right person and not enough to be a usable identifier.
- Callers that legitimately need the full value (the artisan's own edit form, an admin) read the raw
  column; nothing here ever writes it to a log.

The Pehchan card (the PM Vishwakarma artisan ID) is a plain government reference number: normalised
to uppercase without separators, and required exactly when the artisan says they hold one.
"""

from __future__ import annotations

import re

# Verhoeff tables. `_D` is the dihedral-group multiplication table, `_P` the position permutation
# applied to each digit by its distance from the right. UIDAI picks Verhoeff because it catches every
# single-digit error and every adjacent transposition — the two ways a 12-digit number is misread.
_D = (
    (0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    (1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    (2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
    (3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    (4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
    (5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    (6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
    (7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    (8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
    (9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
)

_P = (
    (0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    (1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    (5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
    (8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    (9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
    (4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    (2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
    (7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
)

AADHAAR_LENGTH = 12

# Everything a person might type between groups of digits: spaces, hyphens, en/em dashes.
_SEPARATORS = re.compile(r"[\s‐-―-]+")
_NON_ALNUM = re.compile(r"[^A-Za-z0-9]+")

# ASCII digits ONLY — deliberately not ``str.isdigit()``, which is Unicode-aware and answers True for
# Devanagari "१२३", fullwidth "１２３" and every other decimal script. Those characters would sail
# through the length, leading-digit and Verhoeff checks (``int()`` reads them happily) and be STORED
# verbatim, at which point the unique index sees "１２３４５６７８９０１２" and "123456789012" as two
# different values — the same person recorded twice, which is precisely what this column exists to
# prevent. The web and Android ports both test ASCII digits, so this also keeps the three validators
# answering identically.
_ASCII_DIGITS = re.compile(r"[0-9]+")

PEHCHAN_MIN_LENGTH = 4
PEHCHAN_MAX_LENGTH = 32


def verhoeff_ok(digits: str) -> bool:
    """True when ``digits`` satisfies the Verhoeff checksum (the 12th digit checks the first 11)."""
    checksum = 0
    for index, char in enumerate(reversed(digits)):
        checksum = _D[checksum][_P[index % 8][int(char)]]
    return checksum == 0


def normalize_aadhaar(value: str | None) -> str | None:
    """"1234 5678 9012" -> "123456789012".

    ``None``/blank collapses to ``None`` — meaning "no number", which is a legitimate state for an
    artisan recorded before the field was required and for a number being retracted on an edit.
    :func:`require_aadhaar` is what refuses it on the create path.
    """
    if value is None:
        return None
    cleaned = _SEPARATORS.sub("", str(value)).strip()
    return cleaned or None


def aadhaar_error(value: str | None) -> str | None:
    """The reason ``value`` is not a usable Aadhaar number, or ``None`` when it is fine.

    Returns a message written for the person filling the form, naming the specific problem, because
    "invalid Aadhaar number" gives a field researcher nothing to act on.
    """
    if value is None:
        return None
    if not _ASCII_DIGITS.fullmatch(value):
        return "Aadhaar number must be 12 digits — remove any letters or symbols."
    if len(value) != AADHAAR_LENGTH:
        return f"Aadhaar number must be exactly 12 digits (this one has {len(value)})."
    if value[0] in "01":
        return "Aadhaar numbers never start with 0 or 1 — please re-check the first digit."
    if not verhoeff_ok(value):
        return (
            "That Aadhaar number fails its checksum, so at least one digit is wrong. "
            "Please re-read the card and enter it again."
        )
    return None


def validate_aadhaar(value: str | None) -> str | None:
    """Normalise and validate in one step; raises ``ValueError`` for a Pydantic field validator."""
    normalized = normalize_aadhaar(value)
    error = aadhaar_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def require_aadhaar(value: str) -> str:
    """Like :func:`validate_aadhaar`, but a missing number is itself an error. The CREATE path.

    Separate from ``validate_aadhaar`` rather than a flag on it, because the two callers want
    genuinely different things and a boolean parameter would let the wrong one be passed at the wrong
    call site: creating an artisan must not be possible without the dedup key, while editing one must
    not be blocked by a field the request never mentioned.

    The message explains what the number is FOR. "Field required" tells a researcher that a box is
    empty; it does not tell them that skipping it is what lets the same artisan be entered twice next
    month by someone else.
    """
    normalized = normalize_aadhaar(value)
    if not normalized:
        raise ValueError(
            "Enter the artisan's 12-digit Aadhaar number — it is what keeps the same artisan from "
            "being recorded twice."
        )
    error = aadhaar_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def mask_aadhaar(value: str | None) -> str | None:
    """``"123456789012"`` -> ``"XXXX XXXX 9012"``. The form for every shared/exported surface.

    Anything shorter than a full number is masked entirely rather than partially revealed, so a
    malformed legacy value can never leak more than a well-formed one.
    """
    normalized = normalize_aadhaar(value)
    if not normalized:
        return None
    if len(normalized) < 4:
        return "XXXX XXXX XXXX"
    return f"XXXX XXXX {normalized[-4:]}"


def is_masked_aadhaar(value: str | None) -> bool:
    """True when ``value`` is a mask this module produced rather than a real number.

    A caller who is shown ``XXXX XXXX 9012`` and saves the form without touching that field posts the
    mask straight back. That has to be recognised as "unchanged" and dropped — validating it would
    fail, and storing it would overwrite a real number with an X-string. Checked BEFORE validation
    for exactly that reason.
    """
    if value is None:
        return False
    return "X" in str(value).upper()


def normalize_pehchan(value: str | None) -> str | None:
    """Strip separators and upper-case a Pehchan card number so one card has one spelling."""
    if value is None:
        return None
    cleaned = _NON_ALNUM.sub("", str(value)).upper().strip()
    return cleaned or None


def pehchan_error(value: str | None) -> str | None:
    """The reason ``value`` is not a usable Pehchan card number, or ``None`` when it is fine."""
    if value is None:
        return None
    if not value.isalnum():
        return "Pehchan card number must be letters and digits only."
    if not PEHCHAN_MIN_LENGTH <= len(value) <= PEHCHAN_MAX_LENGTH:
        return (
            f"Pehchan card number must be between {PEHCHAN_MIN_LENGTH} and "
            f"{PEHCHAN_MAX_LENGTH} characters."
        )
    return None


def validate_pehchan(value: str | None) -> str | None:
    """Normalise and validate a Pehchan card number; raises ``ValueError`` when malformed."""
    normalized = normalize_pehchan(value)
    error = pehchan_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def resolve_pehchan_fields(
    available: bool | None, number: str | None
) -> tuple[bool | None, str | None]:
    """Keep the "card available?" answer and the card number consistent with each other.

    The form disables the number box when the answer is No, so a number arriving alongside No is
    stale UI state rather than an instruction — it is dropped instead of stored, which stops a
    cleared checkbox from leaving an orphaned card number behind on the record.
    """
    if available is False:
        return False, None
    return available, number
