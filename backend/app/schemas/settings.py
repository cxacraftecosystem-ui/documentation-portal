from datetime import datetime

from app.schemas.common import APIModel


class AppSettingDto(APIModel):
    transcriptionMode: str
    batchWindowEnabled: bool
    batchWindowStart: str
    batchWindowEnd: str
    batchTimezone: str
    sttProviderOrder: list[str]


class AppSettingUpdate(APIModel):
    transcriptionMode: str | None = None
    batchWindowEnabled: bool | None = None
    batchWindowStart: str | None = None
    batchWindowEnd: str | None = None
    batchTimezone: str | None = None
    sttProviderOrder: list[str] | None = None


class TranscriptionProviderDto(APIModel):
    """One speech-to-text engine as the ranking screen sees it.

    WHAT AN ADMIN IS ALLOWED TO KNOW ABOUT A KEY, AND WHAT THEY ARE NOT
    -------------------------------------------------------------------
    This resource is open to every admin, while the key's VALUE and its last-four hint stay behind
    ``/secrets`` and the master admin. So the fields below carry exactly two things: whether an
    engine can run at all, and what the provider said the last time we asked. Neither is a secret —
    they are the difference between a ranking that works and one that silently does nothing — and
    neither can be walked back into a credential. There is deliberately no ``hint``, no ``source``
    and no prefix here; adding one would quietly widen who can see the deployment's keys.

    ``testError`` is safe for the same reason the master-admin list is: probe errors are built from
    the HTTP status alone and never from the provider's response body, which sometimes echoes the
    offending key back (see ``managed_secrets._probe_http``).
    """

    id: str
    name: str
    keyName: str
    keyLabel: str
    # True when a key resolves at all — i.e. the transcription chain WILL call this engine. Kept
    # separate from ``rankable`` on purpose: a present-but-rejected key is configured and unusable
    # at the same time, and collapsing the two is what let the panel lie in the first place.
    configured: bool
    # NO_KEY / UNTESTED / FAILING / PASSING — see app_settings.STT_KEY_*.
    keyState: str
    # May the admin rank this engine freely? Only a PASSING test earns that.
    rankable: bool
    # Why not, in a sentence the admin can act on. Null when the engine is rankable.
    frozenReason: str | None = None
    testedAt: datetime | None = None
    testError: str | None = None


class TranscriptionProviderOrderDto(APIModel):
    providers: list[TranscriptionProviderDto]
    # What the pipeline would ACTUALLY do with this ranking right now: every engine whose key
    # resolves, in stored order. NOT the same list as the proven one below — an engine with a
    # rejected key is still attempted and still falls through, wasting a round trip per job — and
    # showing both is what makes the cost of leaving a broken key in place legible.
    effectiveChain: list[str]
    # The engines that have actually answered a test, in stored order.
    verifiedChain: list[str]
    # Set when the server had to move something to keep the freeze true. The ranking in this response
    # is the stored one either way; ``normalisedNote`` says what changed and why.
    normalised: bool = False
    normalisedNote: str | None = None


class TranscriptionProviderOrderUpdate(APIModel):
    order: list[str]
