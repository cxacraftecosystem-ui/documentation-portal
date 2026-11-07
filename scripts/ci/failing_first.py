from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

KEYS = {"id", "tests", "revert", "to"}


class ManifestError(Exception):
    pass


@dataclass
class Outcome:
    rc: int
    tests: int
    failures: int
    errors: int
    skipped: int
    report: bool

    @property
    def executed(self) -> int:
        return self.tests - self.skipped

    def describe(self) -> str:
        if not self.report:
            return f"exit {self.rc}, no junit report"
        return (f"exit {self.rc}, {self.tests} collected, {self.failures} failed, "
                f"{self.errors} errors, {self.skipped} skipped")

    def proves_failure(self) -> bool:
        return self.report and self.rc == 1 and self.failures >= 1 and self.errors == 0

    def proves_pass(self) -> bool:
        return self.report and self.rc == 0 and self.failures == 0 and self.errors == 0 and self.executed >= 1


def _relative(value: object, where: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ManifestError(f"{where} must be a non-empty string")
    path = PurePosixPath(value)
    if "\\" in value or path.is_absolute() or ".." in path.parts or value.startswith("-"):
        raise ManifestError(f"{where} {value!r} must be a relative POSIX path inside the repository")
    return value


def load_manifest(path: Path) -> list[dict]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ManifestError(f"{path} does not exist") from exc
    except json.JSONDecodeError as exc:
        raise ManifestError(f"{path} is not valid JSON: {exc}") from exc
    if not isinstance(data, list):
        raise ManifestError(f"{path} must hold a JSON array")
    ids: list[str] = []
    for position, entry in enumerate(data):
        where = f"entry {position}"
        if not isinstance(entry, dict) or set(entry) != KEYS:
            raise ManifestError(f"{where} must be an object with exactly the keys {sorted(KEYS)}")
        if not isinstance(entry["id"], str) or not entry["id"].strip():
            raise ManifestError(f"{where} needs a non-empty string id")
        where = f"entry {entry['id']!r}"
        if not isinstance(entry["to"], str) or not entry["to"].strip() or entry["to"].startswith("-"):
            raise ManifestError(f"{where} needs a commit or ref in 'to'")
        for key in ("tests", "revert"):
            if not isinstance(entry[key], list) or not entry[key]:
                raise ManifestError(f"{where} needs a non-empty '{key}' list")
        tests = [_relative(test, f"{where} test") for test in entry["tests"]]
        revert = [_relative(item, f"{where} revert path") for item in entry["revert"]]
        if len(set(revert)) != len(revert):
            raise ManifestError(f"{where} lists a revert path twice")
        test_files = {test.split("::", 1)[0] for test in tests}
        for item in revert:
            if any(item.endswith("/" + name) or item == name for name in test_files):
                raise ManifestError(f"{where} reverts its own test file {item}; revert the code under test only")
        ids.append(entry["id"])
    if len(set(ids)) != len(ids):
        raise ManifestError("entry ids must be unique")
    if ids != sorted(ids):
        raise ManifestError("entries must be sorted by id")
    return data


def git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, check=False)
    if check and result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result


def exists_at(repo: Path, rev: str, path: str) -> bool:
    return git(repo, "cat-file", "-e", f"{rev}:{path}", check=False).returncode == 0


def put_back(repo: Path, rev: str, paths: list[str]) -> None:
    present = [path for path in paths if exists_at(repo, rev, path)]
    absent = [path for path in paths if path not in present]
    if present:
        git(repo, "checkout", rev, "--", *present)
    for path in absent:
        git(repo, "rm", "-q", "--cached", "--ignore-unmatch", "--", path)
        target = repo / path
        if target.is_file() or target.is_symlink():
            target.unlink()


def run_pytest(pytest_cmd: list[str], workdir: Path, tests: list[str]) -> Outcome:
    with tempfile.TemporaryDirectory() as scratch:
        report = Path(scratch) / "report.xml"
        command = [*pytest_cmd, "-p", "no:cacheprovider", "-rfE", f"--junitxml={report}", *tests]
        print("$ " + " ".join(command), flush=True)
        env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
        rc = subprocess.run(command, cwd=workdir, env=env, check=False).returncode
        if not report.is_file():
            return Outcome(rc, 0, 0, 0, 0, False)
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            return Outcome(rc, 0, 0, 0, 0, False)
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        for suite in suites:
            for key in total:
                total[key] += int(suite.get(key, "0") or 0)
        return Outcome(rc, total["tests"], total["failures"], total["errors"], total["skipped"], True)


def prove(entry: dict, repo: Path, workdir: Path, pytest_cmd: list[str]) -> list[str]:
    problems: list[str] = []
    name = entry["id"]
    to = entry["to"]
    paths = entry["revert"]
    resolved = git(repo, "rev-parse", "--verify", "--quiet", f"{to}^{{commit}}", check=False)
    if resolved.returncode != 0:
        return [f"{name}: 'to' {to!r} is not a commit in this checkout (is the history fetched in full?)"]
    target = resolved.stdout.strip()
    for path in paths:
        if not exists_at(repo, "HEAD", path) and not exists_at(repo, target, path):
            problems.append(f"{name}: {path} exists neither at HEAD nor at {to}")
    if problems:
        return problems
    dirty = git(repo, "status", "--porcelain", "--", *paths).stdout.strip()
    if dirty:
        return [f"{name}: the revert paths have local changes, refusing to touch them:\n{dirty}"]
    if git(repo, "diff", "--quiet", target, "HEAD", "--", *paths, check=False).returncode == 0:
        return [f"{name}: the revert paths are identical at {to} and HEAD, so reverting them proves nothing"]

    print(f"::group::{name}: tests with {', '.join(paths)} reverted to {to}", flush=True)
    try:
        put_back(repo, target, paths)
        reverted = run_pytest(pytest_cmd, workdir, entry["tests"])
    finally:
        put_back(repo, "HEAD", paths)
        print("::endgroup::", flush=True)
    print(f"{name}: with the fix reverted: {reverted.describe()}", flush=True)
    if not reverted.proves_failure():
        if reverted.report and reverted.errors:
            problems.append(f"{name}: with the fix reverted the tests did not fail cleanly ({reverted.describe()}); "
                            "a collection or import error is not proof. Revert only the decision code.")
        else:
            problems.append(f"{name}: with the fix reverted the tests did not fail ({reverted.describe()}), "
                            "so they do not prove the fix")

    print(f"::group::{name}: tests at HEAD", flush=True)
    try:
        fixed = run_pytest(pytest_cmd, workdir, entry["tests"])
    finally:
        print("::endgroup::", flush=True)
    print(f"{name}: at HEAD: {fixed.describe()}", flush=True)
    if not fixed.proves_pass():
        problems.append(f"{name}: at HEAD the tests did not pass ({fixed.describe()})")
    if git(repo, "status", "--porcelain", "--", *paths).stdout.strip():
        problems.append(f"{name}: the revert paths were not restored to HEAD")
    return problems


def run(manifest: Path, repo: Path, workdir: Path, pytest_cmd: list[str]) -> int:
    try:
        entries = load_manifest(manifest)
    except ManifestError as exc:
        print(f"::error::{manifest}: {exc}")
        return 1
    if not entries:
        print(f"{manifest} lists no failing-first proofs.")
        return 0
    rows: list[tuple[str, str]] = []
    failed = False
    for entry in entries:
        try:
            problems = prove(entry, repo, workdir, pytest_cmd)
        except RuntimeError as exc:
            problems = [f"{entry['id']}: {exc}"]
        for problem in problems:
            print(f"::error::{problem}")
        failed = failed or bool(problems)
        rows.append((entry["id"], "proven" if not problems else "NOT proven"))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("### Failing-first proofs\n\n| id | result |\n|---|---|\n")
            handle.writelines(f"| `{name}` | {verdict} |\n" for name, verdict in rows)
    for name, verdict in rows:
        print(f"{name}: {verdict}")
    return 1 if failed else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="backend/tests/failing_first.json")
    parser.add_argument("--workdir", default="backend")
    parser.add_argument("--repo", default=".")
    args = parser.parse_args(argv)
    top = git(Path(args.repo), "rev-parse", "--show-toplevel").stdout.strip()
    repo = Path(top)
    return run(repo / args.manifest, repo, repo / args.workdir, [sys.executable, "-m", "pytest"])


if __name__ == "__main__":
    raise SystemExit(main())
