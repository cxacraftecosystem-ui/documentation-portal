"""Canonical text normalisation shared by every write path — today, title casing for names.

WHY this module exists
----------------------
The same village, craft or artisan reaches the API typed a dozen different ways from three clients
("kutch", "KUTCH ", "Kutch"). That is not merely cosmetic:

* ``Craft.name`` is ``@unique`` and is matched EXACTLY when an artisan names their craft
  (``artisans.resolve_craft_id``), so "bandhani" and "Bandhani" silently become two separate crafts
  and every downstream group-by, filter and export splits in half.
* Exports, the data browser and the .xlsx workbook print these values verbatim, so a sheet ends up
  mixing "Bhuj", "bhuj" and "BHUJ" rows for one place.

Normalising on WRITE is the only place a fix holds for the web app, the Android app and any script at
the same time — which is why :func:`title_case` is wired into ``services.records.clean_data`` rather
than into any one route or client.

The rule
--------
1. Capitalise the first letter of every word.
2. EXCEPT the usual small words (:data:`SMALL_WORDS`) — those stay lowercase unless they are the
   FIRST or the LAST word of the value ("salt of the earth" -> "Salt of the Earth", "what it is made
   of" -> "What It Is Made Of").
3. Intentional casing is never destroyed. A word is left exactly as typed when it contains a digit
   (``PMV001``, ``A4`` — identifiers, not prose) or an uppercase letter anywhere after its first
   character (``ABC``, ``McDonald``, ``O'Brien``, ``D'Souza``, ``iPhone``).
3a. UNLESS the whole value is shouting. Rule 3 alone preserved "ZARI WORK" verbatim while "zari work"
   became "Zari Work", so the exact-match craft lookup created two crafts for one real craft — the
   duplication this module exists to prevent. An acronym is one token inside an otherwise
   normally-cased value; caps-lock shouts the WHOLE value. So when a value has no lowercase letter at
   all AND is either multi-word or a single word longer than an acronym, rule 3 is suspended and it is
   cased normally: "ZARI WORK" -> "Zari Work", "BANDHANI" -> "Bandhani". "SEWA weaving" keeps SEWA,
   because that value is not entirely upper. See :func:`_is_shouting`.
4. Hyphens and slashes are word separators like spaces ("kutch-bhuj" -> "Kutch-Bhuj"), while
   apostrophes are part of the word ("don'ts" -> "Don'ts", never "Don'Ts"). The one exception is a
   single-letter particle before an apostrophe, which is a name prefix: "o'brien" -> "O'Brien".
5. Non-Latin scripts are returned untouched. This repo stores ``localName`` in Devanagari and
   Gujarati, where "capitalising" is meaningless; the guard is written against Latin letters rather
   than against a script list so cased non-Latin scripts (Greek, Cyrillic) are left alone too.
6. Idempotent: ``title_case(title_case(x)) == title_case(x)`` for every input. This matters because
   the value is re-normalised on every subsequent PATCH, and a non-idempotent rule would keep
   producing "changes" that pollute the RecordRevision edit history.

Leading/trailing whitespace is stripped as part of the rule — a name typed as "Bhuj " is the same
name, and the stray space is exactly what breaks the exact-match craft lookup described above.

Mirroring this in the clients
-----------------------------
The web and Android forms want to show the user what will be stored BEFORE they save, so the rule has
to exist in three languages. :func:`title_case_rule` returns it as a JSON-serialisable spec (the
small-word list and the separator/preservation rules) so a client mirrors the same data rather than
hard-coding its own copy that drifts.
"""

from __future__ import annotations

import re
from typing import Any

# The conventional English title-case exceptions, exactly as specified for this repo. Kept as a
# frozenset (not a list) because the hot path is a membership test per word.
SMALL_WORDS: frozenset[str] = frozenset(
    {
        "a",
        "an",
        "and",
        "as",
        "at",
        "but",
        "by",
        "for",
        "from",
        "in",
        "into",
        "nor",
        "of",
        "on",
        "or",
        "over",
        "per",
        "the",
        "to",
        "up",
        "via",
        "vs",
        "with",
    }
)

# Straight and typographic apostrophes both occur — iOS/Android keyboards produce U+2019 while the
# web form produces U+0027, and the same artisan's name must normalise identically from both.
APOSTROPHES = "'’"

# A "word" is a run of letters/digits/apostrophes. Everything else (spaces, hyphens, slashes, commas,
# brackets, ampersands, Devanagari, Gujarati) is a separator and is copied through verbatim, which is
# what makes hyphens and slashes behave as word boundaries without special-casing them.
_LATIN = "A-Za-zÀ-ɏ"
_WORD_RE = re.compile(f"[0-9{_LATIN}{APOSTROPHES}]+")
_LATIN_RE = re.compile(f"[{_LATIN}]")

# The longest single all-caps token still read as an acronym rather than caps-lock. Real acronyms in
# this domain are short (GI, NGO, SEWA, KVIC); a longer all-caps single word — "BANDHANI" — is
# somebody's caps-lock. Only consulted for a ONE-word value; a multi-word all-caps value is always
# treated as shouting. See :func:`_is_shouting`.
_MAX_ACRONYM_LENGTH = 4


def _capitalise(word: str) -> str:
    """Upper-case the first letter, leaving the rest of the (already lower-cased) word alone.

    ``str.capitalize`` cannot be used: it lower-cases the tail, which would undo the intentional
    casing this module exists to protect if it were ever reached with a mixed-case word.
    """
    # "o'brien" -> "O'Brien". A ONE-letter particle before an apostrophe is a name prefix (O', D',
    # L'), never a contraction — "don'ts" has its apostrophe at index 3 and is untouched by this.
    if len(word) > 2 and word[1] in APOSTROPHES:
        return word[0].upper() + word[1] + word[2].upper() + word[3:]
    return word[0].upper() + word[1:]


def _case_word(word: str, *, is_first: bool, is_last: bool, shouting: bool = False) -> str:
    if not _LATIN_RE.search(word):
        # Devanagari / Gujarati / digits-only: there is no meaningful capitalisation to apply.
        return word
    if any(character.isdigit() for character in word):
        # An identifier, not prose: PMV001, A4, GI2019. Reshaping it would corrupt a real id.
        return word
    if not shouting and any(character.isupper() for character in word[1:]):
        # Deliberate interior capital: ABC, PMV, McDonald, O'Brien, D'Souza, iPhone. Left verbatim,
        # which is also what makes the whole function idempotent for everything it has already cased.
        #
        # Skipped when the WHOLE value is shouting — see :func:`_is_shouting`. There, an all-caps word
        # is caps-lock rather than an acronym, and preserving it is what let "ZARI WORK" and
        # "Zari Work" become two different crafts.
        return word
    lowered = word.lower()
    if lowered in SMALL_WORDS and not is_first and not is_last:
        return lowered
    return _capitalise(lowered)


def _is_shouting(text: str) -> bool:
    """Is this whole value caps-lock rather than a deliberate acronym?

    The preserve-interior-capitals rule protects "ABC", "SEWA" and "McDonald" — but it also meant a
    researcher with caps-lock on produced a value nothing would ever match. "ZARI WORK" was preserved
    verbatim while "zari work" became "Zari Work", so the craft lookup (an exact-name match) created
    a SECOND craft for the same real craft. That is precisely the duplication title-casing exists to
    prevent, and it was observed on the running system, not theorised.

    The signal that separates the two cases is scope: an acronym is one token inside an otherwise
    normally-cased value, whereas caps-lock shouts the ENTIRE value. So a value is "shouting" when it
    has at least one cased letter, no lowercase letter anywhere, and either contains a separator (two
    or more words — multi-word acronyms are vanishingly rare in a craft or place name) or is a single
    word longer than the longest plausible acronym.

    "SEWA weaving" keeps SEWA (the value is not entirely upper). "PMV001" never reaches here — the
    digit rule catches it first.
    """
    if any(character.islower() for character in text):
        return False
    if not any(character.isupper() for character in text):
        return False
    words = _WORD_RE.findall(text)
    if len(words) > 1:
        return True
    return bool(words) and len(words[0]) > _MAX_ACRONYM_LENGTH


def title_case(value: str) -> str:
    """Title-case a name-like value according to the rule documented at the top of this module.

    Returns non-string input unchanged so callers can hand it a raw payload value without
    type-checking first.
    """
    if not isinstance(value, str):
        return value
    text = value.strip()
    if not text or not _LATIN_RE.search(text):
        # Nothing to do, or the whole value is in a script without case (an Indic ``localName``).
        return text

    matches = list(_WORD_RE.finditer(text))
    if not matches:
        return text

    # Decided once for the whole value: an all-caps word means something different in
    # "ZARI WORK" than it does in "SEWA weaving".
    shouting = _is_shouting(text)

    pieces: list[str] = []
    cursor = 0
    last_index = len(matches) - 1
    for index, match in enumerate(matches):
        pieces.append(text[cursor : match.start()])
        pieces.append(
            _case_word(
                match.group(0), is_first=index == 0, is_last=index == last_index, shouting=shouting
            )
        )
        cursor = match.end()
    pieces.append(text[cursor:])
    return "".join(pieces)


def title_case_fields(data: dict[str, Any], fields: frozenset[str] | set[str]) -> dict[str, Any]:
    """Title-case every populated string in ``data`` whose key is in ``fields``. Mutates ``data``.

    Keys that are absent, non-string or blank are left exactly as they are: a write path must never
    invent a value it was not given, and blanking rules live in ``records.clean_data`` instead.
    """
    for key in fields:
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            data[key] = title_case(value)
    return data


def title_case_rule() -> dict[str, Any]:
    """The rule as JSON, so the web and Android forms mirror it instead of forking their own copy.

    ``version`` lets a client cache the spec and notice when the server's rule moves on. Serve it from
    any read-only settings/meta endpoint; nothing here is sensitive.
    """
    return {
        "version": 1,
        "smallWords": sorted(SMALL_WORDS),
        # Characters that end a word. Anything not in the word charset below behaves this way; these
        # are simply the ones a client is likely to test against.
        "wordSeparators": [" ", "-", "/", ",", ".", "(", ")", "&", ":", ";"],
        "wordCharacters": "0-9A-Za-zÀ-ɏ plus apostrophes (' and ’)",
        "smallWordsCapitalisedWhen": ["first", "last"],
        # A word matching any of these is copied through untouched.
        "preserveWordWhen": [
            "contains a digit (identifier such as PMV001)",
            "contains an uppercase letter after the first character (ABC, McDonald, O'Brien)",
            "contains no Latin letters (Devanagari, Gujarati, and other scripts)",
        ],
        "trimsSurroundingWhitespace": True,
        "idempotent": True,
    }
