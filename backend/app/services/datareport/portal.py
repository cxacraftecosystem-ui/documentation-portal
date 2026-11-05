from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

PortalKey = Literal["designer", "fieldRepository"]


@dataclass(frozen=True)
class PortalConfig:
    key: PortalKey
    name: str
    short: str
    brand: str = "762CB1"
    open_paths: dict[str, str] = field(default_factory=dict)
    status_filter: bool = False


_COMMON_OPEN = {
    "artisan": "/artisans/{id}",
    "craft": "/crafts/{id}",
    "workshop": "/workshops/{id}",
    "interview": "/questionnaire/sessions/{id}/view",
    "product": "/products/{id}",
    "tool": "/tools/{id}",
    "process": "/processes/{id}",
    "media": "/media/{id}",
}

DESIGNER = PortalConfig(
    key="designer",
    name="Design Prototype Workshop",
    short="DesignWorkshop",
    open_paths={**_COMMON_OPEN, "designWorkshop": "/design-workshops/{id}"},
)

FIELD_REPOSITORY = PortalConfig(
    key="fieldRepository",
    name="Field Repository",
    short="FieldRepository",
    open_paths=dict(_COMMON_OPEN),
    status_filter=True,
)

BY_KEY: dict[str, PortalConfig] = {DESIGNER.key: DESIGNER, FIELD_REPOSITORY.key: FIELD_REPOSITORY}


def current() -> PortalConfig:
    from app.services import naming_portal

    return FIELD_REPOSITORY if naming_portal.PORTAL == "FieldRepository" else DESIGNER


def web_base() -> str:
    from app.core.config import get_settings

    return str(get_settings().next_public_app_url or "").rstrip("/")
