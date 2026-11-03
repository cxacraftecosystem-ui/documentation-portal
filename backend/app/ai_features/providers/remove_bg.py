from app.ai_features.errors import ProviderNotConfigured
from app.ai_features.providers.base import (
    BackgroundRemovalProvider,
    ForegroundSeparationProvider,
    compose_layers,
    extract_alpha,
)
from app.ai_features.providers.http import post_image
from app.ai_features.runtime import Deadline, overrun_note
from app.ai_features.types import Capability, CutoutResult, ImagePayload, SeparationResult


class RemoveBgProvider(BackgroundRemovalProvider, ForegroundSeparationProvider):
    provider_id = "remove_bg"

    def remove_background(self, image: ImagePayload, deadline: Deadline) -> CutoutResult:
        capability = Capability.BACKGROUND_REMOVAL
        cutout, notes = self._cutout(image, capability, deadline)
        return CutoutResult(
            provider=self.provider_id,
            duration_ms=deadline.elapsed_ms,
            source=image,
            image=cutout,
            notes=notes
            + overrun_note(
                deadline, stage="the remove.bg call", capability=capability,
                provider=self.provider_id,
            ),
        )

    def separate_foreground(self, image: ImagePayload, deadline: Deadline) -> SeparationResult:
        capability = Capability.FOREGROUND_SEPARATION
        cutout, notes = self._cutout(image, capability, deadline)
        deadline.check("compositing", capability=capability, provider=self.provider_id)
        matte = extract_alpha(cutout, capability=capability, provider=self.provider_id)
        foreground, background, normalised, layer_notes = compose_layers(
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
            + layer_notes
            + overrun_note(
                deadline, stage="the remove.bg separation", capability=capability,
                provider=self.provider_id,
            ),
        )

    def _cutout(
        self, image: ImagePayload, capability: Capability, deadline: Deadline
    ) -> tuple[bytes, tuple[str, ...]]:
        api_key = self.settings.remove_bg_api_key
        if not api_key:
            raise ProviderNotConfigured(
                "REMOVE_BG_API_KEY is not set",
                missing=("REMOVE_BG_API_KEY",),
                capability=capability,
                provider=self.provider_id,
                remediation="Set REMOVE_BG_API_KEY in the backend environment and restart.",
            )
        deadline.check("the remove.bg request", capability=capability, provider=self.provider_id)

        body, headers = post_image(
            self.settings.remove_bg_endpoint,
            files={"image_file": (image.filename, image.data, image.mime_type)},
            data={"size": self.settings.remove_bg_size, "format": "png"},
            headers={"X-Api-Key": api_key, "Accept": "image/*"},
            deadline=deadline,
            capability=capability,
            provider=self.provider_id,
            max_response_bytes=self.settings.max_image_bytes,
            unauthorised_remediation="Check REMOVE_BG_API_KEY against the remove.bg dashboard.",
            credit_remediation=(
                "Top up the remove.bg account, or set REMOVE_BG_SIZE=preview, which the vendor "
                "documents as free at up to 0.25 MP."
            ),
        )

        content_type = str(headers.get("Content-Type") or headers.get("content-type") or "")
        if not content_type.startswith("image/"):
            raise self._fail(
                f"remove.bg answered 200 with {content_type or 'no content type'}, not an image",
                capability=capability,
                remediation="Check REMOVE_BG_ENDPOINT points at the removebg API.",
            )

        notes: list[str] = []
        charged = headers.get("X-Credits-Charged") or headers.get("x-credits-charged")
        if charged:
            notes.append(f"remove.bg charged {charged} {'credit' if charged.strip() == '1' else 'credits'}")
        return body, tuple(notes)
