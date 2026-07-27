"""The hosted providers, exercised end to end with a stubbed transport and no network.

These also still run with none of the optional dependencies installed. That is possible precisely
because every dependency is imported inside the function that uses it: a stub can be put in
``sys.modules`` before the call and the provider picks it up, which would be impossible if the
module had imported requests at the top. So this file is both a test of remove.bg's error mapping
and a demonstration that the lazy-import rule buys testability as well as boot time.

Nothing here talks to remove.bg or vectorizer.ai. Their real behaviour needs an account and spends
a credit; ``docs/AI_FEATURES.md`` has the one-line curl to try that by hand when someone does.
"""

import importlib.machinery
import struct
import sys
import zlib

import pytest

from app import ai_features
from app.ai_features import registry
from app.ai_features.errors import (
    DependencyMissing,
    ProviderFailed,
    ProviderNotConfigured,
    ProviderRateLimited,
    ProviderTimeout,
)
from app.ai_features.providers.http import describe_error_body
from app.ai_features.settings import build_settings
from app.ai_features.types import Capability


def _png(width: int = 8, height: int = 6) -> bytes:
    raw = b"".join(b"\x00" + b"\x40\x60\x80" * width for _ in range(height))

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


class _StubResponse:
    def __init__(self, status_code: int, headers: dict[str, str], body: bytes) -> None:
        self.status_code = status_code
        self.headers = headers
        self._body = body

    def __enter__(self) -> "_StubResponse":
        return self

    def __exit__(self, *_: object) -> bool:
        return False

    def iter_content(self, chunk_size: int = 8192):
        for start in range(0, len(self._body), chunk_size):
            yield self._body[start:start + chunk_size]


class _StubRequests:
    """The three names ``post_image`` touches: post, Timeout, RequestException."""

    class RequestException(Exception):
        pass

    class Timeout(RequestException):
        pass

    def __init__(self, response: _StubResponse | Exception) -> None:
        self.response = response
        self.calls: list[dict] = []
        # The registry asks find_spec("requests") whether the provider is installed, and
        # find_spec reads __spec__ off whatever is in sys.modules. Without this the stub would
        # make the readiness check answer "requests is missing" and the call would never happen.
        self.__spec__ = importlib.machinery.ModuleSpec("requests", None)

    def post(self, url: str, **kwargs):
        self.calls.append({"url": url, **kwargs})
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


@pytest.fixture
def stub_requests(monkeypatch: pytest.MonkeyPatch):
    """Install a stub in place of requests for the duration of one test."""

    def install(response: _StubResponse | Exception) -> _StubRequests:
        stub = _StubRequests(response)
        monkeypatch.setitem(sys.modules, "requests", stub)
        return stub

    return install


def _hosted_settings(**overrides: str):
    base = {
        "AI_FEATURES_ENABLED": "true",
        "AI_BACKGROUND_REMOVAL_ENABLED": "true",
        "AI_BACKGROUND_REMOVAL_PROVIDER": "remove_bg",
        "REMOVE_BG_API_KEY": "test-key",
    }
    base.update(overrides)
    return build_settings(base)


def _vector_settings(**overrides: str):
    base = {
        "AI_FEATURES_ENABLED": "true",
        "AI_IMAGE_VECTORISATION_ENABLED": "true",
        "AI_IMAGE_VECTORISATION_PROVIDER": "vectorizer_ai",
        "VECTORIZER_AI_API_ID": "an-id",
        "VECTORIZER_AI_API_SECRET": "a-secret",
    }
    base.update(overrides)
    return build_settings(base)


# --------------------------------------------------------------------------------------------
# The happy path, all the way through the public entry point.
# --------------------------------------------------------------------------------------------


def test_hosted_background_removal_returns_the_cutout(stub_requests) -> None:
    cutout = _png(8, 6)
    stub = stub_requests(
        _StubResponse(200, {"Content-Type": "image/png", "X-Credits-Charged": "1"}, cutout)
    )

    result = ai_features.remove_background(_png(), settings=_hosted_settings())

    assert result.image == cutout
    assert result.provider == "remove_bg"
    assert result.mime_type == "image/png"
    assert any("charged 1 credit" in note for note in result.notes)
    sent = stub.calls[0]
    assert sent["url"] == "https://api.remove.bg/v1.0/removebg"
    assert sent["headers"]["X-Api-Key"] == "test-key"
    # format=png or the transparency we are here for can come back flattened.
    assert sent["data"]["format"] == "png"
    assert sent["timeout"][0] <= 10.0


def test_hosted_vectorisation_flags_the_watermark(stub_requests) -> None:
    svg = b'<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg>'
    stub = stub_requests(_StubResponse(200, {"Content-Type": "image/svg+xml"}, svg))

    result = ai_features.vectorise_image(_png(), settings=_vector_settings())

    assert result.svg == svg
    assert any("watermark" in note for note in result.notes)
    assert stub.calls[0]["auth"] == ("an-id", "a-secret")
    assert stub.calls[0]["data"]["mode"] == "test"


def test_production_mode_is_only_ever_chosen_deliberately(stub_requests) -> None:
    svg = b"<svg/>"
    stub = stub_requests(_StubResponse(200, {"Content-Type": "image/svg+xml"}, svg))
    result = ai_features.vectorise_image(
        _png(), settings=_vector_settings(VECTORIZER_AI_MODE="production")
    )
    assert stub.calls[0]["data"]["mode"] == "production"
    assert not any("watermark" in note for note in result.notes)


# --------------------------------------------------------------------------------------------
# Every failure a hosted provider can hand us, and the class it turns into.
# --------------------------------------------------------------------------------------------


def test_out_of_credit_is_a_provider_failure_with_a_way_out(stub_requests) -> None:
    stub_requests(
        _StubResponse(
            402,
            {"Content-Type": "application/json"},
            b'{"errors":[{"title":"Insufficient credits","code":"insufficient_credits"}]}',
        )
    )
    with pytest.raises(ProviderFailed) as caught:
        ai_features.remove_background(_png(), settings=_hosted_settings())
    assert "Insufficient credits" in caught.value.message
    assert "preview" in (caught.value.remediation or "")


def test_rate_limiting_carries_the_retry_hint(stub_requests) -> None:
    stub_requests(
        _StubResponse(429, {"Retry-After": "12"}, b'{"errors":[{"title":"Rate limit exceeded"}]}')
    )
    with pytest.raises(ProviderRateLimited) as caught:
        ai_features.remove_background(_png(), settings=_hosted_settings())
    assert caught.value.retry_after == 12.0
    assert caught.value.as_dict()["retryAfter"] == 12.0


def test_a_rejected_key_is_a_configuration_problem_not_a_failure(stub_requests) -> None:
    stub_requests(_StubResponse(403, {}, b'{"errors":[{"title":"Invalid API key"}]}'))
    with pytest.raises(ProviderNotConfigured) as caught:
        ai_features.remove_background(_png(), settings=_hosted_settings())
    assert "REMOVE_BG_API_KEY" in (caught.value.remediation or "")


def test_a_timeout_is_typed_and_suggests_the_queue(stub_requests) -> None:
    stub_requests(_StubRequests.Timeout("read timed out"))
    with pytest.raises(ProviderTimeout) as caught:
        ai_features.remove_background(_png(), settings=_hosted_settings())
    assert "queue" in (caught.value.remediation or "")


def test_an_unreachable_host_never_escapes_as_a_requests_error(stub_requests) -> None:
    stub_requests(_StubRequests.RequestException("connection refused"))
    with pytest.raises(ProviderFailed) as caught:
        ai_features.remove_background(_png(), settings=_hosted_settings())
    assert "could not reach remove_bg" in caught.value.message


def test_a_200_that_is_not_an_image_is_refused(stub_requests) -> None:
    stub_requests(_StubResponse(200, {"Content-Type": "application/json"}, b'{"ok":true}'))
    with pytest.raises(ProviderFailed) as caught:
        ai_features.remove_background(_png(), settings=_hosted_settings())
    assert "not an image" in caught.value.message


def test_an_endless_response_is_abandoned_rather_than_buffered(stub_requests) -> None:
    settings = _hosted_settings(AI_FEATURES_MAX_IMAGE_BYTES="2048")
    stub_requests(_StubResponse(200, {"Content-Type": "image/png"}, b"\x00" * (2048 * 4 + 1)))
    with pytest.raises(ProviderFailed) as caught:
        ai_features.remove_background(_png(), settings=settings)
    assert "abandoned" in caught.value.message


def test_the_deadline_reaches_the_socket(stub_requests) -> None:
    stub = stub_requests(_StubResponse(200, {"Content-Type": "image/png"}, _png()))
    ai_features.remove_background(_png(), settings=_hosted_settings(AI_FEATURES_TIMEOUT_SECONDS="5"))
    connect, read = stub.calls[0]["timeout"]
    assert 0 < read <= 5.0
    assert 0 < connect <= 5.0


@pytest.mark.parametrize(
    ("body", "expected"),
    [
        (b'{"errors":[{"title":"Nope","code":"x"}]}', "Nope"),
        (b'{"error":{"message":"Also nope"}}', "Also nope"),
        (b"plain text failure", "plain text failure"),
        (b"", "(empty response body)"),
    ],
)
def test_vendor_error_bodies_are_summarised_whatever_shape_they_are(body: bytes, expected) -> None:
    assert expected in describe_error_body(body)


# --------------------------------------------------------------------------------------------
# The capability that needs more than the hosted call.
# --------------------------------------------------------------------------------------------


@pytest.mark.skipif(registry.module_available("PIL"), reason="Pillow is installed in this venv")
def test_hosted_separation_without_pillow_says_so_before_spending_a_credit(stub_requests) -> None:
    # The readiness check runs before the provider is even loaded, so a missing Pillow costs
    # nothing: no upload, no credit, and an error that names the package.
    stub = stub_requests(_StubResponse(200, {"Content-Type": "image/png"}, _png()))
    settings = build_settings(
        {
            "AI_FEATURES_ENABLED": "true",
            "AI_FOREGROUND_SEPARATION_ENABLED": "true",
            "AI_FOREGROUND_SEPARATION_PROVIDER": "remove_bg",
            "REMOVE_BG_API_KEY": "test-key",
        }
    )
    with pytest.raises(DependencyMissing) as caught:
        ai_features.separate_foreground(_png(), settings=settings)
    assert "PIL" in caught.value.modules
    assert stub.calls == []


def test_background_removal_is_available_where_separation_is_not() -> None:
    """The distinction the extra_modules field exists to make, seen through the probe."""
    settings = build_settings(
        {
            "AI_FEATURES_ENABLED": "true",
            "AI_BACKGROUND_REMOVAL_ENABLED": "true",
            "AI_FOREGROUND_SEPARATION_ENABLED": "true",
            "REMOVE_BG_API_KEY": "test-key",
        }
    )
    assert ai_features.is_available(Capability.BACKGROUND_REMOVAL, settings) is True
    if registry.module_available("PIL"):
        pytest.skip("Pillow is installed, so separation is legitimately available too")
    assert ai_features.is_available(Capability.FOREGROUND_SEPARATION, settings) is False
