"""Local raster-to-SVG tracing with VTracer. The one local provider that is cheap enough to mean it.

VTracer is a small Rust extension with no model to download: measured here at a 273 MB peak
working set and about 2.5 s for a 6-megapixel flat-colour motif on a developer laptop, against a
27-59 MB interpreter baseline. That is a different order of thing from local U2Net, which is why
``auto`` prefers local for vectorisation and hosted for matting. The measurement provenance sits
in the provider's ``ResourceProfile`` in :mod:`app.ai_features.registry`; the two burstable vCPU
of a t3.micro will be slower, and a photograph is harder than a motif.

WHY THE PATH API AND NOT THE BYTES API. ``vtracer.convert_raw_image_to_svg`` segfaulted the
interpreter on every input tried under CPython 3.14.6 with vtracer 0.6.15, while the path-based
``convert_image_to_svg_py`` returned correct SVG for the same bytes; on CPython 3.12.10 both
worked. A segfault cannot be caught and retried, so this takes the path that survived both
interpreters. The cost is writing at most 12 MB to a temporary file, which is nothing next to the
trace itself.
"""

import tempfile
from pathlib import Path

from app.ai_features.providers.base import VectorisationProvider
from app.ai_features.runtime import Deadline, overrun_note
from app.ai_features.types import Capability, ImagePayload, VectorResult


class VtracerLocalProvider(VectorisationProvider):
    """Colour or binary tracing, tuned by the three AI_VECTOR_* settings."""

    provider_id = "vtracer_local"

    def vectorise(self, image: ImagePayload, deadline: Deadline) -> VectorResult:
        capability = Capability.VECTORISATION
        deadline.check("tracing", capability=capability, provider=self.provider_id)

        import vtracer  # noqa: PLC0415 - deliberate: vtracer is an optional dependency

        settings = self.settings
        with tempfile.TemporaryDirectory(prefix="ai_vectorise_") as workspace:
            source_path = Path(workspace) / f"input.{image.extension}"
            output_path = Path(workspace) / "output.svg"
            source_path.write_bytes(image.data)
            try:
                vtracer.convert_image_to_svg_py(
                    str(source_path),
                    str(output_path),
                    colormode=settings.vector_colormode,
                    filter_speckle=settings.vector_filter_speckle,
                    # Ignored in binary mode; passing it anyway keeps one call site for both.
                    color_precision=settings.vector_color_precision,
                )
            except Exception as exc:
                raise self._fail(
                    f"vtracer could not trace {image.filename}: {exc}",
                    capability=capability,
                    remediation=(
                        "Check AI_VECTOR_COLORMODE is 'color' or 'binary' and that the image "
                        "decodes; VTracer reads PNG and JPEG only."
                    ),
                ) from exc
            try:
                svg = output_path.read_bytes()
            except OSError as exc:
                raise self._fail(
                    f"vtracer wrote no output for {image.filename}: {exc}",
                    capability=capability,
                ) from exc

        if svg.lstrip()[:1] != b"<":
            raise self._fail(
                "vtracer produced something that does not begin like SVG",
                capability=capability,
                remediation="Check the installed vtracer version against the pinned extra.",
            )

        notes = [f"{settings.vector_colormode} mode, speckle filter {settings.vector_filter_speckle}"]
        if len(svg) > 1_000_000:
            # A megabyte of paths per product photograph adds up across 925 media rows, and it is
            # a browser problem long before it is a storage one.
            notes.append(
                f"the SVG is {len(svg) // 1024} KB — raise AI_VECTOR_FILTER_SPECKLE or trace a "
                "smaller raster if this is going to a browser"
            )
        return VectorResult(
            provider=self.provider_id,
            duration_ms=deadline.elapsed_ms,
            source=image,
            svg=svg,
            notes=tuple(notes)
            + overrun_note(
                deadline, stage="tracing", capability=capability, provider=self.provider_id
            ),
        )
