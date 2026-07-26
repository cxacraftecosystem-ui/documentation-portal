"""Postal address rules: the Indian state / union-territory list, and the PIN code format.

WHY these two fields are validated server-side
----------------------------------------------
State and pincode are the address fields that go wrong in a way nothing downstream can repair.

A state typed as free text arrives as "Gujarat", "gujarat", "GJ", "Gujrat" and "gujarat " for one
state, and every group-by, filter, folder and export sheet then splits five ways for a single place.
This is not hypothetical here: ``services/text_format.py`` exists because exactly that happened to
craft names. The fix that holds for the web app, the Android app and any import script at the same
time is a CLOSED list resolved on write — which is what :func:`validate_state` does.

A pincode is worse, because a wrong one still looks right. Six digits are six digits; only the
format rules say whether they can be a real Indian PIN code. The first digit is the postal zone
(1–9), so a leading 0 is never issued, and anything that is not exactly six ASCII digits is a
transcription slip rather than an address.

WHY the list is served as well as enforced
------------------------------------------
:data:`INDIAN_STATES_AND_UNION_TERRITORIES` is the single source of truth, in two roles at once: the
Pydantic validators below reject anything outside it, and ``api/routes/reference.py`` serves the very
same tuple to the clients. That pairing is the point. A constant alone would still leave the web and
Android forms hard-coding their own copies, which is precisely how two lists drift apart until one
client can only submit values the server refuses. An endpoint alone would leave the server accepting
whatever a script felt like sending. Together, a form that renders its dropdown from the endpoint
cannot produce a value this module rejects, and the two can never disagree because there is only one
list.

Free-text spellings are still ACCEPTED where they are unambiguous — :data:`_ALIASES` covers the
renames (Orissa, Pondicherry, Uttaranchal), the 2020 UT merger, and the "&" spellings — because
records also arrive from import scripts that predate the dropdown. Two-letter codes ("GJ", "MP") are
deliberately NOT accepted: they are indistinguishable from a truncated name, and nothing that renders
the served list would ever send one.
"""

from __future__ import annotations

import re
from typing import Any

# The 28 states, alphabetically. Ordered for direct use as a dropdown, not sorted at call time, so
# the web and Android render the list in exactly the same order the server holds it.
INDIAN_STATES: tuple[str, ...] = (
    "Andhra Pradesh",
    "Arunachal Pradesh",
    "Assam",
    "Bihar",
    "Chhattisgarh",
    "Goa",
    "Gujarat",
    "Haryana",
    "Himachal Pradesh",
    "Jharkhand",
    "Karnataka",
    "Kerala",
    "Madhya Pradesh",
    "Maharashtra",
    "Manipur",
    "Meghalaya",
    "Mizoram",
    "Nagaland",
    "Odisha",
    "Punjab",
    "Rajasthan",
    "Sikkim",
    "Tamil Nadu",
    "Telangana",
    "Tripura",
    "Uttar Pradesh",
    "Uttarakhand",
    "West Bengal",
)

# The 8 union territories, alphabetically. Kept as their own tuple so a form can render a labelled
# "Union territories" group rather than burying Ladakh between Kerala and Madhya Pradesh.
INDIAN_UNION_TERRITORIES: tuple[str, ...] = (
    "Andaman and Nicobar Islands",
    "Chandigarh",
    "Dadra and Nagar Haveli and Daman and Diu",
    "Delhi",
    "Jammu and Kashmir",
    "Ladakh",
    "Lakshadweep",
    "Puducherry",
)

INDIAN_STATES_AND_UNION_TERRITORIES: tuple[str, ...] = INDIAN_STATES + INDIAN_UNION_TERRITORIES

# Every canonical name above is already a fixed point of ``text_format.title_case`` (verified against
# all 36), which matters because ``records.clean_data`` title-cases the ``state`` key on every write.
# A name that changed under that rule would be stored differently from the value this module serves,
# and the dropdown would stop matching what came back.

PINCODE_LENGTH = 6

# Separators a person types inside a name or a pincode: spaces, hyphens, en/em dashes.
_SEPARATORS = re.compile(r"[\s‐-―-]+")
_NON_ALNUM = re.compile(r"[^a-z0-9]+")

# ASCII digits ONLY — deliberately not ``str.isdigit()``, which answers True for Devanagari "१२३" and
# every other decimal script. Those would pass a length check and be stored verbatim, giving one
# village two different pincodes that no query could ever match to each other. Same reasoning, and
# the same regex, as the Aadhaar validator in services/artisan_identity.py.
_ASCII_DIGITS = re.compile(r"[0-9]+")

# Spellings that are unambiguous but not canonical. Keys are already run through :func:`_fold`.
#
#   * Orissa / Pondicherry / Uttaranchal — official renames; historical records still use them.
#   * Dadra and Nagar Haveli / Daman and Diu — two separate UTs until they merged in 2020, so older
#     data names each half. Both resolve to the merged territory.
#   * The Delhi variants — the territory is written a dozen ways in official forms.
#
# "&" is folded to "and" before lookup, so no ampersand spelling needs an entry here.
_ALIASES: dict[str, str] = {
    "orissa": "Odisha",
    "pondicherry": "Puducherry",
    "pondichery": "Puducherry",
    "uttaranchal": "Uttarakhand",
    "chattisgarh": "Chhattisgarh",
    "dadraandnagarhaveli": "Dadra and Nagar Haveli and Daman and Diu",
    "damananddiu": "Dadra and Nagar Haveli and Daman and Diu",
    "newdelhi": "Delhi",
    "delhinct": "Delhi",
    "nctofdelhi": "Delhi",
    "nationalcapitalterritoryofdelhi": "Delhi",
}


def _fold(value: str) -> str:
    """A state name reduced to its comparison key: lower-cased, "&" spelled out, punctuation dropped.

    "Tamil Nadu", "TAMIL NADU", "tamil-nadu" and "Tamilnadu" all fold to ``tamilnadu``, so a value
    that is only mis-cased or mis-spaced is corrected rather than rejected. Folding "&" to "and"
    first is what makes "Jammu & Kashmir" and "Andaman & Nicobar Islands" resolve without needing an
    alias entry for every ampersand variant.
    """
    return _NON_ALNUM.sub("", value.lower().replace("&", "and"))


_LOOKUP: dict[str, str] = {
    **{_fold(name): name for name in INDIAN_STATES_AND_UNION_TERRITORIES},
    **_ALIASES,
}


def normalize_state(value: str | None) -> str | None:
    """Resolve any accepted spelling to its canonical name; ``None``/blank stays ``None``.

    An UNRECOGNISED value is returned trimmed rather than dropped, so :func:`state_error` can name it
    back to the person who typed it. Silently discarding it would save the record with a blank state
    and no explanation of what happened to what they entered.
    """
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return _LOOKUP.get(_fold(text), text)


def state_error(value: str | None) -> str | None:
    """The reason ``value`` is not a state on the canonical list, or ``None`` when it is fine."""
    if value is None:
        return None
    # ``normalize_state`` has already resolved every accepted spelling, so anything that is not a
    # canonical name at this point is a name nobody recognises.
    if value in INDIAN_STATES_AND_UNION_TERRITORIES:
        return None
    return (
        f"'{value}' is not an Indian state or union territory. Choose one of the "
        f"{len(INDIAN_STATES_AND_UNION_TERRITORIES)} names offered by the state list."
    )


def validate_state(value: str | None) -> str | None:
    """Normalise and validate in one step; raises ``ValueError`` for a Pydantic field validator."""
    normalized = normalize_state(value)
    error = state_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def normalize_pincode(value: str | None) -> str | None:
    """"380 001" -> "380001". ``None``/blank stays ``None`` — the field is optional."""
    if value is None:
        return None
    cleaned = _SEPARATORS.sub("", str(value)).strip()
    return cleaned or None


def pincode_error(value: str | None) -> str | None:
    """The reason ``value`` is not a usable PIN code, or ``None`` when it is fine.

    Names the specific problem, the way the Aadhaar validator does, because "invalid pincode" tells a
    field researcher holding a handwritten address nothing about which digit to re-read.
    """
    if value is None:
        return None
    if not _ASCII_DIGITS.fullmatch(value):
        return "Pincode must be 6 digits — remove any letters or symbols."
    if len(value) != PINCODE_LENGTH:
        return f"Pincode must be exactly 6 digits (this one has {len(value)})."
    if value[0] == "0":
        # The leading digit is the postal zone, numbered 1–9. There is no zone 0, so a leading zero
        # is always a typo or a truncated number rather than a real address.
        return "Pincodes never start with 0 — please re-check the first digit."
    return None


def validate_pincode(value: str | None) -> str | None:
    """Normalise and validate in one step; raises ``ValueError`` for a Pydantic field validator."""
    normalized = normalize_pincode(value)
    error = pincode_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def address_reference() -> dict[str, Any]:
    """The state list and the pincode rule as JSON, for the clients to render their forms from.

    ``version`` lets a client cache the payload and notice when the server's list moves on — a UT
    merger or rename is a real event, not a hypothetical one. Nothing here is sensitive or per-user.
    """
    return {
        "version": 1,
        "states": list(INDIAN_STATES),
        "unionTerritories": list(INDIAN_UNION_TERRITORIES),
        # The flat list a single-group dropdown binds to, in the same order as the two groups above.
        "statesAndUnionTerritories": list(INDIAN_STATES_AND_UNION_TERRITORIES),
        "pincode": {
            "length": PINCODE_LENGTH,
            "pattern": "^[1-9][0-9]{5}$",
            "description": (
                "Exactly 6 digits. The first digit is the postal zone (1–9), so a pincode never "
                "starts with 0."
            ),
        },
    }
