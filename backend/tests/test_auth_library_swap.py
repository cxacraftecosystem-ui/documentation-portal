import base64
import json
from types import SimpleNamespace

import jwt
import pytest
from pydantic import ValidationError

from app.core import security
from app.schemas.auth import LoginRequest
from app.schemas.users import UserCreate, UserUpdate

_SECRET = "test-only-signing-key-not-a-secret-0123456789"

_JOSE_SESSION = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJzdWIiOiJjbTF1c2VyMDAwMDAwMDAwMDAwMDAwMDAwMSIsImlhdCI6MTc5MTU0NzIwMCwiZXhwIjo0MzE0NDI3MjAwLCJl"
    "bWFpbCI6ImFAZXhhbXBsZS5vcmciLCJyb2xlIjoiQURNSU4ifQ."
    "WG_SlPBdyaxG707t9z0x2jsiGnUInbifAXeWbCefTes"
)
_JOSE_DATASET = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJzdWIiOiJjbTF1c2VyMDAwMDAwMDAwMDAwMDAwMDAwMiIsImlhdCI6MTc5MTU0NzIwMCwiZXhwIjo0MzE0NDI3MjAwLCJz"
    "Y29wZSI6ImRhdGFzZXQ6cmVhZCJ9."
    "L_DDplqToMzvPr7Qhj5Gw2kvy-4Yr695E84e09_BTLA"
)
_JOSE_EXPIRED = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJzdWIiOiJjbTF1c2VyMDAwMDAwMDAwMDAwMDAwMDAwMyIsImlhdCI6MTc5MTU0NzIwMCwiZXhwIjoxNzkxNTQ2OTAwfQ."
    "S4uANCD5MEY7DvnHJc4KXoglky-0od5gvNy5T5TAbbg"
)
_JOSE_HS512 = (
    "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9."
    "eyJzdWIiOiJjbTF1c2VyMDAwMDAwMDAwMDAwMDAwMDAwNCIsImlhdCI6MTc5MTU0NzIwMCwiZXhwIjo0MzE0NDI3MjAwfQ."
    "v76b-S17epHzd43hF3lHAaGmtHTqew5Ye7gg-LFY_lYPh9M4cRL1JqWoBryvv3f6x_ehvyay7aaWO9UzadoQvw"
)

_PASSLIB_HASHES = {
    "short": (
        "correct horse battery staple",
        "$2b$12$w70iadK2fDlUTYzPuHzgaO9JdRk2M8NLy8xk/yB/Kgs/g5Oy9.5p.",
    ),
    "exactly72": ("p" * 72, "$2b$12$bHB/mfY/iIW/cSRlen0l5.sH3ze6BWX6O5vP/jeUJAf5.T/0pcZGW"),
    "over72": ("q" * 100, "$2b$12$qBZT5gjLW60tGiNnle4NyuPzBgbdAtoW7MZ7rU4.falBiQ4PHctku"),
    "unicode": (
        "पासवर्ड-मज़बूत-१२३",
        "$2b$12$SHJ0VClNvbkdKVX5fBEFWOHL6tlaOPN7Qj6z9U0geJTPBHHVdValK",
    ),
}


@pytest.fixture
def signing(monkeypatch: pytest.MonkeyPatch):
    def configure(algorithm: str = "HS256") -> None:
        knobs = SimpleNamespace(jwt_secret=_SECRET, jwt_algorithm=algorithm, jwt_expires_minutes=60)
        monkeypatch.setattr(security, "get_settings", lambda: knobs)

    configure()
    return configure


def _b64(part: dict) -> str:
    raw = json.dumps(part, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _segment(token: str, index: int) -> dict:
    piece = token.split(".")[index]
    return json.loads(base64.urlsafe_b64decode(piece + "=" * (-len(piece) % 4)))



def test_a_session_token_python_jose_issued_still_signs_its_holder_in(signing) -> None:
    claims = security.decode_access_token(_JOSE_SESSION)

    assert claims == {
        "sub": "cm1user0000000000000000001",
        "iat": 1791547200,
        "exp": 4314427200,
        "email": "a@example.org",
        "role": "ADMIN",
    }


def test_a_dataset_token_keeps_the_scope_that_narrows_it(signing) -> None:
    claims = security.decode_access_token(_JOSE_DATASET)

    assert claims["scope"] == "dataset:read"
    assert claims["sub"] == "cm1user0000000000000000002"


def test_an_expired_python_jose_token_is_still_refused(signing) -> None:
    with pytest.raises(ValueError, match="Invalid or expired token"):
        security.decode_access_token(_JOSE_EXPIRED)


@pytest.mark.filterwarnings("ignore::jwt.warnings.InsecureKeyLengthWarning")
def test_the_configured_algorithm_and_only_it_is_accepted(signing) -> None:
    with pytest.raises(ValueError):
        security.decode_access_token(_JOSE_HS512)

    signing("HS512")
    assert security.decode_access_token(_JOSE_HS512)["sub"] == "cm1user0000000000000000004"


def test_a_token_from_this_module_has_the_shape_python_jose_gave_one(signing) -> None:
    token = security.create_access_token("cm1user0000000000000000005", {"email": "b@example.org"})

    assert _segment(token, 0) == _segment(_JOSE_SESSION, 0) == {"alg": "HS256", "typ": "JWT"}
    claims = _segment(token, 1)
    assert set(claims) == {"sub", "iat", "exp", "email"}
    assert isinstance(claims["iat"], int) and isinstance(claims["exp"], int)
    assert claims["exp"] - claims["iat"] == 60 * 60
    assert security.decode_access_token(token)["email"] == "b@example.org"



def test_an_unsigned_token_is_refused(signing) -> None:
    unsigned = f'{_b64({"alg": "none", "typ": "JWT"})}.{_b64({"sub": "u1", "exp": 4314427200})}.'

    with pytest.raises(ValueError):
        security.decode_access_token(unsigned)


def test_the_rs256_confusion_token_is_refused(signing) -> None:
    header, payload = _b64({"alg": "RS256", "typ": "JWT"}), _b64({"sub": "u1", "exp": 4314427200})
    forged = jwt.encode({"sub": "u1", "exp": 4314427200}, _SECRET, algorithm="HS256")
    confused = f"{header}.{payload}.{forged.split('.')[2]}"

    with pytest.raises(ValueError):
        security.decode_access_token(confused)


@pytest.mark.parametrize("missing", ["exp", "sub"])
def test_a_token_without_exp_or_sub_is_refused(signing, missing: str) -> None:
    claims = {"sub": "u1", "exp": 4314427200}
    del claims[missing]
    token = jwt.encode(claims, _SECRET, algorithm="HS256")

    with pytest.raises(ValueError):
        security.decode_access_token(token)


def test_a_token_signed_with_another_key_is_refused(signing) -> None:
    token = jwt.encode({"sub": "u1", "exp": 4314427200}, _SECRET + "-other", algorithm="HS256")

    with pytest.raises(ValueError):
        security.decode_access_token(token)



@pytest.mark.parametrize("case", sorted(_PASSLIB_HASHES))
def test_every_stored_passlib_hash_still_verifies(case: str) -> None:
    password, stored = _PASSLIB_HASHES[case]

    assert security.verify_password(password, stored) is True
    assert security.verify_password("not the password", stored) is False
    reaches_the_hash = len(password.encode("utf-8")) < security.BCRYPT_MAX_PASSWORD_BYTES
    assert security.verify_password(password + "x", stored) is not reaches_the_hash


def test_a_long_password_set_before_the_swap_still_signs_in_as_it_always_did() -> None:
    password, stored = _PASSLIB_HASHES["over72"]

    assert security.verify_password(password, stored) is True
    assert security.verify_password(password[:72], stored) is True
    assert security.verify_password(password[:71], stored) is False


def test_an_enormous_sign_in_body_is_a_wrong_password_not_a_crash() -> None:
    _, stored = _PASSLIB_HASHES["short"]

    assert security.verify_password("x" * 5000, stored) is False


def test_a_nul_in_a_password_is_never_a_match() -> None:
    _, stored = _PASSLIB_HASHES["short"]

    assert security.verify_password("correct horse\x00battery staple", stored) is False
    with pytest.raises(ValueError, match="NUL"):
        security.hash_password("correct horse\x00battery staple")


def test_a_malformed_stored_hash_is_an_error_not_a_wrong_password() -> None:
    with pytest.raises(ValueError):
        security.verify_password("anything at all", "not-a-bcrypt-hash")


def test_a_missing_stored_hash_is_simply_false() -> None:
    assert security.verify_password("anything at all", None) is False
    assert security.verify_password("anything at all", "") is False



def test_a_new_hash_costs_what_a_passlib_hash_cost() -> None:
    hashed = security.hash_password("correct horse battery staple")

    assert hashed.startswith("$2b$12$") and len(hashed) == 60
    assert security.verify_password("correct horse battery staple", hashed) is True


def test_bcrypt_refuses_more_than_72_bytes_and_the_count_is_in_bytes() -> None:
    assert security.verify_password("a" * 72, security.hash_password("a" * 72)) is True

    with pytest.raises(ValueError, match="72 bytes"):
        security.hash_password("a" * 73)
    with pytest.raises(ValueError, match="72 bytes"):
        security.hash_password("प" * 25)



def _user(password: str) -> dict:
    return {"email": "new@example.org", "name": "New User", "password": password}


def test_creating_a_user_refuses_a_password_bcrypt_cannot_store() -> None:
    assert UserCreate(**_user("p" * 72)).password == "p" * 72

    with pytest.raises(ValidationError):
        UserCreate(**_user("p" * 73))
    with pytest.raises(ValidationError, match="72 bytes"):
        UserCreate(**_user("पासवर्ड" * 5))
    with pytest.raises(ValidationError, match="NUL"):
        UserCreate(**_user("password\x00password"))


def test_changing_a_password_applies_the_same_rule_and_leaves_absent_alone() -> None:
    assert UserUpdate(name="Renamed").password is None
    assert UserUpdate(password="p" * 72).password == "p" * 72

    with pytest.raises(ValidationError, match="72 bytes"):
        UserUpdate(password="पासवर्ड" * 5)


def test_signing_in_has_no_ceiling_at_all() -> None:
    assert LoginRequest(email="a@example.org", password="x" * 500).password == "x" * 500
