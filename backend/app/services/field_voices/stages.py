from __future__ import annotations

from dataclasses import dataclass

__all__ = ["DESIGN_STAGE_CREDITS", "StagePlacement"]

DESIGN_STAGE_CREDITS = range(10, 18)


@dataclass(frozen=True)
class StagePlacement:
    media_id: str
    design_workshop_id: str
    stage_key: str
    stage_title: str
    stage_number: int
    entity_key: str
    entity_title: str
    row_id: str
    row_label: str | None
    field_key: str
    field_label: str
    listed: bool
    caption: str | None = None
    typed_transcript: str | None = None
    artisan_ids: tuple[str, ...] = ()
    product_ids: tuple[str, ...] = ()
    tool_ids: tuple[str, ...] = ()
    prototype_id: str | None = None
    pictures_designers: bool = False

    @property
    def credits_designers(self) -> bool:
        return self.stage_number in DESIGN_STAGE_CREDITS or self.pictures_designers
