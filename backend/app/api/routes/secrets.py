"""Master-admin endpoints for the runtime-editable provider keys.

Every route here is behind ``require_master_admin`` — not merely ``require_admin``. Ordinary admins
manage people and records; handing out live OpenAI/Deepgram credentials (the reveal endpoint returns
plaintext) is a different class of power and stays with the single master admin account.

The ``{key}`` path parameter is always validated against the registry in
:mod:`app.services.managed_secrets` before anything touches the database, so this API can never be
used to write an arbitrary row — a key the code never reads is at best dead weight and at worst a
place to smuggle data into the secrets table.
"""

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.deps import require_master_admin
from app.schemas.secrets import ManagedSecretDto, ManagedSecretRevealDto, ManagedSecretSetIn
from app.services import managed_secrets

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/secrets", tags=["secrets"])


def _require_managed(key: str) -> str:
    """Reject anything outside the registry. 404 rather than 422: from the caller's point of view
    there is simply no such secret to act on."""
    if not managed_secrets.is_managed(key):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Unknown secret key {key!r}. Managed keys: {', '.join(managed_secrets.MANAGED_KEYS)}",
        )
    return key


@router.get("", response_model=list[ManagedSecretDto])
async def list_secrets(_: Any = Depends(require_master_admin)) -> list[dict[str, Any]]:
    """Every manageable key with where its value comes from and how the last test went.

    Cheap by design: no provider is contacted here (POST /secrets/{key}/test does that on demand), so
    opening the Settings hub costs one query no matter how many providers are configured.
    """
    return await managed_secrets.list_managed_secrets()


@router.get("/{key}/reveal", response_model=ManagedSecretRevealDto)
async def reveal_secret(
    key: str,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    """The plaintext of one key, for the eye button.

    The read is audit-logged (who + which key, never the value) because this is the one place a
    credential leaves the server in readable form, and "who looked at the OpenAI key last March"
    needs an answer that is not "nobody knows".

    WARNING, not INFO, on purpose: the API process has no application-wide logging config, so under
    uvicorn an ``app.*`` logger has no handler and INFO records are swallowed entirely — only
    WARNING and above reach stderr (and therefore journald on the deployed box). An audit line that
    is silently discarded is worse than none, because it looks like coverage that isn't there.
    """
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
    """Set or rotate a key. Takes effect on the next provider call — no restart, no redeploy.

    Whitespace is stripped before storing because keys are pasted from dashboards and emails, and a
    trailing newline in an ``Authorization`` header is a 401 that looks exactly like a bad key.
    """
    _require_managed(key)
    value = payload.value.strip()
    if not value:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="value must not be blank. Use DELETE to fall back to the environment value.",
        )
    return await managed_secrets.set_secret(key, value, current_user.id)


@router.delete("/{key}", response_model=ManagedSecretDto)
async def clear_secret(
    key: str,
    _: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    """Drop the stored override so the deployed environment value applies again. Idempotent: removing
    an override that was never stored is a no-op that still returns the key's current state."""
    _require_managed(key)
    return await managed_secrets.delete_secret(key)


@router.post("/{key}/test", response_model=ManagedSecretDto)
async def test_secret(
    key: str,
    _: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    """Call the provider once with the key in force and persist the verdict.

    Answers "is this key actually alive?" without uploading a recording and waiting for the queue.
    The verdict is stored on the row so the list view keeps showing it; a key that resolves from the
    environment has no row, so its verdict is returned for this response only.
    """
    _require_managed(key)
    return await managed_secrets.test_secret(key)
