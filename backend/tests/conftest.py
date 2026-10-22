"""Put the backend root on ``sys.path`` so ``from app...`` resolves however pytest was invoked.

The app is not pip-installed into the venv, so without this a bare ``pytest`` from any directory
other than ``backend/`` cannot import the package under test.
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

#: A TEST-ONLY Aadhaar fingerprint key (services/aadhaar_fingerprint.py), so every artisan write in
#: the suite works on a machine whose shell and backend/.env carry none. Never a deployed value.
TEST_AADHAAR_FINGERPRINT_KEY = "test-only-aadhaar-fingerprint-key-not-a-secret-0123"
if not os.environ.get("AADHAAR_FINGERPRINT_KEY"):
    os.environ["AADHAAR_FINGERPRINT_KEY"] = TEST_AADHAAR_FINGERPRINT_KEY


import pytest  # noqa: E402 - after the sys.path line above, which this import does not need

# QUESTIONNAIRE_V2 defaults ON since the unified switch-on (U5), and the suite was written while it
# defaulted off; every Settings this run builds starts from off (a test of a v2 path turns it on
# itself; see the fixture at the foot of this file). Set before anything here builds Settings.
os.environ.setdefault("QUESTIONNAIRE_V2", "false")


@pytest.fixture(autouse=True)
def _fresh_account_credential_budget():
    """Every test starts with every account's guessing budget full. The bucket is process-wide (it
    has to be, to count guesses across requests), so without this a suite that signs in wrongly as
    one fixture account in several modules would meet a 429 that has nothing to do with the test."""
    from app.scale.rate_limit import reset_account_credential_budget

    reset_account_credential_budget()
    yield


@pytest.fixture(autouse=True)
def _fresh_aadhaar_legacy_answer():
    """Every test starts not knowing whether legacy Aadhaar rows remain. The answer is cached per
    process and sticky once "none" (``aadhaar_fingerprint.legacy_pending``), which is right on a box
    and wrong across tests that each build their own table."""
    from app.services.aadhaar_fingerprint import reset_legacy_pending

    reset_legacy_pending()
    yield


@pytest.fixture(autouse=True)
def _questionnaire_v2_off_unless_a_test_turns_it_on(monkeypatch: pytest.MonkeyPatch):
    """QUESTIONNAIRE_V2 defaults ON since the unified switch-on (UNIFY_2026-10-21.md 5.2, U5), but the
    suite was written while it defaulted off: every test of a v2 path switches it on itself
    (``monkeypatch.setattr(get_settings(), "questionnaire_v2", True)``), and the tests of the old
    routes describe what runs while it is off, which is still the switch's other position. Each test
    starts from off, as before the switch; the default itself is asserted by the wiring tests."""
    try:
        from app.core.config import get_settings

        settings = get_settings()
    except Exception:  # noqa: BLE001 - no settings in this run: nothing to pin
        yield
        return
    monkeypatch.setattr(settings, "questionnaire_v2", False)
    yield
