from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any

UNRECORDED = "UNRECORDED"

MARKER_BODY_KEY = "measurementMethods"


class MeasurementMethod(str, Enum):
    TYPED = "TYPED"

    PHOTO_GEOMETRY = "PHOTO_GEOMETRY"

    VISION_MODEL = "VISION_MODEL"

    UNRECORDED = UNRECORDED

    @property
    def requires_acceptance(self) -> bool:
        return self is MeasurementMethod.VISION_MODEL

    @property
    def reproducible(self) -> bool | None:
        if self is MeasurementMethod.PHOTO_GEOMETRY:
            return True
        if self is MeasurementMethod.VISION_MODEL:
            return False
        return None


TECHNIQUE_SCALE = "SCALE"
TECHNIQUE_RECTIFIED = "RECTIFIED"

GEOMETRY_TECHNIQUES: frozenset[str] = frozenset({TECHNIQUE_SCALE, TECHNIQUE_RECTIFIED})

DIMENSION_FIELDS: frozenset[str] = frozenset({"lengthInches", "breadthInches", "heightInches"})

PROVIDER_CONFIDENCE_KEY = "confidence"

MARKER_CONFIDENCE_KEY = "selfReportedConfidence"


def self_reported_confidence(analysis: Any, *, key: str = PROVIDER_CONFIDENCE_KEY) -> float | None:
    if not isinstance(analysis, Mapping):
        return None
    raw = analysis.get(key)
    if isinstance(raw, str):
        try:
            raw = float(raw.strip())
        except ValueError:
            return None
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return None
    value = float(raw)
    if not math.isfinite(value) or not (0.0 <= value <= 1.0):
        return None
    return value


@dataclass(frozen=True, slots=True)
class MeasurementProvenance:
    method: MeasurementMethod
    provider: str = UNRECORDED
    model_id: str = UNRECORDED
    self_reported_confidence: float | None = None
    technique: str | None = None

    @property
    def requires_acceptance(self) -> bool:
        return self.method.requires_acceptance

    @property
    def reproducible(self) -> bool | None:
        return self.method.reproducible

    def payload(self) -> dict[str, Any]:
        return {
            "method": self.method.value,
            "provider": self.provider,
            "modelId": self.model_id,
            MARKER_CONFIDENCE_KEY: self.self_reported_confidence,
            "confidenceIsCalibrated": False,
            "requiresAcceptance": self.requires_acceptance,
            "methodMarker": self.marker(),
        }

    def marker(self) -> dict[str, Any]:
        marker: dict[str, Any] = {"method": self.method.value}
        if self.provider != UNRECORDED:
            marker["provider"] = self.provider
        if self.model_id != UNRECORDED:
            marker["modelId"] = self.model_id
        if self.self_reported_confidence is not None:
            marker[MARKER_CONFIDENCE_KEY] = self.self_reported_confidence
        if self.technique:
            marker["technique"] = self.technique
        return marker

    def stamp(self) -> dict[str, Any]:
        stamp: dict[str, Any] = {"method": self.method.value}
        if self.provider != UNRECORDED:
            stamp["methodProvider"] = self.provider
        if self.model_id != UNRECORDED:
            stamp["methodModelId"] = self.model_id
        if self.self_reported_confidence is not None:
            stamp["methodConfidence"] = self.self_reported_confidence
        if self.technique:
            stamp["methodTechnique"] = self.technique
        return stamp


UNRECORDED_PROVENANCE = MeasurementProvenance(method=MeasurementMethod.UNRECORDED)


def vision_model_provenance(analysis: Any, *, provider: str, model_id: str) -> MeasurementProvenance:
    return MeasurementProvenance(
        method=MeasurementMethod.VISION_MODEL,
        provider=(provider or "").strip() or UNRECORDED,
        model_id=(model_id or "").strip() or UNRECORDED,
        self_reported_confidence=self_reported_confidence(analysis),
    )


def provenance_of_marker(marker: Any) -> MeasurementProvenance:
    if not isinstance(marker, Mapping):
        return UNRECORDED_PROVENANCE
    raw_method = marker.get("method")
    if not isinstance(raw_method, str):
        return UNRECORDED_PROVENANCE
    try:
        method = MeasurementMethod(raw_method)
    except ValueError:
        return UNRECORDED_PROVENANCE

    def _text(key: str) -> str:
        value = marker.get(key)
        return value.strip() if isinstance(value, str) and value.strip() else UNRECORDED

    technique = marker.get("technique")
    return MeasurementProvenance(
        method=method,
        provider=_text("provider"),
        model_id=_text("modelId"),
        self_reported_confidence=self_reported_confidence(marker, key=MARKER_CONFIDENCE_KEY),
        technique=technique if technique in GEOMETRY_TECHNIQUES else None,
    )


MARKER_FACT_KEYS: tuple[str, ...] = (
    "method",
    "provider",
    "modelId",
    MARKER_CONFIDENCE_KEY,
    "technique",
)


def marker_body_problems(markers: Any, *, present_fields: Any) -> list[str]:
    allowed_fields = ", ".join(sorted(DIMENSION_FIELDS))
    if not isinstance(markers, Mapping):
        return [
            (
                f"{MARKER_BODY_KEY} must be an object keyed by dimension name, such as "
                '{"lengthInches": {"method": "TYPED"}}.'
            )
        ]

    present = {field for field in (present_fields or ()) if field in DIMENSION_FIELDS}
    known_methods = {m.value for m in MeasurementMethod}
    problems: list[str] = []

    for field in sorted(markers):
        marker = markers[field]
        where = f"{MARKER_BODY_KEY}.{field}"

        if field not in DIMENSION_FIELDS:
            problems.append(
                f"{MARKER_BODY_KEY} names {field}, which is not a documented dimension. A "
                f"measurement method may only describe {allowed_fields}."
            )
            continue

        if not isinstance(marker, Mapping):
            problems.append(
                f"{where} must be an object stating a method, such as "
                '{"method": "TYPED"} for a number somebody typed.'
            )
            continue

        raw_method = marker.get("method")
        if not isinstance(raw_method, str) or raw_method not in known_methods:
            problems.append(
                f"{where} states a measurement method that wasn't recognised: {raw_method!r}. Update the app. "
                f"Send one of {', '.join(sorted(known_methods))}."
            )
            continue
        method = MeasurementMethod(raw_method)

        if field not in present:
            problems.append(
                f"{where} states how {field} was measured, but this request sends no value for "
                f"{field}. Send the measurement in the same request, or leave the method out."
            )
            continue

        technique = marker.get("technique")
        if technique is not None:
            if technique not in GEOMETRY_TECHNIQUES:
                problems.append(
                    f"{where} names a photo-measurement technique that wasn't recognised: "
                    f"{technique!r}. Send {' or '.join(sorted(GEOMETRY_TECHNIQUES))}, or leave "
                    f"technique out."
                )
            elif method is not MeasurementMethod.PHOTO_GEOMETRY:
                problems.append(
                    f"{where} carries a technique on a {method.value} reading, and a technique says "
                    f"how a PHOTO_GEOMETRY measurement was derived. Leave it out unless the method "
                    f"is PHOTO_GEOMETRY."
                )

        if marker.get(MARKER_CONFIDENCE_KEY) is not None and (
            self_reported_confidence(marker, key=MARKER_CONFIDENCE_KEY) is None
        ):
            problems.append(
                f"{where} carries a {MARKER_CONFIDENCE_KEY} that can't be read: "
                f"{marker.get(MARKER_CONFIDENCE_KEY)!r}. Send a number from 0 to 1, or leave the "
                f"key out."
            )

    return problems


def method_stamps(markers: Any, *, fields: Any = None) -> dict[str, dict[str, Any]]:
    sent = markers if isinstance(markers, Mapping) else {}
    if fields is None:
        touched = {field for field in sent if field in DIMENSION_FIELDS}
    else:
        touched = {field for field in fields if field in DIMENSION_FIELDS}
    return {field: provenance_of_marker(sent.get(field)).stamp() for field in sorted(touched)}
