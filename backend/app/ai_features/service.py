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

_METHODS: dict[Capability, str] = {
    Capability.BACKGROUND_REMOVAL: "remove_background",
    Capability.FOREGROUND_SEPARATION: "separate_foreground",
    Capability.VECTORISATION: "vectorise",
}

_CONFIGURATION_CODES = frozenset({"disabled", "dependency_missing", "not_configured",
                                  "unknown_provider"})


def remove_background(
    source: ImageSource,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> CutoutResult:
    return _run(Capability.BACKGROUND_REMOVAL, source, provider=provider, settings=settings)


def separate_foreground(
    source: ImageSource,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> SeparationResult:
    return _run(Capability.FOREGROUND_SEPARATION, source, provider=provider, settings=settings)


def vectorise_image(
    source: ImageSource,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> VectorResult:
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
    if isinstance(exc, FeatureDisabled):
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
