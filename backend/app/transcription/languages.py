from __future__ import annotations

STATE_LANGUAGES: dict[str, tuple[str, ...]] = {
    "andhra pradesh": ("Telugu",),
    "arunachal pradesh": ("Hindi",),
    "assam": ("Assamese", "Bengali"),
    "bihar": ("Hindi",),
    "chhattisgarh": ("Hindi",),
    "delhi": ("Hindi",),
    "goa": ("Konkani", "Marathi"),
    "gujarat": ("Gujarati",),
    "haryana": ("Hindi",),
    "himachal pradesh": ("Hindi",),
    "jammu and kashmir": ("Urdu", "Hindi"),
    "jharkhand": ("Hindi",),
    "karnataka": ("Kannada",),
    "kerala": ("Malayalam",),
    "ladakh": ("Hindi",),
    "madhya pradesh": ("Hindi",),
    "maharashtra": ("Marathi",),
    "odisha": ("Odia",),
    "punjab": ("Punjabi",),
    "rajasthan": ("Hindi",),
    "tamil nadu": ("Tamil",),
    "telangana": ("Telugu",),
    "tripura": ("Bengali",),
    "uttar pradesh": ("Hindi",),
    "uttarakhand": ("Hindi",),
    "west bengal": ("Bengali",),
}


def bias_for_state(state: str | None) -> list[str]:
    key = " ".join(str(state or "").replace("&", "and").split()).casefold()
    if key == "orissa":
        key = "odisha"
    found = STATE_LANGUAGES.get(key)
    if not found:
        return []
    return [*found, "English"]
