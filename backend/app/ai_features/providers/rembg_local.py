"""Local matting with rembg's U2Net. The heaviest thing in this package, and the most optional.

READ THIS BEFORE ENABLING IT ON THE PRODUCTION BOX. The API runs on one t3.micro with 1 GiB of
RAM shared between uvicorn, the Prisma query engine and the media queue worker. This was measured
rather than guessed at, on a laptop with room to spare: ``import rembg`` alone took the process
from 35 MB to 167 MB, building the U2Net session took it to 564 MB, and one 1.9-megapixel matte
peaked it at 1,032 MB — more than the production box has in total, before uvicorn is counted. The
4.7 MB lite model does not rescue it either, peaking at 696 MB on the same image, because the
full-resolution buffers cost more than the weights. So: a bigger instance or a separate worker
machine for local matting, and the hosted provider for the box as it stands. None of this is a
reason not to implement it — a laptop or a research workstation runs it happily — but it is a
reason to say so here rather than in a post-mortem.

Every import of rembg, onnxruntime and numpy happens inside a method, after the capability's flag
has been checked. Nothing in this file executes on a boot that has not asked for it.
"""

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

#: One session per model name, shared by every call in the process. Building a second one would
#: double the resident weights, which is the difference between working and being OOM-killed.
_SESSIONS: dict[str, Any] = {}
_SESSION_LOCK = threading.Lock()


def reset_session_cache() -> None:
    """Drop cached ONNX sessions and their weights.

    Exposed because a long-lived process that has finished a batch has no reason to hold several
    hundred megabytes until it restarts. Tests use it too.
    """
    with _SESSION_LOCK:
        _SESSIONS.clear()


class RembgLocalProvider(BackgroundRemovalProvider, ForegroundSeparationProvider):
    """rembg's ``remove()`` for the cutout, and its mask-only mode for the separation."""

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
        # Asking for the mask rather than the cutout: the two layers are built from the matte
        # anyway, and a mask is one channel instead of four to carry back out of the model.
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
        from rembg import remove  # noqa: PLC0415 - deliberate: rembg is an optional dependency

        session = self._session(capability)
        try:
            output = remove(
                image.data,
                session=session,
                only_mask=only_mask,
                # Cleans up the speckle a workshop background leaves in the alpha. It costs a
                # morphology pass, which is nothing beside the inference itself.
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
            # rembg returns whatever kind it was given; bytes in must mean bytes out, and if a
            # future version changes that we want a typed error rather than a PIL object in S3.
            raise self._fail(
                f"rembg returned {type(output).__name__}, expected bytes",
                capability=capability,
                remediation="Pin a rembg version whose remove() returns bytes for bytes input.",
            )
        return bytes(output)

    def _session(self, capability: Capability) -> Any:
        """The cached ONNX session, built on first use — which is also when the model downloads."""
        model = self.settings.local_model
        if self.settings.cache_local_session:
            cached = _SESSIONS.get(model)
            if cached is not None:
                return cached

        with _SESSION_LOCK:
            if self.settings.cache_local_session:
                cached = _SESSIONS.get(model)
                if cached is not None:  # another thread won the race while we waited
                    return cached

            if self.settings.local_model_dir:
                # rembg reads U2NET_HOME when it decides where weights live. setdefault so an
                # operator who already exported it keeps their answer.
                os.environ.setdefault("U2NET_HOME", self.settings.local_model_dir)

            try:
                from rembg import new_session  # noqa: PLC0415 - deliberate: optional dependency
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
