"""THE CATALOGUE: which providers a designer may bring a key for, which models, and for what.

WHY THIS IS A TABLE AND NOT A SETTING
-------------------------------------
A designer supplying their own key is choosing three things at once — a provider, a model, and
implicitly the set of jobs that model can do — and only the first is obvious to them. This module is
the one place that knows all three, so that every surface (the settings screen, the key test, the
verb dispatcher, the Android picker) answers from the same list rather than from four hand-kept
copies that drift.

THE CAPABILITY COLUMN IS THE POINT, AND IT IS NOT COSMETIC
----------------------------------------------------------
The six jobs a designer can point a key at are not interchangeable across providers:

* **Proofread / expand / summarise / translate** are text in, text out. Every chat model here does
  them.
* **Caption a photograph** needs vision. Every current model in all three families has it, so this
  looks redundant today and is not: the transcription-only OpenAI models below do NOT, and a
  designer who picked ``whisper-1`` for captioning would otherwise get a provider error with no
  explanation.
* **Transcribe audio** needs a model that accepts audio, and this is where the families genuinely
  differ. OpenAI has dedicated transcription models; Gemini's current models take audio directly.
  **Anthropic's do not take audio at all** — no Claude model accepts an audio input, so a Claude key
  cannot transcribe, however good the model is at everything else.

That last fact is the reason :func:`models_for` exists and the reason the API returns the task set
per model. Without it the settings screen would happily let a designer save a Claude key against
"transcribe audio", and the failure would arrive days later in a courtyard as an unexplained error
on a recording they cannot re-take. A capability a provider does not have must be refused at the
point of choosing, in words, not at the point of use.

PRICES ARE INDICATIVE AND SAY SO
--------------------------------
Each model carries an approximate per-million-token price, because a designer paying their own bill
has no other way to tell a cheap model from an expensive one, and the difference across this list is
a factor of fifty. They are a snapshot taken on the date in :data:`PRICES_CHECKED_ON` and they WILL
go stale — providers re-price, and two of the figures below are already introductory rates with an
end date. So every surface that prints one also prints that date and links to the provider's own
pricing page, and nothing in this backend ever computes a cost from these numbers. They are a hint
for a human choosing from a list, not an accounting input.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

#: When the prices and the model lists below were last checked against the providers' own pages.
#: Printed next to every price. See the module docstring on why this is not decoration.
PRICES_CHECKED_ON = "2026-08-16"


class AiProvider(str, Enum):
    """The three key families a designer may bring. ``str`` so it serialises as its own name."""

    OPENAI = "OPENAI"
    GEMINI = "GEMINI"
    ANTHROPIC = "ANTHROPIC"


class AiTask(str, Enum):
    """The six jobs a designer's own key may be pointed at.

    These are the verbs that already exist in ``app.services.ai`` — this enum does not invent
    capability, it names what is there so a model can be matched to it.
    """

    PROOFREAD = "PROOFREAD"
    EXPAND = "EXPAND"
    SUMMARISE = "SUMMARISE"
    TRANSLATE = "TRANSLATE"
    TRANSCRIBE = "TRANSCRIBE"
    CAPTION = "CAPTION"


#: The four text jobs, which every chat model in every family can do. Named once so a model row
#: cannot accidentally claim three of the four.
TEXT_TASKS = frozenset({AiTask.PROOFREAD, AiTask.EXPAND, AiTask.SUMMARISE, AiTask.TRANSLATE})

#: Text plus vision: what a current general-purpose model does. The overwhelming majority of rows.
TEXT_AND_VISION = TEXT_TASKS | {AiTask.CAPTION}


@dataclass(frozen=True)
class AiModel:
    """One model a designer can pick, and the honest list of what it can be used for."""

    id: str
    label: str
    #: One short line for the picker. Written for a designer, not an engineer: what it is FOR.
    note: str
    #: Approximate USD per million tokens, input/output, as of :data:`PRICES_CHECKED_ON`.
    #: ``None`` where the provider does not price the model per token (the audio models below).
    price_per_mtok: tuple[float, float] | None
    tasks: frozenset[AiTask]


@dataclass(frozen=True)
class ProviderSpec:
    """One provider: how to get a key, what it can do, and which models it offers."""

    provider: AiProvider
    label: str
    #: The app-level key this provider falls back to when a designer has supplied none — the same
    #: name the Settings hub manages in ``managed_secrets.MANAGED_KEYS``. Ties the two halves of the
    #: feature together in ONE place, so "my key, else the app's key" cannot resolve to a different
    #: provider than the one the designer chose.
    managed_key: str
    #: What a key from this provider looks like, so an obviously-wrong paste is caught before it is
    #: stored. Checked as a prefix only — never as a length or a character set, because providers
    #: lengthen keys without warning and a client-side rule that is stricter than the provider's is
    #: a rule that eventually refuses a valid key.
    key_prefix: str | None
    console_url: str
    pricing_url: str
    #: The accordion: how a designer gets a key, in the order they will do it. Each step is one
    #: action. No step may assume the reader has used a developer console before.
    how_to: tuple[str, ...]
    models: tuple[AiModel, ...]
    #: What a designer gets if they save a key and never touch the model picker.
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
            id="gpt-5.6-terra",
            label="GPT-5.6 Terra",
            note="The balanced choice, and the right default for everyday work.",
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
            id="gpt-5.6-luna",
            label="GPT-5.6 Luna",
            note="The cheapest by a wide margin. Good for proofreading, weaker on long translation.",
            price_per_mtok=(0.20, 1.25),
            tasks=TEXT_AND_VISION,
        ),
        # ── AUDIO ONLY. The `tasks` set on these three is the whole reason this table is not a list
        # of strings: they cannot proofread, cannot translate and cannot look at a photograph, and a
        # designer who chose one for those jobs would get an unexplained provider error.
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
    default_model="gpt-5.6-terra",
)

_GEMINI = ProviderSpec(
    provider=AiProvider.GEMINI,
    label="Google Gemini",
    managed_key="GEMINI_API_KEY",
    # Google's keys have carried an "AIza" prefix for years across every Google API.
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
            id="gemini-3.7-flash",
            label="Gemini 3.7 Flash",
            note="The newest fast model, and the right default. Handles audio and photographs too.",
            price_per_mtok=(0.75, 3.75),
            tasks=TEXT_AND_VISION | {AiTask.TRANSCRIBE},
        ),
        AiModel(
            id="gemini-3.6-flash",
            label="Gemini 3.6 Flash",
            note="The previous fast model, same price. Pick it if 3.7 behaves differently for you.",
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
    default_model="gemini-3.7-flash",
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
        "this page works, and transcription will keep using whatever this server is configured with.",
        "Paste it below and press Test.",
    ),
    models=(
        # NOTE FOR THE NEXT READER: these ids are complete as written and carry NO date suffix.
        # `claude-opus-5`, never `claude-opus-5-20260101`. A dated variant recalled from somewhere
        # else 404s.
        AiModel(
            id="claude-opus-5",
            label="Claude Opus 5",
            note="The strongest of the three and the right default. Best on long or subtle passages.",
            price_per_mtok=(5.0, 25.0),
            tasks=TEXT_AND_VISION,
        ),
        AiModel(
            id="claude-sonnet-5",
            label="Claude Sonnet 5",
            note="Nearly as good for most work, at roughly half the price.",
            price_per_mtok=(3.0, 15.0),
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
    default_model="claude-opus-5",
)


PROVIDERS: dict[AiProvider, ProviderSpec] = {
    spec.provider: spec for spec in (_OPENAI, _GEMINI, _ANTHROPIC)
}


def spec_for(provider: AiProvider | str) -> ProviderSpec | None:
    """The provider spec, or None for a value that is not one of the three."""
    try:
        key = provider if isinstance(provider, AiProvider) else AiProvider(str(provider).upper())
    except ValueError:
        return None
    return PROVIDERS.get(key)


def models_for(provider: AiProvider | str, task: AiTask | None = None) -> tuple[AiModel, ...]:
    """Every model this provider offers, narrowed to those that can actually do *task*.

    THE NARROWING IS THE SAFETY PROPERTY. Called with a task, this is what stops a settings screen
    offering ``whisper-1`` for proofreading or any Claude model for transcription — refusals made
    where the designer is choosing, rather than where they are working.
    """
    spec = spec_for(provider)
    if spec is None:
        return ()
    if task is None:
        return spec.models
    return tuple(model for model in spec.models if task in model.tasks)


def supports(provider: AiProvider | str, model_id: str, task: AiTask) -> bool:
    """Whether this exact (provider, model) pair can do *task*. False for anything unrecognised.

    Fails CLOSED on an unknown model id on purpose: a stored choice that no longer appears in the
    catalogue — a model the provider retired, or one saved by an older build — must not be treated
    as capable of everything. The caller falls back to the app-level key and says so.
    """
    spec = spec_for(provider)
    if spec is None:
        return False
    model = spec.model(model_id)
    return model is not None and task in model.tasks


def default_model_for(provider: AiProvider | str) -> str | None:
    spec = spec_for(provider)
    return spec.default_model if spec else None


def looks_like_key(provider: AiProvider | str, value: str) -> bool:
    """A cheap shape check for an obviously-wrong paste. NOT validation — the Test button is that.

    Prefix only, and a provider with no published prefix accepts anything non-empty. The failure
    this catches is the common one: pasting the wrong provider's key into the wrong box, or pasting
    a project id, an email address or a whole curl command. It must never be stricter than the
    provider itself, so length and character sets are deliberately not checked.
    """
    spec = spec_for(provider)
    candidate = (value or "").strip()
    if spec is None or not candidate:
        return False
    return spec.key_prefix is None or candidate.startswith(spec.key_prefix)
