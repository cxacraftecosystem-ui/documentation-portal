from typing import Any

CRAFT_NAME_REQUIRED = "Choose the craft, or type the craft's name."
ARTISAN_NAME_REQUIRED = "Choose the artisan, or type the artisan's name."
PLACE_REQUIRED = "Choose the artisan, or type the place."

LINKED_CRAFT_UNNAMED = "The chosen craft has no name on file. Type the craft's name."
LINKED_ARTISAN_UNNAMED = "The chosen artisan has no name on file. Type the artisan's name."
LINKED_ARTISAN_PLACELESS = "The chosen artisan has no place on file. Type the place."

NameRule = tuple[str, tuple[str, ...], str]

CRAFT_RULE: NameRule = ("craftName", ("craftId", "craftIds"), CRAFT_NAME_REQUIRED)
ARTISAN_RULE: NameRule = ("artisanName", ("artisanId", "artisanIds"), ARTISAN_NAME_REQUIRED)
PLACE_RULE: NameRule = ("place", ("artisanId", "artisanIds"), PLACE_REQUIRED)

LINKED_RECORD_NAME_RULES: tuple[NameRule, ...] = (CRAFT_RULE, ARTISAN_RULE, PLACE_RULE)


def given(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, set, frozenset)):
        return any(given(item) for item in value)
    return True


def missing_name_messages(model: Any, rules: tuple[NameRule, ...]) -> list[str]:
    missing: list[str] = []
    for name, links, message in rules:
        if given(getattr(model, name, None)):
            continue
        if any(given(getattr(model, link, None)) for link in links):
            continue
        if message not in missing:
            missing.append(message)
    return missing


def require_names_or_links(model: Any, rules: tuple[NameRule, ...]) -> Any:
    missing = missing_name_messages(model, rules)
    if missing:
        raise ValueError(" ".join(missing))
    return model


def require_linked_record_names(model: Any) -> Any:
    return require_names_or_links(model, LINKED_RECORD_NAME_RULES)
