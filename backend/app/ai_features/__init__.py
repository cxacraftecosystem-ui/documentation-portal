"""Optional AI image features: background removal, foreground/background separation, vectorisation.

NONE OF THIS RUNS UNLESS IT IS SWITCHED ON. Every capability is off by default, no route imports
this package, and importing it costs nothing but stdlib: the names below resolve to plain
dataclasses, a settings reader and a dispatcher. The first line of any optional dependency —
rembg, onnxruntime, Pillow, vtracer — is executed inside a method, after that capability's flag
has been checked. On a 1 GiB production box that is not tidiness, it is the difference between a
feature nobody enabled and an OOM kill: ``import rembg`` alone was measured here at 132 MB, and
one 1.9-megapixel matte peaked the process at 1,032 MB.

    from app.ai_features import Capability, remove_background, probe, AiFeatureError

    if is_available(Capability.BACKGROUND_REMOVAL):
        try:
            cutout = remove_background(original_bytes)
        except AiFeatureError as exc:
            log.warning("keeping the original: %s", exc.message)

``probe()`` and ``format_probe()`` answer "why is this doing nothing" without importing a
provider — the flag, the missing package or the unset key, named. ``docs/AI_FEATURES.md`` covers
what each feature is for in a craft-documentation context, what each provider costs, and how to
turn one on.

These are queue-shaped operations, not request-shaped ones: seconds each, tens of seconds for a
local matte, against a CloudFront origin timeout of thirty. Call them from the background worker.
"""

from app.ai_features.errors import (
    AiFeatureError,
    DependencyMissing,
    FeatureDisabled,
    ImageTooLarge,
    InvalidImage,
    ProviderFailed,
    ProviderNotConfigured,
    ProviderRateLimited,
    ProviderTimeout,
    UnknownProvider,
    UnsupportedImageType,
)
from app.ai_features.probe import (
    CapabilityStatus,
    available_capabilities,
    capability_status,
    format_probe,
    is_available,
    probe,
)
from app.ai_features.registry import ALL_PROVIDERS, get_descriptor, providers_for
from app.ai_features.service import remove_background, separate_foreground, vectorise_image
from app.ai_features.settings import (
    CAPABILITY_ENV_VARS,
    ENV_VARS,
    AiFeatureSettings,
    get_ai_settings,
    reset_ai_settings_cache,
)
from app.ai_features.types import (
    Capability,
    CutoutResult,
    ImagePayload,
    ProviderDescriptor,
    ProviderReadiness,
    ResourceProfile,
    SeparationResult,
    VectorResult,
)

__all__ = [
    # the three capabilities
    "remove_background",
    "separate_foreground",
    "vectorise_image",
    # asking whether any of that would work
    "Capability",
    "CapabilityStatus",
    "available_capabilities",
    "capability_status",
    "format_probe",
    "is_available",
    "probe",
    # configuration
    "AiFeatureSettings",
    "CAPABILITY_ENV_VARS",
    "ENV_VARS",
    "get_ai_settings",
    "reset_ai_settings_cache",
    # providers, as data
    "ALL_PROVIDERS",
    "ProviderDescriptor",
    "ProviderReadiness",
    "ResourceProfile",
    "get_descriptor",
    "providers_for",
    # results
    "CutoutResult",
    "ImagePayload",
    "SeparationResult",
    "VectorResult",
    # failures, in the order a caller cares about them
    "AiFeatureError",
    "FeatureDisabled",
    "DependencyMissing",
    "ProviderNotConfigured",
    "UnknownProvider",
    "InvalidImage",
    "UnsupportedImageType",
    "ImageTooLarge",
    "ProviderFailed",
    "ProviderTimeout",
    "ProviderRateLimited",
]
