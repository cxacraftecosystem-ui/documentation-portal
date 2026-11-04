from typing import Any

from fastapi import HTTPException, status

FORM_CONTRACT_KEY = "formContract"

DATES_REQUIRED_CONTRACT = 2

REQUIRED_DATES: tuple[tuple[str, str], ...] = (
    ("dateOfBirth", "date of birth"),
    ("craftStartDate", "craft start date"),
)


REQUIRED_ANSWERS: tuple[tuple[str, str], ...] = (
    ("pehchanCardAvailable", "Answer whether the artisan has a Pehchan card."),
)


def take_form_contract(data: dict[str, Any]) -> int | None:
    value = data.pop(FORM_CONTRACT_KEY, None)
    return value if isinstance(value, int) else None


def missing_required_dates(data: dict[str, Any], stored: Any | None = None) -> list[str]:
    missing: list[str] = []
    for field, words in REQUIRED_DATES:
        if field in data:
            value = data[field]
        else:
            value = getattr(stored, field, None) if stored is not None else None
        if value is None or (isinstance(value, str) and not value.strip()):
            missing.append(words)
    return missing


def refusal_sentence(missing: list[str]) -> str:
    return "Enter the " + " and the ".join(missing) + "."


def missing_required_answers(data: dict[str, Any], stored: Any | None = None) -> list[str]:
    missing: list[str] = []
    for field, sentence in REQUIRED_ANSWERS:
        if field in data:
            value = data[field]
        else:
            value = getattr(stored, field, None) if stored is not None else None
        if value is None or (isinstance(value, str) and not value.strip()):
            missing.append(sentence)
    return missing


def assert_form_contract(
    contract: int | None, data: dict[str, Any], stored: Any | None = None
) -> None:
    assert_required_dates(contract, data, stored)
    if contract is None or contract < DATES_REQUIRED_CONTRACT:
        return
    missing = missing_required_answers(data, stored)
    if missing:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=missing[0],
        )


def assert_required_dates(
    contract: int | None, data: dict[str, Any], stored: Any | None = None
) -> None:
    if contract is None or contract < DATES_REQUIRED_CONTRACT:
        return
    missing = missing_required_dates(data, stored)
    if missing:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=refusal_sentence(missing),
        )
