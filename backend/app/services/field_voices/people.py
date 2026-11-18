from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any

from app.services import lgd, records
from app.services.field_voices import contract, scrub

__all__ = [
    "ARTISAN",
    "DESIGNER",
    "RESEARCHER",
    "ArtisanFacts",
    "Directory",
    "UserFacts",
    "area",
    "load_directory",
    "resolve",
]

ARTISAN = "ARTISAN"
DESIGNER = "DESIGNER"
RESEARCHER = "RESEARCHER"

ARTISAN_SQL = (
    'SELECT a."id", a."name", a."localName", a."phone", a."phoneCountryCode", a."email", a."pehchanCardNumber", '
    'a."craftStartDate", a."experienceYears", a."craftId", l."state", l."district" FROM "Artisan" a '
    'LEFT JOIN "Location" l ON l."id" = a."locationId" WHERE a."id" = ANY($1::text[])'
)
LINK_SQL = (
    'SELECT "artisanId", "remoteArtisanId" FROM "CrossPortalArtisanLink" '
    'WHERE "artisanId" = ANY($1::text[]) ORDER BY "artisanId", "remoteArtisanId"'
)
USER_SQL = 'SELECT "id", "name", "email", "role"::text AS "role" FROM "User" WHERE "id" = ANY($1::text[])'


@dataclass(frozen=True)
class ArtisanFacts:
    id: str
    name: str | None
    local_name: str | None
    craft_id: str | None
    state: str | None
    district: str | None
    craft_start: Any
    experience_years: int | None
    numbers: tuple[str | None, ...] = ()
    emails: tuple[str | None, ...] = ()
    remote_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class UserFacts:
    id: str
    name: str | None
    local_name: str | None
    role: str | None
    numbers: tuple[str | None, ...] = ()
    emails: tuple[str | None, ...] = ()


@dataclass
class Directory:
    portal: str
    survivors: dict[str, str] = field(default_factory=dict)
    artisans: dict[str, ArtisanFacts] = field(default_factory=dict)
    users: dict[str, UserFacts] = field(default_factory=dict)

    def survivor(self, artisan_id: str) -> str:
        return resolve(artisan_id, self.survivors)

    def merged_into(self, survivor_id: str) -> list[str]:
        return sorted(m for m in self.survivors if resolve(m, self.survivors) == survivor_id)


def resolve(artisan_id: str, survivors: Mapping[str, str]) -> str:
    seen = {artisan_id}
    current = artisan_id
    while current in survivors:
        current = survivors[current]
        if current in seen:
            break
        seen.add(current)
    return current


def _composed(code: Any, phone: Any) -> str | None:
    digits = "".join(ch for ch in str(phone or "") if ch.isdigit())
    prefix = "".join(ch for ch in str(code or "") if ch.isdigit())
    return f"{prefix}{digits}" if digits and prefix else None


def area(state: str | None, district: str | None) -> dict[str, Any] | None:
    if not (state or "").strip():
        return None
    unit = lgd.lgd_for_unit(state, district)
    if not unit.get("state") or unit.get("stateLgd") is None:
        return None
    known = unit.get("districtLgd") is not None
    return {
        "state": unit["state"],
        "district": unit["district"] if known else None,
        "stateLgd": unit["stateLgd"],
        "districtLgd": unit["districtLgd"] if known else None,
    }


def _artisan(row: Mapping[str, Any], remote: tuple[str, ...]) -> ArtisanFacts:
    return ArtisanFacts(
        id=str(row["id"]),
        name=row.get("name"),
        local_name=row.get("localName"),
        craft_id=row.get("craftId"),
        state=row.get("state"),
        district=row.get("district"),
        craft_start=row.get("craftStartDate"),
        experience_years=row.get("experienceYears"),
        numbers=(
            row.get("phone"),
            _composed(row.get("phoneCountryCode"), row.get("phone")),
            row.get("pehchanCardNumber"),
        ),
        emails=(row.get("email"),),
        remote_ids=remote,
    )


async def load_directory(
    client: Any, adapter: Any, *, artisan_ids: Iterable[str], user_ids: Iterable[str]
) -> Directory:
    directory = Directory(portal=adapter.PORTAL, survivors=await adapter.survivors(client))
    wanted = {directory.survivor(a) for a in artisan_ids if a}
    artisans = sorted(wanted)
    if artisans:
        links: dict[str, list[str]] = {}
        for row in await client.query_raw(LINK_SQL, artisans):
            links.setdefault(str(row["artisanId"]), []).append(str(row["remoteArtisanId"]))
        for row in await client.query_raw(ARTISAN_SQL, artisans):
            remote = tuple(dict.fromkeys(links.get(str(row["id"]), [])))
            directory.artisans[str(row["id"])] = _artisan(row, remote)
    users = sorted({u for u in user_ids if u})
    if users:
        profiles = await adapter.designer_profiles(client, users)
        for row in await client.query_raw(USER_SQL, users):
            profile = profiles.get(str(row["id"])) or {}
            directory.users[str(row["id"])] = UserFacts(
                id=str(row["id"]),
                name=(profile.get("displayName") or "").strip() or row.get("name"),
                local_name=profile.get("localName"),
                role=row.get("role"),
                numbers=tuple(profile.get("numbers") or ()),
                emails=(row.get("email"), *(profile.get("emails") or ())),
            )
    return directory


def years_in_craft(facts: ArtisanFacts, on: date | None) -> int | None:
    moment = datetime(on.year, on.month, on.day, tzinfo=UTC) if on else None
    derived = records.derive_experience_years(facts.craft_start, on=moment)
    if derived is not None:
        return derived
    years = facts.experience_years
    return years if isinstance(years, int) and 0 <= years <= 90 else None


def held_terms(directory: Directory, artisan_ids: Iterable[str], user_ids: Iterable[str]) -> dict[str, list[Any]]:
    numbers: list[Any] = []
    emails: list[Any] = []
    for artisan_id in artisan_ids:
        facts = directory.artisans.get(artisan_id)
        if facts is None:
            continue
        numbers += list(facts.numbers)
        emails += list(facts.emails)
    for user_id in user_ids:
        facts_user = directory.users.get(user_id)
        if facts_user is None:
            continue
        numbers += list(facts_user.numbers)
        emails += list(facts_user.emails)
    return {"numbers": numbers, "emails": emails}


def artisan_person(
    directory: Directory, artisan_id: str, *, on: date | None, labels: scrub.Labels, path: str,
    craft: dict[str, Any] | None = None,
) -> dict[str, Any]:
    facts = directory.artisans[artisan_id]
    other = contract.other_portal(directory.portal)
    return {
        "id": facts.id,
        "kind": ARTISAN,
        "craft": craft,
        "name": labels.screen(f"{path}.name", facts.name),
        "localName": labels.screen(f"{path}.localName", facts.local_name),
        "yearsInCraft": years_in_craft(facts, on),
        "area": area(facts.state, facts.district),
        "samePersonIn": [{"portal": other, "id": remote} for remote in facts.remote_ids],
        "mergedIds": directory.merged_into(facts.id),
    }


def user_person(
    directory: Directory, user_id: str, *, kind: str, labels: scrub.Labels, path: str
) -> dict[str, Any]:
    facts = directory.users[user_id]
    return {
        "id": facts.id,
        "kind": kind,
        "craft": None,
        "name": labels.screen(f"{path}.name", facts.name),
        "localName": labels.screen(f"{path}.localName", facts.local_name) if kind == DESIGNER else None,
        "yearsInCraft": None,
        "area": None,
        "samePersonIn": [],
        "mergedIds": [],
    }


def user_kind(directory: Directory, user_id: str, designers: Iterable[str]) -> str:
    facts = directory.users.get(user_id)
    if directory.portal == contract.DESIGNER and (
        user_id in set(designers) or (facts is not None and facts.role == DESIGNER)
    ):
        return DESIGNER
    return RESEARCHER
