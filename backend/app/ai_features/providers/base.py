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
    provider_id: ClassVar[str] = ""

    def __init__(self, settings: AiFeatureSettings) -> None:
        self.settings = settings

    @property
    def descriptor(self) -> ProviderDescriptor:
        return get_descriptor(self.provider_id)

    def _fail(self, message: str, *, capability: Capability, **kwargs: Any) -> ProviderFailed:
        return ProviderFailed(
            message, capability=capability, provider=self.provider_id, **kwargs
        )


class BackgroundRemovalProvider(ImageAiProvider):
    @abstractmethod
    def remove_background(self, image: ImagePayload, deadline: Deadline) -> CutoutResult: ...


class ForegroundSeparationProvider(ImageAiProvider):
    @abstractmethod
    def separate_foreground(self, image: ImagePayload, deadline: Deadline) -> SeparationResult: ...


class VectorisationProvider(ImageAiProvider):
    @abstractmethod
    def vectorise(self, image: ImagePayload, deadline: Deadline) -> VectorResult: ...


def _encode_png(pillow_image: Any) -> bytes:
    buffer = BytesIO()
    pillow_image.save(buffer, format="PNG")
    return buffer.getvalue()


def extract_alpha(cutout_png: bytes, *, capability: Capability, provider: str) -> bytes:
    from PIL import Image  # noqa: PLC0415

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
    from PIL import Image, ImageChops  # noqa: PLC0415

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
