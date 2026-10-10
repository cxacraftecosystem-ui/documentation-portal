from typing import Literal

from pydantic import EmailStr, Field, model_validator

from app.schemas.common import APIModel


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
