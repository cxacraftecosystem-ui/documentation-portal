"""A designer's OWN provider keys, and the catalogue the settings screen is built from.

THE PERMISSION MODEL, WHICH IS THE OPPOSITE OF ``routes/secrets.py``
--------------------------------------------------------------------
Every route in ``routes/secrets`` is behind ``require_master_admin``, because those keys belong to
the deployment and one of them can be revealed in plaintext. Every route HERE is behind an ordinary
signed-in dependency and acts **only on the caller's own rows** — the user id comes from the token
and is never a path or body parameter, so there is no shape of request that reads or writes somebody
else's credential. An administrator has no route into these at all, deliberately: a personal key is
billed to a real person's own card, and an admin who could read one could spend it.

**AND THERE IS NO REVEAL ENDPOINT.** The Settings hub has one for the deployment's keys because a
master admin sometimes has to compare a stored key against a provider dashboard. Nobody has that
need for somebody else's personal key, and the owner can always paste a new one — so the plaintext
of these rows leaves the server in exactly one direction, into a provider call made on the owner's
behalf.

WHY THE CATALOGUE IS AN ENDPOINT RATHER THAN A CONSTANT IN EACH CLIENT
----------------------------------------------------------------------
``GET /ai/providers`` returns the provider list, the models, the tasks each model can do, and the
how-to-get-a-key steps. Three clients need it — the web settings page, the Android settings screen,
and the field-repository build — and a model list copied into three clients is a model list that
disagrees with itself the first time a provider retires something. It carries no secrets and is safe
for any signed-in caller.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.deps import get_current_user
from app.services import ai_providers, user_ai_keys
from app.services.ai_providers import AiProvider

logger = logging.getLogger(__name__)

router = APIRouter(tags=["ai-keys"])


def _provider_or_404(provider: str) -> AiProvider:
    """Reject anything outside the catalogue. 404, because from the caller's point of view there is
    simply no such provider to act on — and because accepting an arbitrary string here would let a
    client write rows this backend can never resolve."""
    spec = ai_providers.spec_for(provider)
    if spec is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                f"Unknown provider {provider!r}. This app supports: "
                + ", ".join(p.value for p in ai_providers.PROVIDERS)
            ),
        )
    return spec.provider


def _model_payload(model: ai_providers.AiModel) -> dict[str, Any]:
    return {
        "id": model.id,
        "label": model.label,
        "note": model.note,
        # Sorted so the wire order is stable across processes — an unordered set would reshuffle
        # between workers and make a client's diffing think the catalogue had changed.
        "tasks": sorted(task.value for task in model.tasks),
        "inputPricePerMTok": model.price_per_mtok[0] if model.price_per_mtok else None,
        "outputPricePerMTok": model.price_per_mtok[1] if model.price_per_mtok else None,
    }


@router.get("/ai/providers")
async def list_providers(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The catalogue: providers, models, what each model can do, and how to get a key.

    ``pricesCheckedOn`` travels with the prices and is not decoration — every surface that prints a
    figure prints that date beside it, because these go stale and a stale price shown as current is
    a small lie told to somebody deciding how to spend their own money.
    """
    return {
        "pricesCheckedOn": ai_providers.PRICES_CHECKED_ON,
        "tasks": [task.value for task in ai_providers.AiTask],
        "providers": [
            {
                "provider": spec.provider.value,
                "label": spec.label,
                "keyPrefix": spec.key_prefix,
                "consoleUrl": spec.console_url,
                "pricingUrl": spec.pricing_url,
                "howTo": list(spec.how_to),
                "defaultModel": spec.default_model,
                "models": [_model_payload(model) for model in spec.models],
            }
            for spec in ai_providers.PROVIDERS.values()
        ],
    }


@router.get("/me/ai-keys")
async def my_keys(user: Any = Depends(get_current_user)) -> list[dict[str, Any]]:
    """Every provider, with this person's key where they have set one. Never any plaintext."""
    return await user_ai_keys.list_for_user(user.id)


@router.put("/me/ai-keys/{provider}")
async def set_my_key(
    provider: str, payload: dict[str, Any], user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Save or rotate this person's key for one provider, and optionally choose a model.

    ``key`` may be omitted to change ONLY the model, which is why this is not two endpoints: a
    designer switching from Sonnet to Opus should not have to find their key again, and a UI that
    made them would train them to keep the key somewhere convenient and less safe.
    """
    target = _provider_or_404(provider)
    raw_key = str(payload.get("key") or "").strip()
    model = payload.get("model")
    model = str(model).strip() if model else None

    if model is not None and ai_providers.spec_for(target).model(model) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{model!r} is not a model this app offers for {target.value}. Choose one from the "
                f"list, or leave it unset to use the provider's default."
            ),
        )

    if not raw_key:
        updated = await user_ai_keys.set_model(user.id, target, model)
        if updated is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=(
                    "There is no key saved for this provider yet, so there is no model to change. "
                    "Paste a key first."
                ),
            )
        return updated

    # THE SHAPE CHECK IS A COURTESY, NOT VALIDATION — the Test button is validation. It catches the
    # common paste error (the wrong provider's key in the wrong box, or a whole curl command) at the
    # moment it happens rather than as an authentication failure days later in a courtyard.
    if not ai_providers.looks_like_key(target, raw_key):
        prefix = ai_providers.spec_for(target).key_prefix
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"That does not look like a {ai_providers.spec_for(target).label} key — they begin "
                f"with “{prefix}”. Check you have pasted the right provider's key."
            ),
        )
    return await user_ai_keys.set_key(user.id, target, raw_key, model)


@router.delete("/me/ai-keys/{provider}")
async def delete_my_key(provider: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Remove this person's key. Work then runs on the server's key again, as it did before."""
    return await user_ai_keys.delete_key(user.id, _provider_or_404(provider))


@router.post("/me/ai-keys/{provider}/test")
async def test_my_key(provider: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Ask the provider whether this key works, now, and remember the answer."""
    target = _provider_or_404(provider)
    result = await user_ai_keys.test_key(user.id, target)
    if result is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="There is no key saved for this provider yet, so there is nothing to test.",
        )
    return result
