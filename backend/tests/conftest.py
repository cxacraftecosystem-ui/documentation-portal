import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

TEST_AADHAAR_FINGERPRINT_KEY = "test-only-aadhaar-fingerprint-key-not-a-secret-0123"
if not os.environ.get("AADHAAR_FINGERPRINT_KEY"):
    os.environ["AADHAAR_FINGERPRINT_KEY"] = TEST_AADHAAR_FINGERPRINT_KEY


import pytest  # noqa: E402

os.environ.setdefault("QUESTIONNAIRE_V2", "false")


@pytest.fixture(autouse=True)
def _fresh_account_credential_budget():
    from app.scale.rate_limit import reset_account_credential_budget

    reset_account_credential_budget()
    yield


@pytest.fixture(autouse=True)
def _no_report_survives_a_test():
    import sys

    def forget() -> None:
        cached = sys.modules.get("app.services.data_explorer.report_cache")
        if cached is not None:
            cached.CACHE.clear()

    forget()
    yield
    forget()


@pytest.fixture(autouse=True)
def _fresh_aadhaar_legacy_answer():
    from app.services.aadhaar_fingerprint import reset_legacy_pending

    reset_legacy_pending()
    yield


@pytest.fixture(autouse=True)
def _questionnaire_v2_off_unless_a_test_turns_it_on(monkeypatch: pytest.MonkeyPatch):
    try:
        from app.core.config import get_settings

        settings = get_settings()
    except Exception:  # noqa: BLE001
        yield
        return
    monkeypatch.setattr(settings, "questionnaire_v2", False)
    yield


@pytest.fixture(autouse=True)
def _uploads_reach_storage_unless_a_test_says_otherwise(monkeypatch: pytest.MonkeyPatch):
    try:
        from app.api.routes import media
    except Exception:  # noqa: BLE001
        yield
        return
    monkeypatch.setattr(media, "object_exists", lambda _key: True, raising=False)
    yield
