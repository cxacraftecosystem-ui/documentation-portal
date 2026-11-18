from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "shared" / "field-voices.sha256"
PACKAGE = REPO / "backend" / "app" / "services" / "field_voices"
FRAGMENT = REPO / "shared" / "field-voices.prisma"
SCHEMA = REPO / "backend" / "prisma" / "schema.prisma"
ADAPTER = REPO / "backend" / "app" / "services" / "field_voices_portal.py"
ADAPTER_INTERFACE = (
    "PORTAL",
    "survivors",
    "extra_refusals",
    "designer_profiles",
    "never_media",
    "designer_photos",
    "design_workshops",
    "designers",
    "media_design_workshops",
    "stage_rows_gone",
    "stage_media",
    "stage_images",
)


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def _entries() -> list[tuple[str, str]]:
    lines = MANIFEST.read_text(encoding="utf-8").replace("\r\n", "\n").splitlines()
    return [tuple(line.split("  ", 1)) for line in lines if line.strip()]  # type: ignore[misc]


def _models(text: str) -> dict[str, str]:
    found = re.finditer(r"^model (\w+) \{\n(.*?)^\}", text.replace("\r\n", "\n"), re.MULTILINE | re.DOTALL)
    return {m.group(1): m.group(2) for m in found}


def test_every_shared_file_matches_the_manifest() -> None:
    drifted = [rel for digest, rel in _entries() if not (REPO / rel).is_file() or _digest(REPO / rel) != digest]
    assert not drifted, (
        f"{drifted} differ from shared/field-voices.sha256. Edit designer-portal's copy and run "
        "`python scripts/sync_field_voices.py --to ../documentation-portal` there."
    )


def test_every_package_file_is_listed() -> None:
    listed = {rel for _, rel in _entries()}
    package = {
        path.relative_to(REPO).as_posix()
        for path in PACKAGE.rglob("*.py")
        if "__pycache__" not in path.parts
    }
    assert package <= listed, f"unlisted shared files: {sorted(package - listed)}"


def test_the_schema_holds_the_shared_models_word_for_word() -> None:
    shared = _models(FRAGMENT.read_text(encoding="utf-8"))
    schema = _models(SCHEMA.read_text(encoding="utf-8"))
    assert set(shared) == {"FieldVoiceItem", "FieldVoiceRefresh", "FieldVoiceCopyRelease"}
    for name, body in shared.items():
        assert schema.get(name) == body, f"backend/prisma/schema.prisma's {name} differs from shared/field-voices.prisma"


def test_the_consent_purpose_enum_has_publication() -> None:
    block = re.search(r"enum ConsentPurpose \{(.*?)\}", SCHEMA.read_text(encoding="utf-8"), re.DOTALL)
    assert block is not None and "PUBLICATION" in block.group(1).split()


def test_the_portal_adapter_offers_the_whole_interface() -> None:
    text = ADAPTER.read_text(encoding="utf-8")
    missing = [
        name for name in ADAPTER_INTERFACE
        if not re.search(rf"^(?:async def {name}\(|def {name}\(|{name} = )", text, re.MULTILINE)
    ]
    assert not missing, f"backend/app/services/field_voices_portal.py lacks {missing}"


def test_the_package_reaches_the_portal_only_through_its_adapter() -> None:
    allowed = {
        "app.services",
        "app.services.field_voices",
        "app.services.field_voices.items",
        "app.services.field_voices.ledger",
        "app.services.field_voices.sources",
        "app.services.field_voices.stages",
        "app.transcription",
        "app.transcription.transcript",
        "app.core.db",
        "app.questionnaire",
    }
    for path in PACKAGE.rglob("*.py"):
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if stripped.startswith(("from app", "import app")):
                module = stripped.split()[1]
                assert module in allowed, f"{path.name} imports {module}"


def _sibling() -> Path | None:
    here_is_designer = (REPO / "android" / "app" / "src" / "main" / "java" / "com" / "designprototype").is_dir()
    other = "documentation-portal" if here_is_designer else "designer-portal"
    for candidate in (
        os.environ.get("CROSS_PORTAL_PEER_REPO", "").strip(),
        os.environ.get("SIBLING_REPO", "").strip(),
        str(REPO.parent / other),
    ):
        if candidate and (Path(candidate) / "shared" / "field-voices.sha256").is_file():
            return Path(candidate)
    return None


def test_the_manifest_is_the_other_portals() -> None:
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no checkout of the other portal beside this one")
    theirs = (sibling / "shared" / "field-voices.sha256").read_bytes().replace(b"\r\n", b"\n")
    assert MANIFEST.read_bytes().replace(b"\r\n", b"\n") == theirs, (
        "shared/field-voices.sha256 differs between the portals; run "
        "`python scripts/sync_field_voices.py --to ../documentation-portal` from designer-portal."
    )
