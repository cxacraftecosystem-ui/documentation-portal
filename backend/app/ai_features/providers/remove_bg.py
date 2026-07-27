"""Hosted matting through remove.bg. The provider that actually fits on the production box.

It needs nothing that is not already installed for background removal — ``requests`` is a core
dependency — so switching this on is an API key and two flags, with no new packages and no model
download. Separation is the exception: splitting the returned cutout into two layers is Pillow
work, so that one capability needs the ``ai`` extra.

WHAT LEAVES THE BUILDING. The photograph is uploaded to a third party. For craft documentation
that is a consent question as much as a technical one — the images are of named artisans' work,
gathered under a research agreement — so the decision belongs to whoever holds that agreement,
not to whoever sets the flag. ``docs/AI_FEATURES.md`` says the same thing where an operator will
read it.
"""


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
    """One endpoint, used twice: directly for the cutout, and as the matte source for layers."""

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
        # The matte is recovered from the alpha of the cutout we already paid for; asking the API
        # for a mask separately would be a second credit for information we are holding.
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
            # format=png because the whole point is the alpha channel; the default would hand back
            # an opaque JPEG on some plans and quietly lose the transparency.
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
            # Persisted with the result so the spend is auditable from the job record rather than
            # only from the vendor's dashboard.
            notes.append(f"remove.bg charged {charged} credit(s)")
        return body, tuple(notes)
