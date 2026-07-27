import logging
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.db import db
from app.core.deps import require_admin, require_master_admin
from app.schemas.settings import (
    AppSettingDto,
    AppSettingUpdate,
    TranscriptionProviderOrderDto,
    TranscriptionProviderOrderUpdate,
)
from app.services import ai, managed_secrets
from app.services.app_settings import (
    SINGLETON_ID,
    STT_KEY_ABSENT,
    STT_KEY_FAILING,
    STT_KEY_PASSING,
    STT_KEY_UNTESTED,
    VALID_TRANSCRIPTION_MODES,
    freeze_unrankable,
    get_or_create_app_settings,
    invalid_stt_provider_order,
    is_valid_hhmm,
    load_stt_provider_order,
    recalled_key_verdict,
    remember_key_verdict,
    save_stt_provider_order,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/settings", tags=["settings"])


@router.get("", response_model=AppSettingDto)
async def get_app_settings(_: Any = Depends(require_master_admin)) -> Any:
    """Current global settings (creating the singleton with defaults on first read)."""
    return await get_or_create_app_settings()


@router.put("", response_model=AppSettingDto)
async def update_app_settings(
    payload: AppSettingUpdate,
    current_user: Any = Depends(require_master_admin),
) -> Any:
    await get_or_create_app_settings(current_user.id)
    data = payload.model_dump(exclude_none=True)
    if "transcriptionMode" in data and data["transcriptionMode"] not in VALID_TRANSCRIPTION_MODES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"transcriptionMode must be one of {sorted(VALID_TRANSCRIPTION_MODES)}",
        )
    for field in ("batchWindowStart", "batchWindowEnd"):
        if field in data and not is_valid_hhmm(data[field]):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{field} must be a 24-hour HH:mm time",
            )
    if "sttProviderOrder" in data:
        problem = invalid_stt_provider_order(data["sttProviderOrder"])
        if problem:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"sttProviderOrder {problem}",
            )
        # The freeze applies here too. This is the older, broader route into the same column, and a
        # rule that one writer honours and another does not is not a rule — it is a race waiting for
        # somebody to use the wrong endpoint.
        verified = await _provider_verification()
        ranked = freeze_unrankable(data["sttProviderOrder"], _rankable_ids(verified))
        # Scalar-list columns take an explicit {"set": [...]} on update, unlike create.
        data["sttProviderOrder"] = {"set": ranked}
    data["updatedById"] = current_user.id
    return await db.appsetting.update(where={"id": SINGLETON_ID}, data=data)


# --- Transcription provider ranking (ADMIN and above) -------------------------------------------
#
# A resource of its own, at ``require_admin``, rather than opening the whole settings row up. The
# rest of that row — transcription mode, and the off-peak window that decides when the servers do
# their heavy work — stays with the master admin, but deciding WHICH engine transcribes an interview
# is an editorial call about accuracy on Hindi and regional speech, and it belongs to the admins who
# run the workshops. Narrowing the resource is what lets the ladder be honest: an admin gets exactly
# this and nothing more. PROFESSOR (rank 40) is below ADMIN (50) and is refused here, same as the
# UI hides it from them.


# managed_secrets records each probe on the row as UNKNOWN / OK / FAILED. Mirrored rather than
# imported: those are private constants of that module, and this mapping is the only thing here that
# depends on them.
_PROBE_OK = "OK"
_PROBE_FAILED = "FAILED"


def _require_provider(provider: str) -> tuple[str, str]:
    """``(display name, key name)`` for *provider*, or 404 — there is simply no such engine to act on."""
    catalog = {p: (name, key) for p, name, key in ai.transcription_provider_catalog()}
    if provider not in catalog:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Unknown transcription provider {provider!r}. Known engines: {', '.join(catalog)}",
        )
    return catalog[provider]


def _frozen_reason(state: str, key_label: str, error: str | None) -> str | None:
    """Why this engine cannot be ranked freely, written for whoever has to fix it."""
    if state == STT_KEY_ABSENT:
        return (
            f"No {key_label} API key has been added, so this engine cannot transcribe anything. "
            "A master admin adds the key on this page; once it passes a test it can be ranked."
        )
    if state == STT_KEY_UNTESTED:
        return (
            f"The {key_label} key has never been tested, so there is no evidence it works. "
            "Press Test — it asks the provider and takes a second — and this engine unlocks."
        )
    if state == STT_KEY_FAILING:
        return (
            f"The last test of the {key_label} key failed: {error or 'the provider refused it'}. "
            "Fix or replace the key and test again; until it passes, this engine stays at the bottom."
        )
    return None


async def _provider_verification() -> dict[str, dict[str, Any]]:
    """Every engine's key state and last test verdict, keyed by provider id.

    ``configured`` comes from the same ``_key`` lookup the transcription chain itself uses, so the
    panel and the pipeline cannot disagree about whether an engine will be called. The VERDICT is a
    separate question — the chain has no idea whether the key it is about to send is any good — and
    is read from wherever the last test was recorded. Both places are now durable and both arrive
    on the described entry: the ``ManagedSecret`` row for a key saved through the UI, and the
    ``SecretTestResult`` row for one supplied by the environment, the latter discarded automatically
    if the environment's key has changed since. This process's memory of a verdict is consulted only
    when neither has one, which after a successful test means the durable write failed.
    """
    # list_managed_secrets primes the override cache as a side effect, so a key saved minutes ago in
    # the API keys screen reads as configured here rather than telling the admin to fix what they
    # already fixed. One query for all of them.
    described = {entry["key"]: entry for entry in await managed_secrets.list_managed_secrets()}
    configured = ai.transcription_provider_configured()
    labels = {key: spec.label for key, spec in managed_secrets.MANAGED_KEYS.items()}

    verification: dict[str, dict[str, Any]] = {}
    for provider, name, key_name in ai.transcription_provider_catalog():
        entry = described.get(key_name)
        has_key = configured.get(provider, False)
        state, tested_at, error = STT_KEY_ABSENT, None, None
        if has_key:
            # A verdict on file outranks anything this process merely remembers, and describe()
            # only ever reports one that is about the key currently in force: set_secret resets a
            # ManagedSecret row to UNKNOWN on rotation, and a SecretTestResult whose fingerprint no
            # longer matches the environment's value is not returned at all. Either way a
            # brand-new key reads as untested, which is exactly what it is.
            last_status = entry.get("lastStatus") if entry is not None else None
            if last_status == _PROBE_OK:
                state, tested_at = STT_KEY_PASSING, entry.get("lastCheckedAt")
            elif last_status == _PROBE_FAILED:
                state, tested_at = STT_KEY_FAILING, entry.get("lastCheckedAt")
                error = entry.get("lastError")
            elif entry is not None and entry.get("source") == managed_secrets.SOURCE_DATABASE:
                state = STT_KEY_UNTESTED  # a stored override carries its own verdict or none
            else:
                recalled = recalled_key_verdict(key_name)
                if recalled is None:
                    state = STT_KEY_UNTESTED
                else:
                    state, tested_at, error = (
                        recalled["status"],
                        recalled["checkedAt"],
                        recalled["error"],
                    )
        key_label = labels.get(key_name, key_name)
        verification[provider] = {
            "id": provider,
            "name": name,
            "keyName": key_name,
            "keyLabel": key_label,
            "configured": has_key,
            "keyState": state,
            "rankable": state == STT_KEY_PASSING,
            "frozenReason": _frozen_reason(state, key_label, error),
            "testedAt": tested_at,
            "testError": error,
        }
    return verification


def _rankable_ids(verification: dict[str, dict[str, Any]]) -> set[str]:
    return {provider for provider, entry in verification.items() if entry["rankable"]}


def _provider_order_state(
    order: list[str],
    verification: dict[str, dict[str, Any]],
    *,
    normalised: bool = False,
    note: str | None = None,
) -> dict[str, Any]:
    """The ranking as the panel should draw it. Never re-sorts: what is stored is what is shown.

    A freeze applied on READ would be the same lie in a different direction — the pipeline follows
    the stored row, so quietly displaying a tidier order than the one on disk would once again have
    the screen describing something other than what happens to the next recording. Normalising is a
    thing that happens when somebody SAVES, and it is reported when it does.
    """
    return {
        "providers": [verification[provider] for provider in order],
        "effectiveChain": ai.transcription_provider_chain(order=order),
        "verifiedChain": [p for p in order if verification[p]["rankable"]],
        "normalised": normalised,
        "normalisedNote": note,
    }


def _join_names(names: list[str]) -> str:
    if len(names) <= 1:
        return names[0] if names else ""
    return f"{', '.join(names[:-1])} and {names[-1]}"


@router.get("/transcription-providers", response_model=TranscriptionProviderOrderDto)
async def get_transcription_provider_order(_: Any = Depends(require_admin)) -> dict[str, Any]:
    """The ranked speech-to-text engines with each one's key state and last test verdict."""
    verification = await _provider_verification()
    return _provider_order_state(await load_stt_provider_order(), verification)


@router.put("/transcription-providers", response_model=TranscriptionProviderOrderDto)
async def update_transcription_provider_order(
    payload: TranscriptionProviderOrderUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Re-rank the engines. Effective on the very next transcription job, with nothing restarted:
    both the API and the queue process read this row per job rather than at import.

    NORMALISED, NOT REJECTED, when the ranking puts an unproven engine above a proven one.
    The two are not equivalent and the choice matters. A malformed payload — an unknown id, a
    duplicate, a missing engine — is the caller's mistake and still earns a 422, because there is no
    correct order to infer from it. But the freeze is a constraint over state that moves on its own:
    a key can expire, or a test can flip to failing, between the admin dragging a perfectly legal
    order and pressing Save. Refusing that save would throw away their work and blame them for
    something they did not do and cannot see, on the one screen whose whole job is to explain the
    state of the providers. So the order is repaired — a stable partition that keeps every relative
    preference the admin expressed — stored, and returned with ``normalised`` set and a sentence
    saying exactly which engines moved and why. The UI disables these moves long before Save, so in
    ordinary use this path is the race, not the workflow.
    """
    problem = invalid_stt_provider_order(payload.order)
    if problem:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"order {problem}",
        )
    verification = await _provider_verification()
    ranked = freeze_unrankable(payload.order, _rankable_ids(verification))
    stored = await save_stt_provider_order(ranked, current_user.id)

    sank = [
        verification[p]["name"]
        for p in payload.order
        if not verification[p]["rankable"] and stored.index(p) > payload.order.index(p)
    ]
    note = None
    if sank:
        subject = _join_names(sank)
        note = (
            f"{subject} {'has' if len(sank) == 1 else 'have'} not passed a provider test, so "
            f"{'it was' if len(sank) == 1 else 'they were'} moved below the engines that have. "
            f"Test {'it' if len(sank) == 1 else 'them'} and the ranking is yours again."
        )
    return _provider_order_state(stored, verification, normalised=bool(sank), note=note)


@router.post("/transcription-providers/{provider}/test", response_model=TranscriptionProviderOrderDto)
async def test_transcription_provider(
    provider: str,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Call *provider* once with the key in force, record the verdict, and hand back the whole panel.

    THE POINT OF THIS BUTTON is that "a key is present" and "a key works" are different claims, and
    only the second one justifies ranking an engine first. The probe is the provider's own account/
    models endpoint — no audio, no transcription, nothing billable — so an admin may press it as
    often as they like.

    Returns the FULL order state rather than the one provider, so a passing test thaws the engine in
    the same round trip: the panel re-renders with it rankable, without a reload and without a save.
    Admins may run it although the key's value stays master-admin-only; a verdict is not a
    credential, and being unable to ask "does this work?" is what left the ranking guessing.
    """
    _, key_name = _require_provider(provider)
    described = await managed_secrets.test_secret(key_name)
    passing = described["lastStatus"] == _PROBE_OK
    # test_secret has already persisted this verdict — on the ManagedSecret row for a UI-entered
    # key, in SecretTestResult for an environment one. The in-process copy is kept only so that a
    # verdict whose durable write failed still shows on the panel this request is about to return.
    if described["source"] != managed_secrets.SOURCE_DATABASE and described["configured"]:
        remember_key_verdict(
            key_name,
            passing=passing,
            checked_at=described.get("lastCheckedAt") or datetime.now(timezone.utc),
            error=described.get("lastError"),
        )
    # WARNING, not INFO: under uvicorn an app logger has no handler and INFO is dropped entirely.
    # Who asked a provider about a key, and what it said, is worth having in the journal.
    logger.warning(
        "AUDIT stt-provider test: provider=%s key=%s verdict=%s by=%s",
        provider,
        key_name,
        "PASSING" if passing else "FAILING",
        current_user.email,
    )
    verification = await _provider_verification()
    return _provider_order_state(await load_stt_provider_order(), verification)
