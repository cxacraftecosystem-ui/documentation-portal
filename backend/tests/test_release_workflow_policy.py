from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

REPO = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO / ".github" / "workflows"
PIN_SCRIPT = REPO / "scripts" / "ci" / "pin_ssh_host.sh"
MAIN_REF = "github.ref == 'refs/heads/main'"


def _load(name: str) -> dict:
    path = WORKFLOWS / name
    if not path.exists():
        pytest.skip(f"{name} is not in this repository")
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _text(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def _env_name(job: dict) -> str:
    env = job.get("environment")
    if isinstance(env, dict):
        return str(env.get("name") or "")
    return str(env or "")


def _flat(value: object) -> str:
    return " ".join(str(value or "").split())


def test_backend_deploy_runs_only_from_main_behind_checks_and_a_protected_environment() -> None:
    wf = _load("deploy-backend.yml")
    jobs = wf["jobs"]
    deploy = jobs["deploy"]
    assert _env_name(deploy) == "backend-production"
    condition = _flat(deploy["if"])
    assert MAIN_REF in condition
    assert "needs.wait-for-checks.result == 'success'" in condition
    assert "'skipped'" not in condition
    gate = _flat(jobs["wait-for-checks"]["if"])
    assert "workflow_dispatch" not in gate


def test_no_workflow_trusts_an_unpinned_ssh_host_key() -> None:
    for path in sorted(WORKFLOWS.glob("*.yml")):
        text = path.read_text(encoding="utf-8")
        assert "ssh-keyscan" not in text, path.name
        assert "StrictHostKeyChecking=no" not in text, path.name
        assert "StrictHostKeyChecking no" not in text, path.name


def test_backend_deploy_pins_the_host_key_from_a_secret() -> None:
    text = _text("deploy-backend.yml")
    assert "secrets.EC2_SSH_HOST_KEYS" in text
    assert "scripts/ci/pin_ssh_host.sh" in text


def _pin(tmp_path: Path, keys: str) -> subprocess.CompletedProcess:
    if not PIN_SCRIPT.exists():
        pytest.fail("scripts/ci/pin_ssh_host.sh is missing")
    bash = shutil.which("bash")
    if not bash:
        pytest.skip("bash is not available")
    env = {
        "PATH": os.environ.get("PATH", ""),
        "HOME": str(tmp_path),
        "HOST": "198.51.100.7",
        "HOST_KEYS": keys,
        "SSH_DIR": str(tmp_path / "ssh"),
    }
    return subprocess.run([bash, str(PIN_SCRIPT)], env=env, capture_output=True, text=True, check=False)


def test_pin_script_refuses_without_a_pinned_key(tmp_path: Path) -> None:
    result = _pin(tmp_path, "")
    assert result.returncode != 0
    result = _pin(tmp_path, "not a key at all")
    assert result.returncode != 0


def test_pin_script_writes_a_strict_alias_bound_known_hosts(tmp_path: Path) -> None:
    keys = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDummyDummyDummyDummyDummyDummyDummyDummy root@box
"
    result = _pin(tmp_path, keys)
    assert result.returncode == 0, result.stdout + result.stderr
    config = (tmp_path / "ssh" / "config").read_text(encoding="utf-8")
    assert config.startswith("Host 198.51.100.7
")
    assert "StrictHostKeyChecking yes" in config
    assert "HostKeyAlias deploy-target" in config
    known = (tmp_path / "ssh" / "known_hosts_pinned").read_text(encoding="utf-8")
    assert known.strip() == "deploy-target ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDummyDummyDummyDummyDummyDummyDummyDummy"


def test_android_publish_runs_only_for_main_commits_in_a_protected_environment() -> None:
    wf = _load("publish-android.yml")
    jobs = wf["jobs"]
    publish = jobs["publish"]
    assert _env_name(publish) == "android-release"
    needs = publish.get("needs")
    needs = [needs] if isinstance(needs, str) else list(needs or [])
    assert "provenance" in needs
    provenance = jobs["provenance"]
    blob = yaml.safe_dump(provenance)
    assert "merge-base --is-ancestor" in blob
    assert "refs/heads/main" in blob
    assert "secrets." not in blob.replace("secrets.GITHUB_TOKEN", "")


def test_release_gate_credentials_only_reach_main() -> None:
    wf = _load("release-gate.yml")
    gate = wf["jobs"]["gate"]
    assert _env_name(gate) == "android-release"
    assert MAIN_REF in _flat(gate.get("if"))


def test_frontend_deploy_dispatch_is_bound_to_main() -> None:
    wf = _load("deploy-frontend.yml")
    assert MAIN_REF in _flat(wf["jobs"]["deploy"]["if"])


def test_recover_backend_is_bound_to_main_and_the_backend_environment() -> None:
    path = WORKFLOWS / "recover-backend.yml"
    if not path.exists():
        pytest.skip("recover-backend.yml is not in this repository")
    wf = yaml.safe_load(path.read_text(encoding="utf-8"))
    for name, job in wf["jobs"].items():
        if "secrets." in yaml.safe_dump(job).replace("secrets.GITHUB_TOKEN", ""):
            assert _env_name(job) == "backend-production", name
            assert MAIN_REF in _flat(job.get("if")), name
