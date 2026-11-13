import types
from typing import Annotated, Any, Union, get_args, get_origin

from pydantic import BaseModel, model_validator

_RULES: dict[type, tuple[frozenset[str], frozenset[str]]] = {}


def admits_none(annotation: Any) -> bool:
    if annotation is None or annotation is type(None):
        return True
    origin = get_origin(annotation)
    if origin is Annotated:
        return admits_none(get_args(annotation)[0])
    if origin is Union or origin is types.UnionType:
        return any(admits_none(arg) for arg in get_args(annotation))
    return False


def _rules(cls: type[BaseModel]) -> tuple[frozenset[str], frozenset[str]]:
    rules = _RULES.get(cls)
    if rules is None:
        nullable: set[str] = set()
        required_text: set[str] = set()
        for name, field in cls.model_fields.items():
            keys = {name} | ({field.alias} if field.alias else set())
            if admits_none(field.annotation):
                nullable |= keys
            elif field.annotation is str:
                required_text |= keys
        rules = (frozenset(nullable), frozenset(required_text))
        _RULES[cls] = rules
    return rules


def blank_text_to_none(cls: type[BaseModel], values: Any) -> Any:
    if not isinstance(values, dict):
        return values
    nullable, required_text = _rules(cls)
    changed: dict[Any, Any] | None = None
    for key, value in values.items():
        if not isinstance(value, str) or value.strip():
            continue
        if key in nullable:
            replacement = None
        elif key in required_text and value:
            replacement = ""
        else:
            continue
        if changed is None:
            changed = dict(values)
        changed[key] = replacement
    return values if changed is None else changed


class BlankTextModel(BaseModel):
    @model_validator(mode="before")
    @classmethod
    def _blank_text_to_none(cls, values: Any) -> Any:
        return blank_text_to_none(cls, values)
