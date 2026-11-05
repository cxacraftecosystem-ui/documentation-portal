from __future__ import annotations

import importlib.util
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from gate import Gate, out_dir


def selected(name: str) -> bool:
    only = os.environ.get("GATE_ONLY", "").split()
    return not only or name in only


def run_python_checks() -> bool:
    ok = True
    for path in sorted((HERE / "checks").glob("*.py")):
        name = path.stem
        if name.startswith("_") or not selected(name):
            continue
        gate = Gate(name)
        try:
            spec = importlib.util.spec_from_file_location(
                f"release_gate_check_{name}", path
            )
            assert spec and spec.loader
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            module.run(gate)
        except Exception as exc:
            gate.error = f"{type(exc).__name__}: {exc}"
        passed = gate.finish()
        print(f"{'ok' if passed else 'FAILED'} - {name}")
        ok = ok and passed
    return ok


def _cell(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def summarise(out: Path) -> bool:
    results = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(out.glob("*/result.json"))
    ]
    lines = [
        f"## Release gate: {os.environ.get('GATE_PORTAL', '')} {os.environ.get('GATE_SHA', '')[:12]}",
        "",
    ]
    if not results:
        lines.append("No check ran.")
    lines += ["| Check | Kind | Result | Counts |", "|---|---|---|---|"]
    for result in results:
        counts = ", ".join(f"{key} {value}" for key, value in result["counts"].items())
        lines.append(
            f"| {_cell(result['check'])} | {result['kind']} | {'pass' if result['ok'] else 'FAIL'} | {_cell(counts)} |"
        )
    for result in results:
        if result["ok"]:
            continue
        lines += ["", f"### {_cell(result['check'])}", ""]
        if result["error"]:
            lines.append(f"Error: {_cell(result['error'])}")
        for blocked in result["blocked"]:
            lines.append(f"- Write blocked: `{_cell(blocked)}`")
        failing = [row for row in result["rows"] if not row["ok"]]
        if failing:
            lines += ["", "| Item | Expected | Actual |", "|---|---|---|"]
            lines += [
                f"| {_cell(r['item'])} | {_cell(r['expected'])} | {_cell(r['actual'])} |"
                for r in failing
            ]
    text = "\n".join(lines) + "\n"
    (out / "summary.md").write_text(text, encoding="utf-8")
    (out / "summary.json").write_text(
        json.dumps(results, indent=2) + "\n", encoding="utf-8"
    )
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(text)
    print(text)
    return bool(results) and all(result["ok"] for result in results)


def main(argv: list[str]) -> int:
    out = out_dir()
    out.mkdir(parents=True, exist_ok=True)
    if "--summary" in argv:
        return 0 if summarise(out) else 1
    return 0 if run_python_checks() else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
