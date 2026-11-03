import sys
from datetime import UTC, datetime
from itertools import count
from types import SimpleNamespace
from typing import Any

import pytest
from prisma.errors import UniqueViolationError

import app.core.db as core_db

_ids = count(1)


def _now() -> datetime:
    return datetime.now(UTC)


USER_DEFAULTS: dict[str, Any] = {
    "name": "Someone",
    "passwordHash": None,
    "avatarUrl": None,
    "role": "RESEARCHER",
    "authProvider": "LOCAL",
    "canManageQuestionnaire": False,
    "canManageCrafts": False,
    "canManageWorkshops": False,
    "canReview": False,
    "canViewProvenance": False,
    "canDownloadDataset": False,
    "twoFactorEnabled": False,
    "twoFactorSecret": None,
    "twoFactorPendingSecret": None,
    "twoFactorRecoveryCodes": [],
    "twoFactorLastStep": None,
    "twoFactorEnabledAt": None,
    "twoFactorFailedAttempts": 0,
    "twoFactorFailureWindowStartedAt": None,
    "twoFactorLockedUntil": None,
    "twoFactorLockouts": 0,
    "twoFactorRecoveryFailedAttempts": 0,
    "twoFactorRecoveryWindowStartedAt": None,
    "twoFactorRecoveryLockedUntil": None,
    "twoFactorRecoveryLockouts": 0,
    "firstPasswordCodeAttempts": 0,
    "firstPasswordCodeWindowStartedAt": None,
    "passwordSetAt": None,
    "mustChangePassword": False,
    "firstLoginAt": None,
    "lastLoginAt": None,
    "sessionsValidFrom": None,
}

PASSWORD_RESET_TOKEN_DEFAULTS: dict[str, Any] = {
    "userId": None,
    "tokenHash": None,
    "purpose": "RESET",
    "expiresAt": None,
    "usedAt": None,
    "revokedAt": None,
    "issuedById": None,
}

PASSWORD_RESET_REQUEST_DEFAULTS: dict[str, Any] = {
    "email": None,
    "accountId": None,
    "outcome": None,
    "ipAddress": None,
    "userAgent": None,
}

EMAIL_MESSAGE_DEFAULTS: dict[str, Any] = {
    "kind": None,
    "toAddress": None,
    "recipientId": None,
    "subject": None,
    "params": None,
    "sealed": None,
    "status": "QUEUED",
    "attempts": 0,
    "maxAttempts": 5,
    "runAfter": datetime(2000, 1, 1, tzinfo=UTC),
    "lockedAt": None,
    "lockedBy": None,
    "sentAt": None,
    "providerMessageId": None,
    "error": None,
}

TWO_FACTOR_CHALLENGE_DEFAULTS: dict[str, Any] = {
    "userId": None,
    "attempts": 0,
    "expiresAt": None,
}

ROSTER_DEFAULTS: dict[str, Any] = {
    "status": "PENDING",
    "grantedRole": "CROWDSOURCE_VOLUNTEER",
    "fullName": None,
    "notes": None,
    "joinedAt": None,
    "firstSeenAt": None,
    "requestCount": 1,
    "decidedAt": None,
    "decidedById": None,
}


def _matches(row: Any, where: dict[str, Any] | None) -> bool:
    for field, expected in (where or {}).items():
        if field == "OR":
            if not any(_matches(row, clause) for clause in expected):
                return False
            continue
        if field == "AND":
            if not all(_matches(row, clause) for clause in expected):
                return False
            continue
        actual = getattr(row, field, None)
        if isinstance(expected, dict):
            if "in" in expected:
                if actual not in expected["in"]:
                    return False
            elif "has" in expected:
                if expected["has"] not in (actual or []):
                    return False
            elif "contains" in expected:
                needle = str(expected["contains"]).lower()
                if needle not in str(actual or "").lower():
                    return False
            elif "not" in expected:
                if actual == expected["not"]:
                    return False
            elif "equals" in expected:
                if actual != expected["equals"]:
                    return False
            elif "lt" in expected:
                if actual is None or not actual < expected["lt"]:
                    return False
            elif "gt" in expected:
                if actual is None or not actual > expected["gt"]:
                    return False
            elif "gte" in expected:
                if actual is None or not actual >= expected["gte"]:
                    return False
            elif "lte" in expected:
                if actual is None or not actual <= expected["lte"]:
                    return False
            else:  # pragma: no cover
                raise NotImplementedError(f"fake db cannot match {field}={expected!r}")
        elif actual != expected:
            return False
    return True


class FakeTable:
    def __init__(
        self,
        defaults: dict[str, Any],
        seed: list[dict[str, Any]] | None = None,
        unique_keys: tuple[tuple[str, ...], ...] = (),
    ) -> None:
        self.defaults = defaults
        self.rows: list[SimpleNamespace] = []
        self.writes: list[tuple[str, dict[str, Any]]] = []
        self.unique_keys = unique_keys
        for values in seed or []:
            self.rows.append(self._materialise(values))

    def _refuse_duplicate(self, values: dict[str, Any], exclude_id: str | None = None) -> None:
        for key in self.unique_keys:
            probe = tuple(values.get(field) for field in key)
            if any(part is None for part in probe):
                continue
            for other in self.rows:
                if exclude_id is not None and getattr(other, "id", None) == exclude_id:
                    continue
                if tuple(getattr(other, field, None) for field in key) == probe:
                    raise UniqueViolationError(
                        {
                            "user_facing_error": {
                                "message": (
                                    f"duplicate key value violates unique constraint "
                                    f"{key}: {probe}"
                                ),
                                "meta": {},
                            }
                        }
                    )

    def _materialise(self, values: dict[str, Any]) -> SimpleNamespace:
        stamp = values.get("createdAt") or _now()
        row = {
            "id": values.get("id") or f"row-{next(_ids)}",
            **self.defaults,
            "createdAt": stamp,
            "updatedAt": stamp,
            "firstRequestedAt": stamp,
            "lastRequestedAt": stamp,
        }
        row.update(values)
        return SimpleNamespace(**row)

    async def find_unique(self, where: dict[str, Any], **_: Any) -> Any:
        return next((row for row in self.rows if _matches(row, where)), None)

    async def find_first(self, where: dict[str, Any] | None = None, **_: Any) -> Any:
        return next((row for row in self.rows if _matches(row, where)), None)

    async def find_many(
        self,
        where: dict[str, Any] | None = None,
        skip: int = 0,
        take: int | None = None,
        order: dict[str, str] | None = None,
        **_: Any,
    ) -> list[Any]:
        found = [row for row in self.rows if _matches(row, where)]
        if order:
            clauses = order if isinstance(order, list) else [order]
            for clause in reversed(clauses):
                field, direction = next(iter(clause.items()))
                found.sort(key=lambda row: getattr(row, field, None), reverse=direction == "desc")
        found = found[skip:]
        return found[:take] if take is not None else found

    async def count(self, where: dict[str, Any] | None = None, **_: Any) -> int:
        return len([row for row in self.rows if _matches(row, where)])

    async def create(self, data: dict[str, Any], **_: Any) -> Any:
        row = self._materialise(dict(data))
        self._refuse_duplicate(vars(row))
        self.rows.append(row)
        self.writes.append(("create", dict(data)))
        return row

    async def create_many(self, data: list[dict[str, Any]], **_: Any) -> int:
        for values in data:
            await self.create(values)
        return len(data)

    async def update(self, where: dict[str, Any], data: dict[str, Any], **_: Any) -> Any:
        row = await self.find_unique(where)
        if row is None:
            return None
        candidate = {**vars(row), **data}
        self._refuse_duplicate(candidate, exclude_id=getattr(row, "id", None))
        for field, value in data.items():
            setattr(row, field, value)
        row.updatedAt = _now()
        self.writes.append(("update", dict(data)))
        return row

    async def update_many(self, where: dict[str, Any], data: dict[str, Any], **_: Any) -> int:
        touched = [row for row in self.rows if _matches(row, where)]
        for row in touched:
            for field, value in data.items():
                if isinstance(value, dict) and "increment" in value:
                    value = (getattr(row, field, 0) or 0) + value["increment"]
                elif isinstance(value, dict) and "decrement" in value:
                    value = (getattr(row, field, 0) or 0) - value["decrement"]
                setattr(row, field, value)
            row.updatedAt = _now()
        self.writes.append(("update_many", dict(data)))
        return len(touched)

    async def delete(self, where: dict[str, Any], **_: Any) -> Any:
        row = await self.find_unique(where)
        if row is not None:
            self.rows.remove(row)
            self.writes.append(("delete", dict(where)))
        return row

    async def delete_many(self, where: dict[str, Any] | None = None, **_: Any) -> int:
        doomed = [row for row in self.rows if _matches(row, where)]
        for row in doomed:
            self.rows.remove(row)
        self.writes.append(("delete_many", dict(where or {})))
        return len(doomed)


QUESTIONNAIRE_DEFAULTS: dict[str, Any] = {
    "title": "An instrument",
    "description": None,
    "isActive": True,
    "isDefault": False,
    "sortOrder": 1,
    "createdById": None,
}

SECTION_DEFAULTS: dict[str, Any] = {
    "questionnaireId": None,
    "code": "A",
    "title": "A section",
    "sortOrder": 1,
    "isActive": True,
}

QUESTION_DEFAULTS: dict[str, Any] = {
    "questionnaireId": None,
    "sectionId": None,
    "sectionCode": "A",
    "sectionTitle": "A section",
    "prompt": "A prompt",
    "sortOrder": 1,
    "isActive": True,
}

RESPONSE_DEFAULTS: dict[str, Any] = {
    "interviewId": None,
    "questionId": None,
    "answerText": None,
    "notes": None,
    "answeredById": None,
}

INTERVIEW_DEFAULTS: dict[str, Any] = {
    "title": "An interview",
    "questionnaireId": None,
    "artisanSetKey": None,
    "workshopId": None,
    "status": "PENDING",
    "createdById": None,
}


class FakeDb:
    def __init__(
        self,
        users: list[dict[str, Any]] | None = None,
        roster: list[dict[str, Any]] | None = None,
        questionnaires: list[dict[str, Any]] | None = None,
        sections: list[dict[str, Any]] | None = None,
        questions: list[dict[str, Any]] | None = None,
        responses: list[dict[str, Any]] | None = None,
        interviews: list[dict[str, Any]] | None = None,
    ) -> None:
        self.user = FakeTable(USER_DEFAULTS, users)
        self.twofactorchallenge = FakeTable(TWO_FACTOR_CHALLENGE_DEFAULTS)
        self.passwordresettoken = FakeTable(
            PASSWORD_RESET_TOKEN_DEFAULTS, unique_keys=(("tokenHash",),)
        )
        self.passwordresetrequest = FakeTable(PASSWORD_RESET_REQUEST_DEFAULTS)
        self.emailmessage = FakeTable(EMAIL_MESSAGE_DEFAULTS)
        self.accessroster = FakeTable(ROSTER_DEFAULTS, roster)
        self.questionnaire = FakeTable(
            QUESTIONNAIRE_DEFAULTS, questionnaires, unique_keys=(("title",),)
        )
        self.questionnairesection = FakeTable(
            SECTION_DEFAULTS,
            sections,
            unique_keys=(("questionnaireId", "code"), ("questionnaireId", "sortOrder")),
        )
        self.questionnairequestion = FakeTable(QUESTION_DEFAULTS, questions)
        self.questionnaireresponse = FakeTable(
            RESPONSE_DEFAULTS, responses, unique_keys=(("interviewId", "questionId"),)
        )
        self.questionnaireinterview = FakeTable(
            INTERVIEW_DEFAULTS, interviews, unique_keys=(("questionnaireId", "artisanSetKey"),)
        )

    def tx(self, **_: Any) -> "_FakeTransaction":
        return _FakeTransaction(self)

    def __getattr__(self, name: str) -> Any:  # pragma: no cover
        raise AssertionError(
            f"The sign-in gate touched db.{name}, which this fake does not model. If that is "
            f"deliberate, teach the fake; if it is not, the gate just grew a dependency."
        )


class _FakeTransaction:
    def __init__(self, fake: FakeDb) -> None:
        self.fake = fake
        self.snapshot: dict[str, list[SimpleNamespace]] = {}

    async def __aenter__(self) -> FakeDb:
        for name, table in vars(self.fake).items():
            if isinstance(table, FakeTable):
                self.snapshot[name] = [SimpleNamespace(**vars(row)) for row in table.rows]
        return self.fake

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> bool:
        if exc_type is not None:
            for name, rows in self.snapshot.items():
                getattr(self.fake, name).rows = rows
        return False


class PassThroughTx:
    def tx(self, **_: Any) -> Any:
        fake = self

        class _Block:
            async def __aenter__(self) -> Any:
                return fake

            async def __aexit__(self, *_exc: Any) -> bool:
                return False

        return _Block()

    async def query_raw(self, *_args: Any, **_kwargs: Any) -> list[dict[str, Any]]:
        return []

    async def execute_raw(self, *_args: Any, **_kwargs: Any) -> int:
        return 0


def install(monkeypatch: pytest.MonkeyPatch, fake: FakeDb) -> FakeDb:
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", fake)
    return fake
