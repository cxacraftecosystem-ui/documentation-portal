"""One seeded world for the search tests (docs/SEARCH.md), shared by the database-backed modules.

OPT-IN, LIKE EVERY DATABASE TEST IN THIS REPOSITORY: the modules that import :data:`needs_search_db`
run only with ``SEARCH_DB_TESTS=1`` and a ``DATABASE_URL`` that is a throwaway database with the
migrations applied (never ``backend/.env``, which points at production; a hosted DSN is refused
outright). CI's placeholder DSN and an ordinary ``pytest`` skip them.

Every title carries a per-run ``stamp`` so a query can be scoped to this run's rows on a database
nobody truncates, and every ranking query is narrowed further by this run's craft. Identity values
(Aadhaar, phone, Pehchan, e-mail, date of birth, address) are seeded on purpose, distinctive enough
that an assertion that none reaches the index cannot pass by accident.
"""

from __future__ import annotations

import os
import uuid
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

_URL = os.environ.get("DATABASE_URL", "")
_HOSTED = any(marker in _URL for marker in ("supabase", "pooler", "amazonaws"))

#: Mark for the database-backed search modules. See the module docstring.
needs_search_db = pytest.mark.skipif(
    os.environ.get("SEARCH_DB_TESTS") != "1" or _HOSTED or not _URL,
    reason="needs SEARCH_DB_TESTS=1 and a throwaway, migrated DATABASE_URL",
)

PHONE = "9876501234"
EMAIL = "ramesh.private@example.net"
ADDRESS = "House 42 Gali Seven"
DOB = datetime(1971, 3, 9, tzinfo=UTC)



def _aadhaar_for(stamp: str) -> str:
    """A Verhoeff-valid number unique to this seed: ``aadhaarNumber`` is unique and the database is
    never truncated, so a fixed literal collides with the previous module's seed."""
    from app.services.artisan_identity import verhoeff_ok

    body = "2" + str(int(stamp[1:], 16) % 10**10).zfill(10)
    for last in "0123456789":
        if verhoeff_ok(body + last):
            return body + last
    raise AssertionError("no Verhoeff check digit for " + body)


def _secrets(w: SimpleNamespace) -> tuple[str, ...]:
    """Strings that must never appear in a document's title, subtitle or body."""
    return (w.aadhaar, PHONE, w.pehchan, EMAIL, ADDRESS, "1971-03-09")


def new_stamp() -> str:
    return "q" + uuid.uuid4().hex[:9]


async def _user(stamp: str, slug: str, role: str) -> Any:
    from app.core.db import db
    from app.core.security import hash_password

    return await db.user.create(
        data={
            "email": f"search-{slug}-{stamp}@example.org",
            "name": f"Search {slug} {stamp}",
            "role": role,
            "passwordHash": hash_password("search-test-unused"),
        }
    )


async def consent(artisan_id: str, granted: bool) -> None:
    from app.core.db import db

    await db.artisansharingconsent.upsert(
        where={"artisanId": artisan_id},
        data={
            "create": {"artisanId": artisan_id, "granted": granted},
            "update": {"granted": granted},
        },
    )


async def seed(stamp: str) -> SimpleNamespace:
    """Every record type, artisans with and without sharing consent, and records naming an artisan
    by id, by free text and not at all."""
    from app.core.db import db

    w = SimpleNamespace(stamp=stamp)
    w.aadhaar = _aadhaar_for(stamp)
    w.pehchan = "PEHX" + stamp[1:].upper()  # pehchanCardNumber is unique too
    w.secrets = _secrets(w)
    w.professor = await _user(stamp, "professor", "PROFESSOR")
    w.researcher = await _user(stamp, "researcher", "RESEARCHER")
    w.contributor = await _user(stamp, "contributor", "FIELD_CONTRIBUTOR")
    owner = w.professor.id

    w.code = await db.craftcode.create(data={"code": f"sc-{stamp}", "name": f"Indigo {stamp}"})
    w.craft = await db.craft.create(
        data={
            "name": f"Indigo dyeing {stamp}",
            "localName": "नील रंगाई",
            "description": "Resist dyeing in fermented vats.",
            "craftCode": w.code.code,
            "place": "Bagru",
        }
    )
    w.other_craft = await db.craft.create(
        data={"name": f"Madder printing {stamp}", "place": "Sanganer"}
    )
    w.workshop = await db.workshop.create(
        data={
            "title": f"Dyers meet {stamp}",
            "date": datetime(2026, 9, 2, tzinfo=UTC),
            "place": "Bagru",
            "description": "Field visit to the vats.",
            "createdById": owner,
        }
    )
    common = {"place": "Bagru", "createdById": owner}
    w.unconsented = await db.artisan.create(
        data={
            "name": f"Ramesh Kumar {stamp}",
            "localName": "रमेश कुमार",
            "craftId": w.craft.id,
            "workshopId": w.workshop.id,
            "aadhaarNumber": w.aadhaar,
            "phone": PHONE,
            "email": EMAIL,
            "pehchanCardAvailable": True,
            "pehchanCardNumber": w.pehchan,
            "address": ADDRESS,
            "dateOfBirth": DOB,
            "notes": f"Reach at {PHONE}.",
            **common,
        }
    )
    w.consented = await db.artisan.create(
        data={"name": f"Sita Devi {stamp}", "craftId": w.craft.id, **common}
    )
    await consent(w.consented.id, True)

    product = {"craftName": w.craft.name, "craftId": w.craft.id, **common}
    w.product_title = await db.productdocumentation.create(
        data={
            "productName": f"Indigo stole {stamp}",
            "artisanName": w.consented.name,
            "artisanId": w.consented.id,
            "remarks": "Hand block printed.",
            **product,
        }
    )
    w.product_body = await db.productdocumentation.create(
        data={
            "productName": f"Cotton dupatta {stamp}",
            "artisanName": w.unconsented.name,
            "artisanId": w.unconsented.id,
            "remarks": f"An indigo stole variant. Call {PHONE} or mail {EMAIL}.",
            **product,
        }
    )
    w.product_free = await db.productdocumentation.create(
        data={
            "productName": f"Table runner {stamp}",
            "artisanName": "A weaver from the next village",
            "remarks": "Dyes from the indigo vat.",
            **product,
        }
    )
    w.tool = await db.tooldocumentation.create(
        data={
            "toolkitName": f"Wooden block {stamp}",
            "englishName": "Printing block",
            "artisanName": w.consented.name,
            "artisanId": w.consented.id,
            **product,
        }
    )
    w.process = await db.process.create(
        data={
            "name": f"Vat preparation {stamp}",
            "productId": w.product_title.id,
            "createdById": owner,
        }
    )
    await db.processstep.create(
        data={"processId": w.process.id, "name": "Ferment", "notes": "Seven days.", "sortOrder": 1}
    )

    media = {
        "mediaType": "AUDIO",
        "mimeType": "audio/mpeg",
        "sizeBytes": 1024,
        "bucket": "test-bucket",
        "uploadedById": owner,
    }
    w.transcript_consented = await db.mediafile.create(
        data={
            "originalFilename": f"camp-interview-{stamp}.mp3",
            "objectKey": f"search/{stamp}/a.mp3",
            "artisanId": w.consented.id,
            "transcriptText": f"We soak the cloth in indigo {stamp} three times.",
            **media,
        }
    )
    w.transcript_open = await db.mediafile.create(
        data={
            "originalFilename": f"vat-talk-{stamp}.mp3",
            "objectKey": f"search/{stamp}/open.mp3",
            "craftId": w.craft.id,
            "transcriptText": f"The vat smells sweet when it is ready. Phone {PHONE}.",
            "transcriptSummary": "How a vat is judged ready.",
            **media,
        }
    )

    w.questionnaire = await db.questionnaire.create(
        data={"title": f"Search instrument {stamp}", "sortOrder": 9000}
    )
    w.question_id = await db.questionnairequestion.create(
        data={
            "questionnaireId": w.questionnaire.id,
            "sectionCode": f"S-{stamp}",
            "sectionTitle": "Identity",
            "prompt": "Aadhaar number",
            "sortOrder": 1,
        }
    )
    w.question_dyes = await db.questionnairequestion.create(
        data={
            "questionnaireId": w.questionnaire.id,
            "sectionCode": f"S-{stamp}",
            "sectionTitle": "Practice",
            "prompt": "Which dyes do you use?",
            "sortOrder": 2,
        }
    )
    w.interview = await db.questionnaireinterview.create(
        data={
            "title": f"Practice interview {stamp}",
            "language": "Hindi",
            "createdById": owner,
            "questionnaireId": w.questionnaire.id,
        }
    )
    await db.questionnaireinterviewartisan.create(
        data={"interviewId": w.interview.id, "artisanId": w.consented.id}
    )
    await db.questionnaireresponse.create(
        data={
            "interviewId": w.interview.id,
            "questionId": w.question_id.id,
            "answeredById": owner,
            "answerText": w.aadhaar,
        }
    )
    await db.questionnaireresponse.create(
        data={
            "interviewId": w.interview.id,
            "questionId": w.question_dyes.id,
            "answeredById": owner,
            "answerText": "Indigo and madder.",
        }
    )
    return w


def every_record(w: SimpleNamespace) -> list[tuple[str, str]]:
    return [
        ("artisans", w.unconsented.id),
        ("artisans", w.consented.id),
        ("crafts", w.craft.id),
        ("crafts", w.other_craft.id),
        ("workshops", w.workshop.id),
        ("interviews", w.interview.id),
        ("transcripts", w.transcript_consented.id),
        ("transcripts", w.transcript_open.id),
        ("products", w.product_title.id),
        ("products", w.product_body.id),
        ("products", w.product_free.id),
        ("tools", w.tool.id),
        ("processes", w.process.id),
        ("media", w.transcript_consented.id),
        ("media", w.transcript_open.id),
    ]


async def index_world(w: SimpleNamespace) -> None:
    from app.services import search_indexer

    for entity_type, entity_id in every_record(w):
        await search_indexer.index_one(entity_type, entity_id)


async def document(entity_type: str, entity_id: str) -> dict[str, Any] | None:
    from app.core.db import db

    rows = await db.query_raw(
        # Not SELECT *: the engine cannot deserialize the tsvector (or the raw-SQL-only embedding).
        'SELECT "id", "entityType", "entityId", "title", "subtitle", "body", "place", "craftIds",'
        ' "craftCodes", "workshopIds", "artisanIds", "designWorkshopId", "audience", "shareable",'
        ' "openPath", "recordDate", "contentHash", "embeddedHash", "updatedAt"'
        ' FROM "SearchDocument" WHERE "entityType" = $1 AND "entityId" = $2',
        entity_type,
        entity_id,
    )
    return rows[0] if rows else None


def user_ns(user: Any) -> SimpleNamespace:
    return SimpleNamespace(id=user.id, email=user.email, role=user.role, name=user.name)
