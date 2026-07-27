"""The three entry points. Everything above this line is machinery; this is the package's API.

ORDER OF OPERATIONS, AND WHY IT IS THIS ORDER. Flag first, provider second, image third. Checking
the flag first is what makes a disabled feature cost one dictionary lookup. Resolving the provider
before validating the image means an operator who has switched something on without installing it
is told that, rather than being told their JPEG is too big by a feature that could not have run
either way.

WHERE THESE BELONG. On the background queue, not in a request. A hosted cutout is seconds and a
local matte can be tens of seconds; CloudFront gives the origin thirty before it returns a 504,
and this backend has already been bitten by exactly that on media uploads. The shape to aim for
is the one the media pipeline already uses — enqueue a job, write the derived asset to S3, record
it against the media row — and these functions are written to be called from that worker: they
take bytes or a path, they return bytes plus metadata, and they never touch the database.

FAILURE IS ALWAYS AN EXCEPTION, and always one from :mod:`app.ai_features.errors`. A caller that
wraps a call in ``except AiFeatureError`` can carry on with the original asset, which is the only
sane fallback for a feature that is off by default. Nothing here returns None or a half-result:
silently writing the untouched photograph back as if it were a cutout is the one outcome worse
than a failure.
"""

import logging
from typing import Any

from app.ai_features import registry
from app.ai_features.errors import AiFeatureError, FeatureDisabled, ProviderFailed
from app.ai_features.imaging import ImageSource, load_source
from app.ai_features.runtime import Deadline, warn_once
from app.ai_features.settings import AiFeatureSettings, enable_var, get_ai_settings
from app.ai_features.types import (
    Capability,
    CutoutResult,
    SeparationResult,
    VectorResult,
)

logger = logging.getLogger(__name__)

#: The method each capability calls on its provider. The interfaces in
#: :mod:`app.ai_features.providers.base` are the contract this table depends on.
_METHODS: dict[Capability, str] = {
    Capability.BACKGROUND_REMOVAL: "remove_background",
    Capability.FOREGROUND_SEPARATION: "separate_foreground",
    Capability.VECTORISATION: "vectorise",
}

#: Failures an operator has to fix once, as opposed to failures that are about one image. These
#: get announced once per process; a batch of 925 photographs must not write 925 identical lines.
_CONFIGURATION_CODES = frozenset({"disabled", "dependency_missing", "not_configured",
                                  "unknown_provider"})


def remove_background(
    source: ImageSource,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> CutoutResult:
    """Subject on transparency: the common case, matting included.

    ``source`` is bytes or a path. The result carries PNG bytes with an alpha channel plus the
    provider, the duration and any notes worth persisting beside the asset.
    """
    return _run(Capability.BACKGROUND_REMOVAL, source, provider=provider, settings=settings)


def separate_foreground(
    source: ImageSource,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> SeparationResult:
    """Subject, background and matte as three images, for callers that want the layers.

    Use :func:`remove_background` if all you want is the cutout — this does strictly more work and,
    with a hosted provider, needs Pillow to build the layers.
    """
    return _run(Capability.FOREGROUND_SEPARATION, source, provider=provider, settings=settings)


def vectorise_image(
    source: ImageSource,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> VectorResult:
    """Raster to SVG. Suited to flat, high-contrast subjects — a block-print motif, a stamp, a mark.

    A photograph of a textile traces into thousands of paths and a very large file; check the
    result's notes, which say so when the SVG comes back unusually big.
    """
    return _run(Capability.VECTORISATION, source, provider=provider, settings=settings)


def _run(
    capability: Capability,
    source: ImageSource,
    *,
    provider: str | None,
    settings: AiFeatureSettings | None,
) -> Any:
    active = settings or get_ai_settings()
    try:
        _require_enabled(capability, active)
        descriptor = registry.resolve(capability, active, requested=provider)
        image = load_source(source, active, capability=capability)
        deadline = Deadline.start(active.timeout_seconds)
        implementation = registry.load_provider(descriptor, active)
        method = getattr(implementation, _METHODS[capability])
        try:
            result = method(image, deadline)
        except AiFeatureError:
            raise
        except Exception as exc:
            # A provider library raising something of its own — an ONNX error, a Pillow error, a
            # botched wheel — is still this package's problem to describe. Nothing from a
            # dependency escapes to a caller that only knows about AiFeatureError.
            raise ProviderFailed(
                f"{descriptor.id} raised {type(exc).__name__}: {exc}",
                capability=capability,
                provider=descriptor.id,
                remediation="Check the provider's own logs; this was not an expected failure.",
            ) from exc
    except AiFeatureError as exc:
        _log_failure(capability, exc)
        raise

    logger.info(
        "ai_features: %s via %s in %dms (%s)",
        capability,
        result.provider,
        result.duration_ms,
        image.filename,
    )
    return result


def _require_enabled(capability: Capability, settings: AiFeatureSettings) -> None:
    if settings.capability_enabled(capability):
        return
    raise FeatureDisabled(
        f"{capability} is switched off",
        capability=capability,
        remediation=(
            f"Set AI_FEATURES_ENABLED=true and {enable_var(capability)}=true, then restart the "
            "process. See docs/AI_FEATURES.md for what each provider then needs."
        ),
    )


def _log_failure(capability: Capability, exc: AiFeatureError) -> None:
    """Say it once if it is configuration, say it every time if it is about this image."""
    if isinstance(exc, FeatureDisabled):
        # The default state of this package. Logging it at anything above debug would fill the
        # log of a system that is behaving exactly as intended.
        logger.debug("ai_features: %s", exc.message)
        return
    if exc.code in _CONFIGURATION_CODES:
        warn_once(
            f"config:{capability}:{exc.code}:{exc.provider}",
            "ai_features: %s is enabled but unusable — %s (%s)",
            capability,
            exc.message,
            exc.remediation or "see docs/AI_FEATURES.md",
        )
        return
    logger.warning(
        "ai_features: %s failed via %s — %s", capability, exc.provider or "?", exc.message
    )
