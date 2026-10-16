"""A formatted answer reaches an export as its words, never as its stored JSON (audit DC-A10)."""

import json

from app.services.record_fields import cell, flatten_rich_text


def _doc(*blocks: dict) -> str:
    return json.dumps({"blocks": list(blocks)})


def test_paragraphs_lists_and_tables_flatten_like_the_clients_to_plain() -> None:
    stored = _doc(
        {"kind": "HEADING", "spans": [{"text": "Dyeing"}]},
        {"kind": "PARAGRAPH", "spans": [{"text": "Indigo, "}, {"text": "twice", "marks": ["BOLD"]}]},
        {"kind": "ORDERED_ITEM", "spans": [{"text": "Soak"}]},
        {"kind": "ORDERED_ITEM", "spans": [{"text": "Dry"}]},
        {"kind": "BULLET_ITEM", "spans": [{"text": "Wear gloves"}], "level": 1},
        {"kind": "TABLE", "spans": [], "rows": [[[{"text": "Vat"}], [{"text": "Hours"}]], [[{"text": "A"}], [{"text": "3"}]]]},
        {"kind": "IMAGE", "spans": [{"text": "The vat"}], "media": "m1"},
        {"kind": "IMAGE", "spans": [], "media": "m2"},
    )
    assert cell(stored) == "Dyeing\nIndigo, twice\n1. Soak\n2. Dry\n  • Wear gloves\nVat | Hours\nA | 3\nThe vat"


def test_plain_text_and_brace_led_prose_are_left_alone() -> None:
    assert cell("  plain answer ") == "plain answer"
    assert cell('{"note": "not a document"}') == '{"note": "not a document"}'
    assert cell('{"blocks": broken') == '{"blocks": broken'
    assert flatten_rich_text("{ just a brace") is None
    assert cell(None) == ""
    assert cell(3) == "3"
