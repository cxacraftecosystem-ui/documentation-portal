"""Put the backend root on ``sys.path`` so ``from app...`` resolves however pytest was invoked.

The app is not pip-installed into the venv, so without this a bare ``pytest`` from any directory
other than ``backend/`` cannot import the package under test.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


import pytest  # noqa: E402 - after the sys.path line above, which this import does not need


@pytest.fixture(autouse=True)
def _fresh_account_credential_budget():
    """Every test starts with every account's guessing budget full. The bucket is process-wide (it
    has to be, to count guesses across requests), so without this a suite that signs in wrongly as
    one fixture account in several modules would meet a 429 that has nothing to do with the test."""
    from app.scale.rate_limit import reset_account_credential_budget

    reset_account_credential_budget()
    yield
