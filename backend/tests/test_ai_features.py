"""The contract these tests defend is "a fresh clone behaves exactly as it did before".

They run in a venv with NONE of the optional dependencies installed — no rembg, no onnxruntime,
no Pillow, no vtracer — and that is the point. Every assertion here is about the dormant state:
the package imports on stdlib alone, the flags are off, the probe can say why, and a call fails
with a typed error that names the variable to set instead of an ImportError from a library nobody
installed. If someone later moves an import to module scope, the first test in this file fails on
a machine that does not have the package, which is every machine we deploy to.

The one thing they cannot check is a provider actually working: that needs the dependency and, for
the hosted pair, an account and a credit. ``docs/AI_FEATURES.md`` says what to run by hand instead.
"""

import importlib
import struct
import subprocess
import sys
import zlib
from pathlib import Path

import pytest

from app import ai_features
from app.ai_features import (
    AiFeatureError,
    Capability,
    DependencyMissing,
    FeatureDisabled,
    ImageTooLarge,
    ProviderNotConfigured,
    UnknownProvider,
    UnsupportedImageType,
)
from app.ai_features import registry
from app.ai_features.imaging import load_source, sniff
from app.ai_features.probe import capability_status, format_probe, probe
from app.ai_features.runtime import Deadline, reset_warn_once_cache, warn_once
from app.ai_features.settings import AiFeatureSettings, ENV_VARS, build_settings

BACKEND_ROOT = Path(__file__).resolve().parents[1]

#: Every module in the package, so the "imports without its dependencies" test cannot go stale by
#: someone adding a file and forgetting it.
PACKAGE_MODULES = (
    "app.ai_features",
    "app.ai_features.errors",
    "app.ai_features.imaging",
    "app.ai_features.probe",
    "app.ai_features.registry",
    "app.ai_features.runtime",
    "app.ai_features.service",
    "app.ai_features.settings",
    "app.ai_features.types",
    "app.ai_features.providers",
    "app.ai_features.providers.base",
    "app.ai_features.providers.http",
    "app.ai_features.providers.rembg_local",
    "app.ai_features.providers.remove_bg",
    "app.ai_features.providers.vectorizer_ai",
    "app.ai_features.providers.vtracer_local",
)

#: The heavy things. If any of these is in sys.modules after importing the package, something is
#: being imported at module scope that must not be.
OPTIONAL_DEPENDENCIES = ("rembg", "onnxruntime", "torch", "numpy", "PIL", "vtracer")


def _png(width: int = 8, height: int = 6) -> bytes:
    """A real PNG built with stdlib zlib — the tests cannot use Pillow to make fixtures."""
    raw = b"".join(b"\x00" + b"\x7f\x40\x20" * width for _ in range(height))

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


def _jpeg(width: int = 20, height: int = 10) -> bytes:
    """Enough of a JPEG for the header sniffer: SOI, a padded APP0, then an SOF0 frame."""
    return (
        b"\xff\xd8"
        + b"\xff\xe0" + struct.pack(">H", 16) + b"JFIF\x00" + b"\x00" * 9
        + b"\xff\xc0" + struct.pack(">H", 11) + b"\x08" + struct.pack(">HH", height, width) + b"\x01"
        + b"\xff\xd9"
    )


ALL_OFF = build_settings({})


def _settings(**overrides: str) -> AiFeatureSettings:
    """Settings built from an explicit mapping, never from the machine's environment."""
    return build_settings(overrides)


@pytest.fixture(autouse=True)
def _isolate_process_state() -> None:
    """warn_once is process-global; a leaked key would silence an assertion in another test."""
    reset_warn_once_cache()


# --------------------------------------------------------------------------------------------
# 1. The package is importable, and importing it costs nothing.
# --------------------------------------------------------------------------------------------


def test_every_module_imports_without_the_optional_dependencies() -> None:
    for name in PACKAGE_MODULES:
        assert importlib.import_module(name) is not None


def test_importing_the_package_does_not_pull_in_a_heavy_dependency() -> None:
    # In-process, sys.modules may already be polluted by another test, so this is checked in a
    # clean interpreter: a subprocess that imports the package and nothing else.
    script = (
        "import sys;"
        "import app.ai_features;"
        f"names = {OPTIONAL_DEPENDENCIES!r};"
        "print(','.join(n for n in names if n in sys.modules))"
    )
    completed = subprocess.run(
        [sys.executable, "-c", script],
        cwd=BACKEND_ROOT,
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.strip() == "", (
        f"importing app.ai_features loaded {completed.stdout.strip()} — move that import inside "
        "the function that needs it"
    )


def test_public_surface_is_what_init_advertises() -> None:
    for name in ai_features.__all__:
        assert hasattr(ai_features, name), f"__all__ names {name}, which does not exist"


# --------------------------------------------------------------------------------------------
# 2. Default off: no new environment variable, no behaviour change.
# --------------------------------------------------------------------------------------------


def test_empty_environment_disables_everything() -> None:
    assert ALL_OFF.enabled is False
    for capability in Capability:
        assert ALL_OFF.capability_enabled(capability) is False


def test_master_switch_alone_enables_nothing() -> None:
    settings = _settings(AI_FEATURES_ENABLED="true")
    for capability in Capability:
        assert settings.capability_enabled(capability) is False


def test_capability_flag_alone_enables_nothing() -> None:
    settings = _settings(AI_BACKGROUND_REMOVAL_ENABLED="true")
    assert settings.capability_enabled(Capability.BACKGROUND_REMOVAL) is False


def test_both_switches_are_needed() -> None:
    settings = _settings(AI_FEATURES_ENABLED="1", AI_BACKGROUND_REMOVAL_ENABLED="yes")
    assert settings.capability_enabled(Capability.BACKGROUND_REMOVAL) is True
    assert settings.capability_enabled(Capability.VECTORISATION) is False


def test_a_nonsense_flag_value_is_a_note_not_a_crash() -> None:
    settings = _settings(AI_FEATURES_ENABLED="perhaps", AI_FEATURES_TIMEOUT_SECONDS="soon")
    assert settings.enabled is False
    assert len(settings.notes) == 2
    assert settings.timeout_seconds == ALL_OFF.timeout_seconds


def test_every_variable_the_code_reads_is_documented() -> None:
    # The only reference for these flags is docs/AI_FEATURES.md, since they are deliberately absent
    # from .env.example. A variable that exists in code and not in the document is unfindable.
    document = (BACKEND_ROOT.parent / "docs" / "AI_FEATURES.md").read_text(encoding="utf-8")
    undocumented = [name for name in ENV_VARS if name not in document]
    assert undocumented == [], f"not in docs/AI_FEATURES.md: {undocumented}"


def test_settings_survive_every_variable_being_set_at_once() -> None:
    settings = build_settings({name: "1" for name in ENV_VARS})
    # "1" is nonsense for most of them, so the point is that it produces notes rather than raising.
    assert settings.enabled is True
    assert settings.notes


# --------------------------------------------------------------------------------------------
# 3. Calling a disabled capability fails softly, with the variable named.
# --------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("call", "variable"),
    [
        (ai_features.remove_background, "AI_BACKGROUND_REMOVAL_ENABLED"),
        (ai_features.separate_foreground, "AI_FOREGROUND_SEPARATION_ENABLED"),
        (ai_features.vectorise_image, "AI_IMAGE_VECTORISATION_ENABLED"),
    ],
)
def test_disabled_capability_raises_feature_disabled(call, variable: str) -> None:
    with pytest.raises(FeatureDisabled) as caught:
        call(_png(), settings=ALL_OFF)
    assert caught.value.code == "disabled"
    assert "AI_FEATURES_ENABLED" in (caught.value.remediation or "")
    assert variable in (caught.value.remediation or "")


def test_disabled_beats_a_bad_image() -> None:
    # The order matters: an operator with the feature off should be told it is off, not told
    # their input is wrong by a code path that could not have run either way.
    with pytest.raises(FeatureDisabled):
        ai_features.remove_background(b"not an image at all", settings=ALL_OFF)


def test_a_disabled_call_is_catchable_as_the_base_error() -> None:
    with pytest.raises(AiFeatureError):
        ai_features.vectorise_image(_png(), settings=ALL_OFF)


# --------------------------------------------------------------------------------------------
# 4. Enabled but not installed: a typed error naming the missing package, never an ImportError.
# --------------------------------------------------------------------------------------------

_LOCAL_MISSING = not registry.module_available("vtracer")
_REMBG_MISSING = not registry.module_available("rembg")


@pytest.mark.skipif(not _REMBG_MISSING, reason="rembg is installed in this venv")
def test_enabled_without_the_local_package_raises_dependency_missing() -> None:
    settings = _settings(
        AI_FEATURES_ENABLED="true",
        AI_BACKGROUND_REMOVAL_ENABLED="true",
        AI_BACKGROUND_REMOVAL_PROVIDER="rembg_local",
    )
    with pytest.raises(DependencyMissing) as caught:
        ai_features.remove_background(_png(), settings=settings)
    assert "rembg" in caught.value.modules
    assert "pip install" in (caught.value.remediation or "")


@pytest.mark.skipif(not _LOCAL_MISSING, reason="vtracer is installed in this venv")
def test_vectorisation_without_vtracer_or_a_key_explains_both_options() -> None:
    settings = _settings(AI_FEATURES_ENABLED="true", AI_IMAGE_VECTORISATION_ENABLED="true")
    with pytest.raises(AiFeatureError) as caught:
        ai_features.vectorise_image(_png(), settings=settings)
    assert "vtracer_local" in caught.value.message
    assert "vectorizer_ai" in caught.value.message


def test_enabled_hosted_provider_without_a_key_raises_not_configured() -> None:
    # requests IS installed (it is a core dependency), so this is the credential case rather
    # than the missing-package one, which is exactly the distinction the errors exist for.
    settings = _settings(
        AI_FEATURES_ENABLED="true",
        AI_BACKGROUND_REMOVAL_ENABLED="true",
        AI_BACKGROUND_REMOVAL_PROVIDER="remove_bg",
    )
    with pytest.raises(ProviderNotConfigured) as caught:
        ai_features.remove_background(_png(), settings=settings)
    assert "REMOVE_BG_API_KEY" in caught.value.missing


def test_an_unknown_provider_id_lists_the_real_ones() -> None:
    settings = _settings(
        AI_FEATURES_ENABLED="true",
        AI_BACKGROUND_REMOVAL_ENABLED="true",
        AI_BACKGROUND_REMOVAL_PROVIDER="magic_wand",
    )
    with pytest.raises(UnknownProvider) as caught:
        ai_features.remove_background(_png(), settings=settings)
    assert "remove_bg" in (caught.value.remediation or "")


def test_an_explicit_provider_is_never_silently_swapped() -> None:
    # A caller who asked for the local matte must not be charged for a hosted one instead.
    settings = _settings(
        AI_FEATURES_ENABLED="true",
        AI_BACKGROUND_REMOVAL_ENABLED="true",
        AI_BACKGROUND_REMOVAL_PROVIDER="rembg_local",
        REMOVE_BG_API_KEY="a-key-that-would-otherwise-be-used",
    )
    if not _REMBG_MISSING:
        pytest.skip("rembg is installed, so the local provider would legitimately be chosen")
    with pytest.raises(DependencyMissing) as caught:
        ai_features.remove_background(_png(), settings=settings)
    assert caught.value.provider == "rembg_local"


def test_a_provider_that_does_not_implement_the_capability_is_rejected() -> None:
    settings = _settings(
        AI_FEATURES_ENABLED="true",
        AI_IMAGE_VECTORISATION_ENABLED="true",
        AI_IMAGE_VECTORISATION_PROVIDER="remove_bg",
    )
    with pytest.raises(UnknownProvider):
        ai_features.vectorise_image(_png(), settings=settings)


# --------------------------------------------------------------------------------------------
# 5. Input validation, which works with no dependencies at all.
# --------------------------------------------------------------------------------------------


def test_sniff_reads_png_and_jpeg_headers() -> None:
    assert sniff(_png(8, 6)) == ("image/png", "png", 8, 6)
    assert sniff(_jpeg(20, 10)) == ("image/jpeg", "jpg", 20, 10)
    assert sniff(b"GIF89a and then some") is None


def test_load_source_accepts_bytes_and_paths(tmp_path: Path) -> None:
    from_bytes = load_source(_png(), ALL_OFF)
    assert (from_bytes.width, from_bytes.height) == (8, 6)
    assert from_bytes.filename == "image.png"

    on_disk = tmp_path / "motif.png"
    on_disk.write_bytes(_png(12, 9))
    from_path = load_source(str(on_disk), ALL_OFF)
    assert (from_path.width, from_path.height) == (12, 9)
    assert from_path.filename == "motif.png"


def test_load_source_refuses_what_it_cannot_identify() -> None:
    with pytest.raises(UnsupportedImageType):
        load_source(b"%PDF-1.7 this is not an image", ALL_OFF)
    with pytest.raises(UnsupportedImageType):
        load_source(b"", ALL_OFF)
    with pytest.raises(UnsupportedImageType):
        load_source(12345, ALL_OFF)  # type: ignore[arg-type]


def test_load_source_enforces_the_byte_and_pixel_ceilings(tmp_path: Path) -> None:
    tiny = _settings(AI_FEATURES_MAX_IMAGE_BYTES="1024")
    with pytest.raises(ImageTooLarge):
        load_source(_png(400, 400), tiny)

    few_pixels = _settings(AI_FEATURES_MAX_IMAGE_PIXELS="1024")
    with pytest.raises(ImageTooLarge):
        load_source(_png(64, 64), few_pixels)

    # A path is rejected from its stat(), before the bytes are ever read into memory.
    big = tmp_path / "big.png"
    big.write_bytes(_png(400, 400))
    with pytest.raises(ImageTooLarge):
        load_source(big, tiny)


def test_a_missing_file_is_a_typed_error_not_an_oserror(tmp_path: Path) -> None:
    with pytest.raises(UnsupportedImageType):
        load_source(tmp_path / "nothing-here.png", ALL_OFF)


# --------------------------------------------------------------------------------------------
# 6. The probe: the thing an operator runs to find out why nothing is happening.
# --------------------------------------------------------------------------------------------


def test_probe_reports_dormant_on_a_default_installation() -> None:
    report = probe(ALL_OFF)
    assert report["enabled"] is False
    assert len(report["capabilities"]) == len(Capability)
    for entry in report["capabilities"]:
        assert entry["enabled"] is False
        assert entry["available"] is False
        assert "AI_FEATURES_ENABLED" in entry["reason"]


def test_probe_distinguishes_the_master_switch_from_the_capability_switch() -> None:
    settings = _settings(AI_FEATURES_ENABLED="true")
    status = capability_status(Capability.VECTORISATION, settings)
    assert status.available is False
    assert status.reason == "AI_IMAGE_VECTORISATION_ENABLED is off"


def test_probe_names_the_missing_package_when_the_flags_are_on() -> None:
    if not _LOCAL_MISSING:
        pytest.skip("vtracer is installed in this venv")
    settings = _settings(AI_FEATURES_ENABLED="true", AI_IMAGE_VECTORISATION_ENABLED="true")
    status = capability_status(Capability.VECTORISATION, settings)
    assert status.enabled is True
    assert status.available is False
    assert any("vtracer" in item.missing_modules for item in status.providers)


def test_probe_never_leaks_a_secret() -> None:
    settings = _settings(
        AI_FEATURES_ENABLED="true",
        AI_BACKGROUND_REMOVAL_ENABLED="true",
        REMOVE_BG_API_KEY="sk-do-not-print-me",
    )
    serialised = repr(probe(settings)) + format_probe(settings)
    assert "sk-do-not-print-me" not in serialised


def test_probe_is_json_safe() -> None:
    import json

    json.dumps(probe(ALL_OFF))  # raises if a dataclass or an enum leaked into the report


def test_every_provider_declares_its_resource_cost_and_how_it_was_established() -> None:
    for descriptor in registry.ALL_PROVIDERS:
        resources = descriptor.resources
        assert resources.basis in ("MEASURED", "ESTIMATED", "VENDOR_STATED")
        assert resources.latency and resources.money
        if resources.basis == "MEASURED":
            # A measured claim has to say where it came from, or it is just an estimate wearing
            # a better label.
            assert "MEASURED" in resources.notes


def test_hosted_separation_declares_the_pillow_it_needs() -> None:
    assert "PIL" not in registry.REMOVE_BG.modules_for(Capability.BACKGROUND_REMOVAL)
    assert "PIL" in registry.REMOVE_BG.modules_for(Capability.FOREGROUND_SEPARATION)


def test_format_probe_is_readable_and_says_how_to_turn_it_on() -> None:
    text = format_probe(ALL_OFF)
    assert "disabled (default)" in text
    assert "AI_FEATURES_ENABLED=true" in text
    assert all(str(capability) in text for capability in Capability)


# --------------------------------------------------------------------------------------------
# 7. Call-time plumbing that has to behave with nothing installed.
# --------------------------------------------------------------------------------------------


def test_a_spent_deadline_refuses_to_start_more_work() -> None:
    from app.ai_features.errors import ProviderTimeout

    spent = Deadline(budget_seconds=0.0, started_at=Deadline.start(0).started_at - 5)
    with pytest.raises(ProviderTimeout):
        spent.check("a call", capability=Capability.VECTORISATION, provider="vtracer_local")


def test_a_live_deadline_hands_out_what_is_left() -> None:
    deadline = Deadline.start(30.0)
    assert 25.0 < deadline.remaining() <= 30.0
    deadline.check("a call", capability=Capability.VECTORISATION, provider="vtracer_local")


def test_warn_once_says_it_once(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level("WARNING"):
        for _ in range(5):
            warn_once("a-key", "the thing happened")
    assert sum("the thing happened" in record.message for record in caplog.records) == 1


def test_errors_serialise_for_a_future_route() -> None:
    error = DependencyMissing("no vtracer", modules=("vtracer",), capability=Capability.VECTORISATION)
    payload = error.as_dict()
    assert payload["code"] == "dependency_missing"
    assert payload["modules"] == ["vtracer"]
    assert payload["capability"] == "vectorisation"
