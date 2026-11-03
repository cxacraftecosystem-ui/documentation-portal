import logging
import os
import threading
from typing import Any

from app.ai_features.errors import DependencyMissing, ProviderFailed
from app.ai_features.providers.base import (
    BackgroundRemovalProvider,
    ForegroundSeparationProvider,
    compose_layers,
)
from app.ai_features.runtime import Deadline, overrun_note, warn_once
from app.ai_features.types import Capability, CutoutResult, ImagePayload, SeparationResult

logger = logging.getLogger(__name__)

_SESSIONS: dict[str, Any] = {}
_SESSION_LOCK = threading.Lock()


def reset_session_cache() -> None:
    with _SESSION_LOCK:
        _SESSIONS.clear()


class RembgLocalProvider(BackgroundRemovalProvider, ForegroundSeparationProvider):
    provider_id = "rembg_local"

    def remove_background(self, image: ImagePayload, deadline: Deadline) -> CutoutResult:
        capability = Capability.BACKGROUND_REMOVAL
        deadline.check("local matting", capability=capability, provider=self.provider_id)
        cutout = self._run_rembg(image, capability, only_mask=False)
        return CutoutResult(
            provider=self.provider_id,
            duration_ms=deadline.elapsed_ms,
            source=image,
            image=cutout,
            notes=overrun_note(
                deadline, stage="local matting", capability=capability, provider=self.provider_id
            ),
        )

    def separate_foreground(self, image: ImagePayload, deadline: Deadline) -> SeparationResult:
        capability = Capability.FOREGROUND_SEPARATION
        deadline.check("local matting", capability=capability, provider=self.provider_id)
        matte = self._run_rembg(image, capability, only_mask=True)
        deadline.check("compositing", capability=capability, provider=self.provider_id)
        foreground, background, normalised, notes = compose_layers(
            image, matte, capability=capability, provider=self.provider_id
        )
        return SeparationResult(
            provider=self.provider_id,
            duration_ms=deadline.elapsed_ms,
            source=image,
            foreground=foreground,
            background=background,
            matte=normalised,
            notes=notes
            + overrun_note(
                deadline, stage="local separation", capability=capability,
                provider=self.provider_id,
            ),
        )

    def _run_rembg(self, image: ImagePayload, capability: Capability, *, only_mask: bool) -> bytes:
        from rembg import remove  # noqa: PLC0415

        session = self._session(capability)
        try:
            output = remove(
                image.data,
                session=session,
                only_mask=only_mask,
                post_process_mask=True,
            )
        except Exception as exc:
            raise self._fail(
                f"rembg failed on {image.filename}: {exc}",
                capability=capability,
                remediation=(
                    "Check the process has memory to spare; U2Net on a large image is the most "
                    "likely thing in this backend to be OOM-killed."
                ),
            ) from exc
        if not isinstance(output, (bytes, bytearray)):
            raise self._fail(
                f"rembg returned {type(output).__name__}, expected bytes",
                capability=capability,
                remediation="Pin a rembg version whose remove() returns bytes for bytes input.",
            )
        return bytes(output)

    def _session(self, capability: Capability) -> Any:
        model = self.settings.local_model
        if self.settings.cache_local_session:
            cached = _SESSIONS.get(model)
            if cached is not None:
                return cached

        with _SESSION_LOCK:
            if self.settings.cache_local_session:
                cached = _SESSIONS.get(model)
                if cached is not None:
                    return cached

            if self.settings.local_model_dir:
                os.environ.setdefault("U2NET_HOME", self.settings.local_model_dir)

            try:
                from rembg import new_session  # noqa: PLC0415
            except ImportError as exc:
                raise DependencyMissing(
                    f"rembg is not installed: {exc}",
                    modules=("rembg", "onnxruntime"),
                    capability=capability,
                    provider=self.provider_id,
                    remediation="pip install -e '.[ai-local]' — but read the RAM note first.",
                ) from exc

            warn_once(
                f"rembg_session:{model}",
                "ai_features: building a local rembg session for %r. The first call downloads the "
                "model (u2net is ~176 MB) and the session stays resident until the process exits.",
                model,
            )
            try:
                session = new_session(model)
            except Exception as exc:
                raise ProviderFailed(
                    f"could not start a rembg session for {model!r}: {exc}",
                    capability=capability,
                    provider=self.provider_id,
                    remediation=(
                        "Check AI_FEATURES_LOCAL_MODEL names a model rembg knows, that the box can "
                        "reach the model host on first use, and that AI_FEATURES_LOCAL_MODEL_DIR "
                        "is writable."
                    ),
                ) from exc
            if self.settings.cache_local_session:
                _SESSIONS[model] = session
            return session
