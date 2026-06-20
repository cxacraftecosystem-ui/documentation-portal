from app.schemas.common import APIModel


class AppSettingDto(APIModel):
    transcriptionMode: str
    batchWindowEnabled: bool
    batchWindowStart: str
    batchWindowEnd: str
    batchTimezone: str


class AppSettingUpdate(APIModel):
    transcriptionMode: str | None = None
    batchWindowEnabled: bool | None = None
    batchWindowStart: str | None = None
    batchWindowEnd: str | None = None
    batchTimezone: str | None = None
