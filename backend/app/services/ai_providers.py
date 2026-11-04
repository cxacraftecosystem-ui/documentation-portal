from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

PRICES_CHECKED_ON = "2026-08-16"


class AiProvider(str, Enum):
    OPENAI = "OPENAI"
    GEMINI = "GEMINI"
    ANTHROPIC = "ANTHROPIC"


class AiTask(str, Enum):
    PROOFREAD = "PROOFREAD"
    EXPAND = "EXPAND"
    SUMMARISE = "SUMMARISE"
    TRANSLATE = "TRANSLATE"
    TRANSCRIBE = "TRANSCRIBE"
    CAPTION = "CAPTION"


TEXT_TASKS = frozenset({AiTask.PROOFREAD, AiTask.EXPAND, AiTask.SUMMARISE, AiTask.TRANSLATE})

TEXT_AND_VISION = TEXT_TASKS | {AiTask.CAPTION}


@dataclass(frozen=True)
class AiModel:
    id: str
    label: str
    note: str
    price_per_mtok: tuple[float, float] | None
    tasks: frozenset[AiTask]


@dataclass(frozen=True)
class ProviderSpec:
    provider: AiProvider
    label: str
    managed_key: str
    key_prefix: str | None
    console_url: str
    pricing_url: str
    how_to: tuple[str, ...]
    models: tuple[AiModel, ...]
    default_model: str

    def model(self, model_id: str) -> AiModel | None:
        return next((m for m in self.models if m.id == model_id), None)


_OPENAI = ProviderSpec(
    provider=AiProvider.OPENAI,
    label="OpenAI",
    managed_key="OPENAI_API_KEY",
    key_prefix="sk-",
    console_url="https://platform.openai.com/api-keys",
    pricing_url="https://openai.com/api/pricing/",
    how_to=(
        "Go to platform.openai.com and sign in, or create an account.",
        "Open Settings → Billing and add a payment method. A new account has no free allowance for "
        "the API, so a key created before this step will be refused the first time it is used.",
        "Open the API keys page and choose “Create new secret key”. Give it a name you will "
        "recognise later, such as the name of this app.",
        "Copy the key immediately — it starts with “sk-” and is shown only once. If you lose it, "
        "delete that key and make another; there is no way to read an existing one back.",
        "Paste it below and press Test. Nothing is saved until the test tells you what happened.",
    ),
    models=(
        AiModel(
            id="gpt-6-luna",
            label="GPT-6 Luna",
            note="The default, and the cheapest by a wide margin. Good for proofreading, weaker on long translation.",
            price_per_mtok=(0.10, 0.50),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="gpt-5.6-terra",
            label="GPT-5.6 Terra",
            note="The balanced choice for everyday work, at about ten times the default's price.",
            price_per_mtok=(2.0, 12.0),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="gpt-5.6-sol",
            label="GPT-5.6 Sol",
            note="The most capable of the three. Slower and roughly two and a half times the price.",
            price_per_mtok=(5.0, 30.0),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="gpt-transcribe",
            label="GPT Transcribe",
            note="Transcription only. The most accurate of the three on accented and noisy audio.",
            price_per_mtok=None,
            tasks=frozenset({AiTask.TRANSCRIBE}),
        ),
        AiModel(
            id="gpt-4o-mini-transcribe",
            label="GPT-4o mini Transcribe",
            note="Transcription only. Cheaper and faster; a little weaker on difficult recordings.",
            price_per_mtok=None,
            tasks=frozenset({AiTask.TRANSCRIBE}),
        ),
        AiModel(
            id="whisper-1",
            label="Whisper",
            note="Transcription only. The oldest and cheapest; kept because it handles long files well.",
            price_per_mtok=None,
            tasks=frozenset({AiTask.TRANSCRIBE}),
        ),
    ),
    default_model="gpt-6-luna",
)

_GEMINI = ProviderSpec(
    provider=AiProvider.GEMINI,
    label="Google Gemini",
    managed_key="GEMINI_API_KEY",
    key_prefix="AIza",
    console_url="https://aistudio.google.com/apikey",
    pricing_url="https://ai.google.dev/gemini-api/docs/pricing",
    how_to=(
        "Go to aistudio.google.com and sign in with a Google account.",
        "Open “Get API key” in the left-hand menu, then “Create API key”.",
        "Choose a Google Cloud project when asked, or let it make one for you. The name does not "
        "matter for this app.",
        "Copy the key — it starts with “AIza”. Unlike the other two providers you can come back and "
        "read it again later.",
        "Gemini has a free allowance that is enough for occasional use. If you exceed it the key "
        "keeps working but is rate-limited until you enable billing on that Cloud project.",
        "Paste it below and press Test.",
    ),
    models=(
        AiModel(
            id="gemini-3.8-flash",
            label="Gemini 3.8 Flash",
            note="The newest fast model, and the right default. It translates every transcript here.",
            price_per_mtok=(0.75, 3.75),
            tasks=TEXT_AND_VISION | {AiTask.TRANSCRIBE},
        ),
        AiModel(
            id="gemini-3.7-flash",
            label="Gemini 3.7 Flash",
            note="The previous fast model, same price, and the transcript pipeline's fallback.",
            price_per_mtok=(0.75, 3.75),
            tasks=TEXT_AND_VISION | {AiTask.TRANSCRIBE},
        ),
        AiModel(
            id="gemini-3.6-flash",
            label="Gemini 3.6 Flash",
            note="An older fast model, same price. Pick it if 3.8 behaves differently for you.",
            price_per_mtok=(0.75, 3.75),
            tasks=TEXT_AND_VISION | {AiTask.TRANSCRIBE},
        ),
        AiModel(
            id="gemini-3.1-pro-preview",
            label="Gemini 3.1 Pro (preview)",
            note="The strongest reasoning in this family. Preview — Google may change it without notice.",
            price_per_mtok=(2.0, 12.0),
            tasks=TEXT_AND_VISION | {AiTask.TRANSCRIBE},
        ),
        AiModel(
            id="gemini-3.5-flash-lite",
            label="Gemini 3.5 Flash-Lite",
            note="Cheap and quick. Good for proofreading a paragraph, weaker on a long translation.",
            price_per_mtok=(0.30, 2.50),
            tasks=TEXT_AND_VISION | {AiTask.TRANSCRIBE},
        ),
        AiModel(
            id="gemini-2.5-flash-lite",
            label="Gemini 2.5 Flash-Lite",
            note="The cheapest here. An older generation; keep it for high volume, not for accuracy.",
            price_per_mtok=(0.10, 0.40),
            tasks=TEXT_AND_VISION | {AiTask.TRANSCRIBE},
        ),
    ),
    default_model="gemini-3.8-flash",
)

_ANTHROPIC = ProviderSpec(
    provider=AiProvider.ANTHROPIC,
    label="Anthropic Claude",
    managed_key="ANTHROPIC_API_KEY",
    key_prefix="sk-ant-",
    console_url="https://platform.claude.com/settings/keys",
    pricing_url="https://platform.claude.com/docs/en/pricing",
    how_to=(
        "Go to platform.claude.com and sign in, or create an account.",
        "Open Settings → Billing and buy credits. Anthropic bills the API from a credit balance "
        "rather than a monthly invoice, and a key with no credit behind it is refused on first use.",
        "Open Settings → API keys and choose “Create key”.",
        "Copy the key immediately — it starts with “sk-ant-” and is shown only once.",
        "Claude cannot transcribe audio: no Claude model accepts a sound file. Everything else on "
        "this page works, and transcription uses the app's standard service.",
        "Paste it below and press Test.",
    ),
    models=(
        AiModel(
            id="claude-sonnet-5-5",
            label="Claude Sonnet 5.5",
            note="The default. Fast and strong on everyday writing, translation and captions.",
            price_per_mtok=(2.0, 10.0),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="claude-opus-5",
            label="Claude Opus 5",
            note="The strongest on long or subtle passages, at two and a half times the default's price.",
            price_per_mtok=(5.0, 25.0),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="claude-sonnet-5",
            label="Claude Sonnet 5",
            note="The previous Sonnet, same price. Pick it if Sonnet 5.5 behaves differently for you.",
            price_per_mtok=(2.0, 10.0),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="claude-haiku-4-5",
            label="Claude Haiku 4.5",
            note="The quickest and cheapest. Fine for proofreading; weaker on translation.",
            price_per_mtok=(1.0, 5.0),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="claude-opus-4-8",
            label="Claude Opus 4.8",
            note="The previous Opus, same price. Pick it if Opus 5 behaves differently for you.",
            price_per_mtok=(5.0, 25.0),
            tasks=TEXT_AND_VISION,
        ),
    ),
    default_model="claude-sonnet-5-5",
)


PROVIDERS: dict[AiProvider, ProviderSpec] = {
    spec.provider: spec for spec in (_OPENAI, _GEMINI, _ANTHROPIC)
}


def spec_for(provider: AiProvider | str) -> ProviderSpec | None:
    try:
        key = provider if isinstance(provider, AiProvider) else AiProvider(str(provider).upper())
    except ValueError:
        return None
    return PROVIDERS.get(key)


def models_for(provider: AiProvider | str, task: AiTask | None = None) -> tuple[AiModel, ...]:
    spec = spec_for(provider)
    if spec is None:
        return ()
    if task is None:
        return spec.models
    return tuple(model for model in spec.models if task in model.tasks)


def supports(provider: AiProvider | str, model_id: str, task: AiTask) -> bool:
    spec = spec_for(provider)
    if spec is None:
        return False
    model = spec.model(model_id)
    return model is not None and task in model.tasks


def default_model_for(provider: AiProvider | str) -> str | None:
    spec = spec_for(provider)
    return spec.default_model if spec else None


def looks_like_key(provider: AiProvider | str, value: str) -> bool:
    spec = spec_for(provider)
    candidate = (value or "").strip()
    if spec is None or not candidate:
        return False
    return spec.key_prefix is None or candidate.startswith(spec.key_prefix)


OPENAI_FIXED_SAMPLING_PREFIXES = ("gpt-5", "gpt-6", "o1", "o3", "o4")
GEMINI_DECLINE_REASONS = frozenset({"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY"})


class ModelDeclined(RuntimeError):
    pass


def openai_sampling(model: str, temperature: float) -> dict[str, float]:
    if str(model or "").startswith(OPENAI_FIXED_SAMPLING_PREFIXES):
        return {}
    return {"temperature": temperature}


def openai_message_text(payload: dict) -> str:
    choices = payload.get("choices") or [{}]
    message = (choices[0] or {}).get("message") or {}
    content = message.get("content")
    if not content and message.get("refusal"):
        raise ModelDeclined("openai")
    return str(content or "").strip()


def gemini_reply_text(payload: dict) -> str:
    candidates = payload.get("candidates") or []
    if not candidates:
        if (payload.get("promptFeedback") or {}).get("blockReason"):
            raise ModelDeclined("gemini")
        return ""
    first = candidates[0] or {}
    parts = (first.get("content") or {}).get("parts") or []
    text = "".join(str(part.get("text", "")) for part in parts if not part.get("thought")).strip()
    if not text and first.get("finishReason") in GEMINI_DECLINE_REASONS:
        raise ModelDeclined("gemini")
    return text


GEMINI_DEFAULT_MODEL = "gemini-3.8-flash"
GEMINI_FALLBACK_MODEL = "gemini-3.7-flash"
GEMINI_GENERATE_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


def gemini_models(model: str | None) -> tuple[str, ...]:
    first = model or GEMINI_DEFAULT_MODEL
    if first == GEMINI_FALLBACK_MODEL:
        return (first,)
    return (first, GEMINI_FALLBACK_MODEL)


def post_gemini(post, model: str | None, **kwargs) -> tuple[str, object]:
    models = gemini_models(model)
    for index, candidate in enumerate(models):
        response = post(GEMINI_GENERATE_URL.format(model=candidate), **kwargs)
        if getattr(response, "status_code", None) == 404 and index + 1 < len(models):
            continue
        return candidate, response
    return candidate, response


def app_text_provider(value: str | None) -> AiProvider:
    try:
        return AiProvider(str(value or AiProvider.GEMINI.value).strip().upper())
    except ValueError:
        return AiProvider.GEMINI
