"""Turning "whatever the caller had" into a validated :class:`ImagePayload`, using no dependencies.

WHY HEADER PARSING BY HAND. The bounds this module enforces exist to protect a 1 GiB box from a
single hostile or careless upload, so they have to be enforced BEFORE anything decodes pixels —
which rules out asking Pillow for the dimensions, since opening the file is the expensive part we
are trying to gate. Reading width and height out of a PNG/JPEG/WebP header is forty lines of
struct unpacking and costs nothing, and it means input validation still works when none of the
optional dependencies are installed. That is also what lets the hosted background-removal path
run with no new packages at all.

Only PNG, JPEG and WebP are accepted. That is the intersection of what every provider here takes
and what the media pipeline actually stores for photographs; a TIFF or a HEIC arriving means
something upstream changed and should be looked at rather than silently converted.
"""

import struct
from os import PathLike
from pathlib import Path

from app.ai_features.errors import ImageTooLarge, UnsupportedImageType
from app.ai_features.settings import AiFeatureSettings
from app.ai_features.types import Capability, ImagePayload

#: Anything the caller may hand to a capability function.
ImageSource = bytes | bytearray | memoryview | str | PathLike

_PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
# Start-of-frame markers carrying the frame size. The gaps (0xC4 DHT, 0xC8 JPG, 0xCC DAC) are not
# frame headers, so walking past them matters.
_JPEG_SOF_MARKERS = frozenset(
    {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}
)


def _png_dimensions(data: bytes) -> tuple[int, int] | None:
    if len(data) < 24 or not data.startswith(_PNG_MAGIC) or data[12:16] != b"IHDR":
        return None
    width, height = struct.unpack(">II", data[16:24])
    return int(width), int(height)


def _jpeg_dimensions(data: bytes) -> tuple[int, int] | None:
    if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8:
        return None
    index = 2
    limit = len(data)
    while index + 9 < limit:
        if data[index] != 0xFF:
            index += 1  # resync: some cameras pad between segments
            continue
        marker = data[index + 1]
        if marker == 0xFF:
            index += 1  # fill byte
            continue
        if marker == 0xD8 or marker == 0x01 or 0xD0 <= marker <= 0xD7:
            index += 2  # standalone markers carry no length
            continue
        if marker == 0xD9 or marker == 0xDA:
            return None  # end of image / start of scan: no frame header was found
        segment_length = struct.unpack(">H", data[index + 2:index + 4])[0]
        if segment_length < 2:
            return None
        if marker in _JPEG_SOF_MARKERS:
            height, width = struct.unpack(">HH", data[index + 5:index + 9])
            return int(width), int(height)
        index += 2 + segment_length
    return None


def _webp_dimensions(data: bytes) -> tuple[int, int] | None:
    if len(data) < 30 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        return None
    chunk = data[12:16]
    if chunk == b"VP8X":  # extended format: 24-bit canvas size, stored minus one
        width = int.from_bytes(data[24:27], "little") + 1
        height = int.from_bytes(data[27:30], "little") + 1
        return width, height
    if chunk == b"VP8 ":  # lossy: 3-byte frame tag, 3-byte sync code, then two 14-bit values
        if data[23:26] != b"\x9d\x01\x2a":
            return None
        width = int.from_bytes(data[26:28], "little") & 0x3FFF
        height = int.from_bytes(data[28:30], "little") & 0x3FFF
        return width, height
    if chunk == b"VP8L":  # lossless: one signature byte then 14 bits each, stored minus one
        if data[20] != 0x2F:
            return None
        bits = int.from_bytes(data[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    return None


def sniff(data: bytes) -> tuple[str, str, int, int] | None:
    """``(mime_type, extension, width, height)`` for a supported image, or None.

    Content, never filename: a ``.png`` that is actually a JPEG would be sent to a provider with
    the wrong content type and rejected there, which is a far more confusing failure than here.
    """
    for reader, mime, extension in (
        (_png_dimensions, "image/png", "png"),
        (_jpeg_dimensions, "image/jpeg", "jpg"),
        (_webp_dimensions, "image/webp", "webp"),
    ):
        size = reader(data)
        if size is not None:
            return mime, extension, size[0], size[1]
    return None


def load_source(
    source: ImageSource,
    settings: AiFeatureSettings,
    *,
    capability: Capability | None = None,
) -> ImagePayload:
    """Validate and normalise an input image.

    A path is stat-ed before it is read, so an oversized file is refused without ever occupying
    memory — the difference between a rejected request and an OOM kill on a 1 GiB box.
    """
    origin = "bytes"
    if isinstance(source, (str, PathLike)):
        path = Path(source)
        try:
            stat = path.stat()
        except OSError as exc:
            raise UnsupportedImageType(
                f"cannot read image at {path}: {exc}",
                capability=capability,
                remediation="Pass bytes, or a path that exists and is readable by the API user.",
            ) from exc
        if not path.is_file():
            raise UnsupportedImageType(f"{path} is not a file", capability=capability)
        if stat.st_size > settings.max_image_bytes:
            raise ImageTooLarge(
                f"{path.name} is {stat.st_size} bytes, over the "
                f"{settings.max_image_bytes}-byte limit",
                capability=capability,
                remediation="Downscale the image, or raise AI_FEATURES_MAX_IMAGE_BYTES.",
            )
        data = path.read_bytes()
        origin = path.name
    elif isinstance(source, (bytes, bytearray, memoryview)):
        data = bytes(source)
    else:
        raise UnsupportedImageType(
            f"expected bytes or a path, got {type(source).__name__}", capability=capability
        )

    if not data:
        raise UnsupportedImageType("image is empty", capability=capability)
    if len(data) > settings.max_image_bytes:
        raise ImageTooLarge(
            f"image is {len(data)} bytes, over the {settings.max_image_bytes}-byte limit",
            capability=capability,
            remediation="Downscale the image, or raise AI_FEATURES_MAX_IMAGE_BYTES.",
        )

    sniffed = sniff(data)
    if sniffed is None:
        raise UnsupportedImageType(
            "not a readable PNG, JPEG or WebP",
            capability=capability,
            remediation="Convert the image to PNG or JPEG before submitting it.",
        )
    mime_type, extension, width, height = sniffed
    if width <= 0 or height <= 0:
        raise UnsupportedImageType("image header reports a zero dimension", capability=capability)
    if width * height > settings.max_image_pixels:
        raise ImageTooLarge(
            f"image is {width}x{height} ({width * height} pixels), over the "
            f"{settings.max_image_pixels}-pixel limit",
            capability=capability,
            remediation="Downscale the image, or raise AI_FEATURES_MAX_IMAGE_PIXELS.",
        )
    return ImagePayload(
        data=data,
        mime_type=mime_type,
        extension=extension,
        width=width,
        height=height,
        origin=origin,
    )
