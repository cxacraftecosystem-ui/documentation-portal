"""The shared questionnaire package is byte-identical in both portals (UNIFY_2026-10-21.md, U1).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal. Each repository carries
``shared/questionnaire.sha256``, written by designer-portal's ``scripts/sync_questionnaire.py``;
both manifests are the same file, so a change made in one portal and not copied to the other fails
here, in that portal's CI. Digests are over LF-normalised text (Windows checkouts are CRLF).
"""

from __future__ import annotations

import ast
import hashlib
import os
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "shared" / "questionnaire.sha256"
PACKAGE = REPO / "backend" / "app" / "questionnaire"
SYNC = "python scripts/sync_questionnaire.py --to ../documentation-portal"

#: The only module of the package that may import FastAPI (the error door).
FASTAPI_DOOR = {"errors.py"}
#: Portal code the package must never import; it reaches it through ``ports`` instead.
FORBIDDEN_PREFIXES = ("app.core", "app.api", "app.services", "app.schemas", "app.main", "app.worker")


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def _entries() -> list[tuple[str, str]]:
    lines = MANIFEST.read_text(encoding="utf-8").replace("\r\n", "\n").splitlines()
    return [tuple(line.split("  ", 1)) for line in lines if line.strip()]  # type: ignore[misc]


def test_every_shared_file_matches_the_manifest() -> None:
    missing = [rel for _, rel in _entries() if not (REPO / rel).is_file()]
    assert not missing, f"listed in shared/questionnaire.sha256 but absent: {missing}. Run `{SYNC}` from designer-portal."
    drifted = [rel for digest, rel in _entries() if _digest(REPO / rel) != digest]
    assert not drifted, (
        f"{drifted} differ from shared/questionnaire.sha256. Edit designer-portal's copy and run `{SYNC}` there."
    )


def test_every_package_file_and_shared_contract_is_listed() -> None:
    listed = {rel for _, rel in _entries()}
    package = {p.relative_to(REPO).as_posix() for p in PACKAGE.rglob("*.py") if "__pycache__" not in p.parts}
    # questionnaire-form-contract.json is each portal's own capture form (it differs, on purpose).
    contracts = {p.relative_to(REPO).as_posix() for p in (REPO / "shared").glob("questionnaire*")} - {
        "shared/questionnaire.sha256", "shared/questionnaire-form-contract.json"}
    tests = {p.relative_to(REPO).as_posix() for p in (REPO / "backend" / "tests").glob("test_questionnaire_*parity.py")}
    unlisted = sorted((package | contracts | tests) - listed)
    assert not unlisted, f"shared files missing from shared/questionnaire.sha256: {unlisted}"


def _imports(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    names: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.extend(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            names.append(node.module)
            if node.module == "app":
                names.extend(f"app.{alias.name}" for alias in node.names)
    return names


def test_the_package_imports_no_portal_code() -> None:
    """No ``app.core``/``app.api``/``app.services`` import anywhere in the package (``ports`` reaches
    the portal by name at call time), and FastAPI only through ``errors.py``."""
    for path in sorted(PACKAGE.rglob("*.py")):
        for name in _imports(path):
            assert not name.startswith(FORBIDDEN_PREFIXES), f"{path.name} imports portal code: {name}"
            if name == "fastapi" or name.startswith("fastapi."):
                assert path.name in FASTAPI_DOOR, f"{path.name} imports {name}; only errors.py may"


def test_each_portal_serves_the_ports() -> None:
    """``app/services/questionnaire_ports.py`` provides every name the package asks for, and each
    portal module is either served or refused with ``PortUnavailable`` naming the stream."""
    import importlib

    from app.questionnaire import ports

    impl = importlib.import_module(ports.PORTS_MODULE)
    missing = [name for name in ports.REQUIRED if not hasattr(impl, name)]
    assert not missing, f"{ports.PORTS_MODULE} lacks {missing}"
    assert impl.PORTAL in ("designer-portal", "documentation-portal")
    for name in ports.MODULES:
        try:
            module = impl.module(name)
        except ports.PortUnavailable as refused:
            assert "U1" in str(refused) or "U2" in str(refused), f"{name}: say which stream wires it ({refused})"
        else:
            assert module is not None


def test_a_port_patch_reaches_the_portal_module() -> None:
    """A monkeypatch made through a port patches the portal's own module, so tests written against
    either name see the same thing."""
    from app.questionnaire import ports

    try:
        target = ports.impl().module("naming")
    except ports.PortUnavailable:
        pytest.skip("this portal does not serve naming through the port yet")
    sentinel = object()
    ports.naming._questionnaire_port_probe = sentinel
    try:
        assert target._questionnaire_port_probe is sentinel
    finally:
        del ports.naming._questionnaire_port_probe
    assert not hasattr(target, "_questionnaire_port_probe")


def _sibling() -> Path | None:
    """The other portal's checkout: $CROSS_PORTAL_PEER_REPO or $SIBLING_REPO (CI clones the other
    repository's branch of the same name, else its main, there), else the checkout beside this one."""
    here_is_designer = (REPO / "android" / "app" / "src" / "main" / "java" / "com" / "designprototype").is_dir()
    other = "documentation-portal" if here_is_designer else "designer-portal"
    for candidate in (
        os.environ.get("CROSS_PORTAL_PEER_REPO", "").strip(),
        os.environ.get("SIBLING_REPO", "").strip(),
        str(REPO.parent / other),
    ):
        if candidate and (Path(candidate) / "shared" / "questionnaire.sha256").is_file():
            return Path(candidate)
    return None


def test_the_manifest_is_the_other_portals() -> None:
    """The two manifests are one file, so a shared change copied to one portal only fails here."""
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no checkout of the other portal with shared/questionnaire.sha256 beside this one")
    theirs = (sibling / "shared" / "questionnaire.sha256").read_bytes().replace(b"\r\n", b"\n")
    assert MANIFEST.read_bytes().replace(b"\r\n", b"\n") == theirs, (
        f"shared/questionnaire.sha256 differs between the portals; run `{SYNC}` from designer-portal."
    )
