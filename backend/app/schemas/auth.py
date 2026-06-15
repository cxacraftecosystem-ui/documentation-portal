from pydantic import EmailStr, Field, model_validator

from app.schemas.common import APIModel


class LoginRequest(APIModel):
    email: EmailStr | None = None
    password: str | None = Field(default=None, min_length=8)
    googleIdToken: str | None = None

    @model_validator(mode="after")
    def validate_login_mode(self) -> "LoginRequest":
        has_password_login = bool(self.email and self.password)
        has_google_login = bool(self.googleIdToken)
        if has_password_login == has_google_login:
            raise ValueError("Provide either email/password or a Google ID token")
        return self


class TokenResponse(APIModel):
    accessToken: str
    tokenType: str = "bearer"
    user: dict
