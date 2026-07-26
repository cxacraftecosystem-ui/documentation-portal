"""Reference data the clients render their forms from, rather than hard-coding their own copies.

A list that lives in three codebases is three lists, and they drift — the web gains a union territory
the Android build does not have, and a researcher on the phone cannot enter the address they are
standing in. Serving the list the validators themselves check against removes that failure mode
entirely: whatever a form offers is, by construction, exactly what the API accepts.

Signed-in but otherwise ungated. Nothing here is per-user or sensitive; the auth dependency is there
only so this route matches every other one and no new unauthenticated surface appears.
"""

from typing import Any

from fastapi import APIRouter, Depends

from app.core.deps import get_current_user
from app.services.address import address_reference

router = APIRouter(prefix="/reference", tags=["reference"])


@router.get("/address")
async def get_address_reference(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The canonical Indian state / union-territory list plus the pincode rule.

    One call rather than two, because a form needs both at the same moment. The payload is a pure
    constant — no database read — so a client is free to cache it against its ``version``.
    """
    return address_reference()
