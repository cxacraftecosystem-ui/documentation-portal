from pathlib import PurePath
from uuid import uuid4

import boto3
from botocore.client import Config

from app.core.config import get_settings


def _client():
    settings = get_settings()
    # For a real AWS bucket outside us-east-1, presign against the *regional* endpoint. The global
    # endpoint (bucket.s3.amazonaws.com) 307-redirects to the regional host, which changes the Host
    # header the client sends and breaks the SigV4 signature -> 403 SignatureDoesNotMatch. Pinning
    # the regional endpoint (bucket.s3.<region>.amazonaws.com) keeps the signed host and the request
    # host identical. A custom endpoint (MinIO) is honoured as-is.
    endpoint = settings.aws_s3_endpoint
    # Virtual-hosted addressing only for real AWS (regional endpoint). A custom endpoint such as
    # MinIO needs path-style, so leave its addressing on boto3's default ("auto").
    s3_config: dict = {}
    if not endpoint and settings.aws_region:
        endpoint = f"https://s3.{settings.aws_region}.amazonaws.com"
        s3_config = {"addressing_style": "virtual"}
    return boto3.client(
        "s3",
        region_name=settings.aws_region,
        endpoint_url=endpoint,
        aws_access_key_id=settings.aws_access_key_id,
        aws_secret_access_key=settings.aws_secret_access_key,
        # SigV4 so presigned PUTs validate in every region.
        config=Config(signature_version="s3v4", s3=s3_config),
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


def get_object_bytes(object_key: str) -> bytes:
    settings = get_settings()
    response = _client().get_object(Bucket=settings.aws_s3_bucket, Key=object_key)
    try:
        return response["Body"].read()
    finally:
        response["Body"].close()


def delete_object(object_key: str) -> None:
    """Remove a single object. Used to clean up staged uploads that were cancelled before save."""
    settings = get_settings()
    _client().delete_object(Bucket=settings.aws_s3_bucket, Key=object_key)
