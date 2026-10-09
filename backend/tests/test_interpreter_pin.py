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


def test_both_installers_refuse_a_download_before_unpacking_it():
    # The order is the safety property: hash, compare, and only then `tar -x`. A refactor that moved
    # the unpack above the comparison would still install the right build on every good day.
    for path in (DEPLOY, USER_DATA):
        text = path.read_text(encoding="utf-8")
        compare = text.find('if [ "$got" != "$pbs_sha256" ]; then')
        refuse = text.find("REFUSED", compare)
        unpack = text.find("tar -xzf")
        where = f"{path.name}: compare at {compare}, refuse at {refuse}, unpack at {unpack}"
        assert 0 <= compare < refuse < unpack, where
        assert text.count("tar -xzf") == 1, f"{path.name} unpacks in more than one place"
