"""The answer to "why is this feature not doing anything", computed without importing anything.

An operator looking at a switched-off feature has four candidate explanations — the master flag,
the capability's own flag, a package that was never installed, a credential that was never set —
and no way to tell them apart from the outside. That is what this module is for. It is also the
one part of the package that is safe to call from anywhere: it does no I/O beyond reading the
settings, imports no provider, and cannot raise. A future admin route can return :func:`probe`
verbatim; a shell one-liner can print :func:`format_probe`.

WHAT IT DELIBERATELY DOES NOT DO. It never says a hosted provider is *reachable* or that a key is
*valid* — that costs a network round trip and a credit, and a probe that spends money is a probe
nobody runs. "Ready" here means installed and configured, which is exactly the class of problem
that is invisible from the logs of a dormant feature.
"""

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
    """One capability's answer: on or off, usable or not, and the single reason why not."""

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
    """Everything known about one capability right now."""
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
        # resolve() already composed the sentence an operator needs; repeating that logic here
        # would be the second place it could drift.
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
    """Would a call to this capability get as far as a provider? Cheap enough to ask per item."""
    return capability_status(capability, settings).available


def available_capabilities(
    settings: AiFeatureSettings | None = None,
) -> tuple[Capability, ...]:
    """The capabilities that are on and usable. Empty on a default installation, by design."""
    active = settings or get_ai_settings()
    return tuple(
        capability for capability in Capability if capability_status(capability, active).available
    )


def probe(settings: AiFeatureSettings | None = None) -> dict[str, Any]:
    """A JSON-safe report of the whole package: flags, providers, limits, resource costs."""
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
        },
        # Names only. This report is meant to be pasted into a ticket.
        "configuredVariables": list(configured_vars()),
        "knownVariables": list(ENV_VARS),
        "configurationNotes": list(active.notes),
    }


def format_probe(settings: AiFeatureSettings | None = None) -> str:
    """The same report as a handful of lines, for a terminal.

    ``python -c "from app.ai_features import format_probe; print(format_probe())"`` from
    ``backend/`` is the fastest way to answer the question this module exists for.
    """
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
