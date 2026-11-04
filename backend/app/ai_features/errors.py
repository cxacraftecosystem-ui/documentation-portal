from typing import Any


class AiFeatureError(RuntimeError):
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
        return {
            "code": self.code,
            "message": self.message,
            "capability": self.capability,
            "provider": self.provider,
            "remediation": self.remediation,
        }


class FeatureDisabled(AiFeatureError):
    code = "disabled"


class UnknownProvider(AiFeatureError):
    code = "unknown_provider"


class DependencyMissing(AiFeatureError):
    code = "dependency_missing"

    def __init__(self, message: str, *, modules: tuple[str, ...] = (), **kwargs: Any) -> None:
        super().__init__(message, **kwargs)
        self.modules = modules

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["modules"] = list(self.modules)
        return payload


class ProviderNotConfigured(AiFeatureError):
    code = "not_configured"

    def __init__(self, message: str, *, missing: tuple[str, ...] = (), **kwargs: Any) -> None:
        super().__init__(message, **kwargs)
        self.missing = missing

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["missing"] = list(self.missing)
        return payload


class InvalidImage(AiFeatureError):
    code = "invalid_image"


class UnsupportedImageType(InvalidImage):
    code = "unsupported_type"


class ImageTooLarge(InvalidImage):
    code = "too_large"


class ProviderFailed(AiFeatureError):
    code = "provider_failed"


class ProviderTimeout(ProviderFailed):
    code = "timeout"


class ProviderRateLimited(ProviderFailed):
    code = "rate_limited"

    def __init__(self, message: str, *, retry_after: float | None = None, **kwargs: Any) -> None:
        super().__init__(message, **kwargs)
        self.retry_after = retry_after

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["retryAfter"] = self.retry_after
        return payload
