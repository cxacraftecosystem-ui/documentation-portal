from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from gate import Gate

BACKEND = Path(__file__).resolve().parents[3] / "backend"


def run(gate: Gate) -> None:
    health = gate.get_json("/health")
    want = subprocess.run(
        [sys.executable, str(BACKEND / "app" / "release.py"), str(BACKEND)],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    gate.count(
        release=health.get("release", ""), commit=os.environ.get("GATE_SHA", "")[:12]
    )
    gate.expect("/health status", "ok", health.get("status"))
    gate.expect("/health release is this commit's backend", want, health.get("release"))
    status, _ = gate.request("GET", "/health/ready")
    gate.expect("/health/ready", 200, status)
    signed_in = gate.sign_in()
    gate.count(role=(signed_in.get("user") or {}).get("role", "unknown"))
    gate.expect("signed in with a token", True, bool(gate.token))
