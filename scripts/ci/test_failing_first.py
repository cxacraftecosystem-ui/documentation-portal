from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import failing_first as ff

FAKE_PYTEST = textwrap.dedent("""
    import sys
    from pathlib import Path

    report = next(arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--junitxml="))
    state_file = Path("state.txt")
    state = state_file.read_text(encoding="utf-8").strip() if state_file.is_file() else "fail"
    shapes = {
        "pass": (0, 1, 0, 0, 0),
        "fail": (1, 1, 1, 0, 0),
        "error": (2, 1, 0, 1, 0),
        "skip": (0, 1, 0, 0, 1),
    }
    if state == "noreport":
        sys.exit(1)
    rc, tests, failures, errors, skipped = shapes[state]
    Path(report).write_text(
        f'<testsuites><testsuite name="pytest" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}"/></testsuites>',
        encoding="utf-8",
    )
    sys.exit(rc)
""")

HAS_PYTEST = importlib.util.find_spec("pytest") is not None


def _git(repo: Path, *args: str) -> str:
    env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
           "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"}
    result = subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, env=env, check=True)
    return result.stdout.strip()


class Repo:
    def __init__(self, root: Path):
        self.root = root
        self.backend = root / "backend"
        (self.backend / "tests").mkdir(parents=True)
        _git(root, "init", "-q")

    def write(self, files: dict[str, str | None]) -> None:
        for name, body in files.items():
            path = self.root / name
            if body is None:
                if path.exists():
                    path.unlink()
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding="utf-8")

    def commit(self, files: dict[str, str | None], message: str) -> str:
        self.write(files)
        _git(self.root, "add", "-A")
        _git(self.root, "commit", "-q", "--allow-empty", "-m", message)
        return _git(self.root, "rev-parse", "HEAD")

    def manifest(self, entries: list[dict]) -> Path:
        path = self.root / "manifest.json"
        path.write_text(json.dumps(entries), encoding="utf-8")
        return path

    def clean(self) -> bool:
        return _git(self.root, "status", "--porcelain", "--", "backend") == ""


class FakePytestRuns(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        base = Path(self._dir.name)
        self.repo = Repo(base / "repo")
        fake = base / "fake_pytest.py"
        fake.write_text(FAKE_PYTEST, encoding="utf-8")
        self.cmd = [sys.executable, str(fake)]
        self.repo.commit({"backend/tests/test_thing.py": "", "backend/keep.txt": "x"}, "base")

    def tearDown(self):
        self._dir.cleanup()

    def _run(self, entries: list[dict]) -> int:
        manifest = self.repo.manifest(entries)
        return ff.run(manifest, self.repo.root, self.repo.backend, self.cmd)

    def _entry(self, to: str, revert: list[str] | None = None, name: str = "a-proof") -> dict:
        return {"id": name, "tests": ["tests/test_thing.py::test_it"],
                "revert": revert or ["backend/state.txt"], "to": to}

    def test_a_fix_that_fails_reverted_and_passes_at_head_is_proven(self):
        before = self.repo.commit({"backend/state.txt": "fail"}, "broken")
        self.repo.commit({"backend/state.txt": "pass"}, "fixed")
        self.assertEqual(self._run([self._entry(before)]), 0)
        self.assertTrue(self.repo.clean())
        self.assertEqual((self.repo.backend / "state.txt").read_text(encoding="utf-8"), "pass")

    def test_tests_that_pass_with_the_fix_reverted_prove_nothing(self):
        before = self.repo.commit({"backend/state.txt": "pass\n"}, "already fine")
        self.repo.commit({"backend/state.txt": "pass"}, "fixed")
        self.assertEqual(self._run([self._entry(before)]), 1)
        self.assertTrue(self.repo.clean())

    def test_a_collection_error_with_the_fix_reverted_is_not_proof(self):
        before = self.repo.commit({"backend/state.txt": "error"}, "import breaks")
        self.repo.commit({"backend/state.txt": "pass"}, "fixed")
        self.assertEqual(self._run([self._entry(before)]), 1)
        self.assertTrue(self.repo.clean())

    def test_a_run_that_writes_no_report_is_not_proof(self):
        before = self.repo.commit({"backend/state.txt": "noreport"}, "crash")
        self.repo.commit({"backend/state.txt": "pass"}, "fixed")
        self.assertEqual(self._run([self._entry(before)]), 1)

    def test_tests_that_fail_at_head_fail_the_run(self):
        before = self.repo.commit({"backend/state.txt": "fail"}, "broken")
        self.repo.commit({"backend/state.txt": "fail\n"}, "still broken")
        self.assertEqual(self._run([self._entry(before)]), 1)
        self.assertTrue(self.repo.clean())

    def test_tests_that_only_skip_at_head_fail_the_run(self):
        before = self.repo.commit({"backend/state.txt": "fail"}, "broken")
        self.repo.commit({"backend/state.txt": "skip"}, "skips")
        self.assertEqual(self._run([self._entry(before)]), 1)

    def test_a_file_the_fix_added_is_removed_for_the_proof_and_restored(self):
        before = self.repo.commit({}, "nothing yet")
        self.repo.commit({"backend/state.txt": "pass"}, "fix adds the file")
        self.assertEqual(self._run([self._entry(before)]), 0)
        self.assertTrue(self.repo.clean())
        self.assertTrue((self.repo.backend / "state.txt").is_file())

    def test_identical_revert_paths_are_refused(self):
        before = self.repo.commit({"backend/state.txt": "pass"}, "same")
        self.repo.commit({"backend/other.txt": "y"}, "elsewhere")
        self.assertEqual(self._run([self._entry(before)]), 1)

    def test_an_unknown_commit_is_refused(self):
        self.repo.commit({"backend/state.txt": "pass"}, "fixed")
        self.assertEqual(self._run([self._entry("0" * 40)]), 1)

    def test_local_changes_to_a_revert_path_are_left_alone(self):
        before = self.repo.commit({"backend/state.txt": "fail"}, "broken")
        self.repo.commit({"backend/state.txt": "pass"}, "fixed")
        self.repo.write({"backend/state.txt": "work in progress"})
        self.assertEqual(self._run([self._entry(before)]), 1)
        self.assertEqual((self.repo.backend / "state.txt").read_text(encoding="utf-8"), "work in progress")

    def test_every_entry_is_checked_and_the_tree_is_restored_after_each(self):
        before = self.repo.commit({"backend/state.txt": "fail", "backend/b.txt": "1"}, "broken")
        self.repo.commit({"backend/state.txt": "pass", "backend/b.txt": "2"}, "fixed")
        entries = [self._entry(before, name="a-proof"),
                   self._entry(before, ["backend/b.txt"], name="b-not-proof")]
        self.assertEqual(self._run(entries), 1)
        self.assertTrue(self.repo.clean())

    def test_an_empty_manifest_passes(self):
        self.assertEqual(self._run([]), 0)


class ManifestRules(unittest.TestCase):
    def _load(self, data: object) -> list[dict]:
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "m.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            return ff.load_manifest(path)

    def _entry(self, **changes) -> dict:
        entry = {"id": "x", "tests": ["tests/test_x.py::test_y"], "revert": ["backend/app/x.py"], "to": "abc"}
        entry.update(changes)
        return entry

    def test_a_well_formed_manifest_loads(self):
        self.assertEqual(len(self._load([self._entry(id="a"), self._entry(id="b")])), 2)

    def test_malformed_manifests_are_refused(self):
        bad = [
            {"not": "a list"},
            [self._entry(id="b"), self._entry(id="a")],
            [self._entry(id="a"), self._entry(id="a")],
            [{**self._entry(), "extra": 1}],
            [self._entry(tests=[])],
            [self._entry(revert=[])],
            [self._entry(revert=["/abs/path.py"])],
            [self._entry(revert=["backend/../x.py"])],
            [self._entry(revert=["backend\\app\\x.py"])],
            [self._entry(revert=["backend/tests/test_x.py"])],
            [self._entry(revert=["backend/app/x.py", "backend/app/x.py"])],
            [self._entry(to="")],
            [self._entry(to="--orphan")],
            [self._entry(id="")],
        ]
        for data in bad:
            with self.subTest(data=data), self.assertRaises(ff.ManifestError):
                self._load(data)


@unittest.skipUnless(HAS_PYTEST, "pytest is not installed")
class RealPytestRuns(unittest.TestCase):
    TEST = textwrap.dedent("""
        from mod import half


        def test_half_keeps_the_fraction():
            assert half(3) == 1.5
    """)

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self._dir.name) / "repo")
        self.repo.write({"backend/pytest.ini": "[pytest]\n", "backend/conftest.py": "",
                         "backend/tests/test_mod.py": self.TEST})
        self.cmd = [sys.executable, "-m", "pytest", "-q"]

    def tearDown(self):
        self._dir.cleanup()

    def _run(self, to: str) -> int:
        manifest = self.repo.manifest([{"id": "half", "tests": ["tests/test_mod.py"],
                                        "revert": ["backend/mod.py"], "to": to}])
        return ff.run(manifest, self.repo.root, self.repo.backend, self.cmd)

    def test_an_assertion_failure_with_the_fix_reverted_is_proof(self):
        before = self.repo.commit({"backend/mod.py": "def half(x):\n    return x // 2\n"}, "broken")
        self.repo.commit({"backend/mod.py": "def half(x):\n    return x / 2\n"}, "fixed")
        self.assertEqual(self._run(before), 0)
        self.assertTrue(self.repo.clean())

    def test_an_import_error_with_the_fix_reverted_is_not_proof(self):
        before = self.repo.commit({"backend/mod.py": "def quarter(x):\n    return x / 4\n"}, "no half yet")
        self.repo.commit({"backend/mod.py": "def half(x):\n    return x / 2\n"}, "fixed")
        self.assertEqual(self._run(before), 1)
        self.assertTrue(self.repo.clean())


if __name__ == "__main__":
    unittest.main()
