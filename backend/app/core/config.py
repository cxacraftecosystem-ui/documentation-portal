from functools import lru_cache

from pydantic import AnyHttpUrl, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = Field(alias="DATABASE_URL")
    jwt_secret: str = Field(alias="JWT_SECRET")
    jwt_algorithm: str = "HS256"
    jwt_expires_minutes: int = Field(default=60 * 24 * 7, alias="JWT_EXPIRES_MINUTES")

    aws_access_key_id: str = Field(alias="AWS_ACCESS_KEY_ID")
    aws_secret_access_key: str = Field(alias="AWS_SECRET_ACCESS_KEY")
    aws_region: str = Field(default="us-east-1", alias="AWS_REGION")
    aws_s3_bucket: str = Field(alias="AWS_S3_BUCKET")
    aws_s3_endpoint: str | None = Field(default=None, alias="AWS_S3_ENDPOINT")
    aws_s3_public_base_url: str | None = Field(default=None, alias="AWS_S3_PUBLIC_BASE_URL")

    next_public_app_url: AnyHttpUrl | str = Field(
        default="http://localhost:3000", alias="NEXT_PUBLIC_APP_URL"
    )
    backend_cors_origins: str = Field(default="http://localhost:3000", alias="BACKEND_CORS_ORIGINS")

    google_client_id: str | None = Field(default=None, alias="GOOGLE_CLIENT_ID")
    google_android_client_id: str | None = Field(default=None, alias="GOOGLE_ANDROID_CLIENT_ID")
    master_admin_email: str = Field(default="ankits1802@gmail.com", alias="MASTER_ADMIN_EMAIL")

    openai_api_key: str | None = Field(default=None, alias="OPENAI_API_KEY")
    openai_transcription_model: str = Field(default="whisper-1", alias="OPENAI_TRANSCRIPTION_MODEL")
    gemini_api_key: str | None = Field(default=None, alias="GEMINI_API_KEY")
    maptiler_api_key: str | None = Field(default=None, alias="NEXT_PUBLIC_MAPTILER_API_KEY")

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        populate_by_name=True,
        extra="ignore",
    )

    @property
    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.backend_cors_origins.split(",") if origin.strip()]

    @property
    def google_client_ids(self) -> list[str]:
        return [value for value in [self.google_client_id, self.google_android_client_id] if value]


@lru_cache
def get_settings() -> Settings:
    return Settings()
