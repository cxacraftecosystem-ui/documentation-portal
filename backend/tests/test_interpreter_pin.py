"""The EC2 box's interpreter is pinned in three files that must move together.

Since 2026-10-09 the API runs upstream CPython from a pinned python-build-standalone build, the
exact release CI tests on. The pin lives in deploy-backend.yml's build step (every deploy installs
it when it is missing), in infra/terraform/user_data.sh (a new box installs it on first boot), and
as `python-version` in checks.yml (what the suite runs on). Nothing else connects them: a bump made
in one file and not the others would quietly put production on a build CI never ran, or have a
fresh box install one interpreter and its first deploy another. These tests read the three files
as text, so they need no network and import nothing from the app.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / ".github" / "workflows" / "deploy-backend.yml"
USER_DATA = ROOT / "infra" / "terraform" / "user_data.sh"
CHECKS = ROOT / ".github" / "workflows" / "checks.yml"
KEYS = ("cpython", "pbs_release", "pbs_url", "pbs_sha256")


def _pins(path: Path) -> dict[str, str]:
    text = path.read_text(encoding="utf-8")
    pins = {}
    for key in KEYS:
        found = re.findall(rf"^[ \t]*{key}=(\S+)[ \t]*$", text, re.MULTILINE)
        assert len(found) == 1, f"{path.name}: `{key}=` must be set exactly once, found {found}"
        pins[key] = found[0]
    return pins


def test_the_deploy_and_user_data_pin_the_same_build():
    assert _pins(DEPLOY) == _pins(USER_DATA)


def test_the_pin_names_one_upstream_build_and_its_checksum():
    pins = _pins(DEPLOY)
    assert re.fullmatch(r"3\.\d+\.\d+", pins["cpython"]), pins["cpython"]
    assert re.fullmatch(r"\d{8}", pins["pbs_release"]), pins["pbs_release"]
    assert re.fullmatch(r"[0-9a-f]{64}", pins["pbs_sha256"]), pins["pbs_sha256"]
    # The URL is the release asset the version and release tag name, and nothing else: the full
    # `install_only` x86_64 linux-gnu build, fetched over https from the project's own releases.
    version, release = pins["cpython"], pins["pbs_release"]
    assert pins["pbs_url"] == (
        "https://github.com/astral-sh/python-build-standalone/releases/download/"
        f"{release}/cpython-{version}%2B{release}-x86_64-unknown-linux-gnu-install_only.tar.gz"
    )


def test_ci_tests_the_version_the_box_runs():
    text = CHECKS.read_text(encoding="utf-8")
    versions = re.findall(r'^\s*python-version:\s*"?([0-9][0-9.]*)"?\s*$', text, re.MULTILINE)
    box = _pins(DEPLOY)["cpython"]
    assert versions == [box], f"checks.yml tests {versions}, the box runs {box}: move them together"


#: The one line in each installer that unpacks the interpreter download onto the box.
BOX_UNPACK = 'tar -xzf "$stage/cpython.tar.gz"'
#: Since 2026-10-19 the deploy also unpacks the same build on the GitHub runner, at the same path, to
#: build the release venv there (deploy-backend.yml, "Build the release venv ...").
RUNNER_UNPACK = 'tar -xzf "$RUNNER_TEMP/cpython.tar.gz"'


def test_both_installers_refuse_a_download_before_unpacking_it():
    for path in (DEPLOY, USER_DATA):
        text = path.read_text(encoding="utf-8")
        unpack = text.find(BOX_UNPACK)
        compare = text.rfind('if [ "$got" != "$pbs_sha256" ]; then', 0, max(unpack, 0))
        refuse = text.find("REFUSED", compare)
        where = f"{path.name}: compare at {compare}, refuse at {refuse}, unpack at {unpack}"
        assert 0 <= compare < refuse < unpack, where
        assert text.count(BOX_UNPACK) == 1, f"{path.name} unpacks the interpreter in more than one place"


def test_the_runner_reads_the_one_pin_and_refuses_a_download_before_unpacking_it():
    text = DEPLOY.read_text(encoding="utf-8")
    step = text[text.index("- name: Build the release venv and its Prisma client on the runner") :]
    step = step[: step.index("\n      - name: ")]
    # No second copy of the pin: the runner reads the box step's lines (test above holds them once).
    assert "eval \"$(sed -nE 's/^[[:space:]]*(cpython|pbs_release|pbs_url|pbs_sha256)=" in step
    compare = step.find('if [ "$got" != "$pbs_sha256" ]; then')
    refuse = step.find("REFUSED", compare)
    unpack = step.find(RUNNER_UNPACK)
    assert 0 <= compare < refuse < unpack, f"runner: compare at {compare}, refuse at {refuse}, unpack at {unpack}"
    assert text.count(RUNNER_UNPACK) == 1


def test_both_installers_compile_the_standard_library_before_the_build_takes_its_name():
    stdlib_compile = "-I -m compileall"
    for path in (DEPLOY, USER_DATA):
        text = path.read_text(encoding="utf-8")
        unpack = text.find(BOX_UNPACK)
        compile_all = text.find(stdlib_compile)
        rename = text.find('mv -T "$stage/python" "$prefix"')
        where = f"{path.name}: unpack at {unpack}, compile at {compile_all}, rename at {rename}"
        assert 0 <= unpack < compile_all < rename, where
        assert text.count(stdlib_compile) == 1, f"{path.name} compiles the standard library in more than one place"
