from functools import lru_cache
from ipaddress import ip_address
from urllib.parse import parse_qsl, urlsplit, urlunsplit

from pydantic import AnyHttpUrl, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.two_factor_keys import KeyFormatError, parse_keyring

_ALLOWED_JWT_ALGORITHMS = frozenset({"HS256", "HS384", "HS512"})

_LOCAL_DB_HOSTNAMES = frozenset({"localhost", "host.docker.internal", "postgres", "db"})

_SSL_QUERY_KEYS = frozenset({"sslmode", "sslaccept", "sslcert", "sslidentity", "sslpassword"})


def _is_local_db_host(host: str) -> bool:
    host = host.strip().strip("[]").lower()
    if not host or host in _LOCAL_DB_HOSTNAMES or host.endswith((".local", ".internal", ".localhost")):
        return True
    try:
        address = ip_address(host)
    except ValueError:
        return False
    return address.is_loopback or address.is_private or address.is_link_local


def _with_explicit_sslmode(url: str, require: bool | None) -> str:
    if require is False:
        return url
    try:
        parts = urlsplit(url)
    except ValueError:
        return url
    if not parts.scheme.startswith("postgres"):
        return url
    existing_keys = {key.lower() for key, _ in parse_qsl(parts.query, keep_blank_values=True)}
    if existing_keys & _SSL_QUERY_KEYS:
        return url
    if require is None and _is_local_db_host(parts.hostname or ""):
        return url
    query = f"{parts.query}&sslmode=require" if parts.query else "sslmode=require"
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


class Settings(BaseSettings):
    database_url: str = Field(alias="DATABASE_URL")
    database_use_transaction_pooler: bool = Field(
        default=True, alias="DATABASE_USE_TRANSACTION_POOLER"
    )
    database_connection_limit: int = Field(default=10, alias="DATABASE_CONNECTION_LIMIT")
    database_pool_timeout: int | None = Field(default=None, alias="DATABASE_POOL_TIMEOUT")
    database_require_ssl: bool | None = Field(default=None, alias="DATABASE_REQUIRE_SSL")
    jwt_secret: str = Field(alias="JWT_SECRET")
    jwt_algorithm: str = "HS256"
    jwt_expires_minutes: int = Field(default=60 * 24 * 7, alias="JWT_EXPIRES_MINUTES")
    dataset_token_expires_minutes: int = Field(
        default=60 * 24 * 30, alias="DATASET_TOKEN_EXPIRES_MINUTES"
    )
    allow_weak_jwt_secret: bool = Field(default=False, alias="ALLOW_WEAK_JWT_SECRET")

    auth_user_cache_enabled: bool = Field(default=True, alias="AUTH_USER_CACHE_ENABLED")
    auth_user_cache_ttl_seconds: float = Field(default=5.0, alias="AUTH_USER_CACHE_TTL_SECONDS")
    trusted_proxy_hops: int = Field(default=2, ge=1, le=5, alias="TRUSTED_PROXY_HOPS")
    trusted_proxy_cidrs: str = Field(default="127.0.0.1/32,::1/128", alias="TRUSTED_PROXY_CIDRS")
    origin_verify_secret: str | None = Field(default=None, alias="ORIGIN_VERIFY_SECRET")
    web_proxy_secret: str | None = Field(default=None, alias="WEB_PROXY_SECRET")
    auth_user_cache_max_entries: int = Field(default=512, alias="AUTH_USER_CACHE_MAX_ENTRIES")
    secrets_encryption_key: str | None = Field(default=None, alias="SECRETS_ENCRYPTION_KEY")
    two_factor_encryption_key: str | None = Field(default=None, alias="TWO_FACTOR_ENCRYPTION_KEY")
    two_factor_encryption_previous_keys: str | None = Field(
        default=None, alias="TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS"
    )
    aadhaar_fingerprint_key: str | None = Field(default=None, alias="AADHAAR_FINGERPRINT_KEY")
    artisan_duplicate_hold: bool = Field(default=True, alias="ARTISAN_DUPLICATE_HOLD")

    security_hsts_enabled: bool = Field(default=True, alias="SECURITY_HSTS_ENABLED")
    security_hsts_max_age: int = Field(default=63_072_000, alias="SECURITY_HSTS_MAX_AGE")
    security_force_hsts: bool = Field(default=False, alias="SECURITY_FORCE_HSTS")
    backend_expose_docs: bool = Field(default=False, alias="BACKEND_EXPOSE_DOCS")

    aws_access_key_id: str = Field(alias="AWS_ACCESS_KEY_ID")
    aws_secret_access_key: str = Field(alias="AWS_SECRET_ACCESS_KEY")
    aws_region: str = Field(default="us-east-1", alias="AWS_REGION")
    aws_s3_bucket: str = Field(alias="AWS_S3_BUCKET")
    aws_s3_endpoint: str | None = Field(default=None, alias="AWS_S3_ENDPOINT")
    aws_s3_public_base_url: str | None = Field(default=None, alias="AWS_S3_PUBLIC_BASE_URL")
    media_presigned_reads: bool = Field(default=True, alias="MEDIA_PRESIGNED_READS")
    media_presigned_read_ttl_seconds: int = Field(default=900, alias="MEDIA_PRESIGNED_READ_TTL_SECONDS")
    media_upload_max_bytes: int = Field(default=1_073_741_824, alias="MEDIA_UPLOAD_MAX_BYTES")
    aws_s3_sse_algorithm: str = Field(default="AES256", alias="AWS_S3_SSE_ALGORITHM")

    mail_from_address: str | None = Field(default=None, alias="MAIL_FROM_ADDRESS")
    mail_from_name: str = Field(default="Field Repository", alias="MAIL_FROM_NAME")
    mail_reply_to: str | None = Field(default=None, alias="MAIL_REPLY_TO")
    mail_ses_region: str = Field(default="ap-south-1", alias="MAIL_SES_REGION")
    mail_ses_configuration_set: str | None = Field(
        default=None, alias="MAIL_SES_CONFIGURATION_SET"
    )
    mail_max_attempts: int = Field(default=5, alias="MAIL_MAX_ATTEMPTS")
    mail_batch_size: int = Field(default=10, alias="MAIL_BATCH_SIZE")

    weekly_digest_enabled: bool = Field(default=True, alias="WEEKLY_DIGEST_ENABLED")
    report_schedules_enabled: bool = Field(default=True, alias="REPORT_SCHEDULES_ENABLED")
    report_schedule_attach_max_bytes: int = Field(
        default=7 * 1024 * 1024, alias="REPORT_SCHEDULE_ATTACH_MAX_BYTES"
    )

    app_env: str = Field(default="development", alias="APP_ENV")
    next_public_app_url: AnyHttpUrl | str = Field(
        default="http://localhost:3000", alias="NEXT_PUBLIC_APP_URL"
    )
    backend_cors_origins: str = Field(default="http://localhost:3000", alias="BACKEND_CORS_ORIGINS")

    google_client_id: str | None = Field(default=None, alias="GOOGLE_CLIENT_ID")
    google_android_client_id: str | None = Field(default=None, alias="GOOGLE_ANDROID_CLIENT_ID")
    microsoft_client_id: str | None = Field(default=None, alias="MICROSOFT_CLIENT_ID")
    microsoft_client_secret: str | None = Field(default=None, alias="MICROSOFT_CLIENT_SECRET")
    microsoft_tenant: str = Field(default="common", alias="MICROSOFT_TENANT")
    yahoo_client_id: str | None = Field(default=None, alias="YAHOO_CLIENT_ID")
    yahoo_client_secret: str | None = Field(default=None, alias="YAHOO_CLIENT_SECRET")
    master_admin_email: str = Field(alias="MASTER_ADMIN_EMAIL")
    master_admin_name: str = Field(default="Ankit Kumar", alias="MASTER_ADMIN_NAME")
    default_signup_role: str = Field(default="CROWDSOURCE_VOLUNTEER", alias="DEFAULT_SIGNUP_ROLE")

    access_roster_enforced: bool = Field(default=True, alias="ACCESS_ROSTER_ENFORCED")

    access_roster_max_pending: int = Field(default=2000, alias="ACCESS_ROSTER_MAX_PENDING")

    elevenlabs_api_key: str | None = Field(default=None, alias="ELEVENLABS_API_KEY")
    elevenlabs_stt_model: str = Field(default="scribe_v2", alias="ELEVENLABS_STT_MODEL")
    deepgram_api_key: str | None = Field(default=None, alias="DEEPGRAM_API_KEY")
    deepgram_stt_model: str = Field(default="nova-3", alias="DEEPGRAM_STT_MODEL")
    meta_model_api_key: str | None = Field(default=None, alias="META_MODEL_API_KEY")
    muse_stt_model: str = Field(default="muse-voice-transcribe-1.0", alias="MUSE_STT_MODEL")
    openai_api_key: str | None = Field(default=None, alias="OPENAI_API_KEY")
    openai_transcription_model: str = Field(default="whisper-1", alias="OPENAI_TRANSCRIPTION_MODEL")
    openai_chat_model: str = Field(default="gpt-6-luna", alias="OPENAI_CHAT_MODEL")
    anthropic_api_key: str | None = Field(default=None, alias="ANTHROPIC_API_KEY")
    ai_text_provider: str = Field(default="GEMINI", alias="AI_TEXT_PROVIDER")
    gemini_api_key: str | None = Field(default=None, alias="GEMINI_API_KEY")
    gemini_api_keys_raw: str = Field(default="", alias="GEMINI_API_KEYS")
    gemini_measurement_model: str = Field(default="gemini-3.5-flash-lite", alias="GEMINI_MEASUREMENT_MODEL")
    maptiler_api_key: str | None = Field(default=None, alias="NEXT_PUBLIC_MAPTILER_API_KEY")
    media_queue_worker_enabled: bool = Field(default=True, alias="MEDIA_QUEUE_WORKER_ENABLED")
    media_queue_interval_seconds: float = Field(default=5.0, alias="MEDIA_QUEUE_INTERVAL_SECONDS")
    media_queue_batch_size: int = Field(default=3, alias="MEDIA_QUEUE_BATCH_SIZE")
    media_queue_insights_concurrency: int = Field(default=2, alias="MEDIA_QUEUE_INSIGHTS_CONCURRENCY")
    media_queue_job_max_attempts: int = Field(default=3, alias="MEDIA_QUEUE_JOB_MAX_ATTEMPTS")

    scale_cache_enabled: bool = Field(default=False, alias="SCALE_CACHE_ENABLED")
    scale_cache_backend: str = Field(default="memory", alias="SCALE_CACHE_BACKEND")
    scale_cache_ttl_seconds: float = Field(default=30.0, alias="SCALE_CACHE_TTL_SECONDS")
    scale_cache_max_entries: int = Field(default=512, alias="SCALE_CACHE_MAX_ENTRIES")
    scale_cache_max_bytes: int = Field(default=33_554_432, alias="SCALE_CACHE_MAX_BYTES")
    scale_cache_max_entry_bytes: int = Field(default=1_048_576, alias="SCALE_CACHE_MAX_ENTRY_BYTES")
    scale_cache_singleflight_timeout_seconds: float = Field(
        default=10.0, alias="SCALE_CACHE_SINGLEFLIGHT_TIMEOUT_SECONDS"
    )
    scale_redis_url: str | None = Field(default=None, alias="SCALE_REDIS_URL")
    scale_redis_timeout_seconds: float = Field(default=0.5, alias="SCALE_REDIS_TIMEOUT_SECONDS")

    scale_keyset_pagination_enabled: bool = Field(
        default=False, alias="SCALE_KEYSET_PAGINATION_ENABLED"
    )

    scale_approx_count_enabled: bool = Field(default=False, alias="SCALE_APPROX_COUNT_ENABLED")
    scale_approx_count_threshold: int = Field(default=5_000, alias="SCALE_APPROX_COUNT_THRESHOLD")

    scale_rate_limit_enabled: bool = Field(default=False, alias="SCALE_RATE_LIMIT_ENABLED")
    scale_rate_limit_requests: int = Field(default=120, alias="SCALE_RATE_LIMIT_REQUESTS")
    scale_rate_limit_window_seconds: float = Field(
        default=60.0, alias="SCALE_RATE_LIMIT_WINDOW_SECONDS"
    )

    database_read_replica_url: str | None = Field(default=None, alias="DATABASE_READ_REPLICA_URL")

    ai_answer_drafts_enabled: bool = Field(default=False, alias="AI_ANSWER_DRAFTS_ENABLED")

    cross_portal_identity_enabled: bool = Field(
        default=False, alias="CROSS_PORTAL_IDENTITY_ENABLED"
    )
    cross_portal_token: str | None = Field(default=None, alias="CROSS_PORTAL_TOKEN")

    unified_search_enabled: bool = Field(default=False, alias="UNIFIED_SEARCH_ENABLED")
    search_embeddings_enabled: bool = Field(default=False, alias="SEARCH_EMBEDDINGS_ENABLED")
    search_embedding_provider: str = Field(default="openai", alias="SEARCH_EMBEDDING_PROVIDER")
    search_embedding_model: str = Field(
        default="text-embedding-3-small", alias="SEARCH_EMBEDDING_MODEL"
    )
    cross_portal_search_enabled: bool = Field(default=False, alias="CROSS_PORTAL_SEARCH_ENABLED")
    designer_portal_api_url: str | None = Field(default=None, alias="DESIGNER_PORTAL_API_URL")

    cross_portal_coverage_enabled: bool = Field(
        default=False, alias="CROSS_PORTAL_COVERAGE_ENABLED"
    )
    artisan_cards_enabled: bool = Field(default=True, alias="ARTISAN_CARDS_ENABLED")
    artisan_card_key: str | None = Field(default=None, alias="ARTISAN_CARD_KEY")
    artisan_card_key_version: int = Field(default=1, ge=1, le=9, alias="ARTISAN_CARD_KEY_VERSION")
    artisan_card_key_previous: str | None = Field(default=None, alias="ARTISAN_CARD_KEY_PREVIOUS")
    artisan_card_resolve_miss_limit: int = Field(
        default=20, ge=1, alias="ARTISAN_CARD_RESOLVE_MISS_LIMIT"
    )
    artisan_card_issuer: str = Field(default="F", pattern="^[DF]$", alias="ARTISAN_CARD_ISSUER")
    questionnaire_v2: bool = Field(default=True, alias="QUESTIONNAIRE_V2")
    questionnaire_min_android_version_code_for_create: int | None = Field(
        default=None, ge=1, alias="QUESTIONNAIRE_MIN_ANDROID_VERSION_CODE_FOR_CREATE"
    )
    questionnaire_min_web_version_code_for_create: int | None = Field(
        default=None, ge=1, alias="QUESTIONNAIRE_MIN_WEB_VERSION_CODE_FOR_CREATE"
    )

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        populate_by_name=True,
        extra="ignore",
        hide_input_in_errors=True,
    )

    @field_validator("jwt_algorithm")
    @classmethod
    def _normalise_jwt_algorithm(cls, value: str) -> str:
        algorithm = value.strip().upper()
        if algorithm not in _ALLOWED_JWT_ALGORITHMS:
            raise ValueError(
                f"JWT_ALGORITHM must be one of {sorted(_ALLOWED_JWT_ALGORITHMS)}; got {value!r}"
            )
        return algorithm

    @model_validator(mode="after")
    def _check_two_factor_keys(self) -> "Settings":
        try:
            parse_keyring(self.two_factor_encryption_key, self.two_factor_encryption_previous_keys)
        except KeyFormatError as exc:
            raise ValueError(f"TWO_FACTOR_ENCRYPTION_KEY / _PREVIOUS_KEYS: {exc}") from None
        return self

    @model_validator(mode="after")
    def _harden_database_url(self) -> "Settings":
        self.database_url = _with_explicit_sslmode(self.database_url, self.database_require_ssl)
        if self.database_read_replica_url:
            self.database_read_replica_url = _with_explicit_sslmode(
                self.database_read_replica_url, self.database_require_ssl
            )
        return self

    @property
    def cors_origins(self) -> list[str]:
        origins: list[str] = []
        seen: set[str] = set()
        for chunk in self.backend_cors_origins.replace("\n", ",").split(","):
            origin = chunk.strip().strip("[]").strip().strip("\"'").strip().rstrip("/")
            if not origin or origin in seen:
                continue
            seen.add(origin)
            origins.append(origin)
        return origins

    @property
    def cors_allows_wildcard(self) -> bool:
        return "*" in self.cors_origins

    @property
    def cors_allow_credentials(self) -> bool:
        return not self.cors_allows_wildcard

    @property
    def google_client_ids(self) -> list[str]:
        return [value for value in [self.google_client_id, self.google_android_client_id] if value]

    @property
    def gemini_api_keys(self) -> list[str]:
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


_LOCAL_APP_HOSTS = frozenset({"localhost", "0.0.0.0", "::", "::1", "host.docker.internal"})


def app_url_problem(url: str) -> str | None:
    from urllib.parse import urlsplit

    raw = str(url or "").strip()
    if not raw:
        return "NEXT_PUBLIC_APP_URL is empty"
    parts = urlsplit(raw)
    host = (parts.hostname or "").lower()
    if parts.scheme != "https":
        return f"NEXT_PUBLIC_APP_URL must be https, got {raw!r}"
    if not host:
        return f"NEXT_PUBLIC_APP_URL has no host: {raw!r}"
    if host in _LOCAL_APP_HOSTS or host.endswith((".localhost", ".local")):
        return f"NEXT_PUBLIC_APP_URL points at this machine: {raw!r}"
    try:
        address = ip_address(host)
    except ValueError:
        return None
    if address.is_loopback or address.is_private or address.is_unspecified or address.is_link_local:
        return f"NEXT_PUBLIC_APP_URL points at a private address: {raw!r}"
    return None


def verify_app_url_configuration(settings: Settings | None = None) -> None:
    current = settings or get_settings()
    if str(current.app_env or "").strip().lower() != "production":
        return
    problem = app_url_problem(str(current.next_public_app_url))
    if problem:
        raise RuntimeError(
            f"Refusing to start in production: {problem}. Every e-mailed, password, credential and "
            "cross-portal link is built from it. Set NEXT_PUBLIC_APP_URL to the public web address."
        )
