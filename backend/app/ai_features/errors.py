"""The one exception family every entry point in this package is allowed to raise.

WHY A FAMILY RATHER THAN BARE EXCEPTIONS. These features are optional and dormant, so the
interesting question at a future call site is never "did it work" but "what do I do now": retry,
fall back to the un-processed image, or tell an admin to set a key. A caller that writes
``except AiFeatureError`` gets a machine-readable ``code`` and a human-readable ``remediation``
and can keep serving the request; nothing here escapes as an ImportError, a requests exception
or a Pillow error, because those would look like a bug in the caller rather than a feature that
is simply switched off.

The deliberate omission is a "return None on failure" mode. A silent no-op that looks like
success is how a disabled feature becomes an invisible data-quality problem — the caller writes
the original file back to S3 believing it was a cutout. Failure is always an exception here.
"""

from typing import Any


class AiFeatureError(RuntimeError):
    """Base class: something in the AI image package could not run, and why."""

    code = "ai_feature_error"

    def __init__(
        self,
        message: str,
        *,
        capability: Any = None,
        provider: str | None = None,
        remediation: str | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.capability = str(capability) if capability is not None else None
        self.provider = provider
        self.remediation = remediation

    def as_dict(self) -> dict[str, Any]:
        """JSON-safe shape, so a future route can hand this straight to a client."""
        return {
            "code": self.code,
            "message": self.message,
            "capability": self.capability,
            "provider": self.provider,
            "remediation": self.remediation,
        }


class FeatureDisabled(AiFeatureError):
    """The capability's flag is off. This is the DEFAULT state and is not an error condition."""

    code = "disabled"


class UnknownProvider(AiFeatureError):
    """A provider id was requested that this package does not implement."""

    code = "unknown_provider"


class DependencyMissing(AiFeatureError):
    """The flag is on but the optional Python package the provider needs is not installed."""

    code = "dependency_missing"

    def __init__(self, message: str, *, modules: tuple[str, ...] = (), **kwargs: Any) -> None:
        super().__init__(message, **kwargs)
        self.modules = modules

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["modules"] = list(self.modules)
        return payload


class ProviderNotConfigured(AiFeatureError):
    """The flag is on and the code is installed, but a credential or endpoint is missing."""

    code = "not_configured"

    def __init__(self, message: str, *, missing: tuple[str, ...] = (), **kwargs: Any) -> None:
        super().__init__(message, **kwargs)
        self.missing = missing

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["missing"] = list(self.missing)
        return payload


class InvalidImage(AiFeatureError):
    """The input is not something we are willing to hand to a provider."""

    code = "invalid_image"


class UnsupportedImageType(InvalidImage):
    """Not a PNG/JPEG/WebP, or too damaged to read a header from."""

    code = "unsupported_type"


class ImageTooLarge(InvalidImage):
    """Over the byte or pixel ceiling. Refused before any decode, on purpose."""

    code = "too_large"


class ProviderFailed(AiFeatureError):
    """The provider ran and did not produce a usable result."""

    code = "provider_failed"


class ProviderTimeout(ProviderFailed):
    """The provider exceeded the configured wall-clock budget."""

    code = "timeout"


class ProviderRateLimited(ProviderFailed):
    """A hosted provider answered 429. ``retry_after`` is its own suggestion, in seconds."""

    code = "rate_limited"

    def __init__(self, message: str, *, retry_after: float | None = None, **kwargs: Any) -> None:
        super().__init__(message, **kwargs)
        self.retry_after = retry_after

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["retryAfter"] = self.retry_after
        return payload
