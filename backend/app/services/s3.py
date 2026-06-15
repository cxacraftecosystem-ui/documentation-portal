from pathlib import PurePath
from uuid import uuid4

import boto3

from app.core.config import get_settings


def _client():
    settings = get_settings()
    return boto3.client(
        "s3",
        region_name=settings.aws_region,
        endpoint_url=settings.aws_s3_endpoint,
        aws_access_key_id=settings.aws_access_key_id,
        aws_secret_access_key=settings.aws_secret_access_key,
    )


def safe_filename(filename: str) -> str:
    basename = PurePath(filename).name.strip().replace("\\", "-").replace("/", "-")
    cleaned = "".join(ch if ch.isalnum() or ch in {".", "-", "_"} else "-" for ch in basename)
    return cleaned or "upload.bin"


def make_object_key(user_id: str, filename: str) -> str:
    return f"media/{user_id}/{uuid4().hex}/{safe_filename(filename)}"


def public_url_for_key(object_key: str) -> str | None:
    settings = get_settings()
    if settings.aws_s3_public_base_url:
        return f"{settings.aws_s3_public_base_url.rstrip('/')}/{object_key}"
    if settings.aws_s3_endpoint:
        return f"{settings.aws_s3_endpoint.rstrip('/')}/{settings.aws_s3_bucket}/{object_key}"
    return None


def presign_put_url(object_key: str, mime_type: str) -> str:
    settings = get_settings()
    return _client().generate_presigned_url(
        ClientMethod="put_object",
        Params={
            "Bucket": settings.aws_s3_bucket,
            "Key": object_key,
            "ContentType": mime_type,
        },
        ExpiresIn=900,
        HttpMethod="PUT",
    )
