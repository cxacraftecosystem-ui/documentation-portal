"""The release row keeps the versionCode the publisher sent (no database).

Since 2026-10-21 the version NAME restarted at 0.0.1 while the CODE keeps rising, so the server must
not re-derive the code from the name: 0.0.1 would become code 1, below every installed copy, and no
phone would ever be offered the update.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import app_release
from app.schemas.app_release import AppReleasePublishRequest


class _Releases:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, data: dict[str, Any]) -> dict[str, Any]:
        self.created.append(data)
        return data


@pytest.fixture()
def releases(monkeypatch: pytest.MonkeyPatch) -> _Releases:
    store = _Releases()
    monkeypatch.setattr(app_release, "db", SimpleNamespace(apprelease=store))
    monkeypatch.setattr(app_release, "public_url_for_key", lambda key: f"https://cdn.example/{key}")
    return store


@pytest.mark.parametrize(("name", "code"), [("0.0.1", 28), ("0.0.1", 23), ("0.0.2", 29), ("1.2.3", 7)])
def test_the_version_code_is_stored_as_sent(releases: _Releases, name: str, code: int) -> None:
    body = AppReleasePublishRequest(versionCode=code, versionName=name, objectKey="apk/app.apk")
    asyncio.run(app_release.publish_release(body, current_user=SimpleNamespace(id="admin")))
    assert releases.created[-1]["versionCode"] == code
    assert releases.created[-1]["versionName"] == name


def test_the_name_no_longer_derives_a_code() -> None:
    assert not hasattr(app_release, "_derive_version_code")


def test_the_download_name_carries_the_reset_version() -> None:
    name = app_release._download_filename(SimpleNamespace(versionName="0.0.1", versionCode=28))
    assert name.endswith("_Android-App_Release-Build_v0.0.1.apk")
