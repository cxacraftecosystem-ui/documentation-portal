"""The interface every provider implements, and the compositing both matting providers share.

ONE ABSTRACT CLASS PER CAPABILITY, not one per provider. remove.bg answers two of the three
capabilities and vectorizer.ai answers one; a provider declares what it can do by inheriting the
matching bases, and the registry's descriptor says the same thing in data so the probe can report
it without importing anything.

THE RULE THIS FILE EXISTS TO ENFORCE: no dependency import at module scope, anywhere below this
package. Pillow is imported inside :func:`compose_layers`, requests inside the hosted providers'
request methods, rembg inside the session builder. The cost of forgetting once is that every
uvicorn boot pays for a feature nobody has turned on.
"""

from abc import ABC, abstractmethod
from io import BytesIO
from typing import Any, ClassVar

from app.ai_features.errors import ProviderFailed
from app.ai_features.registry import get_descriptor
from app.ai_features.runtime import Deadline
from app.ai_features.settings import AiFeatureSettings
from app.ai_features.types import (
    Capability,
    CutoutResult,
    ImagePayload,
    ProviderDescriptor,
    SeparationResult,
    VectorResult,
)


class ImageAiProvider(ABC):
    """Common construction. Subclasses hold no state beyond settings and cached sessions."""

    provider_id: ClassVar[str] = ""

    def __init__(self, settings: AiFeatureSettings) -> None:
        self.settings = settings

    @property
    def descriptor(self) -> ProviderDescriptor:
        return get_descriptor(self.provider_id)

    def _fail(self, message: str, *, capability: Capability, **kwargs: Any) -> ProviderFailed:
        """Build a failure that already knows who raised it. Returned, not raised, to keep the
        ``raise`` visible at the call site."""
        return ProviderFailed(
            message, capability=capability, provider=self.provider_id, **kwargs
        )


class BackgroundRemovalProvider(ImageAiProvider):
    """Capability 2: subject on transparency, in one call."""

    @abstractmethod
    def remove_background(self, image: ImagePayload, deadline: Deadline) -> CutoutResult: ...


class ForegroundSeparationProvider(ImageAiProvider):
    """Capability 1: the two layers and the matte between them."""

    @abstractmethod
    def separate_foreground(self, image: ImagePayload, deadline: Deadline) -> SeparationResult: ...


class VectorisationProvider(ImageAiProvider):
    """Capability 3: raster to SVG."""

    @abstractmethod
    def vectorise(self, image: ImagePayload, deadline: Deadline) -> VectorResult: ...


def _encode_png(pillow_image: Any) -> bytes:
    buffer = BytesIO()
    # No optimise pass: it costs CPU on a burstable box to save bytes that S3 charges nothing for.
    pillow_image.save(buffer, format="PNG")
    return buffer.getvalue()


def extract_alpha(cutout_png: bytes, *, capability: Capability, provider: str) -> bytes:
    """The alpha channel of a cutout, as a standalone 8-bit greyscale PNG.

    Hosted providers return a cutout, not a matte. Rather than ask them twice — a second call is a
    second credit — the matte is recovered from the alpha they already sent.
    """
    from PIL import Image  # noqa: PLC0415 - deliberate: Pillow is an optional dependency

    try:
        with Image.open(BytesIO(cutout_png)) as image:
            image.load()
            if image.mode != "RGBA":
                image = image.convert("RGBA")
            return _encode_png(image.getchannel("A"))
    except Exception as exc:
        raise ProviderFailed(
            f"could not read the alpha channel of the cutout: {exc}",
            capability=capability,
            provider=provider,
            remediation="The provider returned something that is not an RGBA image.",
        ) from exc


def compose_layers(
    image: ImagePayload,
    matte_png: bytes,
    *,
    capability: Capability,
    provider: str,
) -> tuple[bytes, bytes, bytes, tuple[str, ...]]:
    """``(foreground, background, matte, notes)`` — the original wearing the matte, then its inverse.

    The background keeps the original pixels and goes transparent where the subject was, which is
    what makes it useful on its own: a workshop backdrop with the product lifted out of it. The
    consequence, spelled out in :class:`~app.ai_features.types.SeparationResult`, is that
    re-compositing the pair is exact only where the matte is fully opaque or fully transparent.
    """
    from PIL import Image, ImageChops  # noqa: PLC0415 - deliberate: Pillow is optional

    notes: list[str] = []
    try:
        with Image.open(BytesIO(image.data)) as opened:
            opened.load()
            base = opened.convert("RGB")
        with Image.open(BytesIO(matte_png)) as opened_matte:
            opened_matte.load()
            matte = opened_matte.convert("L")
    except Exception as exc:
        raise ProviderFailed(
            f"could not decode the image or its matte: {exc}",
            capability=capability,
            provider=provider,
        ) from exc

    if matte.size != base.size:
        # remove.bg returns whatever resolution the account's credits allow, which need not be the
        # resolution we sent. Scaling the matte back is lossy at the edge but keeps the layers
        # aligned with the original, which is the property callers depend on.
        notes.append(
            f"matte was {matte.size[0]}x{matte.size[1]}, resized to "
            f"{base.size[0]}x{base.size[1]} to match the source"
        )
        matte = matte.resize(base.size, Image.Resampling.BILINEAR)

    foreground = base.copy()
    foreground.putalpha(matte)
    background = base.copy()
    background.putalpha(ImageChops.invert(matte))
    return (
        _encode_png(foreground),
        _encode_png(background),
        _encode_png(matte),
        tuple(notes),
    )
