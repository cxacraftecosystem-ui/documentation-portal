"""The bulk dataset API: its credential, its scoping, and its paging.

Three properties are worth more than the rest and most of this file is about them.

**The scoped token must be genuinely narrower than the admin who minted it.** ``POST
/api/datasets/token`` hands a machine a credential that outlives any browser session, so "read-only"
has to be enforced rather than documented. The containment lives in ``deps._user_from_bearer``, which
every authenticated route in the app already funnels through, and the tests below assert it from
BOTH sides: the dataset token opens the dataset API and is refused everywhere else, while an
ordinary session token keeps working exactly as it did.

**Admin rank must be read from the row, never from the token.** A dataset token can outlive its
holder's tenure, so a ``role`` claim baked into a month-old JWT must not be what authorises a
repository-wide download.

**A full-table export must not use OFFSET paging.** ``_stream_rows`` walks by keyset, and the test
here asserts the actual queries it issues — OFFSET is both quadratic at depth and unstable against
concurrent writes, and an archival snapshot that silently duplicates or drops rows is a wrong answer
that looks like a right one.

NOTHING HERE TOUCHES A DATABASE. ``db`` is replaced with stubs and the routes are driven over HTTP
with real HS256 tokens, so the credential tests exercise genuine signing and decoding.
"""

import asyncio
import csv
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import Depends, FastAPI

from app.api.routes import datasets
from app.core import deps
from app.core.security import create_access_token

# =================================================================================================
# Harness
# =================================================================================================


def _row(user_id: str, role: str = "ADMIN", **extra: Any) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id, email=f"{user_id}@example.test", name="Test", role=role, **extra
    )


class _Rows:
    """Stands in for a Prisma delegate over a fixed list of rows.

    ``find_many`` honours only what ``_stream_rows`` and the paged route actually use — the keyset
    ``{"id": {"gt": ...}}`` clause, ``take`` and ``skip`` — and records every ``where`` it was handed
    so a test can assert HOW the rows were asked for, not merely which came back.
    """

    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.wheres: list[Any] = []

    @staticmethod
    def _after(where: Any) -> str | None:
        """The keyset cursor inside a where clause, however deeply it is AND-nested."""
        if not isinstance(where, dict):
            return None
        gt = (where.get("id") or {}).get("gt") if isinstance(where.get("id"), dict) else None
        if gt:
            return str(gt)
        for clause in where.get("AND") or []:
            found = _Rows._after(clause)
            if found:
                return found
        return None

    async def find_many(
        self, where: Any = None, include: Any = None, take: int | None = None,
        skip: int = 0, order: Any = None, **_: Any
    ) -> list[Any]:
        self.wheres.append(where)
        after = self._after(where)
        rows = [r for r in self.rows if after is None or r.id > after]
        rows = rows[skip:]
        return rows[:take] if take else rows

    async def count(self, where: Any = None, **_: Any) -> int:
        return len(self.rows)

    async def find_unique(self, where: dict, **_: Any) -> Any:
        """Enough of a unique lookup for the token endpoint, which finds an admin by email."""
        field, value = next(iter(where.items()))
        return next((r for r in self.rows if getattr(r, field, None) == value), None)


def _app() -> FastAPI:
    """The dataset router, plus one ordinary route to prove what a scoped token may NOT reach."""
    application = FastAPI()
    application.include_router(datasets.router, prefix="/api")

    @application.get("/api/session-only")
    async def session_only(user: Any = Depends(deps.get_current_user)):  # pragma: no cover - HTTP
        return {"id": user.id}

    return application


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    """Drive the real dataset routes with a stubbed identity and stubbed tables."""

    class _Stack:
        def __init__(self) -> None:
            self.users: dict[str, Any] = {"admin": _row("admin", "ADMIN")}
            self.tables: dict[str, _Rows] = {}
            self.app = _app()

        def user(self, user_id: str, role: str = "ADMIN", **extra: Any) -> str:
            self.users[user_id] = _row(user_id, role, **extra)
            return user_id

        def table(self, delegate: str, rows: list[Any]) -> _Rows:
            stub = _Rows(rows)
            self.tables[delegate] = stub
            return stub

        def token(self, user_id: str, *, scope: str | None = None, role_claim: str | None = None) -> str:
            claims: dict[str, Any] = {}
            if scope:
                claims["scope"] = scope
            if role_claim:
                claims["role"] = role_claim
            return create_access_token(subject=user_id, extra_claims=claims or None)

        def request(self, method: str, path: str, *, token: str | None = None, **kw: Any):
            async def run():
                transport = httpx.ASGITransport(app=self.app)
                headers = {"Authorization": f"Bearer {token}"} if token else {}
                async with httpx.AsyncClient(
                    transport=transport, base_url="http://datasets.test"
                ) as client:
                    return await client.request(method, path, headers=headers, **kw)

            return asyncio.run(run())

        def get(self, path: str, *, token: str | None = None):
            return self.request("GET", path, token=token)

    stack = _Stack()

    async def resolve(user_id: str) -> Any:
        return stack.users.get(user_id)

    monkeypatch.setattr(deps, "resolve_user", resolve)

    # The routes read `db.<delegate>`; hand back a stub for the tables a test registered and raise
    # for anything else, so an unexpected read is a loud failure rather than a silently empty result.
    class _Tables:
        def __getattr__(self, name: str) -> Any:
            if name in stack.tables:
                return stack.tables[name]
            raise AssertionError(f"unexpected database read: db.{name}")

    monkeypatch.setattr(datasets, "db", _Tables())
    deps.clear_user_cache()
    yield stack
    deps.clear_user_cache()


# =================================================================================================
# The credential
# =================================================================================================


def test_a_dataset_token_opens_the_dataset_api(api) -> None:
    api.table("workshop", [])
    for name in datasets.DATASETS:
        api.table(datasets.DATASETS[name].delegate, [])

    response = api.get("/api/datasets", token=api.token("admin", scope=deps.DATASET_READ_SCOPE))

    assert response.status_code == 200
    assert response.json()["authenticatedAs"] == "admin@example.test"


def test_a_dataset_token_is_refused_by_every_other_route(api) -> None:
    """The whole point of the scope. A credential a build server holds must not be able to POST."""
    response = api.get("/api/session-only", token=api.token("admin", scope=deps.DATASET_READ_SCOPE))

    assert response.status_code == 403
    assert "scoped to 'dataset:read'" in response.json()["detail"]


def test_an_ordinary_session_token_still_works_everywhere(api) -> None:
    """Existing tokens carry no scope claim, so the default-deny must not touch them."""
    for name in datasets.DATASETS:
        api.table(datasets.DATASETS[name].delegate, [])
    plain = api.token("admin")

    assert api.get("/api/session-only", token=plain).status_code == 200
    assert api.get("/api/datasets", token=plain).status_code == 200


def test_an_unknown_scope_is_refused_rather_than_ignored(api) -> None:
    """Default-deny: a scope this build has never heard of must not fall through to full access."""
    response = api.get("/api/session-only", token=api.token("admin", scope="something:else"))

    assert response.status_code == 403


def test_a_non_admin_cannot_reach_the_dataset_api(api) -> None:
    api.user("prof", "PROFESSOR")

    response = api.get("/api/datasets", token=api.token("prof"))

    assert response.status_code == 403
    assert "Admin access required" in response.json()["detail"]


def test_admin_rank_comes_from_the_row_not_the_token_claim(api) -> None:
    """A month-old dataset token must not keep the authority its holder has since lost."""
    api.user("demoted", "RESEARCHER")

    response = api.get(
        "/api/datasets",
        token=api.token("demoted", scope=deps.DATASET_READ_SCOPE, role_claim="MASTER_ADMIN"),
    )

    assert response.status_code == 403


def test_a_missing_token_is_401_not_403(api) -> None:
    assert api.get("/api/datasets").status_code == 401


# =================================================================================================
# Minting a token from admin credentials
# =================================================================================================


def _hashed(password: str) -> str:
    from app.core.security import hash_password

    return hash_password(password)


def _account(user_id: str, role: str, password: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.com",
        name="Test",
        role=role,
        passwordHash=_hashed(password),
    )


def test_admin_credentials_mint_a_scoped_token_that_works(api) -> None:
    """The end-to-end claim of this API: email + password in, a working read-only credential out."""
    api.table("user", [_account("boss", "ADMIN", "correct-horse-battery")])
    for name in datasets.DATASETS:
        api.table(datasets.DATASETS[name].delegate, [])
    api.users["boss"] = _row("boss", "ADMIN")

    minted = api.request(
        "POST",
        "/api/datasets/token",
        json={"email": "boss@example.com", "password": "correct-horse-battery"},
    )

    assert minted.status_code == 200
    body = minted.json()
    assert body["scope"] == deps.DATASET_READ_SCOPE
    assert body["expiresInMinutes"] > 0
    assert body["account"]["email"] == "boss@example.com"
    # And the thing it handed over actually opens the API it was minted for.
    assert api.get("/api/datasets", token=body["accessToken"]).status_code == 200


def test_a_non_admin_is_refused_at_issue_time_not_at_first_use(api) -> None:
    """The operator wiring up a cron job finds out while they are still looking at the terminal."""
    api.table("user", [_account("prof", "PROFESSOR", "correct-horse-battery")])

    response = api.request(
        "POST",
        "/api/datasets/token",
        json={"email": "prof@example.com", "password": "correct-horse-battery"},
    )

    assert response.status_code == 403
    assert "Admin access required" in response.json()["detail"]


def test_a_wrong_password_and_an_unknown_account_are_indistinguishable(api) -> None:
    """Telling them apart turns this endpoint into an oracle for which admin addresses exist."""
    api.table("user", [_account("boss", "ADMIN", "correct-horse-battery")])

    wrong = api.request(
        "POST", "/api/datasets/token",
        json={"email": "boss@example.com", "password": "not-the-password"},
    )
    unknown = api.request(
        "POST", "/api/datasets/token",
        json={"email": "ghost@example.com", "password": "not-the-password"},
    )

    assert wrong.status_code == unknown.status_code == 401
    assert wrong.json()["detail"] == unknown.json()["detail"]


def test_extra_claims_cannot_overwrite_the_subject_or_the_expiry(api) -> None:
    """Defence in depth on the minting helper itself. Nothing forwards client-influenced claims
    today; the guard is what keeps that from becoming a token-forgery primitive if something ever
    does — `decode_access_token` requires `sub` and `exp` to be PRESENT, not to be ours."""
    for claim in ("sub", "iat", "exp"):
        with pytest.raises(ValueError, match="reserved"):
            create_access_token(subject="u1", extra_claims={claim: "hijacked"})

    # `scope` stays writable — POST /datasets/token sets it, and it can only ever narrow reach.
    assert create_access_token(subject="u1", extra_claims={"scope": deps.DATASET_READ_SCOPE})


def test_a_google_id_token_is_refused_with_a_reason(api) -> None:
    """Google sign-in needs a browser; the credential this endpoint issues is for a process."""
    response = api.request("POST", "/api/datasets/token", json={"googleIdToken": "whatever"})

    assert response.status_code == 422
    assert "email and password" in response.json()["detail"]


# =================================================================================================
# Route resolution — the literals must survive the path parameter
# =================================================================================================


def test_the_token_route_is_not_swallowed_as_a_dataset_name(api) -> None:
    """`/{dataset_name}` would happily match "token" if it were declared first.

    example.com, not example.test: ``LoginRequest.email`` is an ``EmailStr`` and email_validator
    rejects the reserved ``.test`` TLD, which would 422 on the body before the credential check and
    make this assert about the wrong thing.
    """
    api.table("user", [])

    response = api.request(
        "POST",
        "/api/datasets/token",
        json={"email": "nobody@example.com", "password": "wrong-password"},
    )

    # 401 from the credential check proves the POST handler ran. A 405 would mean the path resolved
    # to the GET-only `/{dataset_name}` route instead, which is the regression this guards.
    assert response.status_code == 401


def test_the_suffixed_routes_are_not_read_as_dataset_names(api) -> None:
    """`[^/]+` matches a dot, so a bare `/{dataset_name}` would answer for "crafts.ndjson"."""
    api.table("craft", [])
    token = api.token("admin")

    ndjson = api.get("/api/datasets/crafts.ndjson", token=token)

    assert ndjson.status_code == 200
    assert ndjson.headers["content-type"].startswith("application/x-ndjson")


def test_an_unknown_dataset_is_a_404_that_lists_the_real_ones(api) -> None:
    response = api.get("/api/datasets/artisan", token=api.token("admin"))

    assert response.status_code == 404
    assert "artisans" in response.json()["detail"]


# =================================================================================================
# Row selection
# =================================================================================================


def _where(name: str, **kw: Any) -> dict[str, Any]:
    defaults: dict[str, Any] = {
        "workshop_ids": None,
        "created_by": None,
        "updated_since": None,
        "created_since": None,
    }
    defaults.update(kw)
    return datasets._build_where(datasets.DATASETS[name], **defaults)


def test_no_filters_means_no_predicate(api) -> None:
    """An empty where, not a vacuous AND — the export is the whole table by default."""
    assert _where("crafts") == {}


def test_every_filter_composes_under_and(api) -> None:
    """Never as sibling top-level keys: the artisan workshop clause IS an OR, and a second bare OR
    would replace it — widening the scope while looking like it narrowed."""
    where = _where("products", workshop_ids=["w1"], created_by="u1")

    assert set(where) == {"AND"}
    assert {"workshopId": {"in": ["w1"]}} in where["AND"]
    assert {"createdById": "u1"} in where["AND"]


def test_the_workshops_table_filters_on_its_own_primary_key(api) -> None:
    """A workshop row IS the workshop; `Workshop.workshopId` is a column that does not exist."""
    where = _where("workshops", workshop_ids=["w1"])

    assert where["AND"] == [{"id": {"in": ["w1"]}}]


def test_artisans_use_the_shared_three_way_reachability_clause(api) -> None:
    """An artisan reaches a workshop by column, by roster, and by having sat in its interviews."""
    where = _where("artisans", workshop_ids=["w1"])

    branches = where["AND"][0]["OR"]
    assert {"workshopId": {"in": ["w1"]}} in branches
    assert {"workshops": {"some": {"workshopId": {"in": ["w1"]}}}} in branches
    assert any("questionnaireInterviews" in b for b in branches)


def test_crafts_reach_a_workshop_by_column_or_by_the_join_table(api) -> None:
    """Every craft on the live repository predates the workshopId column and is linked only by the
    WorkshopCraft join, so a column-only predicate returns nothing over a full corpus."""
    where = _where("crafts", workshop_ids=["w1"])

    branches = where["AND"][0]["OR"]
    assert {"workshopId": {"in": ["w1"]}} in branches
    assert {"workships": {"some": {"workshopId": {"in": ["w1"]}}}} not in branches
    assert {"workshops": {"some": {"workshopId": {"in": ["w1"]}}}} in branches


def test_an_unassigned_craft_must_be_unlinked_by_BOTH_readings(api) -> None:
    """A craft with a join row but no column is linked to that workshop by every query in the app;
    calling it unassigned as well would count it twice."""
    branches = _where("crafts", workshop_ids=["none"])["AND"][0]["OR"]

    assert {"AND": [{"workshopId": None}, {"workshops": {"none": {}}}]} in branches


def test_unassigned_only_against_the_workshops_table_matches_nothing(api) -> None:
    """The honest answer. Leaving the table unfiltered would hand over every workshop under a scope
    that excludes all of them."""
    where = _where("workshops", workshop_ids=["none"])

    assert where["AND"] == [{"id": {"in": []}}]


def test_unassigned_widens_the_other_tables_to_null_workshops(api) -> None:
    where = _where("tools", workshop_ids=["w1", "none"])

    assert where["AND"][0] == {"OR": [{"workshopId": {"in": ["w1"]}}, {"workshopId": None}]}


def test_media_is_owned_by_its_uploader_not_its_creator(api) -> None:
    """MediaFile has no createdById; filtering on one would be an error, not an empty result."""
    assert _where("media", created_by="u1")["AND"] == [{"uploadedById": "u1"}]


# =================================================================================================
# Streaming
# =================================================================================================


def _rows(n: int) -> list[SimpleNamespace]:
    # Zero-padded so lexical id ordering matches numeric order — the keyset walk compares strings.
    return [SimpleNamespace(id=f"id{i:03d}", name=f"row {i}") for i in range(n)]


def test_the_stream_walks_by_keyset_and_never_by_offset(api) -> None:
    """The property this endpoint exists for. Each batch asks for `id > last seen`."""
    table = api.table("craft", _rows(5))

    async def run() -> list[Any]:
        seen: list[Any] = []
        async for batch in datasets._stream_rows(datasets.DATASETS["crafts"], {}):
            seen.extend(batch)
        return seen

    monkey_batch = datasets.STREAM_BATCH
    try:
        datasets.STREAM_BATCH = 2
        collected = asyncio.run(run())
    finally:
        datasets.STREAM_BATCH = monkey_batch

    assert [r.id for r in collected] == [f"id{i:03d}" for i in range(5)]
    # First query has no cursor; every later one carries the previous batch's last id.
    assert _Rows._after(table.wheres[0]) is None
    assert [_Rows._after(w) for w in table.wheres[1:]] == ["id001", "id003"]


def test_the_stream_stops_on_a_short_batch_without_an_extra_query(api) -> None:
    """A batch shorter than the take size is the last one; asking again is a wasted round trip
    against a cross-region database, on every export."""
    table = api.table("craft", _rows(3))

    async def run() -> None:
        async for _ in datasets._stream_rows(datasets.DATASETS["crafts"], {}):
            pass

    original = datasets.STREAM_BATCH
    try:
        datasets.STREAM_BATCH = 5
        asyncio.run(run())
    finally:
        datasets.STREAM_BATCH = original

    assert len(table.wheres) == 1


def test_the_stream_preserves_the_callers_filter_alongside_the_cursor(api) -> None:
    """The cursor must be ANDed onto the filter, never replace it."""
    table = api.table("craft", _rows(4))
    base = {"AND": [{"createdById": "u1"}]}

    async def run() -> None:
        async for _ in datasets._stream_rows(datasets.DATASETS["crafts"], base):
            pass

    original = datasets.STREAM_BATCH
    try:
        datasets.STREAM_BATCH = 2
        asyncio.run(run())
    finally:
        datasets.STREAM_BATCH = original

    second = table.wheres[1]
    assert base in second["AND"]
    assert {"id": {"gt": "id001"}} in second["AND"]


def test_ndjson_emits_one_complete_record_per_line_and_declares_the_total(api) -> None:
    api.table("craft", _rows(3))

    response = api.get("/api/datasets/crafts.ndjson", token=api.token("admin"))

    lines = [line for line in response.text.splitlines() if line]
    assert response.headers["x-dataset-total"] == "3"
    assert len(lines) == 3
    import json as _json

    assert [_json.loads(line)["id"] for line in lines] == ["id000", "id001", "id002"]


# =================================================================================================
# CSV
# =================================================================================================


def _workshop_row(row_id: str, title: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=row_id,
        title=title,
        place="Bagru",
        startDate=None,
        date=None,
        endDate=None,
        description="A workshop",
        notes=None,
        status="APPROVED",
        createdBy=SimpleNamespace(name="Asha"),
        createdAt=None,
        media=[],
    )


def test_csv_columns_come_from_the_shared_field_registry(api) -> None:
    api.table("workshop", [_workshop_row("id001", "Block printing")])

    response = api.get("/api/datasets/workshops.csv", token=api.token("admin"))
    header, *rows = [line for line in response.text.splitlines() if line]

    assert header.split(",")[:2] == ["ID", "Workshop"]
    assert "Place" in header and "Media count" in header
    assert rows[0].startswith("id001,Block printing,Bagru")


# Which relations each registry spec's getters TRAVERSE, and therefore which relations that
# dataset's `include` must load. Mirrors app/services/record_fields.py:
#   CRAFT/ARTISAN  "Workshops"/"Workshop" -> _workshop_titles(x) reads x.workshops[].workshop
#   ARTISAN        Craft / Village / District / State / Pincode -> x.craft, x.location
#   PRODUCT/TOOL   "Workshop" -> _rel(x, "workshop", "title")
#   PROCESS        Product / Artisan / Craft -> x.product; "Steps" -> x.steps
#   INTERVIEW      "Artisans" -> artisan_names(i) reads i.artisans[].artisan
# A relation a getter reads but the include does not load renders as an EMPTY CELL — a column that
# says "no workshop" instead of saying "this export forgot to ask for it". That shipped once, for
# crafts, which loaded the singular `workshop` FK while the spec read the plural join.
SPEC_RELATIONS: dict[str, tuple[str, ...]] = {
    "workshops": (),
    "crafts": ("workshops",),
    "artisans": ("workshops", "craft", "location"),
    "products": ("workshop",),
    "tools": ("workshop",),
    "processes": ("product", "steps"),
    "interviews": ("artisans",),
}


@pytest.mark.parametrize("name", sorted(SPEC_RELATIONS))
def test_every_dataset_loads_the_relations_its_csv_columns_read(api, name: str) -> None:
    include = datasets.DATASETS[name].include
    for relation in SPEC_RELATIONS[name]:
        assert relation in include, (
            f"{name}.include is missing {relation!r}, which the {datasets.DATASETS[name].kind} "
            f"spec's getters read — that column would render empty for every row"
        )


def test_the_relation_map_covers_every_dataset_with_a_csv_form(api) -> None:
    """So a NEW dataset cannot be added without deciding what its columns need."""
    with_csv = {n for n, d in datasets.DATASETS.items() if d.kind}
    assert with_csv == set(SPEC_RELATIONS)


def test_the_craft_workshops_column_actually_renders_a_title(api) -> None:
    """The end of the bug above: the join row -> nested workshop -> its title reaches the cell."""
    from app.services.record_fields import sheet_columns, sheet_row

    craft = SimpleNamespace(
        id="c1", name="Bagru Hand Block Printing", localName=None, category="Printing",
        place=None, description=None, status=None, createdBy=None, createdAt=None, media=[],
        workshops=[SimpleNamespace(craftId="c1", workshop=SimpleNamespace(title="Shristi 2nd Toolkit"))],
    )
    row = dict(zip(sheet_columns("craft"), sheet_row("craft", craft, [])))

    assert row["Workshops"] == "Shristi 2nd Toolkit"


def test_the_catalogue_advertises_exactly_the_columns_the_csv_emits(api) -> None:
    """THE contract of the catalogue: a client mirrors from it without reading documentation, so
    `dict(zip(entry["columns"], row))` must line up. These were two separate expressions — the plan
    omitted the leading "ID" the header carries — so every field a client read was one column to the
    left of its value, and the last column was dropped entirely."""
    api.table("workshop", [_workshop_row("id001", "Block printing")])
    for name in datasets.DATASETS:
        api.table(datasets.DATASETS[name].delegate, api.tables.get(datasets.DATASETS[name].delegate).rows
                  if datasets.DATASETS[name].delegate in api.tables else [])
    token = api.token("admin")

    plan = api.get("/api/datasets", token=token).json()
    advertised = {e["name"]: e["columns"] for e in plan["datasets"]}

    header = api.get("/api/datasets/workshops.csv", token=token).text.splitlines()[0]
    assert advertised["workshops"] == header.split(",")
    assert advertised["workshops"][0] == "ID"
    # And a dataset with no CSV form advertises no columns rather than a misleading list.
    assert advertised["media"] is None


@pytest.mark.parametrize("name", sorted(n for n, d in datasets.DATASETS.items() if d.kind))
def test_the_advertised_columns_match_the_registry_for_every_dataset(api, name: str) -> None:
    from app.services.record_fields import sheet_columns

    kind = datasets.DATASETS[name].kind
    assert datasets._csv_columns(kind) == ["ID", *sheet_columns(kind)]


def test_process_media_is_resolved_by_tag_not_by_an_absent_relation(api) -> None:
    """Process has NO `media` back-relation, so reading `record.media` reported "0 media" on every
    row while /api/data/report printed the real filenames for the same processes — one record
    described two ways. The files are attached by the tag pair and are found by it here.
    """
    proc = SimpleNamespace(
        id="pr1", name="Block printing", notes=None, status="APPROVED",
        createdBy=SimpleNamespace(name="Asha"), createdAt=None, steps=[],
        product=SimpleNamespace(productName="Cloth", artisanName="Ramesh", craftName="Printing"),
    )
    api.table("process", [proc])
    api.table("mediafile", [
        SimpleNamespace(id="m1", linkedRecordType="process", linkedRecordId="pr1",
                        originalFilename="dyeing.mp4", url="https://s3/dyeing.mp4"),
        # A STEP attachment: it belongs to the report's separate Process steps sheet, so it must NOT
        # be folded onto the parent process row here.
        SimpleNamespace(id="m2", linkedRecordType="processstep", linkedRecordId="st1",
                        originalFilename="step.jpg", url="https://s3/step.jpg"),
    ])

    text = api.get("/api/datasets/processes.csv", token=api.token("admin")).text
    row = dict(zip(datasets._csv_columns("process"), next(csv.reader(text.splitlines()[1:2]))))

    assert row["Media count"] == "1"
    assert row["Media files"] == "dyeing.mp4"
    assert "step.jpg" not in text


def test_a_dataset_the_registry_does_not_describe_has_no_csv_form(api) -> None:
    """Media is not a record type. A 422 naming the working alternative beats an empty file."""
    response = api.get("/api/datasets/media.csv", token=api.token("admin"))

    assert response.status_code == 422
    assert "media.ndjson" in response.json()["detail"]


# =================================================================================================
# Regulated identity numbers
# =================================================================================================


def _artisan_row() -> SimpleNamespace:
    return SimpleNamespace(
        id="a1",
        name="Ramesh",
        aadhaarNumber="123456789012",
        pehchanCardNumber="PC12345678",
        createdById="someone-else",
    )


def test_identity_numbers_are_masked_by_default(api) -> None:
    """A bulk export must not be the one place in the codebase where Aadhaar comes out raw."""
    api.table("artisan", [_artisan_row()])

    payload = api.get("/api/datasets/artisans", token=api.token("admin")).json()

    # The app's own mask, spacing included — see services/artisan_identity.mask_aadhaar.
    assert payload["items"][0]["aadhaarNumber"] == "XXXX XXXX 9012"


def test_a_master_admin_can_ask_for_unmasked_numbers(api) -> None:
    api.user("root", "MASTER_ADMIN")
    api.table("artisan", [_artisan_row()])

    payload = api.get(
        "/api/datasets/artisans?includeIdentityNumbers=true", token=api.token("root")
    ).json()

    assert payload["items"][0]["aadhaarNumber"] == "123456789012"


def test_an_ordinary_admin_cannot_ask_for_unmasked_numbers(api) -> None:
    """Admin is enough to take the repository; taking regulated identifiers in bulk is one rung up,
    and asking must fail loudly rather than quietly returning the masked value."""
    api.table("artisan", [_artisan_row()])

    response = api.get("/api/datasets/artisans?includeIdentityNumbers=true", token=api.token("admin"))

    assert response.status_code == 403
    assert "Master admin" in response.json()["detail"]
