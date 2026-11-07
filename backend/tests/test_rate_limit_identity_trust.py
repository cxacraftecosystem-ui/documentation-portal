from __future__ import annotations

import pytest

from app.core.config import get_settings
from app.core.security import create_access_token
from app.scale import rate_limit

EDGE = b"70.132.0.1"


@pytest.fixture(autouse=True)
def _two_hops_no_proxy_secret(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "web_proxy_secret", None, raising=False)
    for name in ("forwarded_client_hops", "trusted_proxy_hops"):
        if hasattr(settings, name):
            monkeypatch.setattr(settings, name, 2)


def _scope(*headers: tuple[bytes, bytes]) -> dict:
    return {"type": "http", "headers": [(b"x-amz-cf-id", b"edge"), *headers], "client": ("127.0.0.1", 1)}


def _via_cloudfront(viewer_supplied: bytes, real: bytes = b"198.51.100.4") -> tuple[bytes, bytes]:
    chain = (viewer_supplied + b", " if viewer_supplied else b"") + real + b", " + EDGE
    return (b"x-forwarded-for", chain)


def test_a_viewer_supplied_forwarded_prefix_does_not_change_the_identity():
    honest = rate_limit._identity(_scope(_via_cloudfront(b"")))
    spoofed = {
        rate_limit._identity(_scope(_via_cloudfront(f"10.0.0.{n}".encode())))
        for n in range(5)
    }
    assert spoofed == {honest}
    assert honest == "ip:198.51.100.4"


def test_a_viewer_cannot_spend_another_address_bucket():
    victim = rate_limit._identity(_scope(_via_cloudfront(b"", real=b"203.0.113.50")))
    attacker = rate_limit._identity(_scope(_via_cloudfront(b"203.0.113.50", real=b"198.51.100.4")))
    assert attacker != victim


def test_the_credential_bucket_uses_the_hop_counted_address():
    spoofed = {
        rate_limit._identity(_scope(_via_cloudfront(f"10.9.9.{n}".encode())), ignore_bearer=True)
        for n in range(5)
    }
    assert spoofed == {"ip:198.51.100.4"}


def test_rotating_an_unverified_bearer_string_does_not_buy_fresh_buckets():
    identities = {
        rate_limit._identity(_scope(_via_cloudfront(b""), (b"authorization", f"Bearer junk-{n}".encode())))
        for n in range(5)
    }
    assert identities == {"ip:198.51.100.4"}


def test_a_verified_token_is_keyed_on_its_account_not_its_bytes():
    first = create_access_token("user_meera")
    second = create_access_token("user_meera", expires_minutes=5)
    other = create_access_token("user_arjun")

    def key(token: str) -> str:
        return rate_limit._identity(_scope(_via_cloudfront(b""), (b"authorization", f"Bearer {token}".encode())))

    assert key(first) == key(second)
    assert key(first) != key(other)
    assert not key(first).startswith("ip:")
