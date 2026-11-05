from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Self

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import gate as gate_module
from gate import Gate, WriteRefused, allowed


class FakeResponse:
    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_: object) -> None:
        return None


class Recorder:
    def __init__(self, answers: dict[str, tuple[int, bytes]]) -> None:
        self.answers = answers
        self.seen: list[tuple[str, str, dict[str, str]]] = []

    def __call__(self, request, timeout: int) -> FakeResponse:
        path = request.full_url.split("https://api.invalid", 1)[1]
        self.seen.append((request.get_method(), path, dict(request.header_items())))
        status, body = self.answers.get(f"{request.get_method()} {path}", (404, b"{}"))
        return FakeResponse(status, body)


class GateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())

    def make(self, answers: dict[str, tuple[int, bytes]]) -> tuple[Gate, Recorder]:
        recorder = Recorder(answers)
        gate = Gate(
            "probe",
            out=self.tmp,
            api="https://api.invalid/",
            email="a@b.c",
            password="pw",
            opener=recorder,
        )
        return gate, recorder

    def test_only_reads_and_listed_posts_are_allowed(self) -> None:
        self.assertTrue(allowed("get", "/api/anything"))
        self.assertTrue(allowed("POST", "/api/auth/login?x=1"))
        for method in ("POST", "PUT", "PATCH", "DELETE"):
            self.assertFalse(allowed(method, "/api/records/1"))
        self.assertEqual(gate_module.read_only_posts(), frozenset({"/api/auth/login"}))

    def test_a_write_is_refused_before_it_is_sent(self) -> None:
        gate, recorder = self.make({})
        with self.assertRaises(WriteRefused):
            gate.request("DELETE", "/api/records/1")
        self.assertEqual(recorder.seen, [])
        self.assertEqual(gate.blocked, ["DELETE /api/records/1"])
        self.assertFalse(gate.finish())

    def test_sign_in_then_reads_carry_the_token(self) -> None:
        gate, recorder = self.make(
            {
                "POST /api/auth/login": (
                    200,
                    json.dumps({"accessToken": "t0k", "user": {"role": "X"}}).encode(),
                ),
                "GET /api/things": (200, b'{"total": 3}'),
            }
        )
        self.assertEqual(gate.sign_in()["user"]["role"], "X")
        self.assertEqual(gate.get_json("/api/things"), {"total": 3})
        self.assertEqual(recorder.seen[-1][2].get("Authorization"), "Bearer t0k")

    def test_results_record_counts_rows_and_the_verdict(self) -> None:
        gate, _ = self.make({})
        gate.count(rows=12)
        self.assertTrue(gate.expect("sheet A rows", 12, 12))
        self.assertFalse(gate.expect("sheet B rows", 4, 0))
        self.assertFalse(gate.finish())
        result = json.loads(
            (self.tmp / "probe" / "result.json").read_text(encoding="utf-8")
        )
        self.assertEqual(result["counts"], {"rows": 12})
        self.assertEqual([row["ok"] for row in result["rows"]], [True, False])
        self.assertFalse(result["ok"])

    def test_the_summary_fails_when_nothing_ran_or_a_check_failed(self) -> None:
        env = {**os.environ, "GATE_OUT": str(self.tmp / "out")}
        run = [sys.executable, str(HERE / "run_checks.py"), "--summary"]
        self.assertEqual(
            subprocess.run(run, env=env, capture_output=True, check=False).returncode, 1
        )
        passing = Gate("one", out=self.tmp / "out", api="https://api.invalid")
        passing.expect("x", 1, 1)
        passing.finish()
        self.assertEqual(
            subprocess.run(run, env=env, capture_output=True, check=False).returncode, 0
        )
        failing = Gate("two", out=self.tmp / "out", api="https://api.invalid")
        failing.expect("y", 1, 2)
        failing.finish()
        done = subprocess.run(run, env=env, capture_output=True, text=True, check=False)
        self.assertEqual(done.returncode, 1)
        self.assertIn(
            "| y | 1 | 2 |",
            (self.tmp / "out" / "summary.md").read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
