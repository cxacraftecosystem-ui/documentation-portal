from typing import Literal

from pydantic import EmailStr, Field, field_validator, model_validator

from app.core.security import BCRYPT_MAX_PASSWORD_BYTES
from app.schemas.common import APIModel
from app.schemas.users import _storable_password


class LoginRequest(APIModel):
    email: EmailStr | None = None
    password: str | None = Field(default=None, min_length=8)
    googleIdToken: str | None = None
    # ── MICROSOFT AND YAHOO: an authorization code, and what proves this caller started the flow ──
    #
    # Not an ID token, unlike Google: see ``app/services/oidc_sign_in.py`` for why these two
    # providers' codes are redeemed here. All five travel together or not at all. The verifier's
    # alphabet and length are RFC 7636's; the nonce is the RAW value whose SHA-256 the client put in
    # the authorization request. The bounds are budgets: nothing longer is ever legitimate, so
    # nothing longer is ever forwarded.
    oidcProvider: Literal["MICROSOFT", "YAHOO"] | None = None
    oidcCode: str | None = Field(default=None, min_length=1, max_length=4096)
    oidcCodeVerifier: str | None = Field(
        default=None, min_length=43, max_length=128, pattern=r"^[A-Za-z0-9\-._~]+$"
    )
    oidcRedirectUri: str | None = Field(default=None, min_length=1, max_length=2048)
    oidcNonce: str | None = Field(default=None, min_length=16, max_length=256)

    @property
    def has_oidc_login(self) -> bool:
        return bool(
            self.oidcProvider
            and self.oidcCode
            and self.oidcCodeVerifier
            and self.oidcRedirectUri
            and self.oidcNonce
        )

    @model_validator(mode="after")
    def validate_login_mode(self) -> "LoginRequest":
        has_password_login = bool(self.email and self.password)
        has_google_login = bool(self.googleIdToken)
        partial_oidc = any(
            (self.oidcProvider, self.oidcCode, self.oidcCodeVerifier, self.oidcRedirectUri, self.oidcNonce)
        )
        if partial_oidc and not self.has_oidc_login:
            raise ValueError("A Microsoft or Yahoo sign-in needs all five oidc fields")
        if [has_password_login, has_google_login, self.has_oidc_login].count(True) != 1:
            raise ValueError(
                "Provide exactly one of email/password, a Google ID token, or a Microsoft or Yahoo code"
            )
        return self


class TokenResponse(APIModel):
    accessToken: str
    tokenType: str = "bearer"
    user: dict


class TwoFactorChallengeResponse(APIModel):
    """What sign-in answers instead of a session when the account has two-step verification on."""

    two_factor_required: bool = True
    challenge_token: str


class TwoFactorVerifyRequest(APIModel):
    challenge_token: str = Field(min_length=1, max_length=4096)
    #: A six-digit code or a recovery code; told apart by shape.
    code: str = Field(min_length=1, max_length=64)


class TwoFactorCodeRequest(APIModel):
    code: str = Field(min_length=1, max_length=64)


class DatasetTokenRequest(LoginRequest):
    """``POST /datasets/token``: an admin's email and password, and — when that account has two-step
    verification on — a current 6-digit code or a recovery code. Without it the password alone would
    mint a 30-day credential, which is exactly what the second factor exists to stop."""

    code: str | None = Field(default=None, min_length=1, max_length=64)


# ── PASSWORDS: links, the link redemption, the change, and "Forgot your password?" ─────────────────
#
# Ported on 2026-10-16 from the Design Prototype Workshop's app/schemas/auth.py — same class names,
# same field names, so the two portals' clients send one body. Every NEW password goes through
# ``schemas.users._storable_password`` (bcrypt's 72-byte rule, as a 422), the one rule the admin forms
# use, so a temporary password an administrator can set is one these forms can accept back.


class IssuePasswordLinkRequest(APIModel):
    """An administrator asking for a password link for somebody else's account. No ``purpose``: it is
    derived (``credential_links.purpose_for``) from whether the account has a password and has ever
    signed in."""

    userId: str = Field(min_length=1, max_length=64)
    delivery: Literal["COPY_LINK", "EMAIL"] = "COPY_LINK"


class PasswordLinkCheckRequest(APIModel):
    """Asking whether a link is still good, with the token in the BODY so no request line carries it.
    No length bound: an over-long token is "malformed" from ``credential_links.verify_token``."""

    token: str | None = None


class SetPasswordRequest(APIModel):
    """Redeeming a link. Unauthenticated by necessity: the token is the entire authority."""

    token: str = Field(min_length=1, max_length=1024)
    password: str = Field(min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)

    @field_validator("password")
    @classmethod
    def password_fits_bcrypt(cls, value: str) -> str:
        return _storable_password(value)


class ChangePasswordRequest(APIModel):
    """The signed-in account changing its own password, or setting its first one.

    ``currentPassword`` is required for every account that HAS a password, even one carrying
    ``mustChangePassword``. An account with NO password (Google, Microsoft or Yahoo only) sends none
    and SETS a first password instead, allowed only on a session opened in the last
    ``auth.PASSWORD_REAUTH_MINUTES`` minutes (see ``routes/auth.change_password``).
    """

    currentPassword: str = Field(default="", max_length=256)
    newPassword: str = Field(min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)

    @field_validator("newPassword")
    @classmethod
    def password_fits_bcrypt(cls, value: str) -> str:
        return _storable_password(value)


class ForgotPasswordRequest(APIModel):
    """``POST /auth/forgot-password``: the address a signed-out person asks a link for. A malformed
    address is the one refusal this door gives about its body (422)."""

    email: EmailStr = Field(max_length=320)
