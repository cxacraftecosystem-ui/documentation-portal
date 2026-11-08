from __future__ import annotations

import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
GRADLE = REPO / "android" / "app" / "build.gradle.kts"
PUBLISHED = REPO / "android" / "last-published.json"
README = REPO / "README.md"


def _gradle(name: str) -> str:
    found = re.findall(rf'^val {name} = "?([^"\n]+?)"?\s*$', GRADLE.read_text(encoding="utf-8"), re.MULTILINE)
    assert len(found) == 1, (name, found)
    return found[0]


def test_the_next_android_build_is_the_last_published_code_plus_one() -> None:
    published = json.loads(PUBLISHED.read_text(encoding="utf-8"))
    assert int(_gradle("appVersionCode")) == int(published["versionCode"]) + 1


def test_the_android_name_is_the_release_the_readme_names() -> None:
    badge = re.search(r"badge/Release-(\d+\.\d+\.\d+)-", README.read_text(encoding="utf-8"))
    assert badge is not None
    assert _gradle("appVersionName") == badge.group(1)
