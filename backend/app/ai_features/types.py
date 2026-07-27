"""Value types shared by the three capabilities: what goes in, what comes back, what it cost.

Everything here is a frozen dataclass with an ``as_dict()``, because the two plausible consumers
are a background-queue job (which persists a JSON blob) and, eventually, an admin route (which
returns one). Neither wants to reach into provider-specific structures.
"""

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Literal


class Capability(StrEnum):
    """The three things this package can do. Values double as env-var infixes and JSON keys."""

    BACKGROUND_REMOVAL = "background_removal"
    FOREGROUND_SEPARATION = "foreground_separation"
    VECTORISATION = "vectorisation"


#: How a resource number was arrived at. Never label an unmeasured number MEASURED — the point of
#: the field is that an operator sizing a box can tell a vendor's claim from our guess.
ResourceBasis = Literal["VENDOR_STATED", "ESTIMATED", "MEASURED"]

ProviderKind = Literal["local", "hosted"]


@dataclass(frozen=True)
class ResourceProfile:
    """What running one image through a provider actually costs, and how we know.

    ``peak_ram_mb`` is the number that decides whether a provider can live on the production box
    at all: it has 1 GiB total, shared with uvicorn, the Prisma query engine and the queue worker.
    """

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
    """Everything the probe needs to report on a provider WITHOUT importing it.

    ``required_modules`` are checked with ``importlib.util.find_spec``, never an import, so asking
    "is rembg available" costs a path lookup rather than 176 MB of ONNX weights.

    ``implementation`` is a ``"module:ClassName"`` string rather than the class itself for the
    same reason: the registry can describe every provider at import time while the module that
    knows how to talk to onnxruntime stays unloaded until someone actually calls the feature.
    """

    id: str
    label: str
    kind: ProviderKind
    capabilities: frozenset[Capability]
    required_modules: tuple[str, ...]
    required_settings: tuple[str, ...]
    resources: ResourceProfile
    summary: str
    implementation: str = ""
    #: Modules one capability needs on top of ``required_modules``. Splitting a hosted cutout into
    #: layers means compositing locally, which returning the cutout itself never has to do — so
    #: "is separation available" and "is removal available" have genuinely different answers.
    extra_modules: tuple[tuple[Capability, tuple[str, ...]], ...] = ()

    def modules_for(self, capability: Capability) -> tuple[str, ...]:
        """Every module this provider needs to serve one capability, in declaration order."""
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
    """Whether one provider could serve one capability right now, and what is missing if not.

    Produced by the registry from ``find_spec`` lookups and settings values alone, so both the
    probe and the dispatcher answer the question the same way and neither has to import anything.
    """

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
    """A validated input image: bytes plus the facts we could establish from its header alone."""

    data: bytes
    mime_type: str
    extension: str
    width: int
    height: int
    origin: str  # "bytes" or the filename it was read from — useful in logs and provider uploads

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def pixels(self) -> int:
        return self.width * self.height

    @property
    def filename(self) -> str:
        """A name to put in a multipart upload. Hosted providers sniff the extension."""
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
    """Capability 2: the subject on transparency. ``image`` is always a PNG with an alpha channel."""

    image: bytes = b""
    mime_type: str = "image/png"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.image)})
        return payload


@dataclass(frozen=True)
class SeparationResult(_BaseResult):
    """Capability 1: the two layers plus the matte that relates them.

    Both layers are the original pixels wearing opposite alpha: the foreground carries the matte,
    the background carries its inverse. Compositing one over the other returns the original
    exactly wherever the matte is fully 0 or fully 255, and darkens slightly in the soft band
    between — the unavoidable arithmetic of splitting one image into two straight-alpha layers,
    and the reason the ``matte`` is returned separately for callers that want to re-composite
    their own way.
    """

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
    """Capability 3: raster in, SVG out. ``svg`` is UTF-8 encoded markup, not a data URL."""

    svg: bytes = b""
    mime_type: str = "image/svg+xml"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.svg)})
        return payload
