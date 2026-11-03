from typing import Literal

from pydantic import EmailStr, Field, field_validator, model_validator

from app.core.security import BCRYPT_MAX_PASSWORD_BYTES
from app.schemas.common import APIModel
from app.schemas.users import _storable_password


class LoginRequest(APIModel):
    email: EmailStr | None = None
    password: str | None = Field(default=None, min_length=8)
    googleIdToken: str | None = None
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
    two_factor_required: bool = True
    challenge_token: str


class TwoFactorVerifyRequest(APIModel):
    challenge_token: str = Field(min_length=1, max_length=4096)
    code: str = Field(min_length=1, max_length=64)


class TwoFactorCodeRequest(APIModel):
    code: str = Field(min_length=1, max_length=64)


class DatasetTokenRequest(LoginRequest):
    code: str | None = Field(default=None, min_length=1, max_length=64)



class IssuePasswordLinkRequest(APIModel):
    userId: str = Field(min_length=1, max_length=64)
    delivery: Literal["COPY_LINK", "EMAIL"] = "COPY_LINK"


class PasswordLinkCheckRequest(APIModel):
    token: str | None = None


class SetPasswordRequest(APIModel):
    token: str = Field(min_length=1, max_length=1024)
    password: str = Field(min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)

    @field_validator("password")
    @classmethod
    def password_fits_bcrypt(cls, value: str) -> str:
        return _storable_password(value)


class ChangePasswordRequest(APIModel):
    currentPassword: str = Field(default="", max_length=256)
    newPassword: str = Field(min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
    twoFactorCode: str | None = Field(default=None, max_length=64)
    googleIdToken: str | None = Field(default=None, max_length=8192)
    oidcProvider: Literal["MICROSOFT", "YAHOO"] | None = None
    oidcCode: str | None = Field(default=None, min_length=1, max_length=4096)
    oidcCodeVerifier: str | None = Field(
        default=None, min_length=43, max_length=128, pattern=r"^[A-Za-z0-9\-._~]+$"
    )
    oidcRedirectUri: str | None = Field(default=None, min_length=1, max_length=2048)
    oidcNonce: str | None = Field(default=None, min_length=16, max_length=256)
    providerProof: str | None = Field(default=None, min_length=1, max_length=2048)

    @property
    def has_oidc_proof(self) -> bool:
        return bool(
            self.oidcProvider and self.oidcCode and self.oidcCodeVerifier and self.oidcRedirectUri and self.oidcNonce
        )

    @field_validator("newPassword")
    @classmethod
    def password_fits_bcrypt(cls, value: str) -> str:
        return _storable_password(value)


class ForgotPasswordRequest(APIModel):
    email: EmailStr = Field(max_length=320)
