import logging
import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Mapping

from app.ai_features.types import Capability

logger = logging.getLogger(__name__)

ENV_VARS: tuple[str, ...] = (
    "AI_FEATURES_ENABLED",
    "AI_BACKGROUND_REMOVAL_ENABLED",
    "AI_FOREGROUND_SEPARATION_ENABLED",
    "AI_IMAGE_VECTORISATION_ENABLED",
    "AI_BACKGROUND_REMOVAL_PROVIDER",
    "AI_FOREGROUND_SEPARATION_PROVIDER",
    "AI_IMAGE_VECTORISATION_PROVIDER",
    "AI_FEATURES_MAX_IMAGE_BYTES",
    "AI_FEATURES_MAX_IMAGE_PIXELS",
    "AI_FEATURES_TIMEOUT_SECONDS",
    "AI_FEATURES_LOCAL_MODEL",
    "AI_FEATURES_LOCAL_MODEL_DIR",
    "AI_FEATURES_CACHE_LOCAL_SESSION",
    "REMOVE_BG_API_KEY",
    "REMOVE_BG_ENDPOINT",
    "REMOVE_BG_SIZE",
    "VECTORIZER_AI_API_ID",
    "VECTORIZER_AI_API_SECRET",
    "VECTORIZER_AI_ENDPOINT",
    "VECTORIZER_AI_MODE",
    "AI_VECTOR_COLORMODE",
    "AI_VECTOR_FILTER_SPECKLE",
    "AI_VECTOR_COLOR_PRECISION",
    "AI_TRANSCRIPT_INSIGHTS_ENABLED",
    "AI_TRANSCRIPT_INSIGHTS_PROVIDER",
    "AI_TRANSCRIPT_INSIGHTS_MAX_TRANSLATE_CHARS",
)

CAPABILITY_ENV_VARS: dict[Capability, tuple[str, str]] = {
    Capability.BACKGROUND_REMOVAL: (
        "AI_BACKGROUND_REMOVAL_ENABLED",
        "AI_BACKGROUND_REMOVAL_PROVIDER",
    ),
    Capability.FOREGROUND_SEPARATION: (
        "AI_FOREGROUND_SEPARATION_ENABLED",
        "AI_FOREGROUND_SEPARATION_PROVIDER",
    ),
    Capability.VECTORISATION: (
        "AI_IMAGE_VECTORISATION_ENABLED",
        "AI_IMAGE_VECTORISATION_PROVIDER",
    ),
    Capability.TRANSCRIPT_INSIGHTS: (
        "AI_TRANSCRIPT_INSIGHTS_ENABLED",
        "AI_TRANSCRIPT_INSIGHTS_PROVIDER",
    ),
}


def enable_var(capability: Capability) -> str:
    return CAPABILITY_ENV_VARS[capability][0]


def provider_var(capability: Capability) -> str:
    return CAPABILITY_ENV_VARS[capability][1]


_DEFAULT_MAX_BYTES = 12 * 1024 * 1024
_DEFAULT_MAX_PIXELS = 24_000_000
_DEFAULT_TIMEOUT_SECONDS = 60.0
_DEFAULT_MAX_TRANSLATE_CHARS = 96_000
_MIN_MAX_TRANSLATE_CHARS = 4_000

_TRUE = frozenset({"1", "true", "yes", "on", "y", "t"})
_FALSE = frozenset({"0", "false", "no", "off", "n", "f", ""})


@dataclass(frozen=True)
class AiFeatureSettings:
    enabled: bool = False
    background_removal_enabled: bool = False
    foreground_separation_enabled: bool = False
    vectorisation_enabled: bool = False
    transcript_insights_enabled: bool = False

    background_removal_provider: str = "auto"
    foreground_separation_provider: str = "auto"
    vectorisation_provider: str = "auto"
    transcript_insights_provider: str = "auto"
    transcript_insights_max_translate_chars: int = _DEFAULT_MAX_TRANSLATE_CHARS

    max_image_bytes: int = _DEFAULT_MAX_BYTES
    max_image_pixels: int = _DEFAULT_MAX_PIXELS
    timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS

    local_model: str = "u2net"
    local_model_dir: str | None = None
    cache_local_session: bool = True

    remove_bg_api_key: str | None = None
    remove_bg_endpoint: str = "https://api.remove.bg/v1.0/removebg"
    remove_bg_size: str = "auto"

    vectorizer_api_id: str | None = None
    vectorizer_api_secret: str | None = None
    vectorizer_endpoint: str = "https://vectorizer.ai/api/v1/vectorize"
    vectorizer_mode: str = "test"

    vector_colormode: str = "color"
    vector_filter_speckle: int = 4
    vector_color_precision: int = 6

    notes: tuple[str, ...] = field(default=())

    def capability_enabled(self, capability: Capability) -> bool:
        if not self.enabled:
            return False
        return {
            Capability.BACKGROUND_REMOVAL: self.background_removal_enabled,
            Capability.FOREGROUND_SEPARATION: self.foreground_separation_enabled,
            Capability.VECTORISATION: self.vectorisation_enabled,
            Capability.TRANSCRIPT_INSIGHTS: self.transcript_insights_enabled,
        }[capability]

    def provider_choice(self, capability: Capability) -> str:
        return {
            Capability.BACKGROUND_REMOVAL: self.background_removal_provider,
            Capability.FOREGROUND_SEPARATION: self.foreground_separation_provider,
            Capability.VECTORISATION: self.vectorisation_provider,
            Capability.TRANSCRIPT_INSIGHTS: self.transcript_insights_provider,
        }[capability]

    def value_for(self, env_var: str) -> str | None:
        return {
            "REMOVE_BG_API_KEY": self.remove_bg_api_key,
            "REMOVE_BG_ENDPOINT": self.remove_bg_endpoint,
            "VECTORIZER_AI_API_ID": self.vectorizer_api_id,
            "VECTORIZER_AI_API_SECRET": self.vectorizer_api_secret,
            "VECTORIZER_AI_ENDPOINT": self.vectorizer_endpoint,
        }.get(env_var)


def _as_bool(raw: str | None, default: bool, name: str, notes: list[str]) -> bool:
    if raw is None:
        return default
    value = raw.strip().lower()
    if value in _TRUE:
        return True
    if value in _FALSE:
        return False
    notes.append(f"{name}={raw!r} is not a boolean; using {default}")
    return default


def _as_int(raw: str | None, default: int, name: str, notes: list[str], minimum: int = 1) -> int:
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not an integer; using {default}")
        return default
    if value < minimum:
        notes.append(f"{name}={value} is below the minimum {minimum}; using {minimum}")
        return minimum
    return value


def _as_float(raw: str | None, default: float, name: str, notes: list[str]) -> float:
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not a number; using {default}")
        return default
    if value <= 0:
        notes.append(f"{name}={value} must be positive; using {default}")
        return default
    return value


def _as_max_translate_chars(raw: str | None, notes: list[str]) -> int:
    name = "AI_TRANSCRIPT_INSIGHTS_MAX_TRANSLATE_CHARS"
    if raw is None or not raw.strip():
        return _DEFAULT_MAX_TRANSLATE_CHARS
    try:
        value = int(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not an integer; using {_DEFAULT_MAX_TRANSLATE_CHARS}")
        return _DEFAULT_MAX_TRANSLATE_CHARS
    if value < _MIN_MAX_TRANSLATE_CHARS:
        notes.append(
            f"{name}={value} is below the minimum {_MIN_MAX_TRANSLATE_CHARS}; "
            f"using {_DEFAULT_MAX_TRANSLATE_CHARS}"
        )
        return _DEFAULT_MAX_TRANSLATE_CHARS
    return value


def _as_str(raw: str | None, default: str) -> str:
    value = (raw or "").strip()
    return value or default


def _as_optional_str(raw: str | None) -> str | None:
    value = (raw or "").strip()
    return value or None


def _as_choice(raw: str | None, default: str, allowed: frozenset[str], name: str,
               notes: list[str]) -> str:
    value = (raw or "").strip().lower()
    if not value:
        return default
    if value not in allowed:
        notes.append(f"{name}={raw!r} is not one of {sorted(allowed)}; using {default}")
        return default
    return value


def build_settings(env: Mapping[str, str]) -> AiFeatureSettings:
    notes: list[str] = []
    get = env.get

    return AiFeatureSettings(
        enabled=_as_bool(get("AI_FEATURES_ENABLED"), False, "AI_FEATURES_ENABLED", notes),
        background_removal_enabled=_as_bool(
            get("AI_BACKGROUND_REMOVAL_ENABLED"), False, "AI_BACKGROUND_REMOVAL_ENABLED", notes
        ),
        foreground_separation_enabled=_as_bool(
            get("AI_FOREGROUND_SEPARATION_ENABLED"), False,
            "AI_FOREGROUND_SEPARATION_ENABLED", notes,
        ),
        vectorisation_enabled=_as_bool(
            get("AI_IMAGE_VECTORISATION_ENABLED"), False, "AI_IMAGE_VECTORISATION_ENABLED", notes
        ),
        background_removal_provider=_as_str(get("AI_BACKGROUND_REMOVAL_PROVIDER"), "auto").lower(),
        foreground_separation_provider=_as_str(
            get("AI_FOREGROUND_SEPARATION_PROVIDER"), "auto"
        ).lower(),
        vectorisation_provider=_as_str(get("AI_IMAGE_VECTORISATION_PROVIDER"), "auto").lower(),
        transcript_insights_enabled=_as_bool(
            get("AI_TRANSCRIPT_INSIGHTS_ENABLED"), False, "AI_TRANSCRIPT_INSIGHTS_ENABLED", notes
        ),
        transcript_insights_provider=_as_str(
            get("AI_TRANSCRIPT_INSIGHTS_PROVIDER"), "auto"
        ).lower(),
        transcript_insights_max_translate_chars=_as_max_translate_chars(
            get("AI_TRANSCRIPT_INSIGHTS_MAX_TRANSLATE_CHARS"), notes
        ),
        max_image_bytes=_as_int(
            get("AI_FEATURES_MAX_IMAGE_BYTES"), _DEFAULT_MAX_BYTES,
            "AI_FEATURES_MAX_IMAGE_BYTES", notes, minimum=1024,
        ),
        max_image_pixels=_as_int(
            get("AI_FEATURES_MAX_IMAGE_PIXELS"), _DEFAULT_MAX_PIXELS,
            "AI_FEATURES_MAX_IMAGE_PIXELS", notes, minimum=1024,
        ),
        timeout_seconds=_as_float(
            get("AI_FEATURES_TIMEOUT_SECONDS"), _DEFAULT_TIMEOUT_SECONDS,
            "AI_FEATURES_TIMEOUT_SECONDS", notes,
        ),
        local_model=_as_str(get("AI_FEATURES_LOCAL_MODEL"), "u2net"),
        local_model_dir=_as_optional_str(get("AI_FEATURES_LOCAL_MODEL_DIR")),
        cache_local_session=_as_bool(
            get("AI_FEATURES_CACHE_LOCAL_SESSION"), True, "AI_FEATURES_CACHE_LOCAL_SESSION", notes
        ),
        remove_bg_api_key=_as_optional_str(get("REMOVE_BG_API_KEY")),
        remove_bg_endpoint=_as_str(
            get("REMOVE_BG_ENDPOINT"), "https://api.remove.bg/v1.0/removebg"
        ),
        remove_bg_size=_as_choice(
            get("REMOVE_BG_SIZE"), "auto",
            frozenset({"auto", "preview", "small", "regular", "medium", "hd", "full", "4k"}),
            "REMOVE_BG_SIZE", notes,
        ),
        vectorizer_api_id=_as_optional_str(get("VECTORIZER_AI_API_ID")),
        vectorizer_api_secret=_as_optional_str(get("VECTORIZER_AI_API_SECRET")),
        vectorizer_endpoint=_as_str(
            get("VECTORIZER_AI_ENDPOINT"), "https://vectorizer.ai/api/v1/vectorize"
        ),
        vectorizer_mode=_as_choice(
            get("VECTORIZER_AI_MODE"), "test",
            frozenset({"test", "preview", "production"}), "VECTORIZER_AI_MODE", notes,
        ),
        vector_colormode=_as_choice(
            get("AI_VECTOR_COLORMODE"), "color", frozenset({"color", "binary"}),
            "AI_VECTOR_COLORMODE", notes,
        ),
        vector_filter_speckle=_as_int(
            get("AI_VECTOR_FILTER_SPECKLE"), 4, "AI_VECTOR_FILTER_SPECKLE", notes, minimum=0
        ),
        vector_color_precision=_as_int(
            get("AI_VECTOR_COLOR_PRECISION"), 6, "AI_VECTOR_COLOR_PRECISION", notes, minimum=1
        ),
        notes=tuple(notes),
    )


def _env_file_candidates() -> tuple[Path, ...]:
    package_root = Path(__file__).resolve().parents[2]
    return (Path(".env"), package_root / ".env")


def _read_env_file() -> dict[str, str]:
    wanted = set(ENV_VARS)
    found: dict[str, str] = {}
    for candidate in _env_file_candidates():
        try:
            if not candidate.is_file():
                continue
            text = candidate.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, _, value = stripped.partition("=")
            key = key.strip()
            if key not in wanted or key in found:
                continue
            found[key] = value.strip().strip("\"'")
        if found:
            break
    return found


@lru_cache
def _merged_environment() -> dict[str, str]:
    merged = dict(_read_env_file())
    for name in ENV_VARS:
        value = os.environ.get(name)
        if value is not None:
            merged[name] = value
    return merged


@lru_cache
def get_ai_settings() -> AiFeatureSettings:
    settings = build_settings(_merged_environment())
    for note in settings.notes:
        logger.warning("ai_features configuration: %s", note)
    return settings


def configured_vars() -> tuple[str, ...]:
    environment = _merged_environment()
    return tuple(name for name in ENV_VARS if (environment.get(name) or "").strip())


def reset_ai_settings_cache() -> None:
    _merged_environment.cache_clear()
    get_ai_settings.cache_clear()
