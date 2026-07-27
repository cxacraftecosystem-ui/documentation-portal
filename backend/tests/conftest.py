"""Put the backend root on ``sys.path`` so ``from app...`` resolves however pytest was invoked.

The app is not pip-installed into the venv, so without this a bare ``pytest`` from any directory
other than ``backend/`` cannot import the package under test.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
