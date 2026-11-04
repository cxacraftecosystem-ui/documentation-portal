from dataclasses import dataclass
from typing import Any

from app.ai_features.errors import AiFeatureError
from app.ai_features.registry import ALL_PROVIDERS, providers_for, readiness, resolve
from app.ai_features.settings import (
    AiFeatureSettings,
    ENV_VARS,
    configured_vars,
    enable_var,
    get_ai_settings,
    provider_var,
)
from app.ai_features.types import Capability, ProviderReadiness


@dataclass(frozen=True)
class CapabilityStatus:
    capability: Capability
    enabled: bool
    available: bool
    provider: str | None
    configured_provider: str
    reason: str
    providers: tuple[ProviderReadiness, ...]

    def as_dict(self) -> dict[str, Any]:
        return {
            "capability": str(self.capability),
            "enabled": self.enabled,
            "available": self.available,
            "provider": self.provider,
            "configuredProvider": self.configured_provider,
            "reason": self.reason,
            "enableVariable": enable_var(self.capability),
            "providerVariable": provider_var(self.capability),
            "providers": [item.as_dict() for item in self.providers],
        }


def capability_status(
    capability: Capability, settings: AiFeatureSettings | None = None
) -> CapabilityStatus:
    active = settings or get_ai_settings()
    candidates = tuple(readiness(item, capability, active) for item in providers_for(capability))
    configured = active.provider_choice(capability)

    if not active.enabled:
        return CapabilityStatus(
            capability=capability,
            enabled=False,
            available=False,
            provider=None,
            configured_provider=configured,
            reason="AI_FEATURES_ENABLED is off - the whole package is dormant",
            providers=candidates,
        )
    if not active.capability_enabled(capability):
        return CapabilityStatus(
            capability=capability,
            enabled=False,
            available=False,
            provider=None,
            configured_provider=configured,
            reason=f"{enable_var(capability)} is off",
            providers=candidates,
        )

    try:
        descriptor = resolve(capability, active)
    except AiFeatureError as exc:
        return CapabilityStatus(
            capability=capability,
            enabled=True,
            available=False,
            provider=None,
            configured_provider=configured,
            reason=exc.message,
            providers=candidates,
        )
    return CapabilityStatus(
        capability=capability,
        enabled=True,
        available=True,
        provider=descriptor.id,
        configured_provider=configured,
        reason=f"ready via {descriptor.label}",
        providers=candidates,
    )


def is_available(capability: Capability, settings: AiFeatureSettings | None = None) -> bool:
    return capability_status(capability, settings).available


def available_capabilities(
    settings: AiFeatureSettings | None = None,
) -> tuple[Capability, ...]:
    active = settings or get_ai_settings()
    return tuple(
        capability for capability in Capability if capability_status(capability, active).available
    )


def probe(settings: AiFeatureSettings | None = None) -> dict[str, Any]:
    active = settings or get_ai_settings()
    return {
        "enabled": active.enabled,
        "capabilities": [
            capability_status(capability, active).as_dict() for capability in Capability
        ],
        "providers": [descriptor.as_dict() for descriptor in ALL_PROVIDERS],
        "limits": {
            "maxImageBytes": active.max_image_bytes,
            "maxImagePixels": active.max_image_pixels,
            "timeoutSeconds": active.timeout_seconds,
            "transcriptInsightsMaxTranslateChars": active.transcript_insights_max_translate_chars,
        },
        "configuredVariables": list(configured_vars()),
        "knownVariables": list(ENV_VARS),
        "configurationNotes": list(active.notes),
    }


def format_probe(settings: AiFeatureSettings | None = None) -> str:
    active = settings or get_ai_settings()
    lines = [
        f"AI image features: {'ENABLED' if active.enabled else 'disabled (default)'}",
    ]
    for capability in Capability:
        status = capability_status(capability, active)
        mark = "ok " if status.available else "off"
        lines.append(f"  [{mark}] {capability}: {status.reason}")
        for item in status.providers:
            lines.append(f"        - {item.provider}: {item.reason}")
    for note in active.notes:
        lines.append(f"  ! {note}")
    if not active.enabled:
        lines.append(
            "  Nothing above runs until AI_FEATURES_ENABLED=true - see docs/AI_FEATURES.md."
        )
    return "\n".join(lines)
