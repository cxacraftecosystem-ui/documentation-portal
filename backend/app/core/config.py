from functools import lru_cache
from ipaddress import ip_address
from urllib.parse import parse_qsl, urlsplit, urlunsplit

from pydantic import AnyHttpUrl, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Only symmetric HMAC signing is ever legitimate for this API's tokens. The allow-list exists to
# stop "algorithm confusion": JWT_ALGORITHM is read from the environment (the field has no alias, so
# pydantic-settings maps it from an env var of the same name), and a value of "none" — or an RSA/EC
# algorithm whose "public key" is our shared secret — would let an attacker mint tokens we accept.
_ALLOWED_JWT_ALGORITHMS = frozenset({"HS256", "HS384", "HS512"})

# Hostnames whose Postgres serves no TLS certificate: the docker-compose database, a local tunnel,
# a compose service name. Demanding sslmode=require against these would break local development, so
# the automatic TLS decision below leaves them alone.
_LOCAL_DB_HOSTNAMES = frozenset({"localhost", "host.docker.internal", "postgres", "db"})

# Any of these in the connection string means TLS behaviour was configured deliberately; we never
# second-guess an explicit choice.
_SSL_QUERY_KEYS = frozenset({"sslmode", "sslaccept", "sslcert", "sslidentity", "sslpassword"})


def _is_local_db_host(host: str) -> bool:
    """True when the database host is loopback / on a private network, i.e. a development database.

    Covers both named hosts (``localhost``, a docker-compose service name) and literal addresses
    (``127.0.0.1``, ``10.x``, ``192.168.x``, IPv6 loopback). Anything else — Supabase's pooler, an
    RDS endpoint — counts as remote and therefore MUST be reached over TLS.
    """
    host = host.strip().strip("[]").lower()
    if not host or host in _LOCAL_DB_HOSTNAMES or host.endswith((".local", ".internal", ".localhost")):
        return True
    try:
        address = ip_address(host)
    except ValueError:
        return False  # a real DNS name that isn't in the local list -> treat as remote
    return address.is_loopback or address.is_private or address.is_link_local


def _with_explicit_sslmode(url: str, require: bool | None) -> str:
    """Return *url* with an explicit ``sslmode`` so traffic to a remote database is encrypted.

    Why this is not redundant with "Supabase already speaks TLS": libpq and Prisma's Postgres
    connector default to ``sslmode=prefer``, which tries TLS and then **silently falls back to
    plaintext** if the handshake fails. A downgrade attack (or a proxy that terminates TLS badly)
    therefore ships credentials and every row of field data in the clear, with nothing in the logs.
    ``sslmode=require`` refuses to connect at all rather than fall back, which is the property we
    want for the pooler URL.

    The decision is automatic by default: remote hosts get ``sslmode=require``; loopback/private
    hosts (docker-compose Postgres, which ships no certificate) are returned untouched so local
    development and the test suite keep working. ``DATABASE_REQUIRE_SSL`` forces either answer.
    A URL that already carries any ssl* parameter is returned verbatim — an explicit operator
    choice always wins. Migrations are unaffected: ``prisma migrate deploy`` reads ``DATABASE_URL``
    straight from the environment, not from this Settings object.
    """
    if require is False:
        return url
    try:
        parts = urlsplit(url)
    except ValueError:
        return url
    if not parts.scheme.startswith("postgres"):
        return url  # not a Postgres URL (nothing else is expected here) — leave it alone
    existing_keys = {key.lower() for key, _ in parse_qsl(parts.query, keep_blank_values=True)}
    if existing_keys & _SSL_QUERY_KEYS:
        return url
    if require is None and _is_local_db_host(parts.hostname or ""):
        return url
    # Append textually rather than re-encoding the whole query: the credentials/params in the source
    # URL are already percent-encoded and urlencode() would double-encode them.
    query = f"{parts.query}&sslmode=require" if parts.query else "sslmode=require"
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


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
    # Max client connections the runtime engine opens to the pooler, PER uvicorn worker. Kept SMALL on
    # purpose: the pooler enforces a 200 *client*-connection ceiling shared across every worker AND
    # every overlapping process during a deploy/restart. At 40 × 2 workers (plus restart overlap and
    # the background queue worker) we were tripping (EMAXCONN) "max client connections reached", which
    # crash-looped startup and made uploads fail intermittently. Real query concurrency is gated by the
    # pooler's ~15 server connections anyway, so a small client pool loses nothing and leaves headroom.
    database_connection_limit: int = Field(default=10, alias="DATABASE_CONNECTION_LIMIT")
    # Optional Prisma pool acquire timeout (seconds). Left unset uses Prisma's default (10s).
    database_pool_timeout: int | None = Field(default=None, alias="DATABASE_POOL_TIMEOUT")
    # Force TLS on the database link. None (default) decides automatically — sslmode=require for a
    # remote host, untouched for a loopback/private one so docker-compose Postgres still works.
    # See _with_explicit_sslmode for why "Supabase already uses TLS" is not sufficient.
    database_require_ssl: bool | None = Field(default=None, alias="DATABASE_REQUIRE_SSL")
    jwt_secret: str = Field(alias="JWT_SECRET")
    # Constrained to HMAC algorithms by _normalise_jwt_algorithm below (see _ALLOWED_JWT_ALGORITHMS).
    jwt_algorithm: str = "HS256"
    jwt_expires_minutes: int = Field(default=60 * 24 * 7, alias="JWT_EXPIRES_MINUTES")
    # Escape hatch for local development ONLY: lets the API start with a short/placeholder
    # JWT_SECRET instead of refusing to boot (see app.core.security.verify_jwt_configuration).
    # Never set this in a deployed environment — a guessable secret means anyone can mint tokens
    # for any account, including the master admin.
    allow_weak_jwt_secret: bool = Field(default=False, alias="ALLOW_WEAK_JWT_SECRET")
    # Fernet key encrypting the runtime-editable provider keys in ManagedSecret (see
    # app.services.managed_secrets). OPTIONAL: when unset, a key is derived deterministically from
    # JWT_SECRET so the feature needs no new configuration. Set it explicitly BEFORE you ever rotate
    # JWT_SECRET — otherwise the rotation makes every stored secret undecryptable and each one has to
    # be re-entered in the Settings hub. Accepts a Fernet key or any high-entropy passphrase.
    secrets_encryption_key: str | None = Field(default=None, alias="SECRETS_ENCRYPTION_KEY")

    # --- Security response headers (see app.main.SecurityHeadersMiddleware) ---------------------
    # HSTS tells browsers to refuse plaintext HTTP to this host for max_age seconds. It is only
    # emitted on requests that arrived over TLS, so a local http:// dev server never poisons a
    # developer's browser.
    security_hsts_enabled: bool = Field(default=True, alias="SECURITY_HSTS_ENABLED")
    security_hsts_max_age: int = Field(default=63_072_000, alias="SECURITY_HSTS_MAX_AGE")  # 2 years
    # Emit HSTS even when the request looks like plain HTTP at the origin. Needed for the production
    # shape (browser --HTTPS--> CloudFront --HTTP--> nginx --HTTP--> uvicorn), where nginx overwrites
    # X-Forwarded-Proto with its own scheme, so the app cannot tell that the *viewer* used TLS.
    security_force_hsts: bool = Field(default=False, alias="SECURITY_FORCE_HSTS")

    aws_access_key_id: str = Field(alias="AWS_ACCESS_KEY_ID")
    aws_secret_access_key: str = Field(alias="AWS_SECRET_ACCESS_KEY")
    aws_region: str = Field(default="us-east-1", alias="AWS_REGION")
    aws_s3_bucket: str = Field(alias="AWS_S3_BUCKET")
    aws_s3_endpoint: str | None = Field(default=None, alias="AWS_S3_ENDPOINT")
    aws_s3_public_base_url: str | None = Field(default=None, alias="AWS_S3_PUBLIC_BASE_URL")
    # Server-side encryption algorithm requested on the uploads the API itself starts (multipart
    # create). "AES256" is SSE-S3, the managed-key mode every AWS bucket supports; "aws:kms" needs a
    # key policy that also grants the media IAM user. Set it EMPTY for local MinIO without a KMS
    # backend, which rejects the header outright. Note this cannot cover presigned single PUTs —
    # see app/services/s3.py and docs/SECURITY.md for why bucket default encryption carries those.
    aws_s3_sse_algorithm: str = Field(default="AES256", alias="AWS_S3_SSE_ALGORITHM")

    next_public_app_url: AnyHttpUrl | str = Field(
        default="http://localhost:3000", alias="NEXT_PUBLIC_APP_URL"
    )
    backend_cors_origins: str = Field(default="http://localhost:3000", alias="BACKEND_CORS_ORIGINS")

    google_client_id: str | None = Field(default=None, alias="GOOGLE_CLIENT_ID")
    google_android_client_id: str | None = Field(default=None, alias="GOOGLE_ANDROID_CLIENT_ID")
    master_admin_email: str = Field(alias="MASTER_ADMIN_EMAIL")
    master_admin_name: str = Field(default="Ankit Kumar", alias="MASTER_ADMIN_NAME")
    # Role given to brand-new self-registered Google accounts. Defaults to the lowest tier so an
    # unknown account cannot read/write as a researcher until an admin elevates it. Set to
    # RESEARCHER to restore the pre-six-tier behavior.
    default_signup_role: str = Field(default="CROWDSOURCE_VOLUNTEER", alias="DEFAULT_SIGNUP_ROLE")

    # Speech-to-text provider chain (highest priority first): ElevenLabs Scribe when
    # ELEVENLABS_API_KEY is set, else Deepgram Nova-3 when DEEPGRAM_API_KEY is set, else OpenAI
    # Whisper. The OpenAI key's primary role is transcript refinement/translation; it only
    # transcribes when neither dedicated STT provider is configured.
    elevenlabs_api_key: str | None = Field(default=None, alias="ELEVENLABS_API_KEY")
    elevenlabs_stt_model: str = Field(default="scribe_v1", alias="ELEVENLABS_STT_MODEL")
    deepgram_api_key: str | None = Field(default=None, alias="DEEPGRAM_API_KEY")
    deepgram_stt_model: str = Field(default="nova-3", alias="DEEPGRAM_STT_MODEL")
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

    @field_validator("jwt_algorithm")
    @classmethod
    def _normalise_jwt_algorithm(cls, value: str) -> str:
        """Pin the signing algorithm to a symmetric HMAC one, loudly rejecting anything else.

        This is the configuration half of the algorithm-confusion defence; the decode half is in
        app.core.security, which passes exactly this one algorithm to jose. Failing here (at
        startup) rather than at token-verification time means a bad JWT_ALGORITHM can never quietly
        weaken authentication in production.
        """
        algorithm = value.strip().upper()
        if algorithm not in _ALLOWED_JWT_ALGORITHMS:
            raise ValueError(
                f"JWT_ALGORITHM must be one of {sorted(_ALLOWED_JWT_ALGORITHMS)}; got {value!r}"
            )
        return algorithm

    @model_validator(mode="after")
    def _harden_database_url(self) -> "Settings":
        """Make database TLS explicit as soon as settings load, so every consumer inherits it.

        The rewrite lives here rather than in core/db.py because ``database_url`` is what the
        runtime Prisma client is built from (``build_runtime_database_url(get_settings()
        .database_url)``); hardening at the source means the pooler rewrite, any script and any
        future consumer all get the TLS-enforcing URL without having to remember to ask for it.
        """
        self.database_url = _with_explicit_sslmode(self.database_url, self.database_require_ssl)
        return self

    @property
    def cors_origins(self) -> list[str]:
        """Browser origins allowed to call the API, parsed defensively from BACKEND_CORS_ORIGINS.

        Deployments paste this value from Vercel/Terraform/GitHub secrets, so the parser tolerates
        the shapes that arrive in practice: comma- *or* newline-separated, wrapped in JSON-ish
        brackets or quotes, with stray whitespace. Trailing slashes are stripped because an Origin
        header is only ever ``scheme://host[:port]`` — a configured ``https://app.example.com/``
        matches nothing and silently breaks the web app. Order is preserved and duplicates dropped.
        """
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
        """True when the origin list is (or contains) the ``*`` wildcard."""
        return "*" in self.cors_origins

    @property
    def cors_allow_credentials(self) -> bool:
        """Whether the CORS layer may advertise ``Access-Control-Allow-Credentials: true``.

        Credentials and a wildcard origin must never be combined. Browsers reject the literal
        ``Access-Control-Allow-Origin: *`` with credentials, and Starlette's CORS middleware works
        around that rejection by echoing the *caller's* origin back when the request carries
        cookies — which would turn a lazily-configured ``*`` into "every website on the internet
        may call this API as the logged-in user". So a wildcard silently downgrades to
        credential-less CORS instead (main.py logs loudly when that happens).
        """
        return not self.cors_allows_wildcard

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
