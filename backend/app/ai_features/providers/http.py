"""One HTTP call, translated into this package's error family — shared by both hosted providers.

WHY THE RESPONSE IS STREAMED AND CAPPED. ``response.content`` reads whatever the other end sends.
On a 1 GiB box, a misconfigured endpoint or a redirect to something enormous would be an OOM kill
rather than a failed job, so the body is pulled in chunks and abandoned the moment it passes a
ceiling derived from the input limit. The same reasoning as the input validation in
:mod:`app.ai_features.imaging`, at the other end of the call.

WHY THE STATUS MAPPING LIVES HERE. Both vendors answer 402 for "out of credits" and 429 for "too
fast", and the differences that matter — is this worth retrying, is this an operator's problem —
are the same question at every call site. Only the remediation sentences differ, so those are
arguments; the message inside the vendor's error JSON is dug out by shape rather than by schema,
because two vendors have already chosen two different shapes for it.
"""

import json
from typing import Any, Mapping

from app.ai_features.errors import (
    ProviderFailed,
    ProviderNotConfigured,
    ProviderRateLimited,
    ProviderTimeout,
)
from app.ai_features.runtime import Deadline
from app.ai_features.types import Capability

#: A hosted cutout of a 12 MB JPEG comes back as a full-resolution PNG, which is legitimately
#: several times the input. Four times the input ceiling is generous for that and still far short
#: of anything that would trouble the box.
_RESPONSE_SIZE_MULTIPLIER = 4
_CONNECT_TIMEOUT_CEILING = 10.0


def _first_message(payload: Any) -> str | None:
    """Best effort at the human-readable line inside a vendor's error JSON."""
    if isinstance(payload, Mapping):
        for key in ("message", "title", "detail", "description"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        for key in ("error", "errors"):
            nested = payload.get(key)
            found = _first_message(nested)
            if found:
                return found
    if isinstance(payload, list):
        for item in payload:
            found = _first_message(item)
            if found:
                return found
    if isinstance(payload, str) and payload.strip():
        return payload.strip()
    return None


def describe_error_body(body: bytes) -> str:
    """A short, log-safe description of an error response, JSON or not."""
    text = body[:2000].decode("utf-8", errors="replace").strip()
    if not text:
        return "(empty response body)"
    try:
        return _first_message(json.loads(text)) or text[:300]
    except ValueError:
        return text[:300]


def post_image(
    url: str,
    *,
    files: Mapping[str, Any],
    data: Mapping[str, Any] | None = None,
    headers: Mapping[str, str] | None = None,
    auth: tuple[str, str] | None = None,
    deadline: Deadline,
    capability: Capability,
    provider: str,
    max_response_bytes: int,
    unauthorised_remediation: str,
    credit_remediation: str,
) -> tuple[bytes, Mapping[str, str]]:
    """POST a multipart image and return ``(body, response headers)``, or raise a typed error."""
    import requests  # noqa: PLC0415 - a core dependency, but the no-imports-at-module-scope rule
    # in this package has no exceptions; one rule with no carve-outs is the one that survives.

    remaining = deadline.remaining()
    timeout = (min(_CONNECT_TIMEOUT_CEILING, max(1.0, remaining)), max(1.0, remaining))
    ceiling = max_response_bytes * _RESPONSE_SIZE_MULTIPLIER

    try:
        with requests.post(
            url,
            files=dict(files),
            data=dict(data or {}),
            headers=dict(headers or {}),
            auth=auth,
            timeout=timeout,
            stream=True,
        ) as response:
            body = bytearray()
            for chunk in response.iter_content(chunk_size=64 * 1024):
                body.extend(chunk)
                if len(body) > ceiling:
                    raise ProviderFailed(
                        f"{provider} sent more than {ceiling} bytes; the response was abandoned",
                        capability=capability,
                        provider=provider,
                        remediation="Check the endpoint URL — this does not look like an image.",
                    )
            _raise_for_status(
                response.status_code,
                dict(response.headers),
                bytes(body),
                capability=capability,
                provider=provider,
                unauthorised_remediation=unauthorised_remediation,
                credit_remediation=credit_remediation,
            )
            return bytes(body), dict(response.headers)
    except requests.Timeout as exc:
        raise ProviderTimeout(
            f"{provider} did not answer within {remaining:.0f}s",
            capability=capability,
            provider=provider,
            remediation=(
                "Raise AI_FEATURES_TIMEOUT_SECONDS, or run this on the background queue where a "
                "slow provider only delays a job."
            ),
        ) from exc
    except requests.RequestException as exc:
        raise ProviderFailed(
            f"could not reach {provider}: {exc}",
            capability=capability,
            provider=provider,
            remediation=(
                "Check outbound HTTPS from the API host and that the endpoint setting is right."
            ),
        ) from exc


def _raise_for_status(
    status: int,
    headers: Mapping[str, str],
    body: bytes,
    *,
    capability: Capability,
    provider: str,
    unauthorised_remediation: str,
    credit_remediation: str,
) -> None:
    if 200 <= status < 300:
        return
    detail = describe_error_body(body)
    if status in (401, 403):
        raise ProviderNotConfigured(
            f"{provider} rejected the credentials ({status}): {detail}",
            capability=capability,
            provider=provider,
            remediation=unauthorised_remediation,
        )
    if status == 402:
        raise ProviderFailed(
            f"{provider} has no credit left ({status}): {detail}",
            capability=capability,
            provider=provider,
            remediation=credit_remediation,
        )
    if status == 429:
        retry_after = headers.get("Retry-After") or headers.get("retry-after")
        try:
            seconds = float(retry_after) if retry_after else None
        except ValueError:  # the header may be an HTTP date; a missing hint is not a failure
            seconds = None
        raise ProviderRateLimited(
            f"{provider} is rate limiting us ({status}): {detail}",
            retry_after=seconds,
            capability=capability,
            provider=provider,
            remediation="Retry later, or slow the queue down. This is a transient condition.",
        )
    raise ProviderFailed(
        f"{provider} returned {status}: {detail}",
        capability=capability,
        provider=provider,
        remediation=(
            "Transient server error; retry."
            if status >= 500
            else "The request was rejected — check the image and the provider settings."
        ),
    )
