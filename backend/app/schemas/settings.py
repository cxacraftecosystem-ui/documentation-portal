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
    id: str
    name: str
    keyName: str
    keyLabel: str
    configured: bool
    keyState: str
    rankable: bool
    frozenReason: str | None = None
    testedAt: datetime | None = None
    testError: str | None = None


class TranscriptionProviderOrderDto(APIModel):
    providers: list[TranscriptionProviderDto]
    effectiveChain: list[str]
    verifiedChain: list[str]
    normalised: bool = False
    normalisedNote: str | None = None


class TranscriptionProviderOrderUpdate(APIModel):
    order: list[str]
