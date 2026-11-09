"use client";

import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { locationFromForm } from "@/lib/forms";
import type { SessionLocation } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

const PLACE_MAX = 200;

export function sessionLocationFromForm(form: FormData): SessionLocation | null {
  const read = locationFromForm(form);
  if (!read) return null;
  const out: SessionLocation = { latitude: read.latitude, longitude: read.longitude };
  for (const [key, value] of Object.entries(read)) {
    if (value !== undefined && value !== null && value !== "") (out as Record<string, unknown>)[key] = value;
  }
  return out;
}

export function locationInitial(location: SessionLocation | null | undefined): LocationInitialValues {
  if (!location) return {};
  return {
    latitude: location.latitude,
    longitude: location.longitude,
    altitude: location.altitude ?? null,
    accuracy: location.accuracy ?? null,
    capturedAt: location.capturedAt ?? null,
    placeName: location.placeName ?? null,
    address: location.address ?? null,
    state: location.state ?? null,
    district: location.district ?? null,
    village: location.village ?? null,
    pincode: location.pincode ?? null,
    subjectLatitude: location.subjectLatitude ?? null,
    subjectLongitude: location.subjectLongitude ?? null
  };
}

export function placeFromLocation(place: string, location: SessionLocation | null): string {
  const typed = place.trim();
  if (typed || !location) return typed;
  return [location.village, location.district, location.state].filter((part) => part && String(part).trim()).slice(0, 2).join(", ").slice(0, PLACE_MAX);
}

export function locationChanged(before: SessionLocation | null, after: SessionLocation | null): boolean {
  if (!before || !after) return before !== after;
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  return [...keys].some((key) => String((before as Record<string, unknown>)[key] ?? "") !== String((after as Record<string, unknown>)[key] ?? ""));
}

export function SessionPlaceField({
  id,
  place,
  onPlace,
  saved,
  editing = false,
  disabled,
  onDirty
}: {
  id: string;
  place: string;
  onPlace: (next: string) => void;
  saved?: SessionLocation | null;
  editing?: boolean;
  disabled?: boolean;
  onDirty?: () => void;
}) {
  return (
    <div className="grid min-w-0 gap-3" data-session-place>
      <div className="grid gap-1">
        <label htmlFor={id} className="field-label">
          {qs("fieldPlace")}
        </label>
        <input
          id={id}
          className="field-input"
          value={place}
          maxLength={PLACE_MAX}
          autoComplete="off"
          disabled={disabled}
          onChange={(e) => onPlace(e.target.value)}
        />
      </div>
      <LocationFields initial={editing ? locationInitial(saved) : undefined} required={false} subjectLabel="this session" onDirty={onDirty} />
    </div>
  );
}
