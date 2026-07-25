"""S3 object storage: presigned uploads, multipart uploads and object access.

Security shape of this module (the full picture, including the console-side pieces, is in
docs/SECURITY.md):

* **In transit** — every AWS endpoint built here is ``https://``, so bytes are TLS-protected on the
  browser/phone -> S3 hop as well as on the API -> S3 hop. A custom ``AWS_S3_ENDPOINT`` (MinIO) is
  honoured verbatim; a plaintext one is logged as a warning unless it is a local dev host.
* **At rest** — the multipart path requests SSE-S3 explicitly. The single-PUT presign path *cannot*
  (see ``presign_put_url``), so encryption for those objects comes from the bucket's **default
  encryption** setting, which S3 applies server-side to every object regardless of what the client
  sends. That bucket setting is the load-bearing control; the header here is belt-and-braces.
"""

import logging
from pathlib import PurePath
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import boto3
from botocore.client import Config

from app.core.config import get_settings

logger = logging.getLogger(__name__)

# Hosts where a plaintext object-storage endpoint is expected and harmless (local MinIO).
_LOCAL_ENDPOINT_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "minio", "host.docker.internal"})
_insecure_endpoint_warned = False


def _warn_if_insecure_endpoint(endpoint: str | None) -> None:
    """Log once if media bytes would travel to storage over plaintext HTTP.

    Uploads go CLIENT -> storage directly (presigned URL), so a plaintext endpoint means every
    photo, recording and transcript crosses the network unencrypted, and the presigned URL itself —
    a bearer credential for writing that object — is exposed to anyone on the path. Local MinIO on
    a loopback host is exempt.
    """
    global _insecure_endpoint_warned
    if not endpoint or _insecure_endpoint_warned:
        return
    parts = urlsplit(endpoint)
    if parts.scheme != "http":
        return
    if (parts.hostname or "").lower() in _LOCAL_ENDPOINT_HOSTS:
        return
    _insecure_endpoint_warned = True
    logger.error(
        "AWS_S3_ENDPOINT is plaintext HTTP (%s): media and presigned upload URLs are exposed in "
        "transit. Point it at an https:// endpoint.",
        endpoint,
    )


def _sse_params() -> dict[str, str]:
    """Server-side-encryption parameters for uploads the API itself starts.

    Empty when ``AWS_S3_SSE_ALGORITHM`` is blank — local MinIO without a KMS backend rejects the
    header outright, and failing an upload is worse than falling back to the storage layer's own
    at-rest behaviour in development.
    """
    algorithm = (get_settings().aws_s3_sse_algorithm or "").strip()
    return {"ServerSideEncryption": algorithm} if algorithm else {}


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
        # Dual-stack regional endpoint (s3.dualstack.<region>) so presigned PUT URLs resolve a
        # native IPv6 (AAAA) address. IPv4-only mobile data is increasingly IPv6-only (Jio/Airtel);
        # the plain s3.<region> host has no AAAA, so uploads from such phones fail to connect.
        # Dual-stack serves IPv4 too, so Wi-Fi is unaffected, and SigV4 signs the dual-stack host.
        endpoint = f"https://s3.dualstack.{settings.aws_region}.amazonaws.com"
        s3_config = {"addressing_style": "virtual"}
    _warn_if_insecure_endpoint(endpoint)
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


def _promote_dualstack(url: str, region: str | None) -> str:
    """Rewrite a regional AWS S3 host to its dual-stack form so stored media URLs resolve a native
    IPv6 address on IPv6-only mobile networks. Idempotent, and a no-op when the host isn't the
    plain regional S3 endpoint (e.g. a custom CDN/MinIO base)."""
    if not region:
        return url
    plain = f".s3.{region}.amazonaws.com"
    dual = f".s3.dualstack.{region}.amazonaws.com"
    if dual in url or plain not in url:
        return url
    return url.replace(plain, dual)


def public_url_for_key(object_key: str) -> str | None:
    settings = get_settings()
    if settings.aws_s3_public_base_url:
        base = f"{settings.aws_s3_public_base_url.rstrip('/')}/{object_key}"
    elif settings.aws_s3_endpoint:
        # Custom endpoint (MinIO/CDN) is served verbatim — no dual-stack promotion.
        return f"{settings.aws_s3_endpoint.rstrip('/')}/{settings.aws_s3_bucket}/{object_key}"
    else:
        return None
    # Promote the public base only when it points at a real AWS regional host (not a custom endpoint).
    if not settings.aws_s3_endpoint:
        base = _promote_dualstack(base, settings.aws_region)
    return base


def presign_put_url(object_key: str, mime_type: str) -> str:
    """Presigned PUT URL for a whole (small) file, valid for 15 minutes.

    **Why there is no ``ServerSideEncryption`` here, deliberately.** Adding it would put
    ``x-amz-server-side-encryption: AES256`` into the SigV4 *signed headers*, which makes the header
    mandatory for the client: every PUT that omits it fails with ``SignatureDoesNotMatch``. The web
    client and the Android client both send only the headers ``/media/presign`` hands them
    (currently ``Content-Type``), and Android builds already installed in the field can never be
    updated retroactively — so signing the header here would break all existing uploads.

    Objects uploaded this way are still encrypted at rest, by the bucket's **default encryption**
    setting: S3 applies it server-side to every object, whatever the client sends, and it needs no
    cooperation from the signature. docs/SECURITY.md has the exact bucket configuration (default
    SSE-S3 + a policy denying non-TLS requests) that a human must apply in the console.
    """
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


def create_multipart_upload(object_key: str, mime_type: str) -> str:
    """Begin an S3 multipart upload (for large files). Returns the UploadId the client uploads parts
    against; S3 stitches the parts into one object on complete, so the stored file stays whole.

    Requests server-side encryption at rest explicitly (SSE-S3 / AES-256 by default). This call is
    made by the API with its own credentials, not presigned, so the encryption header costs the
    client nothing — unlike the single-PUT path above. The parts themselves inherit the setting, so
    ``presign_upload_part`` needs no encryption header either."""
    response = _client().create_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        ContentType=mime_type,
        **_sse_params(),
    )
    return str(response["UploadId"])


def presign_upload_part(object_key: str, upload_id: str, part_number: int) -> str:
    """Presigned PUT URL for one part, so the (large) bytes go straight to S3, never via the API."""
    return _client().generate_presigned_url(
        ClientMethod="upload_part",
        Params={
            "Bucket": get_settings().aws_s3_bucket,
            "Key": object_key,
            "UploadId": upload_id,
            "PartNumber": part_number,
        },
        ExpiresIn=3600,
        HttpMethod="PUT",
    )


def complete_multipart_upload(object_key: str, upload_id: str, parts: list[dict[str, Any]]) -> None:
    """Finalise the multipart upload — S3 assembles the parts into a single object."""
    _client().complete_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        UploadId=upload_id,
        MultipartUpload={"Parts": parts},
    )


def abort_multipart_upload(object_key: str, upload_id: str) -> None:
    """Discard an interrupted multipart upload so its uploaded parts don't linger and incur storage."""
    _client().abort_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        UploadId=upload_id,
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
