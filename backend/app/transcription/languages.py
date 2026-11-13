from __future__ import annotations

from typing import Any

AUTO = "auto"
MIXED = "mixed"
MULTILINGUAL_FIELD = "secondary_language_codes"
UNCERTAIN_BELOW = 0.8
METADATA_KEY = "spokenLanguage"
LEGACY_METADATA_KEY = "languageHint"
AUTO_LABEL = "Detect automatically"
MIXED_LABEL = "Mixed (several languages)"

FEATURED: tuple[tuple[str, str], ...] = (
    ("as", "Assamese"),
    ("bn", "Bengali"),
    ("en", "English"),
    ("gu", "Gujarati"),
    ("hi", "Hindi"),
    ("kn", "Kannada"),
    ("ml", "Malayalam"),
    ("mr", "Marathi"),
    ("ne", "Nepali"),
    ("or", "Odia"),
    ("pa", "Punjabi"),
    ("ta", "Tamil"),
    ("te", "Telugu"),
    ("ur", "Urdu"),
)

OTHERS: tuple[tuple[str, str], ...] = (
    ("af", "Afrikaans"),
    ("am", "Amharic"),
    ("ar", "Arabic"),
    ("hy", "Armenian"),
    ("ast", "Asturian"),
    ("az", "Azerbaijani"),
    ("be", "Belarusian"),
    ("bs", "Bosnian"),
    ("bg", "Bulgarian"),
    ("my", "Burmese"),
    ("yue", "Cantonese"),
    ("ca", "Catalan"),
    ("ceb", "Cebuano"),
    ("ny", "Chichewa"),
    ("hr", "Croatian"),
    ("cs", "Czech"),
    ("da", "Danish"),
    ("nl", "Dutch"),
    ("et", "Estonian"),
    ("fil", "Filipino"),
    ("fi", "Finnish"),
    ("fr", "French"),
    ("ff", "Fula"),
    ("gl", "Galician"),
    ("lg", "Ganda"),
    ("ka", "Georgian"),
    ("de", "German"),
    ("el", "Greek"),
    ("ha", "Hausa"),
    ("he", "Hebrew"),
    ("hu", "Hungarian"),
    ("is", "Icelandic"),
    ("ig", "Igbo"),
    ("id", "Indonesian"),
    ("ga", "Irish"),
    ("it", "Italian"),
    ("ja", "Japanese"),
    ("jv", "Javanese"),
    ("kea", "Kabuverdianu"),
    ("kk", "Kazakh"),
    ("km", "Khmer"),
    ("ko", "Korean"),
    ("ku", "Kurdish"),
    ("ky", "Kyrgyz"),
    ("lo", "Lao"),
    ("lv", "Latvian"),
    ("ln", "Lingala"),
    ("lt", "Lithuanian"),
    ("luo", "Luo"),
    ("lb", "Luxembourgish"),
    ("mk", "Macedonian"),
    ("ms", "Malay"),
    ("mt", "Maltese"),
    ("zh", "Mandarin Chinese"),
    ("mi", "Maori"),
    ("mn", "Mongolian"),
    ("nso", "Northern Sotho"),
    ("no", "Norwegian"),
    ("oc", "Occitan"),
    ("ps", "Pashto"),
    ("fa", "Persian"),
    ("pl", "Polish"),
    ("pt", "Portuguese"),
    ("ro", "Romanian"),
    ("ru", "Russian"),
    ("sr", "Serbian"),
    ("sn", "Shona"),
    ("sd", "Sindhi"),
    ("sk", "Slovak"),
    ("sl", "Slovenian"),
    ("so", "Somali"),
    ("es", "Spanish"),
    ("sw", "Swahili"),
    ("sv", "Swedish"),
    ("tg", "Tajik"),
    ("th", "Thai"),
    ("tr", "Turkish"),
    ("uk", "Ukrainian"),
    ("umb", "Umbundu"),
    ("uz", "Uzbek"),
    ("vi", "Vietnamese"),
    ("cy", "Welsh"),
    ("wo", "Wolof"),
    ("xh", "Xhosa"),
    ("zu", "Zulu"),
)

SPOKEN_LANGUAGES: tuple[tuple[str, str], ...] = FEATURED + OTHERS
LABELS: dict[str, str] = dict(SPOKEN_LANGUAGES)
MIXED_LANGUAGES: tuple[str, ...] = tuple(code for code, _ in FEATURED)

_BY_LABEL = {label.casefold(): code for code, label in SPOKEN_LANGUAGES}
_ALIASES = {"oriya": "or", "orissa": "or", "bangla": "bn", "ori": "or", "hin": "hi", "ben": "bn", "eng": "en"}


def choice_of(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    key = " ".join(value.split()).casefold()
    if not key:
        return None
    if key in (AUTO, MIXED) or key in LABELS:
        return key
    if key in _BY_LABEL:
        return _BY_LABEL[key]
    if key in _ALIASES:
        return _ALIASES[key]
    short = key.replace("_", "-").split("-", 1)[0]
    return short if short in LABELS else None


def normalise(value: Any) -> str:
    return choice_of(value) or AUTO


def is_confirmed(value: Any) -> bool:
    found = choice_of(value)
    return found is not None and found != AUTO


def label(choice: str | None) -> str:
    if choice == MIXED:
        return MIXED_LABEL
    if choice in LABELS:
        return LABELS[str(choice)]
    return AUTO_LABEL


def scribe_fields(choice: str | None, *, conservative: bool = False) -> list[tuple[str, str]]:
    found = normalise(choice)
    if found == MIXED:
        return []
    if found in LABELS:
        return [("language_code", found)]
    return []


def bias(choice: str | None) -> list[str]:
    found = normalise(choice)
    if found in LABELS:
        return [LABELS[found]]
    return []


def from_metadata(metadata: Any) -> str | None:
    if not isinstance(metadata, dict):
        return None
    chosen = choice_of(metadata.get(METADATA_KEY))
    if chosen is not None:
        return chosen
    legacy = metadata.get(LEGACY_METADATA_KEY)
    if isinstance(legacy, list):
        legacy = legacy[0] if len(legacy) == 1 else None
    return choice_of(legacy)


def uncertain(probability: Any) -> bool:
    return isinstance(probability, int | float) and not isinstance(probability, bool) and probability < UNCERTAIN_BELOW


def catalogue() -> list[dict[str, Any]]:
    featured = {code for code, _ in FEATURED}
    return [{"code": code, "label": name, "featured": code in featured} for code, name in SPOKEN_LANGUAGES]
