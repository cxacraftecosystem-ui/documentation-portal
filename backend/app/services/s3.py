import contextlib
import logging
import os
import threading
from dataclasses import dataclass
from functools import lru_cache
from pathlib import PurePath
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

from app.core.config import get_settings
from app.services.naming import ascii_leaf, content_disposition
from app.transcription import workdir

logger = logging.getLogger(__name__)

_LOCAL_ENDPOINT_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "minio", "host.docker.internal"})
_insecure_endpoint_warned = False


def _warn_if_insecure_endpoint(endpoint: str | None) -> None:
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
    algorithm = (get_settings().aws_s3_sse_algorithm or "").strip()
    return {"ServerSideEncryption": algorithm} if algorithm else {}


_CLIENT_LOCK = threading.Lock()


@lru_cache(maxsize=4)
def _build_client(
    region: str | None,
    endpoint: str | None,
    access_key: str | None,
    secret_key: str | None,
    addressing_style: str | None,
):
    import boto3
    from botocore.client import Config

    return boto3.client(
        "s3",
        region_name=region,
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        config=Config(
            signature_version="s3v4",
            s3={} if addressing_style is None else {"addressing_style": addressing_style},
        ),
    )


def _client():
    settings = get_settings()
    endpoint = settings.aws_s3_endpoint
    addressing_style: str | None = None
    if not endpoint and settings.aws_region:
        endpoint = f"https://s3.dualstack.{settings.aws_region}.amazonaws.com"
        addressing_style = "virtual"
    _warn_if_insecure_endpoint(endpoint)
    with _CLIENT_LOCK:
        return _build_client(
            settings.aws_region,
            endpoint,
            settings.aws_access_key_id,
            settings.aws_secret_access_key,
            addressing_style,
        )


def safe_filename(filename: str) -> str:
    return ascii_leaf(PurePath(str(filename).replace("\\", "/")).name)


def make_object_key(user_id: str, filename: str) -> str:
    return f"media/{user_id}/{uuid4().hex}/{safe_filename(filename)}"


def _promote_dualstack(url: str, region: str | None) -> str:
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
        return f"{settings.aws_s3_endpoint.rstrip('/')}/{settings.aws_s3_bucket}/{object_key}"
    else:
        return None
    if not settings.aws_s3_endpoint:
        base = _promote_dualstack(base, settings.aws_region)
    return base


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


def presign_get_url(
    object_key: str,
    *,
    filename: str,
    mime_type: str,
    expires_in: int = 900,
    disposition: str = "attachment",
) -> str:
    return presign_get_url_aged(
        object_key,
        filename=filename,
        mime_type=mime_type,
        expires_in=expires_in,
        disposition=disposition,
    )[0]


def presign_get_url_aged(
    object_key: str,
    *,
    filename: str,
    mime_type: str,
    expires_in: int = 900,
    disposition: str = "attachment",
) -> tuple[str, int]:
    from app.services.data_explorer import signing

    settings = get_settings()
    kind = "inline" if disposition == "inline" else "attachment"
    key = (
        object_key,
        settings.aws_s3_bucket,
        settings.aws_s3_endpoint,
        settings.aws_region,
        settings.aws_access_key_id,
        filename,
        mime_type,
        kind,
        int(expires_in),
    )

    def sign() -> str:
        return _client().generate_presigned_url(
            ClientMethod="get_object",
            Params={
                "Bucket": settings.aws_s3_bucket,
                "Key": object_key,
                "ResponseContentType": mime_type,
                "ResponseContentDisposition": content_disposition(filename, inline=kind == "inline"),
            },
            ExpiresIn=expires_in,
            HttpMethod="GET",
        )

    return signing.cached(key, int(expires_in), sign)

def create_multipart_upload(object_key: str, mime_type: str) -> str:
    response = _client().create_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        ContentType=mime_type,
        **_sse_params(),
    )
    return str(response["UploadId"])


def presign_upload_part(object_key: str, upload_id: str, part_number: int) -> str:
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
    _client().complete_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        UploadId=upload_id,
        MultipartUpload={"Parts": parts},
    )


def abort_multipart_upload(object_key: str, upload_id: str) -> None:
    _client().abort_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        UploadId=upload_id,
    )


def put_object_bytes(object_key: str, data: bytes, *, mime_type: str) -> None:
    settings = get_settings()
    _client().put_object(
        Bucket=settings.aws_s3_bucket,
        Key=object_key,
        Body=data,
        ContentType=mime_type,
        **_sse_params(),
    )


def get_object_bytes(object_key: str) -> bytes:
    settings = get_settings()
    response = _client().get_object(Bucket=settings.aws_s3_bucket, Key=object_key)
    try:
        return response["Body"].read()
    finally:
        response["Body"].close()



@dataclass(frozen=True, slots=True)
class ObjectHead:
    size_bytes: int
    mime_type: str | None = None


class ObjectTooLarge(Exception):
    def __init__(self, object_key: str, size_bytes: int, limit_bytes: int) -> None:
        self.object_key = object_key
        self.size_bytes = size_bytes
        self.limit_bytes = limit_bytes
        super().__init__(f"{object_key} is {size_bytes} bytes, over the {limit_bytes}-byte limit")


def head_object(object_key: str) -> ObjectHead | None:
    settings = get_settings()
    try:
        response = _client().head_object(Bucket=settings.aws_s3_bucket, Key=object_key)
        return ObjectHead(int(response.get("ContentLength") or 0), response.get("ContentType") or None)
    except Exception:  # noqa: BLE001
        return None


class _BoundedWriter:
    def __init__(self, handle: Any, object_key: str, limit: int) -> None:
        self._handle = handle
        self._object_key = object_key
        self._limit = limit
        self._lock = threading.Lock()

    def write(self, data: Any) -> int:
        with self._lock:
            reach = self._handle.tell() + len(data)
            if reach > self._limit:
                raise ObjectTooLarge(self._object_key, reach, self._limit)
            return self._handle.write(data)

    def seek(self, *args: Any, **kwargs: Any) -> int:
        with self._lock:
            return self._handle.seek(*args, **kwargs)

    def tell(self) -> int:
        with self._lock:
            return self._handle.tell()

    def seekable(self) -> bool:
        return self._handle.seekable()

    def flush(self) -> None:
        self._handle.flush()

    def __getattr__(self, name: str) -> Any:
        return getattr(self._handle, name)


def download_to_temp(object_key: str, *, suffix: str = "", max_bytes: int | None = None) -> str:
    settings = get_settings()
    if max_bytes is not None:
        head = head_object(object_key)
        if head is not None and head.size_bytes > max_bytes:
            raise ObjectTooLarge(object_key, head.size_bytes, max_bytes)
    handle = workdir.named_file(prefix="s3obj-", suffix=suffix)
    path = handle.name
    try:
        target: Any = handle if max_bytes is None else _BoundedWriter(handle, object_key, max_bytes)
        _client().download_fileobj(settings.aws_s3_bucket, object_key, target)
        handle.flush()
        handle.close()
    except BaseException:
        with contextlib.suppress(Exception):
            handle.close()
        discard_temp(path)
        raise
    return path


async def download_to_temp_async(object_key: str, *, suffix: str = "", max_bytes: int | None = None) -> str:
    return await workdir.owned(download_to_temp, object_key, suffix=suffix, max_bytes=max_bytes)


def discard_temp(path: str | None) -> None:
    if not path:
        return
    try:
        os.unlink(path)
    except FileNotFoundError:
        return
    except OSError as exc:
        logger.warning("Could not remove the temporary object file %s: %s", path, exc)


def delete_object(object_key: str) -> None:
    settings = get_settings()
    _client().delete_object(Bucket=settings.aws_s3_bucket, Key=object_key)


def presign_put_url_checksum(object_key: str, mime_type: str, checksum_b64: str | None, expires_in: int = 900) -> str:
    params: dict[str, Any] = {"Bucket": get_settings().aws_s3_bucket, "Key": object_key, "ContentType": mime_type}
    if checksum_b64:
        params["ChecksumSHA256"] = checksum_b64
    return _client().generate_presigned_url(ClientMethod="put_object", Params=params, ExpiresIn=expires_in, HttpMethod="PUT")


class NoSuchUpload(Exception):
    pass


def list_uploaded_parts(object_key: str, upload_id: str) -> list[dict[str, Any]]:
    from botocore.exceptions import ClientError

    parts: list[dict[str, Any]] = []
    marker = 0
    client = _client()
    while True:
        try:
            response = client.list_parts(
                Bucket=get_settings().aws_s3_bucket, Key=object_key, UploadId=upload_id, PartNumberMarker=marker
            )
        except ClientError as exc:
            if str(exc.response.get("Error", {}).get("Code")) in ("NoSuchUpload", "404"):
                raise NoSuchUpload(upload_id) from exc
            raise
        parts.extend({"partNumber": int(p["PartNumber"]), "etag": str(p["ETag"])} for p in response.get("Parts", []))
        if not response.get("IsTruncated"):
            return parts
        marker = int(response.get("NextPartNumberMarker") or 0)


def stream_sha256(object_key: str, chunk_size: int = 1024 * 1024) -> tuple[str, int]:
    import hashlib

    digest = hashlib.sha256()
    size = 0
    response = _client().get_object(Bucket=get_settings().aws_s3_bucket, Key=object_key)
    body = response["Body"]
    try:
        for chunk in iter(lambda: body.read(chunk_size), b""):
            digest.update(chunk)
            size += len(chunk)
    finally:
        body.close()
    return digest.hexdigest(), size


def object_exists(object_key: str) -> bool:
    from botocore.exceptions import ClientError

    try:
        _client().head_object(Bucket=get_settings().aws_s3_bucket, Key=object_key)
        return True
    except ClientError:
        return False
