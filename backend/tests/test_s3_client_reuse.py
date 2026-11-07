from __future__ import annotations

import threading
from types import SimpleNamespace

import pytest

from app.services import s3


@pytest.fixture
def settings(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    values = SimpleNamespace(
        aws_s3_endpoint=None,
        aws_region="ap-south-1",
        aws_access_key_id="AKIAEXAMPLE",
        aws_secret_access_key="example-secret",
    )
    monkeypatch.setattr(s3, "get_settings", lambda: values)
    s3._build_client.cache_clear()
    yield values
    s3._build_client.cache_clear()


def test_every_storage_call_reuses_one_client(settings):
    first = s3._client()
    assert all(s3._client() is first for _ in range(50))


def test_worker_threads_share_the_same_client(settings):
    seen: list[object] = []
    threads = [threading.Thread(target=lambda: seen.append(s3._client())) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert len({id(client) for client in seen}) == 1


def test_changed_credentials_get_their_own_client(settings):
    first = s3._client()
    settings.aws_access_key_id = "AKIAOTHER"
    assert s3._client() is not first
