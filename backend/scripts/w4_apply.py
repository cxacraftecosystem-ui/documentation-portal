import argparse
import asyncio
import inspect
import sys
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from app.api.routes import access_roster as roster_routes
from app.api.routes import artisans as artisan_routes
from app.api.routes import crafts as craft_routes
from app.api.routes import workshops as workshop_routes
from app.core.db import connect_db, db, disconnect_db
from app.schemas.access import WorkshopGrantIn
from app.schemas.access_roster import AccessRosterUpdate
from app.schemas.common import LocationInput
from app.schemas.questionnaire import WorkshopQuestionnaireUpdate
from app.schemas.records import ArtisanCreate, CraftCreate, WorkshopCreate
from app.services import aadhaar_fingerprint
from app.services import questionnaire_instruments as instruments
from app.services import questionnaire_seeding

MASTER_ADMIN_ID = "cmqg22xf6000018qdavy2jvel"
W3_WORKSHOP_ID = "cmtydcwle0007u1we4h4p4538"

W4_ID = getattr(instruments, "W4_ID", "qnr_4th_craft_toolkit_workshop")
W4_TITLE = getattr(instruments, "W4_TITLE", "4th Craft Toolkit Workshop")
QUESTIONNAIRE_CORPUS = questionnaire_seeding.DATA_DIR / "questionnaire_questions_w3.json"
QUESTIONNAIRE_DESCRIPTION = (
    "The instrument used at the 4th Craft Toolkit Workshop (stone crafts): 22 sections (A-V), "
    "81 questions. Section V's questions ask for lists — designers, suppliers, buyers, digital, "
    "financial and institutional connections — and are answered as prose, like every other "
    "question here."
)

WORKSHOP_TITLE = "Shristi O Anusandhan 4th Toolkit Workshop for Stone Craft Tradition"
WORKSHOP_CLIENT_KEY = "fr-w4-apply-workshop"
WORKSHOP_START = datetime(2026, 11, 12, 0, 0, 0, tzinfo=UTC)
WORKSHOP_END = datetime(2026, 11, 23, 23, 59, 59, 999000, tzinfo=UTC)
DESCRIPTION = (
    "Shristi O Anusandhan: 4th Toolkit Workshop for Stone Craft Traditions, organized at the "
    "Rajendra Mishra School of Engineering Entrepreneurship (RMSoEE), IIT Kharagpur from 12th to "
    "23rd November, is a national initiative dedicated to documenting, augmenting, and digitally "
    "archiving India's stone crafts. Hosted under the Centre of Excellence for Unified AI-Enabled "
    "Craft Ecosystem Platform in collaboration with the Development Commissioner (Handicrafts), "
    "Ministry of Textiles, this 12-day workshop brings together master artisans from Varanasi Soft "
    "Stone Jali Work and Agra Marble Inlay (Uttar Pradesh), Mahabalipuram Stone Sculpture (Tamil "
    "Nadu), Konark Stone Carving (Odisha), Agates of Cambay (Gujarat), and the stone-carving "
    "traditions of Meghalaya and Mysuru (Karnataka). Through live demonstrations, ergonomic tool "
    "mapping, student-artisan innovation labs and detailed cataloguing, the programme aims to "
    "upgrade traditional toolkits, reduce physical strain and dust exposure, and open sustainable "
    "livelihoods while safeguarding the identity of these stone crafts."
)

CRAFT_CATEGORY = "Stone Craft Category"
CRAFT_SUB_CATEGORY = "Stone (Carving)"
MEGHALAYA = "Meghalaya Stone Carving"
MYSURU = "Mysuru Stone Carving"
NEW_CRAFTS = ((MEGHALAYA, "Meghalaya"), (MYSURU, "Karnataka"))

VARANASI_JALI = "cmcb9519be539d1c481140860"
KONARK = "cmcd18bd8f996ae4d634c7956"
MAHABALIPURAM = "cmc75254f979582c8adcb037d"
AGRA_INLAY = "cmc4e59e7e7aad89f02269fdf"
AGATES_CAMBAY = "cmcb690d13de330062691d979"
STONE_OTHERS = "cmca2a8d74d097c1c1e3813bd"

DOS_DONTS = "To be recorded at the workshop."

APPROVE_EMAILS = ("durbaghosh2210@gmail.com", "bluemushroom2003@gmail.com")
APPROVED_ROLE = "RESEARCHER"
CONTRIBUTOR_EMAIL = "subhadeep.iitkgp26@gmail.com"
CONTRIBUTE = "CONTRIBUTE"
COPY_NOTE = "Copied from the 3rd Toolkit Workshop roster."

DISTRICTS: dict[str, tuple[str, str, float, float]] = {
    "varanasi": ("Uttar Pradesh", "Varanasi", 25.3176, 82.9739),
    "agra": ("Uttar Pradesh", "Agra", 27.1767, 78.0081),
    "west_khasi_hills": ("Meghalaya", "West Khasi Hills", 25.5167, 91.2667),
    "kamrup_metro": ("Assam", "Kamrup Metropolitan", 26.1445, 91.7362),
    "baleshwar": ("Odisha", "Baleshwar", 21.4942, 86.9317),
    "puri": ("Odisha", "Puri", 19.8135, 85.8312),
    "kanchipuram": ("Tamil Nadu", "Kanchipuram", 12.8342, 79.7036),
    "mysuru": ("Karnataka", "Mysuru", 12.2958, 76.6394),
    "anand": ("Gujarat", "Anand", 22.5645, 72.9289),
}


@dataclass(frozen=True)
class ArtisanSpec:
    name: str
    craft: str
    place: str
    address: str
    district: str
    phones: tuple[str, ...] = ()
    pehchan: str | None = None
    aadhaar: str | None = None
    gender: str | None = None
    village: str | None = None
    pincode: str | None = None
    notes: tuple[str, ...] = ()


ARTISANS: tuple[ArtisanSpec, ...] = (
    ArtisanSpec(
        name="Aadarsh Kr. Maurya", craft=VARANASI_JALI, place="Varanasi",
        address="Varanasi, Uttar Pradesh", district="varanasi", phones=("9795350010",),
        pehchan="CRVNC415810", notes=("Award: State Awardee",),
    ),
    ArtisanSpec(
        name="Rajesh Yadav", craft=VARANASI_JALI, place="Varanasi",
        address="Varanasi, Uttar Pradesh", district="varanasi", phones=("9044448925",),
        pehchan="CRVNC26391",
    ),
    ArtisanSpec(
        name="Uday Pratap Maurya", craft=VARANASI_JALI, place="Varanasi",
        address="Varanasi, Uttar Pradesh", district="varanasi", phones=("7275377297",),
        pehchan="CRVNC672324",
    ),
    ArtisanSpec(
        name="Jamesdonald Kharsyntiew", craft=MEGHALAYA, place="Nongstoin",
        address=(
            "C/O: Donborlin Blah, 66 Mawkawah, Nongstoin, P.O. Nongstoin, West Khasi Hills, "
            "Meghalaya – 793119"
        ),
        district="west_khasi_hills", phones=("9383224898",), pehchan="NERMGH543884",
        aadhaar="652281244163", gender="Male", village="Nongstoin", pincode="793119",
        notes=(
            "PAN: QYUPK3873F",
            "Region: Meghalaya",
            "Social category: General",
            "Age: 29",
        ),
    ),
    ArtisanSpec(
        name="Khargeswar", craft=STONE_OTHERS, place="Guwahati (Bodoland)",
        address="Guwahati (Bodoland), Assam", district="kamrup_metro",
        notes=("Craft not given in the artisan sheet",),
    ),
    ArtisanSpec(
        name="Draupadi Gedi", craft=KONARK, place="Santaragadia",
        address="Santaragadia, Dist. Balesore, Odisha", district="baleshwar",
        phones=("7751838284", "9090746226"), pehchan="ERRAC215778", village="Santaragadia",
        notes=("Award: National Award", "Region/HSC: HSC Bhubaneswar"),
    ),
    ArtisanSpec(
        name="Jambeswar Behera", craft=KONARK, place="Delanga Bilkaapuru",
        address="Delanga Bilkaapuru, Dist. Puri, Odisha", district="puri",
        phones=("9937747074",), pehchan="ERBBC343165", village="Delanga Bilkaapuru",
        notes=("Award: National Award", "Region/HSC: HSC Bhubaneswar"),
    ),
    ArtisanSpec(
        name="Ashok Kumar Mohapatra", craft=KONARK, place="Pathuria Sahi",
        address="Pathuria Sahi, Dist. Puri, Odisha", district="puri",
        phones=("9938973059", "7873945659"), pehchan="ERBBC284352", village="Pathuria Sahi",
        notes=(
            "Award: National Award",
            "Region/HSC: HSC Bhubaneswar",
            "Remarks: Already attended the program at IIT KGP (1st Toolkit Workshop)",
        ),
    ),
    ArtisanSpec(
        name="Mathivanan", craft=MAHABALIPURAM, place="Kelambakkam",
        address=(
            "2/192, Hanuman Nagar, 5th Street, Kelambakkam, Thazhampur, Kanchipuram – 603103, "
            "Tamil Nadu"
        ),
        district="kanchipuram", phones=("9543161791",), pehchan="SRCHC207059",
        aadhaar="788583719362", village="Kelambakkam", pincode="603103",
        notes=("PAN: CKHPM1027Q",),
    ),
    ArtisanSpec(
        name="Sandesh P", craft=MYSURU, place="Mysore",
        address=(
            "No. 48, 2nd Cross, J P Nagar, Ring Road, Ashraya Badavane, J P Nagar, Mysore-570008, "
            "Karnataka"
        ),
        district="mysuru", phones=("9060985969",), aadhaar="818879425717", pincode="570008",
        notes=("PAN: MLYPS2120N",),
    ),
    ArtisanSpec(
        name="Fahim", craft=AGRA_INLAY, place="Agra", address="Agra, Uttar Pradesh",
        district="agra", phones=("8077173698",), pehchan="CRAGC624402",
        notes=(
            "PAN: MLYPS2120N",
            "Region/HSC: HSC Agra",
            "Note: Aadhaar in the artisan sheet (ending 5717) duplicates Sandesh P's; to be verified",
        ),
    ),
    ArtisanSpec(
        name="Moin Khan", craft=AGRA_INLAY, place="Agra", address="Agra, Uttar Pradesh",
        district="agra", phones=("9720310374",), pehchan="CRAGC624442",
        notes=("Region/HSC: HSC Agra", "Craft as listed: Agra parchin kari / marble inlay"),
    ),
    ArtisanSpec(
        name="Mansuri Mohammad Yaasin", craft=AGATES_CAMBAY, place="Khambhat",
        address="Khambhat, Anand, Gujarat", district="anand", phones=("9824600992",),
        village="Khambhat",
    ),
)

COUNTED_MODELS = (
    "questionnaire",
    "questionnairesection",
    "questionnairequestion",
    "craft",
    "workshop",
    "location",
    "workshopassignment",
    "artisan",
    "workshopartisan",
    "accessroster",
    "user",
)

PLANNED = "WOULD CREATE"
CREATED = "CREATED"
EXISTS = "EXISTS"
WOULD_UPDATE = "WOULD UPDATE"
UPDATED = "UPDATED"
DEFERRED = "DEFERRED"
CONFLICT = "CONFLICT"


class Refused(RuntimeError):
    pass


@dataclass
class Report:
    apply: bool
    rows: list[tuple[str, str, str, str]] = field(default_factory=list)

    def add(self, step: str, outcome: str, label: str, ident: str = "") -> None:
        self.rows.append((step, outcome, label, ident))
        print(f"  [{step}] {outcome:<12} {label}{f'  -> {ident}' if ident else ''}", flush=True)

    def count(self, step: str | None = None, outcome: str | None = None) -> int:
        return sum(
            1
            for s, o, _, _ in self.rows
            if (step is None or s == step) and (outcome is None or o == outcome)
        )

    def ids(self, step: str) -> list[str]:
        return [i for s, _, _, i in self.rows if s == step and i]

    @property
    def conflicts(self) -> int:
        return self.count(outcome=CONFLICT)

    @property
    def pending_writes(self) -> int:
        return self.count(outcome=PLANNED) + self.count(outcome=WOULD_UPDATE)

    def summary(self) -> str:
        steps = []
        for step, _, _, _ in self.rows:
            if step not in steps:
                steps.append(step)
        outcomes = (PLANNED, WOULD_UPDATE, CREATED, UPDATED, EXISTS, DEFERRED, CONFLICT)
        lines = ["", "SUMMARY" + ("" if self.apply else " (dry run)")]
        for step in steps:
            parts = [f"{o.lower()} {self.count(step, o)}" for o in outcomes if self.count(step, o)]
            lines.append(f"  {step:<14} {', '.join(parts)}")
            for _, outcome, label, ident in self.rows:
                if _ == step and ident and outcome in (CREATED, UPDATED, EXISTS):
                    lines.append(f"      {outcome:<8} {ident}  {label}")
        return "\n".join(lines)


def _text(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _value(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def notes_for(spec: ArtisanSpec) -> str | None:
    lines = list(spec.notes)
    if len(spec.phones) > 1:
        position = 1 if lines and lines[0].startswith("PAN:") else 0
        lines.insert(position, f"Second phone: +91 {spec.phones[1]}")
    return "\n".join(lines) if lines else None


def location_for(spec: ArtisanSpec) -> LocationInput:
    state, district, latitude, longitude = DISTRICTS[spec.district]
    return LocationInput(
        latitude=latitude,
        longitude=longitude,
        state=state,
        district=district,
        village=spec.village,
        pincode=spec.pincode,
        address=spec.address,
    )


def artisan_payload(spec: ArtisanSpec, *, craft_id: str, workshop_id: str) -> ArtisanCreate:
    body: dict[str, Any] = {
        "name": spec.name,
        "place": spec.place,
        "address": spec.address,
        "notes": notes_for(spec),
        "dos": DOS_DONTS,
        "donts": DOS_DONTS,
        "craftId": craft_id,
        "workshopId": workshop_id,
        "status": "APPROVED",
        "location": location_for(spec),
    }
    if spec.gender:
        body["gender"] = spec.gender
    if spec.phones:
        body["phone"] = spec.phones[0]
        body["phoneCountryCode"] = "+91"
    if spec.pehchan:
        body["pehchanCardNumber"] = spec.pehchan.replace(" ", "").upper()
    if spec.aadhaar:
        body["aadhaarNumber"] = spec.aadhaar
    else:
        body["aadhaarNotShared"] = True
    return ArtisanCreate(**body)


def craft_payload(name: str, state: str, sub_category_id: str) -> CraftCreate:
    return CraftCreate(
        name=name,
        subCategoryId=sub_category_id,
        stateOfOrigin=[state],
        stateOfPractice=[state],
        giStatus="NON_GI",
    )


LOCATION_FIELDS = tuple(LocationInput.model_fields)


def workshop_payload(w3: Any) -> WorkshopCreate:
    location = _value(w3, "location")
    if location is None:
        raise Refused("the 3rd workshop has no location to copy")
    copied = {
        name: _value(location, name)
        for name in LOCATION_FIELDS
        if _value(location, name) is not None
    }
    return WorkshopCreate(
        title=WORKSHOP_TITLE,
        workshopType="OTHER",
        startDate=WORKSHOP_START,
        endDate=WORKSHOP_END,
        place=_value(w3, "place"),
        description=DESCRIPTION,
        status="APPROVED",
        location=LocationInput(**copied),
        clientKey=WORKSHOP_CLIENT_KEY,
    )


@dataclass
class State:
    admin: Any = None
    workshop_id: str | None = None
    craft_ids: dict[str, str] = field(default_factory=dict)


async def load_admin(client: Any) -> Any:
    admin = await client.user.find_unique(where={"id": MASTER_ADMIN_ID})
    if admin is None:
        raise Refused(f"no user {MASTER_ADMIN_ID}")
    if _text(admin.role) != "MASTER_ADMIN":
        raise Refused(f"{MASTER_ADMIN_ID} is {_text(admin.role)}, not MASTER_ADMIN")
    return admin


async def step_questionnaire(report: Report, client: Any) -> None:
    sections = questionnaire_seeding.load_corpus(QUESTIONNAIRE_CORPUS)
    want_sections = len(sections)
    want_questions = sum(len(s["questions"]) for s in sections)
    label = f"{W4_TITLE} ({want_sections} sections, {want_questions} questions)"
    row = await client.questionnaire.find_unique(where={"id": W4_ID})
    if row is not None:
        have_sections = await client.questionnairesection.count(where={"questionnaireId": W4_ID})
        have_questions = await client.questionnairequestion.count(where={"questionnaireId": W4_ID})
        if (have_sections, have_questions) == (want_sections, want_questions):
            report.add("questionnaire", EXISTS, label, W4_ID)
            return
    else:
        clash = await client.questionnaire.find_first(where={"title": W4_TITLE})
        if clash is not None:
            report.add("questionnaire", CONFLICT, f"title {W4_TITLE!r} already used by {clash.id}")
            return
    supports_owner = "created_by_id" in inspect.signature(
        questionnaire_seeding.seed_instrument
    ).parameters
    if not supports_owner:
        first_master = await client.user.find_first(
            where={"role": "MASTER_ADMIN"}, order={"createdAt": "asc"}
        )
        if first_master is None or first_master.id != MASTER_ADMIN_ID:
            report.add(
                "questionnaire",
                CONFLICT,
                "the deployed seeder would own it by "
                f"{getattr(first_master, 'id', None)}, not {MASTER_ADMIN_ID}",
            )
            return
    if not report.apply:
        report.add("questionnaire", PLANNED, f"{label}, owner {MASTER_ADMIN_ID}", W4_ID)
        return
    kwargs: dict[str, Any] = {
        "questionnaire_id": W4_ID,
        "title": W4_TITLE,
        "description": QUESTIONNAIRE_DESCRIPTION,
        "corpus_path": QUESTIONNAIRE_CORPUS,
    }
    if supports_owner:
        kwargs["created_by_id"] = MASTER_ADMIN_ID
    questionnaire_id, counts = await questionnaire_seeding.seed_instrument(**kwargs)
    questionnaire_seeding.print_counts(W4_TITLE, questionnaire_id, counts)
    stored = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if stored is None or stored.createdById != MASTER_ADMIN_ID:
        raise Refused(f"{questionnaire_id} is owned by {getattr(stored, 'createdById', None)}")
    report.add("questionnaire", CREATED, label, questionnaire_id)


async def step_crafts(report: Report, client: Any, state: State) -> None:
    sub = await client.craftsubcategory.find_first(
        where={"name": CRAFT_SUB_CATEGORY, "category": {"is": {"name": CRAFT_CATEGORY}}}
    )
    if sub is None:
        raise Refused(f"no sub-category {CRAFT_CATEGORY} > {CRAFT_SUB_CATEGORY}")
    for name, craft_state in NEW_CRAFTS:
        payload = craft_payload(name, craft_state, sub.id)
        existing = await client.craft.find_first(
            where={"name": {"equals": name, "mode": "insensitive"}}
        )
        if existing is not None:
            state.craft_ids[name] = existing.id
            report.add("crafts", EXISTS, name, existing.id)
            continue
        if not report.apply:
            state.craft_ids[name] = f"<new {name}>"
            report.add(
                "crafts", PLANNED,
                f"{name} under {CRAFT_CATEGORY} > {CRAFT_SUB_CATEGORY}, NON_GI, {craft_state}",
            )
            continue
        created = await craft_routes.create_craft(payload, current_user=state.admin)
        state.craft_ids[name] = created["id"]
        report.add("crafts", CREATED, name, created["id"])


async def step_workshop(report: Report, client: Any, state: State) -> None:
    w3 = await client.workshop.find_unique(where={"id": W3_WORKSHOP_ID}, include={"location": True})
    if w3 is None:
        raise Refused(f"no 3rd workshop {W3_WORKSHOP_ID}")
    payload = workshop_payload(w3)
    existing = await client.workshop.find_many(
        where={"OR": [{"title": WORKSHOP_TITLE}, {"clientKey": WORKSHOP_CLIENT_KEY}]}
    )
    if len(existing) > 1:
        report.add("workshop", CONFLICT, f"{len(existing)} workshops titled {WORKSHOP_TITLE!r}")
        return
    if existing:
        state.workshop_id = existing[0].id
        report.add("workshop", EXISTS, WORKSHOP_TITLE, existing[0].id)
        return
    if not report.apply:
        report.add(
            "workshop", PLANNED,
            f"{WORKSHOP_TITLE} | OTHER, APPROVED | {WORKSHOP_START.isoformat()} .. "
            f"{WORKSHOP_END.isoformat()} | {payload.place} | location copied from {w3.locationId}",
        )
        return
    created = await workshop_routes.create_workshop(payload, current_user=state.admin)
    state.workshop_id = created["id"]
    report.add("workshop", CREATED, WORKSHOP_TITLE, created["id"])


async def step_binding(report: Report, client: Any, state: State) -> None:
    label = f"{W4_ID} as the workshop's questionnaire"
    if state.workshop_id is None:
        report.add("binding", WOULD_UPDATE if not report.apply else CONFLICT, label)
        return
    workshop = await client.workshop.find_unique(where={"id": state.workshop_id})
    current = getattr(workshop, "questionnaireId", None)
    if current == W4_ID:
        report.add("binding", EXISTS, label, state.workshop_id)
        return
    if current is not None:
        report.add("binding", CONFLICT, f"workshop already bound to {current}")
        return
    if not report.apply:
        report.add("binding", WOULD_UPDATE, label, state.workshop_id)
        return
    await workshop_routes.set_workshop_questionnaire(
        state.workshop_id,
        WorkshopQuestionnaireUpdate(questionnaireId=W4_ID),
        current_user=state.admin,
    )
    report.add("binding", UPDATED, label, state.workshop_id)


async def grant(
    report: Report,
    client: Any,
    state: State,
    *,
    user_id: str,
    level: str,
    label: str,
    note: str | None,
    step: str = "assignments",
) -> None:
    existing = None
    if state.workshop_id is not None:
        existing = await client.workshopassignment.find_unique(
            where={"workshopId_userId": {"workshopId": state.workshop_id, "userId": user_id}}
        )
    if existing is not None and _text(existing.status) == "GRANTED" and _text(existing.accessLevel) == level:
        report.add(step, EXISTS, f"{label} {level}", existing.id)
        return
    if not report.apply:
        outcome = PLANNED if existing is None else WOULD_UPDATE
        report.add(step, outcome, f"{label} {level} GRANTED")
        return
    row = await workshop_routes.grant_workshop_assignment(
        state.workshop_id,
        WorkshopGrantIn(userId=user_id, accessLevel=level, note=note),
        current_user=state.admin,
    )
    report.add(step, CREATED if existing is None else UPDATED, f"{label} {level}", row["id"])


async def step_assignments(report: Report, client: Any, state: State) -> None:
    rows = await client.workshopassignment.find_many(
        where={"workshopId": W3_WORKSHOP_ID}, include={"user": True}, order={"createdAt": "asc"}
    )
    granted = [r for r in rows if _text(r.status) == "GRANTED"]
    if len(granted) != len(rows):
        print(f"  [assignments] {len(rows) - len(granted)} non-GRANTED rows on the 3rd are not copied")
    copied: set[str] = set()
    for row in granted:
        copied.add(row.userId)
        await grant(
            report, client, state,
            user_id=row.userId,
            level=_text(row.accessLevel) or CONTRIBUTE,
            label=f"copy {row.user.email}",
            note=COPY_NOTE,
        )
    contributor = await client.user.find_unique(where={"email": CONTRIBUTOR_EMAIL})
    if contributor is None:
        report.add("assignments", CONFLICT, f"{CONTRIBUTOR_EMAIL} has no user row")
    elif contributor.id in copied:
        print(f"  [assignments] {CONTRIBUTOR_EMAIL} is already covered by the copy")
    else:
        await grant(
            report, client, state,
            user_id=contributor.id, level=CONTRIBUTE, label=CONTRIBUTOR_EMAIL, note=None,
        )


async def find_artisan(client: Any, spec: ArtisanSpec, workshop_id: str | None) -> tuple[str, Any] | None:
    if spec.pehchan:
        hit = await client.artisan.find_first(where={"pehchanCardNumber": spec.pehchan})
        if hit is not None:
            return "Pehchan", hit
    for phone in spec.phones:
        hit = await client.artisan.find_first(where={"phone": {"endsWith": phone}})
        if hit is not None:
            return "phone", hit
    if spec.aadhaar:
        legacy = await aadhaar_fingerprint.legacy_pending(client)
        hit = await client.artisan.find_first(
            where=aadhaar_fingerprint.match_where(spec.aadhaar, legacy=legacy)
        )
        if hit is not None:
            return "Aadhaar", hit
    if workshop_id:
        hit = await client.artisan.find_first(
            where={"workshopId": workshop_id, "name": {"equals": spec.name, "mode": "insensitive"}}
        )
        if hit is not None:
            return "name", hit
    return None


async def step_artisans(report: Report, client: Any, state: State) -> None:
    listed = sorted({spec.craft for spec in ARTISANS if spec.craft not in state.craft_ids})
    crafts = {c.id: c for c in await client.craft.find_many(where={"id": {"in": listed}})}
    for craft_id in listed:
        craft = crafts.get(craft_id)
        if craft is None or _text(craft.status) != "ACTIVE":
            report.add("artisans", CONFLICT, f"craft {craft_id} is missing or not ACTIVE")
        else:
            print(f"  [artisans] craft ok {craft_id} {craft.name}")
    for spec in ARTISANS:
        craft_id = state.craft_ids.get(spec.craft, spec.craft)
        payload = artisan_payload(
            spec, craft_id=craft_id, workshop_id=state.workshop_id or "<new workshop>"
        )
        identity = "Aadhaar" if spec.aadhaar else "Aadhaar not shared"
        label = f"{spec.name} | craft {craft_id} | {payload.location.district}, {payload.location.state} | {identity}"
        found = await find_artisan(client, spec, state.workshop_id)
        if found is not None:
            how, hit = found
            if hit.workshopId == state.workshop_id and state.workshop_id is not None:
                report.add("artisans", EXISTS, f"{spec.name} (by {how})", hit.id)
            else:
                report.add(
                    "artisans", CONFLICT,
                    f"{spec.name}: {how} matches {hit.id} {hit.name!r} at workshop {hit.workshopId}",
                )
            continue
        if not report.apply:
            report.add("artisans", PLANNED, label)
            continue
        created = await artisan_routes.create_artisan(payload, current_user=state.admin)
        report.add("artisans", CREATED, spec.name, created["id"])


async def step_roster(report: Report, client: Any, state: State) -> None:
    for email in APPROVE_EMAILS:
        row = await client.accessroster.find_unique(where={"email": email})
        if row is None:
            report.add("roster", CONFLICT, f"{email} has no roster entry")
            continue
        if _text(row.status) == "ACTIVE" and _text(row.grantedRole) == APPROVED_ROLE:
            report.add("roster", EXISTS, f"{email} ACTIVE {APPROVED_ROLE}", row.id)
            continue
        if not report.apply:
            report.add(
                "roster", WOULD_UPDATE,
                f"{email} {_text(row.status)}/{_text(row.grantedRole)} -> ACTIVE/{APPROVED_ROLE}, invite",
                row.id,
            )
            continue
        await roster_routes.update_roster_entry(
            row.id,
            AccessRosterUpdate(status="ACTIVE", grantedRole=APPROVED_ROLE, sendInvite=True),
            current_user=state.admin,
        )
        report.add("roster", UPDATED, f"{email} ACTIVE {APPROVED_ROLE}, invite sent", row.id)


async def step_after_signin(report: Report, client: Any, state: State) -> None:
    for email in APPROVE_EMAILS:
        user = await client.user.find_unique(where={"email": email})
        if user is None:
            report.add(
                "after-signin", DEFERRED,
                f"{email} {CONTRIBUTE}: no account until the first sign-in; re-run --apply then",
            )
            continue
        await grant(
            report, client, state,
            user_id=user.id, level=CONTRIBUTE, label=email, note=None, step="after-signin",
        )


STEPS = (
    ("questionnaire", step_questionnaire),
    ("crafts", step_crafts),
    ("workshop", step_workshop),
    ("binding", step_binding),
    ("assignments", step_assignments),
    ("artisans", step_artisans),
    ("roster", step_roster),
    ("after-signin", step_after_signin),
)


async def plan(report: Report, client: Any) -> Report:
    state = State(admin=await load_admin(client))
    print(f"acting as {state.admin.name} ({state.admin.id}, {_text(state.admin.role)})")
    for name, step in STEPS:
        print(f"\n{name}")
        if step is step_questionnaire:
            await step(report, client)
        else:
            await step(report, client, state)
        if report.apply and report.conflicts:
            raise Refused(f"stopped at {name}: {report.conflicts} conflict(s)")
    return report


async def table_counts() -> dict[str, int]:
    async with db.tx(max_wait=timedelta(seconds=30), timeout=timedelta(seconds=120)) as tx:
        await tx.execute_raw("SET TRANSACTION READ ONLY")
        return {name: await getattr(tx, name).count() for name in COUNTED_MODELS}


async def write_is_refused() -> bool:
    refused = False
    try:
        async with db.tx(max_wait=timedelta(seconds=30), timeout=timedelta(seconds=60)) as tx:
            await tx.execute_raw("SET TRANSACTION READ ONLY")
            try:
                await tx.workshop.update_many(
                    where={"id": "w4-apply-read-only-probe"}, data={"title": "never"}
                )
            except Exception as exc:  # noqa: BLE001
                refused = "read-only" in str(exc)
    except Exception:  # noqa: BLE001
        pass
    return refused


async def dry_run() -> tuple[Report, dict[str, Any]]:
    report = Report(apply=False)
    before = await table_counts()
    async with db.tx(max_wait=timedelta(seconds=30), timeout=timedelta(seconds=600)) as tx:
        await tx.execute_raw("SET TRANSACTION READ ONLY")
        mode = await tx.query_raw("SELECT current_setting('transaction_read_only') AS ro")
        print(f"DRY RUN: every lookup runs in one transaction with transaction_read_only={mode[0]['ro']}")
        await plan(report, tx)
        closing = await tx.query_raw("SELECT current_setting('transaction_read_only') AS ro")
    after = await table_counts()
    refused = await write_is_refused()
    proof = {
        "readOnly": mode[0]["ro"] == "on" and closing[0]["ro"] == "on",
        "countsUnchanged": before == after,
        "writeRefused": refused,
        "counts": after,
    }
    return report, proof


async def apply_run() -> Report:
    print("PREFLIGHT: the plan, looked up before anything is written")
    preflight = await plan(Report(apply=False), db)
    if preflight.conflicts:
        raise Refused(f"{preflight.conflicts} conflict(s) in the preflight; nothing written")
    report = Report(apply=True)
    print("\nAPPLY: writing through the application's own route functions")
    await plan(report, db)
    return report


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="4th Toolkit Workshop data load")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    await connect_db()
    try:
        if args.dry_run:
            try:
                report, proof = await dry_run()
            except Refused as exc:
                print(f"\nREFUSED: {exc}")
                return 3
            print(report.summary())
            print(
                f"\nREAD-ONLY PROOF: transaction_read_only={'on' if proof['readOnly'] else 'OFF'}; "
                f"row counts before == after: {proof['countsUnchanged']}; "
                f"a write inside the same kind of transaction is refused: {proof['writeRefused']}"
            )
            print("  counts: " + ", ".join(f"{k}={v}" for k, v in proof["counts"].items()))
            print(f"\n{report.pending_writes} write(s) planned, {report.conflicts} conflict(s).")
            if not all((proof["readOnly"], proof["countsUnchanged"], proof["writeRefused"])):
                return 4
            return 3 if report.conflicts else 0
        try:
            report = await apply_run()
        except Refused as exc:
            print(f"\nREFUSED: {exc}")
            return 3
        print(report.summary())
        return 0
    finally:
        await disconnect_db()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
