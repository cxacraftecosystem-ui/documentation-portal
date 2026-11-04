import re
import subprocess
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))

import strip_comments as sc

EXPRESSION = re.compile(r"\$\{\{.*?\}\}", re.S)
MARKER = re.compile(r"<<(-?)\s*(?:'(\w+)'|\"(\w+)\"|\\(\w+))")


def shell_runs(doc):
    if not isinstance(doc, dict):
        return
    top = ((doc.get("defaults") or {}).get("run") or {}).get("shell")
    for job in (doc.get("jobs") or {}).values():
        if not isinstance(job, dict):
            continue
        default = ((job.get("defaults") or {}).get("run") or {}).get("shell") or top
        windows = "windows" in str(job.get("runs-on", "")).lower()
        for step in job.get("steps") or []:
            run = step.get("run") if isinstance(step, dict) else None
            if not isinstance(run, str):
                continue
            shell = step.get("shell") or default
            if shell is None and windows:
                continue
            if shell is not None and not sc.is_posix_shell(str(shell)):
                continue
            yield run


def shell_heredocs(script):
    lines = script.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        i += 1
        m = MARKER.search(line)
        if not m or line.lstrip().startswith("#"):
            continue
        mode = sc.heredoc_mode(line[: m.start()], line[m.end():])
        delim = m.group(2) or m.group(3) or m.group(4)
        body = []
        while i < len(lines):
            current = lines[i]
            i += 1
            if (current.lstrip("\t") if m.group(1) else current).strip() == delim:
                break
            body.append(current)
        if mode == "cat" and i < len(lines) and sc.piped_to_shell(lines[i]):
            mode = "shell"
        if mode != "shell":
            continue
        text = "\n".join(body) + "\n"
        yield text
        yield from shell_heredocs(text)


def parses(text):
    done = subprocess.run(["bash", "-n"], input=text.encode("utf-8"), capture_output=True)
    return done.returncode == 0, done.stderr.decode("utf-8", "replace").strip()


def main(argv):
    checked = 0
    failures = []
    for folder in argv:
        for path in sorted(Path(folder).glob("*.y*ml")):
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
            for n, run in enumerate(shell_runs(doc)):
                script = EXPRESSION.sub("x", run)
                for k, text in enumerate([script, *shell_heredocs(script)]):
                    checked += 1
                    ok, err = parses(text)
                    if not ok:
                        failures.append(f"{path} run {n} part {k}: {err}")
    for failure in failures:
        print(failure, file=sys.stderr)
    print(f"bash -n over {checked} workflow script(s), {len(failures)} failed", file=sys.stderr)
    return 1 if failures or not checked else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
