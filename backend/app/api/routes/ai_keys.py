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
        "tasks": sorted(task.value for task in model.tasks),
        "inputPricePerMTok": model.price_per_mtok[0] if model.price_per_mtok else None,
        "outputPricePerMTok": model.price_per_mtok[1] if model.price_per_mtok else None,
    }


@router.get("/ai/providers")
async def list_providers(_: Any = Depends(get_current_user)) -> dict[str, Any]:
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
    return await user_ai_keys.list_for_user(user.id)


@router.put("/me/ai-keys/{provider}")
async def set_my_key(
    provider: str, payload: dict[str, Any], user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    target = _provider_or_404(provider)
    raw_key = str(payload.get("key") or "").strip()
    model = payload.get("model")
    model = str(model).strip() if model else None

    if model is not None and ai_providers.spec_for(target).model(model) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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

    if not ai_providers.looks_like_key(target, raw_key):
        prefix = ai_providers.spec_for(target).key_prefix
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"That does not look like a {ai_providers.spec_for(target).label} key — they begin "
                f"with “{prefix}”. Check you have pasted the right provider's key."
            ),
        )
    return await user_ai_keys.set_key(user.id, target, raw_key, model)


@router.delete("/me/ai-keys/{provider}")
async def delete_my_key(provider: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await user_ai_keys.delete_key(user.id, _provider_or_404(provider))


@router.post("/me/ai-keys/{provider}/test")
async def test_my_key(provider: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    target = _provider_or_404(provider)
    result = await user_ai_keys.test_key(user.id, target)
    if result is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="There is no key saved for this provider yet, so there is nothing to test.",
        )
    return result
