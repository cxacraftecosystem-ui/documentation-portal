"""Hosted tracing through vectorizer.ai, for the motifs VTracer does not do justice to.

The default mode is ``test``: free, watermarked, and enough to judge whether the paid result is
worth having. Nothing here promotes itself to ``production`` — that is a VECTORIZER_AI_MODE change
an operator makes on purpose, because it is the one setting in this package that spends money per
image.
"""


from app.ai_features.errors import ProviderNotConfigured
from app.ai_features.providers.base import VectorisationProvider
from app.ai_features.providers.http import post_image
from app.ai_features.runtime import Deadline, overrun_note
from app.ai_features.types import Capability, ImagePayload, VectorResult


class VectorizerAiProvider(VectorisationProvider):
    """HTTP basic auth with an API id and secret, multipart image in, SVG out."""

    provider_id = "vectorizer_ai"

    def vectorise(self, image: ImagePayload, deadline: Deadline) -> VectorResult:
        capability = Capability.VECTORISATION
        settings = self.settings
        missing = tuple(
            name
            for name, value in (
                ("VECTORIZER_AI_API_ID", settings.vectorizer_api_id),
                ("VECTORIZER_AI_API_SECRET", settings.vectorizer_api_secret),
            )
            if not value
        )
        if missing:
            raise ProviderNotConfigured(
                "vectorizer.ai credentials are incomplete: " + ", ".join(missing),
                missing=missing,
                capability=capability,
                provider=self.provider_id,
                remediation="Set both halves of the credential pair, then restart the process.",
            )
        deadline.check(
            "the vectorizer.ai request", capability=capability, provider=self.provider_id
        )

        body, headers = post_image(
            settings.vectorizer_endpoint,
            files={"image": (image.filename, image.data, image.mime_type)},
            data={"mode": settings.vectorizer_mode},
            auth=(str(settings.vectorizer_api_id), str(settings.vectorizer_api_secret)),
            deadline=deadline,
            capability=capability,
            provider=self.provider_id,
            max_response_bytes=settings.max_image_bytes,
            unauthorised_remediation=(
                "Check VECTORIZER_AI_API_ID and VECTORIZER_AI_API_SECRET — they are a pair, and a "
                "half-copied secret looks exactly like a wrong one."
            ),
            credit_remediation=(
                "Top up the vectorizer.ai account, or set VECTORIZER_AI_MODE=test, which the "
                "vendor documents as free and watermarked."
            ),
        )

        content_type = str(headers.get("Content-Type") or headers.get("content-type") or "")
        if "svg" not in content_type and body.lstrip()[:1] != b"<":
            raise self._fail(
                f"vectorizer.ai answered 200 with {content_type or 'no content type'}, not SVG",
                capability=capability,
                remediation="Check VECTORIZER_AI_ENDPOINT points at the vectorize API.",
            )

        notes = [f"mode={settings.vectorizer_mode}"]
        if settings.vectorizer_mode != "production":
            # Said out loud because a watermarked SVG that reaches a catalogue page is a mistake
            # that is easy to make and embarrassing to find later.
            notes.append("this result is a test render and carries the vendor's watermark")
        credits = headers.get("X-Credits-Charged") or headers.get("x-credits-charged")
        if credits:
            notes.append(f"vectorizer.ai charged {credits} credit(s)")
        return VectorResult(
            provider=self.provider_id,
            duration_ms=deadline.elapsed_ms,
            source=image,
            svg=body,
            notes=tuple(notes)
            + overrun_note(
                deadline, stage="the vectorizer.ai call", capability=capability,
                provider=self.provider_id,
            ),
        )
