"""Wire shapes for the runtime-editable API keys (see app.services.managed_secrets).

Note what is NOT here: no field of :class:`ManagedSecretDto` can carry a plaintext key. The list/
update/test responses all use that one model precisely so a future field cannot accidentally leak a
value into the ordinary (non-reveal) responses — the only model that carries plaintext is
:class:`ManagedSecretRevealDto`, returned by the single master-admin reveal endpoint.
"""

from datetime import datetime

from pydantic import Field

from app.schemas.common import APIModel


class ManagedSecretDto(APIModel):
    key: str
    label: str
    description: str | None = None
    # True when the key resolves to *something* — a stored override or an environment value.
    configured: bool
    # "database" (an override is stored), "environment" (only the deployed env var), or "unset".
    source: str
    # Last four characters of the effective value, so two keys can be told apart without revealing
    # either. "…" when the value is too short to partially show safely.
    hint: str | None = None
    lastStatus: str = "UNKNOWN"
    lastCheckedAt: datetime | None = None
    lastError: str | None = None
    # Display name (or email) of whoever last saved the override; null for environment-sourced keys.
    updatedBy: str | None = None
    updatedAt: datetime | None = None


class ManagedSecretSetIn(APIModel):
    """Body of PUT /secrets/{key}. Bounded because an API key is short — an 8 KB body here would be
    a paste of the wrong thing (a whole .env, a JSON credential file)."""

    value: str = Field(min_length=1, max_length=8000)


class ManagedSecretRevealDto(APIModel):
    """The eye button. Master admin only, one key at a time, and the read is audit-logged."""

    key: str
    # The value actually in force: the decrypted override when stored, otherwise the environment
    # value (that is what the provider calls use, so it is what the admin needs to see). Null when
    # the key is unset, or when a stored value cannot be decrypted any more.
    value: str | None = None
    source: str
