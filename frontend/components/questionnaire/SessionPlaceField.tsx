"use client";

import { useCallback, useState } from "react";
import { LocateFixed, MapPinned } from "lucide-react";

import { MapPointPicker, mapPickerAvailable } from "@/components/forms/MapPointPicker";
import { PlaceSearchBox } from "@/components/forms/PlaceSearchBox";
import { ButtonRow } from "@/components/ui/ButtonRow";
import type { PlaceHit } from "@/lib/placeSearch";
import type { SessionLocation } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

export type SessionPlace = { place: string; location: SessionLocation | null };

const PLACE_MAX = 200;

export function placeFromHit(hit: Pick<PlaceHit, "name" | "context">): string {
  const parts = [hit.name, ...hit.context.split(",")]
    .map((part) => part.trim())
    .filter((part, index, all) => part && part.toLowerCase() !== "india" && all.findIndex((p) => p.toLowerCase() === part.toLowerCase()) === index);
  return parts.slice(0, 3).join(", ").slice(0, PLACE_MAX);
}

export function locationFromHit(hit: PlaceHit): SessionLocation {
  const full = [hit.name, hit.context].filter(Boolean).join(", ");
  return { latitude: hit.lat, longitude: hit.lon, accuracy: null, placeName: full.slice(0, PLACE_MAX) || null };
}

export function pinnedLine(location: SessionLocation): string {
  return qs("placePinned", { lat: location.latitude.toFixed(5), lon: location.longitude.toFixed(5) });
}

export function SessionPlaceField({
  id,
  value,
  onChange,
  deviceLocation = false,
  disabled
}: {
  id: string;
  value: SessionPlace;
  onChange: (next: SessionPlace) => void;
  deviceLocation?: boolean;
  disabled?: boolean;
}) {
  const [mapOpen, setMapOpen] = useState(false);
  const [gps, setGps] = useState<"idle" | "finding" | "unavailable">("idle");
  const { place, location } = value;

  const proximity = useCallback(() => (location ? { lon: location.longitude, lat: location.latitude } : null), [location]);

  function captureDevice() {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setGps("unavailable");
      return;
    }
    setGps("finding");
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setGps("idle");
        onChange({
          place,
          location: {
            latitude: position.coords.latitude,
            longitude: position.coords.longitude,
            accuracy: Number.isFinite(position.coords.accuracy) ? position.coords.accuracy : null,
            altitude: position.coords.altitude ?? null,
            placeName: place.trim() || null
          }
        });
      },
      () => setGps("unavailable"),
      { enableHighAccuracy: true, timeout: 20_000, maximumAge: 60_000 }
    );
  }

  return (
    <div className="grid min-w-0 gap-2" data-session-place>
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
        onChange={(e) => onChange({ place: e.target.value, location })}
      />
      <PlaceSearchBox
        label={qs("placeSearch")}
        hint={qs("placeSearchHint")}
        disabled={disabled}
        getProximity={proximity}
        onChoose={(hit) => onChange({ place: placeFromHit(hit), location: locationFromHit(hit) })}
      />
      <ButtonRow align="start" size="sm">
        {deviceLocation ? (
          <button type="button" className="field-button-secondary" disabled={disabled || gps === "finding"} onClick={captureDevice} data-session-place-gps>
            <LocateFixed className="h-4 w-4" aria-hidden />
            {gps === "finding" ? qs("locationFinding") : qs("useMyLocation")}
          </button>
        ) : null}
        {mapPickerAvailable() ? (
          <button type="button" className="field-button-secondary" disabled={disabled} aria-expanded={mapOpen} onClick={() => setMapOpen((open) => !open)} data-session-place-map>
            <MapPinned className="h-4 w-4" aria-hidden />
            {mapOpen ? qs("hideMap") : qs("pickOnMap")}
          </button>
        ) : null}
      </ButtonRow>
      {gps === "unavailable" ? <p className="text-xs text-ink-600">{qs("locationUnavailable")}</p> : null}
      {mapOpen ? (
        <MapPointPicker
          lat={location?.latitude ?? null}
          lon={location?.longitude ?? null}
          ariaLabel={qs("placeMapLabel")}
          onPick={(lat, lon) => onChange({ place, location: { latitude: Number(lat), longitude: Number(lon), accuracy: null, placeName: place.trim() || null } })}
        />
      ) : null}
      {location ? (
        <p className="flex flex-wrap items-center gap-2 text-xs text-ink-600" data-session-place-pin data-device-location={location.accuracy != null ? "saved" : undefined}>
          <span>{location.accuracy != null ? qs("locationCaptured", { metres: Math.max(1, Math.round(location.accuracy)) }) : pinnedLine(location)}</span>
          <button type="button" className="font-medium text-purple-700 underline dark:text-purple-300" disabled={disabled} onClick={() => onChange({ place, location: null })}>
            {qs("locationRemove")}
          </button>
        </p>
      ) : null}
    </div>
  );
}
