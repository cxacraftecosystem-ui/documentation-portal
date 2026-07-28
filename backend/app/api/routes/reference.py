"""Reference data the clients render their forms from, rather than hard-coding their own copies.

A list that lives in three codebases is three lists, and they drift — the web gains a union territory
the Android build does not have, and a researcher on the phone cannot enter the address they are
standing in. Serving the list the validators themselves check against removes that failure mode
entirely: whatever a form offers is, by construction, exactly what the API accepts.

That argument is what makes the district list belong here rather than in each client. It is 795 names
that change several times a year, and it is only correct PER STATE — so a client that hard-coded it
would be wrong about which districts exist and wrong about which state they belong to, and would be
wrong differently on each platform. It ships with the state list, and with the date and source it was
compiled from, so an exported dataset can record which vintage it was coded against.

Signed-in but otherwise ungated. Nothing here is per-user or sensitive; the auth dependency is there
only so this route matches every other one and no new unauthenticated surface appears.
"""

from typing import Any

from fastapi import APIRouter, Depends

from app.core.deps import get_current_user
from app.services.address import address_reference
from app.services.district_lineage import lineage_reference

router = APIRouter(prefix="/reference", tags=["reference"])


@router.get("/address")
async def get_address_reference(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The canonical state / union-territory list, the districts of each, and the pincode rule.

    One call rather than three, because a form needs all of them at the same moment — and because a
    district dropdown that has to fetch its options after the state is chosen stalls visibly on a
    field connection and can briefly disagree with the list the server validates against.

    11.7 KB of JSON, 5.1 KB gzipped, of which the 795 districts are the large part; see
    ``address_reference`` for why that shape was chosen. The payload is a pure constant — no database
    read — so a client should cache it and re-fetch only when ``version`` changes.
    """
    return address_reference()


@router.get("/district-lineage")
async def get_district_lineage(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Which district a newer district was carved out of, for the 43 that have no border of their own.

    SERVED RATHER THAN DUPLICATED. Both maps need to say "shown inside Barmer, which Balotra was carved
    from", and two hand-copied tables of 43 rows is how the two apps come to disagree about the same
    43 districts. Keyed exactly as ``frontend/public/boundaries/manifest.json`` keys its ``missing``
    list, so a client can join the two directly.

    Also a pure constant, and small — cache it beside the address reference. Read
    ``services/district_lineage``'s header for why a fabricated border was not an option, and why
    three Delhi entries are reported as unverified rather than given a plausible-looking parent.
    """
    return lineage_reference()
