"""The two authentication libraries were swapped on 2026-10-09, and nothing they produce may change.

python-jose became PyJWT and passlib became bcrypt called directly (app/core/security.py says why).
EVERY TOKEN AND HASH LITERAL IN THIS FILE WAS MINTED BY THE OLD PAIR — python-jose 3.5.0, and passlib
1.7.4 on bcrypt 4.0.1, the exact versions production ran — in a throwaway venv, with the calls the old
security.py made: ``jwt.encode({"sub", "iat", "exp", ...extra}, secret, algorithm=...)`` and
``CryptContext(schemes=["bcrypt"], deprecated="auto").hash(password)``. So these are not tests of the
new code agreeing with itself. They are stand-ins for the tokens already sitting in browsers and on
handsets, and the hashes already sitting in the users table, checked by the code that now has to
accept them. If one of them ever fails, a deploy would have signed everybody out or locked them out.

The rest pins the hardening the module docstring promises (one algorithm, mandatory exp and sub) and
the one behaviour bcrypt 5 changed: a password over 72 bytes is refused when it is SET, and truncated,
as passlib always did, when it is CHECKED.

Nothing here touches a database or the environment: ``security.get_settings`` is replaced with the
three values token signing reads.
"""

import base64
import json
from types import SimpleNamespace

import jwt
import pytest
from pydantic import ValidationError

from app.core import security
from app.schemas.auth import LoginRequest
from app.schemas.users import UserCreate, UserUpdate

# The signing key the old library was given. Long enough to pass verify_jwt_configuration's floor,
# and obviously not a secret.
_SECRET = "test-only-signing-key-not-a-secret-0123456789"

# Minted 2026-10-09 at iat 1791547200 (2026-10-09T12:00:00Z) by python-jose 3.5.0. `session` and
# `dataset` expire in 2106 so this file does not start failing on a date; `expired` expired five
# minutes before it was issued.
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

# (password, hash written by passlib 1.7.4 on bcrypt 4.0.1). `over72` is the case bcrypt 5 changed:
# passlib cut the 100-byte password to its first 72 bytes before hashing, without saying so.
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
    """Point token signing at ``_SECRET``; the factory takes the configured algorithm."""

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


# --- Tokens python-jose issued keep working ------------------------------------------------------


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
    """``scope`` is what confines a dataset token to the dataset endpoints (deps._user_from_bearer).
    Losing it in the swap would WIDEN the token to the whole API, so it is asserted by value."""
    claims = security.decode_access_token(_JOSE_DATASET)

    assert claims["scope"] == "dataset:read"
    assert claims["sub"] == "cm1user0000000000000000002"


def test_an_expired_python_jose_token_is_still_refused(signing) -> None:
    with pytest.raises(ValueError, match="Invalid or expired token"):
        security.decode_access_token(_JOSE_EXPIRED)


@pytest.mark.filterwarnings("ignore::jwt.warnings.InsecureKeyLengthWarning")
def test_the_configured_algorithm_and_only_it_is_accepted(signing) -> None:
    """The HS512 token is genuine — same key — and is refused under HS256 all the same: the token's
    own header never chooses the algorithm. Configured for HS512, the same token verifies, which is
    what shows every HMAC size came across the swap, not only the default one.

    PyJWT warns that a 45-byte key is short for HS512 (RFC 7518 asks for the hash size, 64 bytes);
    the key is the fixture's, the warning is about it and not about the code, so it is silenced here
    only. docs/SECURITY.md §3.1 says what that warning means for a real deployment."""
    with pytest.raises(ValueError):
        security.decode_access_token(_JOSE_HS512)

    signing("HS512")
    assert security.decode_access_token(_JOSE_HS512)["sub"] == "cm1user0000000000000000004"


def test_a_token_from_this_module_has_the_shape_python_jose_gave_one(signing) -> None:
    """The other direction, which is what makes a rollback safe: a token minted now carries the
    header python-jose wrote and the same claim types (integer iat and exp), so the previous build
    verifies it just as this one verifies the previous build's."""
    token = security.create_access_token("cm1user0000000000000000005", {"email": "b@example.org"})

    assert _segment(token, 0) == _segment(_JOSE_SESSION, 0) == {"alg": "HS256", "typ": "JWT"}
    claims = _segment(token, 1)
    assert set(claims) == {"sub", "iat", "exp", "email"}
    assert isinstance(claims["iat"], int) and isinstance(claims["exp"], int)
    assert claims["exp"] - claims["iat"] == 60 * 60
    assert security.decode_access_token(token)["email"] == "b@example.org"


# --- The hardening the module docstring promises ---------------------------------------------------


def test_an_unsigned_token_is_refused(signing) -> None:
    unsigned = f'{_b64({"alg": "none", "typ": "JWT"})}.{_b64({"sub": "u1", "exp": 4314427200})}.'

    with pytest.raises(ValueError):
        security.decode_access_token(unsigned)


def test_the_rs256_confusion_token_is_refused(signing) -> None:
    """The classic: claim RS256 and sign with the shared secret, hoping the verifier treats the HMAC
    key as an RSA public key. The configured-algorithm list is what turns it away."""
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


# --- Hashes passlib wrote keep verifying --------------------------------------------------------


@pytest.mark.parametrize("case", sorted(_PASSLIB_HASHES))
def test_every_stored_passlib_hash_still_verifies(case: str) -> None:
    password, stored = _PASSLIB_HASHES[case]

    assert security.verify_password(password, stored) is True
    assert security.verify_password("not the password", stored) is False
    # A character past the 72nd byte never reached the hash, under passlib or now; one inside the
    # first 72 always did.
    reaches_the_hash = len(password.encode("utf-8")) < security.BCRYPT_MAX_PASSWORD_BYTES
    assert security.verify_password(password + "x", stored) is not reaches_the_hash


def test_a_long_password_set_before_the_swap_still_signs_in_as_it_always_did() -> None:
    """passlib hashed only the first 72 bytes of this 100-byte password, so the first 72 bytes were
    always enough to sign in and 71 never were. Refusing the long password now, as bcrypt 5 would on
    its own, would lock its owner out of an account whose password has not changed."""
    password, stored = _PASSLIB_HASHES["over72"]

    assert security.verify_password(password, stored) is True
    assert security.verify_password(password[:72], stored) is True
    assert security.verify_password(password[:71], stored) is False


def test_an_enormous_sign_in_body_is_a_wrong_password_not_a_crash() -> None:
    """passlib raised PasswordSizeError past 4096 characters, which was a 500 at the front door."""
    _, stored = _PASSLIB_HASHES["short"]

    assert security.verify_password("x" * 5000, stored) is False


def test_a_nul_in_a_password_is_never_a_match() -> None:
    _, stored = _PASSLIB_HASHES["short"]

    assert security.verify_password("correct horse\x00battery staple", stored) is False
    with pytest.raises(ValueError, match="NUL"):
        security.hash_password("correct horse\x00battery staple")


def test_a_malformed_stored_hash_is_an_error_not_a_wrong_password() -> None:
    """Parity with passlib's UnknownHashError: a broken row is something an operator must hear about.
    Answering False would hide it behind "wrong password" for as long as nobody looked."""
    with pytest.raises(ValueError):
        security.verify_password("anything at all", "not-a-bcrypt-hash")


def test_a_missing_stored_hash_is_simply_false() -> None:
    assert security.verify_password("anything at all", None) is False
    assert security.verify_password("anything at all", "") is False


# --- New hashes ------------------------------------------------------------------------------------


def test_a_new_hash_costs_what_a_passlib_hash_cost() -> None:
    hashed = security.hash_password("correct horse battery staple")

    assert hashed.startswith("$2b$12$") and len(hashed) == 60
    assert security.verify_password("correct horse battery staple", hashed) is True


def test_bcrypt_refuses_more_than_72_bytes_and_the_count_is_in_bytes() -> None:
    assert security.verify_password("a" * 72, security.hash_password("a" * 72)) is True

    with pytest.raises(ValueError, match="72 bytes"):
        security.hash_password("a" * 73)
    # 25 characters, 75 bytes: under any character limit, over bcrypt's.
    with pytest.raises(ValueError, match="72 bytes"):
        security.hash_password("प" * 25)


# --- The schemas refuse what bcrypt would, before the hash is attempted -----------------------------


def _user(password: str) -> dict:
    return {"email": "new@example.org", "name": "New User", "password": password}


def test_creating_a_user_refuses_a_password_bcrypt_cannot_store() -> None:
    assert UserCreate(**_user("p" * 72)).password == "p" * 72

    with pytest.raises(ValidationError):
        UserCreate(**_user("p" * 73))
    with pytest.raises(ValidationError, match="72 bytes"):
        UserCreate(**_user("पासवर्ड" * 5))  # 35 characters, 105 bytes
    with pytest.raises(ValidationError, match="NUL"):
        UserCreate(**_user("password\x00password"))


def test_changing_a_password_applies_the_same_rule_and_leaves_absent_alone() -> None:
    assert UserUpdate(name="Renamed").password is None
    assert UserUpdate(password="p" * 72).password == "p" * 72

    with pytest.raises(ValidationError, match="72 bytes"):
        UserUpdate(password="पासवर्ड" * 5)


def test_signing_in_has_no_ceiling_at_all() -> None:
    """Deliberately: a person types what they type, and verify_password decides."""
    assert LoginRequest(email="a@example.org", password="x" * 500).password == "x" * 500
