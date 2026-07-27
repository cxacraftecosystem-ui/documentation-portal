"""The catalogue: which providers exist, what each one costs, and which one a call should use.

WHY DESCRIPTIONS AND IMPLEMENTATIONS LIVE APART. Everything in this module is plain data and
stdlib lookups — no provider module is imported here, and ``importlib.util.find_spec`` is used to
answer "is rembg installed" because it walks the path finders without executing a line of the
package. That is what lets the probe describe a 176 MB ONNX model on a box that has never had
onnxruntime installed, and what keeps ``import app.ai_features`` free.

THE RESOURCE NUMBERS ARE LABELLED, AND THE LABELS ARE THE POINT. ``basis`` says how each figure
was arrived at. The two local providers were actually run on a developer laptop and their notes
give the machine, the versions and the numbers; the hosted providers' RAM and latency are
estimates, because nobody here has an account to time; the money is what the vendor advertises
and needs re-checking before anyone enables it. An operator sizing the production box has to be
able to tell those three apart, which is why an unmeasured number is never labelled MEASURED.
"""

import importlib
import importlib.util
from typing import Any

from app.ai_features.errors import DependencyMissing, ProviderNotConfigured, UnknownProvider
from app.ai_features.settings import AiFeatureSettings, provider_var
from app.ai_features.types import (
    Capability,
    ProviderDescriptor,
    ProviderReadiness,
    ResourceProfile,
)

_MATTING = frozenset({Capability.BACKGROUND_REMOVAL, Capability.FOREGROUND_SEPARATION})

#: Splitting a cutout into two layers is Pillow work, whoever produced the cutout. Declaring it
#: per capability keeps the probe honest: hosted background removal needs nothing but ``requests``
#: (a core dependency), while hosted separation genuinely needs the ``ai`` extra.
_SEPARATION_NEEDS_PILLOW: tuple[tuple[Capability, tuple[str, ...]], ...] = (
    (Capability.FOREGROUND_SEPARATION, ("PIL",)),
)


REMBG_LOCAL = ProviderDescriptor(
    id="rembg_local",
    label="rembg (U2Net, on this machine)",
    kind="local",
    capabilities=_MATTING,
    required_modules=("rembg", "onnxruntime", "PIL"),
    required_settings=(),
    implementation="app.ai_features.providers.rembg_local:RembgLocalProvider",
    resources=ResourceProfile(
        basis="MEASURED",
        model_download_mb=176.0,
        peak_ram_mb=1032.0,
        latency="1.3-2.0 s at 1.9 MP, 2.0-2.4 s at 6 MP, plus ~5 s to import and ~4 s to build "
        "the session (MEASURED on a laptop; two burstable vCPU will be slower)",
        money="No per-image charge; you pay in RAM and CPU on your own box.",
        notes=(
            "MEASURED 2026-07-26 in a throwaway venv: rembg with onnxruntime 1.28.0, CPython "
            "3.12.10, Windows, Intel i5-10300H, peak working set of the whole process. u2net on "
            "a 1.9 MP image: 35 MB at rest, 167 MB after 'import rembg' alone, 564 MB once the "
            "session exists, 1,032 MB after one inference; at 6 MP the peak was 1,123 MB. The "
            "lite model (AI_FEATURES_LOCAL_MODEL=u2netp, a 4.7 MB download) still peaked at 696 "
            "MB on the same 1.9 MP image, because the full-resolution RGBA buffers cost more "
            "than the weights do. The production box has 1,024 MB in total and is already "
            "running uvicorn and the Prisma engine, so BOTH models are out of the question "
            "there: this provider needs a bigger instance or a separate worker. The 176 MB "
            "download lands in ~/.u2net on first use (AI_FEATURES_LOCAL_MODEL_DIR moves it) and "
            "took ~17 s here."
        ),
    ),
    summary=(
        "Local U2Net matting through rembg. Free per image, no data leaves the machine, and the "
        "heaviest thing this package can be asked to do."
    ),
)

REMOVE_BG = ProviderDescriptor(
    id="remove_bg",
    label="remove.bg (hosted)",
    kind="hosted",
    capabilities=_MATTING,
    required_modules=("requests",),
    required_settings=("REMOVE_BG_API_KEY",),
    implementation="app.ai_features.providers.remove_bg:RemoveBgProvider",
    extra_modules=_SEPARATION_NEEDS_PILLOW,
    resources=ResourceProfile(
        basis="ESTIMATED",
        model_download_mb=None,
        peak_ram_mb=45.0,
        latency="2-6 s from ap-south-1 for a 3-5 MB photograph, upload included (ESTIMATED)",
        money=(
            "VENDOR-STATED: one credit per full-size image, preview size free. Re-check "
            "remove.bg/pricing before enabling - this was not verified against an account."
        ),
        notes=(
            "RAM is ESTIMATED as roughly three times the image in flight (request buffer, "
            "response buffer, the copy handed back) against the 12 MB input ceiling. Nothing is "
            "downloaded and nothing is cached, so this is the only matting provider that fits on "
            "the t3.micro as it stands. Note that it sends the photograph to a third party: "
            "check that against the consent under which the craft images were collected."
        ),
    ),
    summary=(
        "Hosted cutout. Needs only an API key and the core requests dependency; separation adds "
        "Pillow because the two layers are composited here from the returned alpha."
    ),
)

VTRACER_LOCAL = ProviderDescriptor(
    id="vtracer_local",
    label="VTracer (on this machine)",
    kind="local",
    capabilities=frozenset({Capability.VECTORISATION}),
    required_modules=("vtracer",),
    required_settings=(),
    implementation="app.ai_features.providers.vtracer_local:VtracerLocalProvider",
    resources=ResourceProfile(
        basis="MEASURED",
        model_download_mb=None,
        peak_ram_mb=275.0,
        latency="0.25 s at 0.5 MP, 0.7-1.1 s at 1.9 MP, 2.4-2.8 s at 6 MP (MEASURED)",
        money="No per-image charge. The wheel is a few MB and there is no model to download.",
        notes=(
            "MEASURED 2026-07-26 in a throwaway venv: vtracer 0.6.15, CPython 3.12.10, Windows, "
            "Intel i5-10300H, three runs per size on a synthetic flat-colour motif. Peak working "
            "set of the whole process was 48 MB at 0.5 MP, 111 MB at 1.9 MP and 273 MB at 6 MP, "
            "of which the interpreter baseline was 27/35/59 MB. Importing vtracer itself added "
            "nothing measurable. A photograph is the harder case - more regions, more paths - so "
            "treat these as a floor, and the two burstable vCPU of a t3.micro as slower again. "
            "The 6 MP motif produced a 1.1 MB SVG, which is worth knowing before storing one per "
            "product image."
        ),
    ),
    summary=(
        "Local colour tracing to SVG. Cheap enough to be the sensible default for vectorisation, "
        "unlike local matting."
    ),
)

VECTORIZER_AI = ProviderDescriptor(
    id="vectorizer_ai",
    label="vectorizer.ai (hosted)",
    kind="hosted",
    capabilities=frozenset({Capability.VECTORISATION}),
    required_modules=("requests",),
    required_settings=("VECTORIZER_AI_API_ID", "VECTORIZER_AI_API_SECRET"),
    implementation="app.ai_features.providers.vectorizer_ai:VectorizerAiProvider",
    resources=ResourceProfile(
        basis="ESTIMATED",
        model_download_mb=None,
        peak_ram_mb=45.0,
        latency="3-10 s including upload (ESTIMATED)",
        money=(
            "VENDOR-STATED: mode=test is free and returns a watermarked result, mode=production "
            "spends a credit. This package defaults to test so a mis-wired experiment cannot run "
            "up a bill. Re-check vectorizer.ai/pricing before switching to production."
        ),
        notes=(
            "RAM is ESTIMATED on the same basis as remove.bg. Worth the money only where VTracer's "
            "output is visibly worse - compare the two on one real motif before paying."
        ),
    ),
    summary="Hosted tracing, generally cleaner curves than VTracer on photographic input.",
)

ALL_PROVIDERS: tuple[ProviderDescriptor, ...] = (
    REMBG_LOCAL,
    REMOVE_BG,
    VTRACER_LOCAL,
    VECTORIZER_AI,
)

#: Order in which ``auto`` tries providers. Matting prefers the hosted service because the box
#: this runs on has 1 GiB of RAM and local U2Net does not fit beside the API; vectorisation
#: prefers local because VTracer is a small Rust extension with no model and no per-image charge.
#: Both orders can be overridden per capability with the AI_*_PROVIDER variables.
_PREFERENCE: dict[Capability, tuple[ProviderDescriptor, ...]] = {
    Capability.BACKGROUND_REMOVAL: (REMOVE_BG, REMBG_LOCAL),
    Capability.FOREGROUND_SEPARATION: (REMOVE_BG, REMBG_LOCAL),
    Capability.VECTORISATION: (VTRACER_LOCAL, VECTORIZER_AI),
}


def get_descriptor(provider_id: str) -> ProviderDescriptor:
    """Look up a provider by id, or explain which ids exist."""
    for descriptor in ALL_PROVIDERS:
        if descriptor.id == provider_id:
            return descriptor
    known = ", ".join(item.id for item in ALL_PROVIDERS)
    raise UnknownProvider(
        f"no AI image provider called {provider_id!r}",
        provider=provider_id,
        remediation=f"Use one of: {known}, or 'auto'.",
    )


def providers_for(capability: Capability) -> tuple[ProviderDescriptor, ...]:
    """Providers that implement a capability, in the order ``auto`` would try them."""
    return _PREFERENCE[capability]


def module_available(name: str) -> bool:
    """Is an importable module of this name on the path? Asked without importing it.

    ``find_spec`` on a top-level name runs the path finders only. Dotted names would import the
    parent package, which is exactly the cost we are avoiding, so every entry in a descriptor's
    ``required_modules`` is deliberately top-level.
    """
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError, AttributeError):
        # A half-installed distribution can leave a spec that raises rather than resolves. That is
        # an unavailable dependency for our purposes, not a reason to fail a probe.
        return False


def readiness(
    descriptor: ProviderDescriptor,
    capability: Capability,
    settings: AiFeatureSettings,
) -> ProviderReadiness:
    """Can this provider serve this capability right now? Data only — nothing is imported or called.

    "Ready" means the code is installed and the credentials are present. It cannot mean the
    service is reachable or the key is valid; finding that out costs a network round trip and
    belongs in the call itself, not in a probe an operator runs to ask why a feature is off.
    """
    if capability not in descriptor.capabilities:
        return ProviderReadiness(
            provider=descriptor.id,
            capability=capability,
            ready=False,
            reason=f"{descriptor.id} does not implement {capability}",
        )
    missing_modules = tuple(
        name for name in descriptor.modules_for(capability) if not module_available(name)
    )
    missing_settings = tuple(
        name for name in descriptor.required_settings if not (settings.value_for(name) or "")
    )
    if missing_modules or missing_settings:
        parts = []
        if missing_modules:
            parts.append("missing packages: " + ", ".join(missing_modules))
        if missing_settings:
            parts.append("unset settings: " + ", ".join(missing_settings))
        return ProviderReadiness(
            provider=descriptor.id,
            capability=capability,
            ready=False,
            missing_modules=missing_modules,
            missing_settings=missing_settings,
            reason="; ".join(parts),
        )
    return ProviderReadiness(
        provider=descriptor.id, capability=capability, ready=True, reason="ready"
    )


def _explain_all(readinesses: tuple[ProviderReadiness, ...]) -> str:
    return " | ".join(f"{item.provider}: {item.reason}" for item in readinesses)


def resolve(
    capability: Capability,
    settings: AiFeatureSettings,
    *,
    requested: str | None = None,
) -> ProviderDescriptor:
    """Pick the provider for one call, or raise an error that says exactly what is missing.

    An explicit provider id is never silently substituted. Asking for the local matte and getting
    a remove.bg charge instead — or asking for the hosted one and quietly loading 700 MB of ONNX
    — would both be worse than an error naming the one thing that has to be installed or set.
    """
    choice = (requested or settings.provider_choice(capability) or "auto").strip().lower()

    if choice != "auto":
        descriptor = get_descriptor(choice)
        if capability not in descriptor.capabilities:
            raise UnknownProvider(
                f"{descriptor.id} does not implement {capability}",
                capability=capability,
                provider=descriptor.id,
                remediation=(
                    "Choose one of: "
                    + ", ".join(item.id for item in providers_for(capability))
                    + ", or 'auto'."
                ),
            )
        state = readiness(descriptor, capability, settings)
        if state.ready:
            return descriptor
        if state.missing_modules:
            raise DependencyMissing(
                f"{descriptor.id} is selected for {capability} but {state.reason}",
                modules=state.missing_modules,
                capability=capability,
                provider=descriptor.id,
                remediation=(
                    "Install the optional extra: pip install -e '.[ai]' (or "
                    "'.[ai-local]' for the local providers)."
                ),
            )
        raise ProviderNotConfigured(
            f"{descriptor.id} is selected for {capability} but {state.reason}",
            missing=state.missing_settings,
            capability=capability,
            provider=descriptor.id,
            remediation="Set " + ", ".join(state.missing_settings) + " in the backend environment.",
        )

    candidates = providers_for(capability)
    states = tuple(readiness(item, capability, settings) for item in candidates)
    for descriptor, state in zip(candidates, states, strict=True):
        if state.ready:
            return descriptor

    only_settings_missing = all(not state.missing_modules for state in states)
    detail = f"no provider for {capability} is usable - {_explain_all(states)}"
    if only_settings_missing:
        raise ProviderNotConfigured(
            detail,
            missing=tuple(dict.fromkeys(sum((s.missing_settings for s in states), ()))),
            capability=capability,
            remediation=(
                "Set the credentials for one of these providers, or point "
                f"{provider_var(capability)} at the one you want and install it."
            ),
        )
    raise DependencyMissing(
        detail,
        modules=tuple(dict.fromkeys(sum((s.missing_modules for s in states), ()))),
        capability=capability,
        remediation=(
            "Install the optional extra for the provider you want: pip install -e "
            "'.[ai]' for the hosted ones, '.[ai-local]' for local inference."
        ),
    )


def load_provider(descriptor: ProviderDescriptor, settings: AiFeatureSettings) -> Any:
    """Import a provider module and construct it. The first moment any of this code is loaded.

    An ImportError here is a dependency problem however it happened — a partial install, a wheel
    built for another Python — so it is reported as one rather than escaping as a traceback the
    caller has no way to act on.
    """
    module_path, _, class_name = descriptor.implementation.partition(":")
    try:
        module = importlib.import_module(module_path)
        factory = getattr(module, class_name)
    except ImportError as exc:
        raise DependencyMissing(
            f"{descriptor.id} could not be loaded: {exc}",
            modules=descriptor.required_modules,
            provider=descriptor.id,
            remediation="Install the optional extra, then restart the process.",
        ) from exc
    except AttributeError as exc:  # a rename inside this package, not an operator's problem
        raise UnknownProvider(
            f"{descriptor.implementation} does not exist",
            provider=descriptor.id,
        ) from exc
    return factory(settings)
