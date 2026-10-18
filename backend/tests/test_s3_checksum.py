"""A single PUT presigned with ChecksumSHA256 refuses other bytes (DESIGN test 31).

Runs against moto's in-process S3 when moto is installed (it is not in the deployed lock; install
it into a throwaway venv to run this). The signing half, which needs no S3, always runs: the
checksum is signed into the URL, so a client cannot drop or change it.
"""

from __future__ import annotations

import base64
import hashlib
from urllib.parse import parse_qs, urlsplit

import pytest


def _settings(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.core.config import get_settings

    s = get_settings()
    for key, value in (("aws_s3_bucket", "qv2-test"), ("aws_region", "us-east-1"), ("aws_s3_endpoint", None),
                       ("aws_access_key_id", "test"), ("aws_secret_access_key", "test")):
        monkeypatch.setattr(s, key, value, raising=False)


def test_the_checksum_is_signed_into_the_url(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import s3

    _settings(monkeypatch)
    digest = base64.b64encode(hashlib.sha256(b"take one").digest()).decode()
    url = s3.presign_put_url_checksum("media/u/0123/FieldRepository_Interview_Recording.m4a", "audio/mp4", digest)
    query = parse_qs(urlsplit(url).query)
    signed = query.get("X-Amz-SignedHeaders", [""])[0]
    assert "x-amz-checksum-sha256" in signed or query.get("x-amz-checksum-sha256") == [digest]


def test_a_mismatched_checksum_put_is_rejected_by_s3(monkeypatch: pytest.MonkeyPatch) -> None:
    moto = pytest.importorskip("moto")
    requests = pytest.importorskip("requests")
    from app.services import s3

    _settings(monkeypatch)
    with moto.mock_aws():
        import boto3

        boto3.client("s3", region_name="us-east-1").create_bucket(Bucket="qv2-test")
        monkeypatch.setattr(s3, "_client", lambda: boto3.client("s3", region_name="us-east-1"))
        digest = base64.b64encode(hashlib.sha256(b"take one").digest()).decode()
        url = s3.presign_put_url_checksum("media/u/0123/clip.m4a", "audio/mp4", digest)
        headers = {"Content-Type": "audio/mp4", "x-amz-checksum-sha256": digest}
        assert requests.put(url, data=b"take one", headers=headers).status_code == 200
        assert requests.put(url, data=b"other bytes", headers=headers).status_code >= 400
