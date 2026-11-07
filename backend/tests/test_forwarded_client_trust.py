from __future__ import annotations

import pytest

from app.core.config import get_settings
from app.scale import rate_limit

HOPS_SETTING = "trusted_proxy_hops"
ORIGIN_SECRET = "originsecretfortestsonly0123456789"


@pytest.fixture(autouse=True)
def production_shape(monkeypatch):
    settings = get_settings()
    for name, value in (
        ("web_proxy_secret", None),
        (HOPS_SETTING, 2),
        ("trusted_proxy_cidrs", "127.0.0.1/32,::1/128"),
        ("origin_verify_secret", None),
    ):
        if hasattr(settings, name):
            monkeypatch.setattr(settings, name, value)
    monkeypatch.setattr(rate_limit, "_hop_mismatch_logged", True, raising=False)
    return settings


def _scope(chain: str | None, peer: str | None = "127.0.0.1", *extra: tuple[bytes, bytes]) -> dict:
    headers = [(b"x-forwarded-for", chain.encode("latin-1"))] if chain is not None else []
    scope = {"type": "http", "headers": [*headers, *extra]}
    if peer is not None:
        scope["client"] = (peer, 4321)
    return scope


def test_a_caller_that_is_not_our_proxy_cannot_choose_its_address():
    for forged in ("198.51.100.4, 130.176.1.1", "10.0.0.1", "203.0.113.9, 203.0.113.9, 203.0.113.9"):
        assert rate_limit.client_address(_scope(forged, peer="203.0.113.66")) == "ip:203.0.113.66"


def test_rotating_a_forged_chain_from_outside_never_buys_a_fresh_bucket():
    keys = {rate_limit.client_address(_scope(f"198.51.100.{n}, 130.176.1.{n}", peer="203.0.113.66")) for n in range(20)}
    assert keys == {"ip:203.0.113.66"}


def test_behind_our_proxies_the_client_is_counted_from_the_right():
    chain = "6.6.6.6, 198.51.100.4, 130.176.1.1"
    assert rate_limit.client_address(_scope(chain)) == "ip:198.51.100.4"
    assert rate_limit.client_address(_scope(chain, "::1")) == "ip:198.51.100.4"


def test_the_cloudfront_hop_is_believed_only_with_the_origin_secret(production_shape):
    production_shape.origin_verify_secret = ORIGIN_SECRET
    chain = "6.6.6.6, 198.51.100.4, 130.176.1.1"
    verified = (rate_limit.ORIGIN_VERIFY_HEADER, ORIGIN_SECRET.encode())
    forged = (rate_limit.ORIGIN_VERIFY_HEADER, b"guessed")
    assert rate_limit.client_address(_scope(chain, "127.0.0.1", verified)) == "ip:198.51.100.4"
    assert rate_limit.client_address(_scope(chain, "127.0.0.1", forged)) == "ip:130.176.1.1"
    assert rate_limit.client_address(_scope(chain)) == "ip:130.176.1.1"


def test_a_garbage_entry_stops_the_walk_at_the_last_address_a_proxy_wrote():
    assert rate_limit.client_address(_scope("not-an-address, 130.176.1.1")) == "ip:130.176.1.1"
    assert rate_limit.client_address(_scope("198.51.100.4, unknown")) == "ip:127.0.0.1"


def test_a_short_chain_is_the_address_our_proxy_saw():
    assert rate_limit.client_address(_scope("203.0.113.7")) == "ip:203.0.113.7"
    assert rate_limit.client_address(_scope(None)) == "ip:127.0.0.1"


def test_configured_proxy_networks_are_trusted_and_others_are_not(production_shape):
    production_shape.trusted_proxy_cidrs = "10.0.0.0/8, not-a-network"
    chain = "198.51.100.4, 130.176.1.1"
    assert rate_limit.client_address(_scope(chain, "10.4.5.6")) == "ip:198.51.100.4"
    assert rate_limit.client_address(_scope(chain, "127.0.0.1")) == "ip:127.0.0.1"


def test_a_trusted_inner_hop_is_walked_past_without_the_origin_secret(production_shape):
    production_shape.origin_verify_secret = ORIGIN_SECRET
    production_shape.trusted_proxy_cidrs = "127.0.0.1/32,10.0.0.0/8"
    assert rate_limit.client_address(_scope("198.51.100.4, 10.0.0.7")) == "ip:198.51.100.4"


def test_an_unknown_or_missing_peer_never_reads_the_headers():
    assert rate_limit.client_address(_scope("198.51.100.4, 130.176.1.1", peer=None)) == "ip:unknown"
    assert rate_limit.client_address(_scope("198.51.100.4, 130.176.1.1", peer="testclient")) == "ip:testclient"


def test_every_server_command_hands_the_socket_peer_to_the_app():
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    launches = [
        root / "backend" / "Dockerfile",
        root / "backend" / "deploy" / "blue_green.sh",
        root / "infra" / "k8s" / "base" / "deployment-api.yaml",
    ]
    for path in launches:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        assert "--no-proxy-headers" in text, path
        assert "--forwarded-allow-ips" not in text, path
