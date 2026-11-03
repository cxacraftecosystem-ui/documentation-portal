import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.deps import require_master_admin
from app.schemas.secrets import ManagedSecretDto, ManagedSecretRevealDto, ManagedSecretSetIn
from app.services import managed_secrets

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/secrets", tags=["secrets"])


def _require_managed(key: str) -> str:
    if not managed_secrets.is_managed(key):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Unknown secret key {key!r}. Managed keys: {', '.join(managed_secrets.MANAGED_KEYS)}",
        )
    return key


@router.get("", response_model=list[ManagedSecretDto])
async def list_secrets(_: Any = Depends(require_master_admin)) -> list[dict[str, Any]]:
    return await managed_secrets.list_managed_secrets()


@router.get("/{key}/reveal", response_model=ManagedSecretRevealDto)
async def reveal_secret(
    key: str,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    logger.warning(
        "AUDIT managed-secret reveal: key=%s by=%s (%s)", key, current_user.email, current_user.id
    )
    value = await managed_secrets.get_secret(key)
    described = await managed_secrets.describe_secret(key)
    return {"key": key, "value": value, "source": described["source"]}


@router.put("/{key}", response_model=ManagedSecretDto)
async def set_secret(
    key: str,
    payload: ManagedSecretSetIn,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    value = payload.value.strip()
    if not value:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Enter a value, or use Clear override to go back to the default.",
        )
    return await managed_secrets.set_secret(key, value, current_user.id)


@router.delete("/{key}", response_model=ManagedSecretDto)
async def clear_secret(
    key: str,
    _: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    return await managed_secrets.delete_secret(key)


@router.post("/{key}/test", response_model=ManagedSecretDto)
async def test_secret(
    key: str,
    _: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    return await managed_secrets.test_secret(key)
