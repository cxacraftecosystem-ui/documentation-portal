from functools import lru_cache

from pydantic import AnyHttpUrl, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = Field(alias="DATABASE_URL")
    # Route the RUNTIME Prisma client through the Supabase transaction-mode pooler (:6543,
    # pgbouncer=true) instead of the session pooler (:5432). Session mode pins one of only 15
    # server connections per client for its whole life, so a couple of uvicorn workers exhaust
    # the pool (EMAXCONNSESSION). Transaction mode releases the connection per statement and
    # multiplexes many app connections (up to the pooler's 200-client ceiling) over the 15
    # server connections. Migrations still use the session URL from the env (see core/db.py).
    database_use_transaction_pooler: bool = Field(
        default=True, alias="DATABASE_USE_TRANSACTION_POOLER"
    )
    # Max client connections the runtime engine opens to the pooler, PER uvicorn worker. With 2
    # workers and a 200-client pooler ceiling this leaves wide headroom; raise it to scale burst
    # capacity further (real query concurrency is still gated by the pooler's server pool).
    database_connection_limit: int = Field(default=40, alias="DATABASE_CONNECTION_LIMIT")
    # Optional Prisma pool acquire timeout (seconds). Left unset uses Prisma's default (10s).
    database_pool_timeout: int | None = Field(default=None, alias="DATABASE_POOL_TIMEOUT")
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
    master_admin_email: str = Field(alias="MASTER_ADMIN_EMAIL")
    master_admin_name: str = Field(default="Ankit Kumar", alias="MASTER_ADMIN_NAME")

    openai_api_key: str | None = Field(default=None, alias="OPENAI_API_KEY")
    openai_transcription_model: str = Field(default="whisper-1", alias="OPENAI_TRANSCRIPTION_MODEL")
    # Chat model used to refine a raw transcript into a clean interviewer/interviewee conversation
    # (and optionally translate it to English). Defaults to the cost-efficient gpt-4o-mini.
    openai_chat_model: str = Field(default="gpt-4o-mini", alias="OPENAI_CHAT_MODEL")
    gemini_api_key: str | None = Field(default=None, alias="GEMINI_API_KEY")
    gemini_api_keys_raw: str = Field(default="", alias="GEMINI_API_KEYS")
    # Vision model for grid measurement. Default is a current "flash lite" model; override via env to
    # any exact id (e.g. a newer flash-lite). The old gemini-1.5-flash id now 404s, so this matters.
    gemini_measurement_model: str = Field(default="gemini-2.5-flash-lite", alias="GEMINI_MEASUREMENT_MODEL")
    maptiler_api_key: str | None = Field(default=None, alias="NEXT_PUBLIC_MAPTILER_API_KEY")
    media_queue_worker_enabled: bool = Field(default=True, alias="MEDIA_QUEUE_WORKER_ENABLED")
    media_queue_interval_seconds: float = Field(default=5.0, alias="MEDIA_QUEUE_INTERVAL_SECONDS")
    media_queue_batch_size: int = Field(default=3, alias="MEDIA_QUEUE_BATCH_SIZE")
    media_queue_job_max_attempts: int = Field(default=3, alias="MEDIA_QUEUE_JOB_MAX_ATTEMPTS")

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

    @property
    def gemini_api_keys(self) -> list[str]:
        """All configured Gemini keys, in order, de-duplicated.

        Combines the single ``GEMINI_API_KEY`` (kept for backward compatibility) with any number
        of keys in ``GEMINI_API_KEYS`` (comma- or newline-separated). Add more keys to the env var
        at any time without touching code; the measurement worker rotates across them.
        """
        raw_parts: list[str] = []
        if self.gemini_api_key:
            raw_parts.append(self.gemini_api_key)
        for chunk in self.gemini_api_keys_raw.replace("\n", ",").split(","):
            candidate = chunk.strip()
            if candidate:
                raw_parts.append(candidate)
        seen: set[str] = set()
        ordered: list[str] = []
        for key in raw_parts:
            if key not in seen:
                seen.add(key)
                ordered.append(key)
        return ordered


@lru_cache
def get_settings() -> Settings:
    return Settings()
