import tempfile
from pathlib import Path

from app.ai_features.providers.base import VectorisationProvider
from app.ai_features.runtime import Deadline, overrun_note
from app.ai_features.types import Capability, ImagePayload, VectorResult


class VtracerLocalProvider(VectorisationProvider):
    provider_id = "vtracer_local"

    def vectorise(self, image: ImagePayload, deadline: Deadline) -> VectorResult:
        capability = Capability.VECTORISATION
        deadline.check("tracing", capability=capability, provider=self.provider_id)

        import vtracer  # noqa: PLC0415

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
