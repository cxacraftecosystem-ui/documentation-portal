from __future__ import annotations

from typing import Any

import pytest
from botocore.exceptions import ClientError

from app.api.routes import media as routes
from app.services import s3


class _Client:
    def __init__(self, code: str | None, status_code: int = 200) -> None:
        self.code = code
        self.status_code = status_code

    def head_object(self, **_: Any) -> dict[str, Any]:
        if self.code is None:
            return {"ContentLength": 1}
        raise ClientError(
            {"Error": {"Code": self.code}, "ResponseMetadata": {"HTTPStatusCode": self.status_code}},
            "HeadObject",
        )


@pytest.mark.parametrize(
    ("code", "status_code", "expected"),
    [
        (None, 200, False),
        ("403", 403, True),
        ("404", 404, True),
        ("NoSuchKey", 404, True),
        ("SlowDown", 503, False),
    ],
)
def test_a_head_that_storage_refuses_or_cannot_find_reads_as_a_missing_file(
    monkeypatch: pytest.MonkeyPatch, code: str | None, status_code: int, expected: bool
) -> None:
    monkeypatch.setattr(s3, "_client", lambda: _Client(code, status_code))
    assert routes._object_missing("media/u/x/Devotee.jpg") is expected
