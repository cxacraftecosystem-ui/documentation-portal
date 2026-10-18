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
def _merge_redirects_need_a_database(monkeypatch):
    """The merge redirect (``services/artisan_merge.resolve`` / ``merged_among``) reads
    ``ArtisanMerge`` on every artisan-naming write. A unit test that drives a route with its own
    stubbed reads has no database behind the global client, and an id it invents was never merged,
    so the redirect answers "not merged" there. A test with a connected client, or one that hands
    its own ``client``, reads the real table as before."""
    from app.core.db import db
    from app.services import artisan_merge

    real_resolve = artisan_merge.resolve
    real_merged_among = artisan_merge.merged_among

    def _offline(client):
        # Only the real client, unconnected: a test that swaps in its own fake db keeps the real path.
        return client is None and artisan_merge.db is db and not db.is_connected()

    async def resolve(artisan_id, *, client=None):
        return None if _offline(client) else await real_resolve(artisan_id, client=client)

    async def merged_among(ids, *, client=None):
        return set() if _offline(client) else await real_merged_among(ids, client=client)

    monkeypatch.setattr(artisan_merge, "resolve", resolve)
    monkeypatch.setattr(artisan_merge, "merged_among", merged_among)
    yield
