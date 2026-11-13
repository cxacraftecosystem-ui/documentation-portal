from __future__ import annotations

import unicodedata

import pytest

from app.core import odia_script
from app.core.odia_script import apply_to_result, has_devanagari, is_odia, to_odia, to_odia_mapped

SCRIBE_TURN = (
    "अ, तो madam, अ, आपणां केबे "
    "कामु पाइन केबे "
    "बाहरकु माने "
    "स्थानान्तरित "
    "होईछन्ति craft पाइन?"
)
ODIA_TURN = (
    "ଅ, ତୋ madam, ଅ, ଆପଣାଂ କେବେ "
    "କାମୁ ପାଇନ କେବେ "
    "ବାହରକୁ ମାନେ "
    "ସ୍ଥାନାନ୍ତରିତ "
    "ହୋଈଛନ୍ତି craft ପାଇନ?"
)


def test_the_production_turn_becomes_odia_script() -> None:
    assert SCRIBE_TURN.startswith("अ, तो madam")
    out = to_odia(SCRIBE_TURN)
    assert out == ODIA_TURN
    assert out.startswith("ଅ, ତୋ madam, ଅ, ଆପଣାଂ")
    assert not has_devanagari(out)
    assert any(0x0B00 <= ord(c) <= 0x0B7F for c in out)
    assert "madam" in out and "craft" in out and out.endswith("?")


def test_every_mapped_code_point_is_an_assigned_odia_character() -> None:
    for cp in range(0x0900, 0x0980):
        mapped = odia_script.TABLE[chr(cp)]
        for ch in mapped:
            if 0x0B00 <= ord(ch) <= 0x0B7F:
                assert ord(ch) in odia_script.ODIA_ASSIGNED, f"U+{cp:04X}"
                assert unicodedata.name(ch).startswith("ORIYA "), f"U+{cp:04X}"
            else:
                assert ch in {"।", "॥", "."}, f"U+{cp:04X} -> U+{ord(ch):04X}"
    for cp in odia_script.ODIA_ASSIGNED:
        assert unicodedata.name(chr(cp)).startswith("ORIYA ")


def test_the_parallel_blocks_map_by_offset() -> None:
    assert to_odia("कखगघ") == "କଖଗଘ"
    assert to_odia("अआइईउऊऋ") == "ଅଆଇଈଉଊଋ"
    assert to_odia("किकीकुकूकेकैकोकौ") == (
        "କିକୀକୁକୂକେକୈକୋକୌ"
    )
    assert to_odia("ँंः्ऽ") == "ଁଂଃ୍ଽ"


@pytest.mark.parametrize(
    ("source", "expected"),
    [
        ("ड़", "ଡ଼"),
        ("ढ़", "ଢ଼"),
        ("ड़", "ଡ଼"),
        ("ढ़", "ଢ଼"),
        ("य़", "ୟ"),
        ("य़", "ୟ"),
        ("क़ख़ग़ज़फ़", "କଖଗଜଫ"),
        ("ज़", "ଜ"),
        ("ॐ", "ଓଁ"),
        ("। ॥", "। ॥"),
        ("०१२३४५६७८९", "୦୧୨୩୪୫୬୭୮୯"),
        ("कॅ", "କେ"),
        ("डॉक्टर", "ଡକ୍ଟର"),
        ("ऑफिस", "ଅଫିସ"),
        ("ऍ", "ଏ"),
        ("क्‍ष", "କ୍‍ଷ"),
        ("क्‌ष", "କ୍‌ଷ"),
        ("व", "ବ"),
        ("य", "ୟ"),
        ("ऱऴऩ", "ରଳନ"),
        ("ॕॖॗ", "େୁୂ"),
        ("॰ॱॲ", ".ଅ"),
        ("ॳॴॵॶॷ", "ଏଏଔଉଊ"),
        ("॒॑॓॔ॽ", ""),
    ],
)
def test_the_exceptions(source: str, expected: str) -> None:
    assert to_odia(source) == expected


def test_other_scripts_and_punctuation_stay() -> None:
    for text in ("Hello, world! 123 (craft) - ok?", "କାମ", "কাজ", "கை", "", "।"):
        assert to_odia(text) == text


def test_it_is_idempotent() -> None:
    once = to_odia(SCRIBE_TURN + " ॐ ड़ डॉ")
    assert to_odia(once) == once


def test_positions_follow_length_changes() -> None:
    text = "ॐ डॉ ab"
    out, positions = to_odia_mapped(text)
    assert out == "ଓଁ ଡ ab"
    assert len(positions) == len(text) + 1
    assert positions[text.index("a")] == out.index("a")
    assert positions[-1] == len(out)
    words = [{"text": "ॐ", "offset": 0}, {"text": "डॉ", "offset": 2}, {"text": "ab", "offset": 5}]
    moved = odia_script.remap_words(words, positions)
    assert [w["offset"] for w in moved] == [0, 3, 5]
    assert [out[w["offset"] : w["offset"] + len(w["text"])] for w in moved] == [w["text"] for w in moved]
    assert odia_script.remap_spans([[2, 4], [5, 7]], positions) == [[3, 4], [5, 7]]


@pytest.mark.parametrize("code", ["or", "ori", "OR", "or-IN", "ory", " ori "])
def test_odia_codes(code: str) -> None:
    assert is_odia(code)


@pytest.mark.parametrize("code", [None, "", "hi", "en", "orx", "auto", "mixed", 3])
def test_other_codes(code: object) -> None:
    assert not is_odia(code)


def _result(language: str | None, **extra: object) -> dict[str, object]:
    return {
        "status": "COMPLETED",
        "text": SCRIBE_TURN,
        "formattedTranscript": "Transcript\n\n" + SCRIBE_TURN,
        "languageCode": language,
        "segments": [{"text": SCRIBE_TURN, "startMs": 0, "endMs": 1}],
        "words": [{"text": "तो", "start": 0.0, "end": 0.1}],
        **extra,
    }


def test_results_convert_only_for_odia() -> None:
    odia = _result("or")
    assert apply_to_result(odia)
    assert odia["text"] == ODIA_TURN
    assert odia["formattedTranscript"] == "Transcript\n\n" + ODIA_TURN
    assert odia["segments"][0]["text"] == ODIA_TURN
    assert odia["words"][0]["text"] == "ତୋ"
    assert not apply_to_result(odia)

    hinted = _result(None)
    assert apply_to_result(hinted, "or") and hinted["text"] == ODIA_TURN
    spoken = _result("hi", spokenLanguage="ori")
    assert apply_to_result(spoken) and spoken["text"] == ODIA_TURN

    for hindi in (_result("hi"), _result(None), _result("hi", spokenLanguage="hi"), _result("mr")):
        assert not apply_to_result(hindi, "hi")
        assert hindi["text"] == SCRIBE_TURN and hindi["words"][0]["text"] == "तो"
