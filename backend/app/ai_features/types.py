from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Literal


class Capability(StrEnum):
    BACKGROUND_REMOVAL = "background_removal"
    FOREGROUND_SEPARATION = "foreground_separation"
    VECTORISATION = "vectorisation"
    TRANSCRIPT_INSIGHTS = "transcript_insights"


ResourceBasis = Literal["VENDOR_STATED", "ESTIMATED", "MEASURED"]

ProviderKind = Literal["local", "hosted"]


@dataclass(frozen=True)
class ResourceProfile:
    basis: ResourceBasis
    model_download_mb: float | None
    peak_ram_mb: float | None
    latency: str
    money: str
    notes: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "basis": self.basis,
            "modelDownloadMb": self.model_download_mb,
            "peakRamMb": self.peak_ram_mb,
            "latency": self.latency,
            "money": self.money,
            "notes": self.notes,
        }


@dataclass(frozen=True)
class ProviderDescriptor:
    id: str
    label: str
    kind: ProviderKind
    capabilities: frozenset[Capability]
    required_modules: tuple[str, ...]
    required_settings: tuple[str, ...]
    resources: ResourceProfile
    summary: str
    implementation: str = ""
    extra_modules: tuple[tuple[Capability, tuple[str, ...]], ...] = ()

    def modules_for(self, capability: Capability) -> tuple[str, ...]:
        extra = next((mods for cap, mods in self.extra_modules if cap == capability), ())
        return self.required_modules + tuple(
            module for module in extra if module not in self.required_modules
        )

    def as_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "label": self.label,
            "kind": self.kind,
            "capabilities": sorted(str(item) for item in self.capabilities),
            "requiredModules": list(self.required_modules),
            "requiredSettings": list(self.required_settings),
            "resources": self.resources.as_dict(),
            "summary": self.summary,
        }


@dataclass(frozen=True)
class ProviderReadiness:
    provider: str
    capability: Capability
    ready: bool
    missing_modules: tuple[str, ...] = ()
    missing_settings: tuple[str, ...] = ()
    reason: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "capability": str(self.capability),
            "ready": self.ready,
            "missingModules": list(self.missing_modules),
            "missingSettings": list(self.missing_settings),
            "reason": self.reason,
        }


@dataclass(frozen=True)
class ImagePayload:
    data: bytes
    mime_type: str
    extension: str
    width: int
    height: int
    origin: str

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def pixels(self) -> int:
        return self.width * self.height

    @property
    def filename(self) -> str:
        return self.origin if self.origin != "bytes" else f"image.{self.extension}"

    def as_dict(self) -> dict[str, Any]:
        return {
            "mimeType": self.mime_type,
            "byteSize": self.byte_size,
            "width": self.width,
            "height": self.height,
        }


@dataclass(frozen=True)
class _BaseResult:
    provider: str
    duration_ms: int
    source: ImagePayload
    notes: tuple[str, ...] = field(default=())

    def _common(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "durationMs": self.duration_ms,
            "source": self.source.as_dict(),
            "notes": list(self.notes),
        }


@dataclass(frozen=True)
class CutoutResult(_BaseResult):
    image: bytes = b""
    mime_type: str = "image/png"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.image)})
        return payload


@dataclass(frozen=True)
class SeparationResult(_BaseResult):
    foreground: bytes = b""
    background: bytes = b""
    matte: bytes = b""
    mime_type: str = "image/png"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update(
            {
                "mimeType": self.mime_type,
                "foregroundBytes": len(self.foreground),
                "backgroundBytes": len(self.background),
                "matteBytes": len(self.matte),
            }
        )
        return payload


@dataclass(frozen=True)
class VectorResult(_BaseResult):
    svg: bytes = b""
    mime_type: str = "image/svg+xml"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.svg)})
        return payload
