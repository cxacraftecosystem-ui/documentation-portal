from __future__ import annotations

import pytest

from app.core.config import get_settings
from app.scale import rate_limit

SECRET = "a-proxy-secret-for-tests-only-0123456789"

CHAIN = b"203.0.113.7, 76.76.21.9, 130.176.1.1"


def _scope(*headers: tuple[bytes, bytes]) -> dict:
    return {"type": "http", "headers": [(b"x-forwarded-for", CHAIN), (b"x-amz-cf-id", b"edge"), *headers], "client": ("127.0.0.1", 1)}


@pytest.fixture
def proxy_secret(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "web_proxy_secret", SECRET)
    monkeypatch.setattr(settings, "trusted_proxy_hops", 2)
    return settings


def test_the_visitor_is_the_key_when_the_secret_is_right(proxy_secret):
    scope = _scope((b"x-web-proxy-secret", SECRET.encode()), (b"x-viewer-address", b"198.51.100.23"))
    assert rate_limit.trusted_viewer_address(scope) == "198.51.100.23"
    assert rate_limit.client_address(scope) == "ip:198.51.100.23"
    assert rate_limit._identity(scope, ignore_bearer=True) == "ip:198.51.100.23"


def test_without_the_header_the_hop_count_still_decides(proxy_secret):
    assert rate_limit.trusted_viewer_address(_scope()) is None
    assert rate_limit.client_address(_scope()) == "ip:76.76.21.9"


@pytest.mark.parametrize(
    "headers",
    [
        ((b"x-viewer-address", b"198.51.100.23"),),
        ((b"x-web-proxy-secret", b"guess"), (b"x-viewer-address", b"198.51.100.23")),
        ((b"x-web-proxy-secret", b""), (b"x-viewer-address", b"198.51.100.23")),
        ((b"x-web-proxy-secret", SECRET[:-1].encode()), (b"x-viewer-address", b"198.51.100.23")),
        ((b"x-web-proxy-secret", SECRET.encode()), (b"x-viewer-address", b"not-an-ip")),
        ((b"x-web-proxy-secret", SECRET.encode()), (b"x-viewer-address", b"1.2.3.4, 5.6.7.8")),
        ((b"x-origin-verify", SECRET.encode()), (b"x-viewer-address", b"198.51.100.23")),
    ],
)
def test_a_forged_or_malformed_address_is_never_believed(proxy_secret, headers):
    scope = _scope(*headers)
    assert rate_limit.trusted_viewer_address(scope) is None
    assert rate_limit.client_address(scope) == "ip:76.76.21.9"


def test_no_secret_configured_means_never(monkeypatch):
    monkeypatch.setattr(get_settings(), "web_proxy_secret", None)
    scope = _scope((b"x-web-proxy-secret", b""), (b"x-viewer-address", b"198.51.100.23"))
    assert rate_limit.trusted_viewer_address(scope) is None


def test_ipv6_viewers_are_normalised(proxy_secret):
    scope = _scope((b"x-web-proxy-secret", SECRET.encode()), (b"x-viewer-address", b"2001:DB8:0:0::1"))
    assert rate_limit.client_address(scope) == "ip:2001:db8::1"


def test_forgot_password_budget_is_per_visitor_behind_the_proxy(proxy_secret, monkeypatch):
    monkeypatch.setattr(rate_limit, "_forgot_password_buckets", rate_limit._TokenBuckets(capacity=1, refill_per_second=0.0001))
    monkeypatch.setattr(rate_limit, "_forgot_password_global", rate_limit._TokenBuckets(capacity=100, refill_per_second=1))

    def visitor(address: bytes) -> dict:
        return _scope((b"x-web-proxy-secret", SECRET.encode()), (b"x-viewer-address", address))

    assert rate_limit.forgot_password_attempt(visitor(b"198.51.100.1"))[0] is True
    assert rate_limit.forgot_password_attempt(visitor(b"198.51.100.1"))[0] is False
    assert rate_limit.forgot_password_attempt(visitor(b"198.51.100.2"))[0] is True
