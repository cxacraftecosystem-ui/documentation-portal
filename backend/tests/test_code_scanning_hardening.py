from __future__ import annotations

import logging
from types import SimpleNamespace

from app.scale import rate_limit
from app.services import two_factor
from app.services.datareport import writer
from app.transcription.transcript import render

CONTROL = "".join(chr(c) for c in (*range(0x09), 0x0B, 0x0C, *range(0x0E, 0x20)))


def test_a_bad_proxy_entry_is_counted_not_echoed(caplog) -> None:
    with caplog.at_level(logging.WARNING):
        networks = rate_limit._networks("10.0.0.0/8, s3cr3t-value, ::1/128, also-bad")
    assert [str(n) for n in networks] == ["10.0.0.0/8", "::1/128"]
    text = " ".join(record.getMessage() for record in caplog.records)
    assert "s3cr3t-value" not in text and "also-bad" not in text
    assert "2 TRUSTED_PROXY_CIDRS entries" in text


def test_good_proxy_entries_log_nothing(caplog) -> None:
    with caplog.at_level(logging.WARNING):
        rate_limit._networks("127.0.0.1/32,::1/128")
    assert not [r for r in caplog.records if "TRUSTED_PROXY_CIDRS" in r.getMessage()]


def test_two_factor_logs_read_only_the_user_id() -> None:
    assert two_factor._user_id({"id": "u-1", "twoFactorSecret": "S"}) == "u-1"
    assert two_factor._user_id(SimpleNamespace(id="u-2", passwordHash="h")) == "u-2"
    assert two_factor._user_id({}) == "-"


def test_spreadsheet_cells_drop_xml_illegal_characters() -> None:
    illegal = CONTROL + "".join(chr(c) for c in (0xD800, 0xDFFF, 0xFFFE, 0xFFFF))
    kept = chr(0xFFFD) + chr(0x1F600)
    raw = "a" + chr(9) + "b" + chr(10) + "c" + chr(13) + "d" + illegal + kept + "e"
    assert writer.clean(raw) == "a" + chr(9) + "b" + chr(10) + "c" + chr(13) + "d" + kept + "e"


def test_transcript_docx_text_drops_control_characters() -> None:
    assert render._x(f"a\tb\n{CONTROL}<c>") == "a\tb\n&lt;c&gt;"
