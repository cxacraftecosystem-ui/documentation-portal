"use client";

import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { LocateFixed, MapPinned } from "lucide-react";

import { Field, Select, TextInput } from "@/components/FormControls";
import { apiFetch } from "@/lib/api";
import type { AddressReference } from "@/lib/types";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

/**
 * The canonical state list, fetched once per page load from the API that validates against it
 * (`GET /reference/address`, backed by backend/app/services/address.py).
 *
 * Deliberately NOT a constant in this file. A list hard-coded here would be a second copy of the
 * server's, and the day the two disagree is the day a researcher picks a state the API refuses. The
 * payload is a pure constant server-side, so one request per page load is the whole cost; a failure
 * is not cached, so the next form that mounts asks again.
 */
let addressReferenceRequest: Promise<AddressReference> | null = null;

function loadAddressReference(): Promise<AddressReference> {
  addressReferenceRequest ??= apiFetch<AddressReference>("/reference/address").catch((error) => {
    addressReferenceRequest = null;
    throw error;
  });
  return addressReferenceRequest;
}

/** Indian PIN codes are six digits and never begin with 0 (the first digit is the postal zone). */
const PINCODE_LENGTH = 6;

/**
 * The reason `value` is not a usable pincode, or null when it is fine (blank included — the field is
 * optional).
 *
 * Same three checks, in the same order and with the same sentences, as `pincode_error` in
 * backend/app/services/address.py — so the researcher reads one message whether it was caught here
 * or by the API, and reads it before the round trip rather than as a 422 after it.
 */
export function pincodeValidationError(value: string | null | undefined): string | null {
  const digits = (value ?? "").trim();
  if (!digits) return null;
  if (!/^[0-9]+$/.test(digits)) return "Pincode must be 6 digits — remove any letters or symbols.";
  if (digits.length !== PINCODE_LENGTH) {
    return `Pincode must be exactly 6 digits (this one has ${digits.length}).`;
  }
  if (digits[0] === "0") return "Pincodes never start with 0 — please re-check the first digit.";
  return null;
}

/** Initial location values for edit forms (matches the API's Location payload). */
export type LocationInitialValues = {
  latitude?: number | string | null;
  longitude?: number | string | null;
  altitude?: number | string | null;
  accuracy?: number | string | null;
  placeName?: string | null;
  address?: string | null;
  /** Canonical state/union-territory name and bare 6-digit pincode — columns on Location. */
  state?: string | null;
  pincode?: string | null;
};

function asText(value: number | string | null | undefined) {
  return value === null || value === undefined ? "" : String(value);
}

function comparableName(value: string) {
  return value.toLowerCase().replace(/[^a-z]/g, "");
}

/**
 * The entry of `states` that `text` names, or "" when nothing in the list matches.
 *
 * The geocoder's wording is not the register's ("NCT of Delhi", "Daman and Diu"), so an exact match
 * is tried first and a containment match second — long enough on either side that a short name like
 * Goa cannot be swallowed by an unrelated word. Returning "" rather than the raw text is deliberate:
 * the list is closed, so a name the API would reject is worse than no suggestion at all, and a value
 * the dropdown cannot show is a value the researcher cannot see, let alone correct.
 */
function matchIndianState(text: string, states: readonly string[]): string {
  const wanted = comparableName(text);
  if (!wanted) return "";
  const exact = states.find((entry) => comparableName(entry) === wanted);
  if (exact) return exact;
  const loose = states.find((entry) => {
    const name = comparableName(entry);
    if (name.length >= 5 && wanted.includes(name)) return true;
    return wanted.length >= 5 && name.includes(wanted);
  });
  return loose ?? "";
}

/** A geocoded postal code, kept only when it satisfies the same rule the researcher is held to. */
function usablePincode(text: string): string {
  const digits = text.replace(/\D/g, "");
  return pincodeValidationError(digits) ? "" : digits;
}

/** MapTiler geocoding features: the kind of place is the part of `id` before the dot. */
type GeocodeFeature = {
  id?: string;
  text?: string;
  place_type?: string[];
  context?: Array<{ id?: string; text?: string }>;
};

/** The name of the first `kind` ("region", "postal_code") found, most specific feature first. */
function pickPlace(features: GeocodeFeature[], kind: string): string {
  for (const feature of features) {
    const kinds = feature.place_type ?? (feature.id ? [feature.id.split(".")[0]] : []);
    if (kinds.includes(kind) && feature.text) return feature.text;
    for (const entry of feature.context ?? []) {
      if (entry.id?.split(".")[0] === kind && entry.text) return entry.text;
    }
  }
  return "";
}

/** Ask MapTiler what is at these coordinates. Throws on anything the caller should ignore. */
async function reverseGeocode(lat: string, lng: string, signal: AbortSignal) {
  const response = await fetch(
    `https://api.maptiler.com/geocoding/${encodeURIComponent(lng)},${encodeURIComponent(lat)}.json` +
      `?key=${maptilerKey}&language=en`,
    { signal }
  );
  if (!response.ok) throw new Error(`Geocoder returned ${response.status}`);
  const body = (await response.json()) as { features?: GeocodeFeature[] };
  const features = body.features ?? [];
  return { region: pickPlace(features, "region"), postalCode: pickPlace(features, "postal_code") };
}

/**
 * What a point suggests for the two address fields, both resolved against the API's own rules: the
 * region against the served state list (a name it does not hold is dropped rather than offered), the
 * postal code against the pincode format. A reference list that fails to load costs the state
 * suggestion only — the pincode is still worth having.
 */
async function suggestAddress(lat: string, lng: string, signal: AbortSignal) {
  const [place, reference] = await Promise.all([
    reverseGeocode(lat, lng, signal),
    loadAddressReference().catch(() => null)
  ]);
  return {
    state: reference ? matchIndianState(place.region, reference.statesAndUnionTerritories) : "",
    pincode: usablePincode(place.postalCode)
  };
}

/**
 * "Location" card (Android `LocationEditor` parity): two centered actions — "Use current GPS"
 * (primary) and "Pick on map" (secondary) — with the coordinate/place fields below. Pass `initial`
 * on edit forms (the record's stored location, or null when it has none): the fields are pre-filled
 * and the device location is NOT auto-captured on mount — capture then only happens on the explicit
 * "Use current GPS" button. Omitting `initial` (new-record forms) keeps the silent auto-capture on
 * mount.
 *
 * State and pincode are the postal half of the same answer, so they live in this card and are
 * SUGGESTED by reverse-geocoding whenever a pin is dropped or a fix is taken — see `applyPrefill`
 * for why they are only ever suggested.
 */
export function LocationFields({
  initial,
  onDirty
}: {
  initial?: LocationInitialValues | null;
  /** Raise the surrounding form's unsaved-changes flag; see the state dropdown below for why. */
  onDirty?: () => void;
}) {
  const mapRef = useRef<HTMLDivElement | null>(null);
  const mapInstance = useRef<maplibregl.Map | null>(null);
  const marker = useRef<maplibregl.Marker | null>(null);
  const [latitude, setLatitude] = useState(asText(initial?.latitude));
  const [longitude, setLongitude] = useState(asText(initial?.longitude));
  const [altitude, setAltitude] = useState(asText(initial?.altitude));
  const [accuracy, setAccuracy] = useState(asText(initial?.accuracy));
  const [placeName, setPlaceName] = useState(initial?.placeName ?? "");
  const [address, setAddress] = useState(initial?.address ?? "");
  const [stateName, setStateName] = useState(initial?.state ?? "");
  const [pincode, setPincode] = useState(() => (initial?.pincode ?? "").replace(/\D/g, "").slice(0, PINCODE_LENGTH));
  const [states, setStates] = useState<readonly string[]>([]);
  // Raised the first time the browser refuses the form because of the pincode; until then a
  // half-typed code is not narrated back as an error (Aadhaar field parity).
  const [pincodeProblemShown, setPincodeProblemShown] = useState(false);
  const pincodeRef = useRef<HTMLInputElement>(null);
  const [mapOpen, setMapOpen] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  // A value that came from a human — typed here, or stored on the record — is never overwritten by
  // the geocoder. Reverse geocoding is wrong often enough in rural India that stomping a correction
  // would be worse than not suggesting anything at all.
  const humanSet = useRef({ state: Boolean(initial?.state), pincode: Boolean(initial?.pincode) });
  const lookup = useRef<AbortController | null>(null);

  const pincodeProblem = pincodeValidationError(pincode);

  useEffect(() => {
    let live = true;
    loadAddressReference()
      .then((reference) => {
        if (live) setStates(reference.statesAndUnionTerritories);
      })
      .catch(() => {
        // Offline, or the endpoint is unhappy. The stored value is still offered below and still
        // saves; a hard-coded stand-in list is what this fetch exists to avoid.
      });
    return () => {
      live = false;
    };
  }, []);

  /**
   * The dropdown's options: the served list, with the record's own value kept at the front until
   * that list arrives. Without it an edit form would show "Select state" over a state the record
   * really holds, which reads as "not answered" and invites the researcher to answer it again.
   */
  const stateOptions = stateName && !states.includes(stateName) ? [stateName, ...states] : states;

  // Native constraint validation is what actually stops the submit, so the browser scrolls to this
  // box and names the problem rather than the save failing somewhere out of sight.
  useEffect(() => {
    pincodeRef.current?.setCustomValidity(pincodeProblem ?? "");
  }, [pincodeProblem]);

  /**
   * Suggest the state and pincode for a freshly picked point.
   *
   * Prefill, never lock, and never block: a geocoder that is down, rate-limited, or simply wrong
   * about a hamlet must not be able to stop a record being saved, so a failure is silent and leaves
   * both fields exactly as they were — ready for manual entry.
   */
  function applyPrefill(lat: string, lng: string) {
    if (!maptilerKey) return;
    // The previous point's answer is no longer the answer; cancel it so a slow reply cannot land on
    // top of a newer pin.
    lookup.current?.abort();
    const controller = new AbortController();
    lookup.current = controller;
    suggestAddress(lat, lng, controller.signal)
      .then(({ state, pincode: code }) => {
        if (controller.signal.aborted) return;
        if (state && !humanSet.current.state) {
          setStateName(state);
          onDirty?.();
        }
        if (code && !humanSet.current.pincode) {
          setPincode(code);
          setPincodeProblemShown(false);
          onDirty?.();
        }
      })
      .catch(() => undefined);
  }

  useEffect(() => () => lookup.current?.abort(), []);

  function syncMapMarker(lat: string, lng: string, zoom = 15) {
    if (!mapInstance.current || !lat || !lng) return;
    const next: [number, number] = [Number(lng), Number(lat)];
    marker.current?.remove();
    marker.current = new maplibregl.Marker({ color: "#a9583e" }).setLngLat(next).addTo(mapInstance.current);
    mapInstance.current.flyTo({ center: next, zoom, essential: true });
  }

  useEffect(() => {
    if (!mapOpen || !mapRef.current || !maptilerKey) return;
    const center: [number, number] = [Number(longitude) || 87.3105, Number(latitude) || 22.3149];
    const map = new maplibregl.Map({
      container: mapRef.current,
      style: `https://api.maptiler.com/maps/streets-v2/style.json?key=${maptilerKey}`,
      center,
      zoom: latitude && longitude ? 14 : 4
    });
    mapInstance.current = map;
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");
    if (latitude && longitude) {
      syncMapMarker(latitude, longitude, 14);
    }
    map.on("click", (event) => {
      const lat = event.lngLat.lat.toFixed(7);
      const lng = event.lngLat.lng.toFixed(7);
      setLatitude(lat);
      setLongitude(lng);
      syncMapMarker(lat, lng);
      applyPrefill(lat, lng);
    });
    return () => {
      // Destroy the instance when the panel closes/unmounts so reopening re-initialises cleanly.
      marker.current?.remove();
      marker.current = null;
      map.remove();
      if (mapInstance.current === map) mapInstance.current = null;
    };
    // Recreate only when the panel opens/closes; lat/lng are read at open time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapOpen]);

  function capturePreciseLocation(silent = false) {
    if (!navigator.geolocation) {
      if (!silent) setMessage("Precise location is not supported by this browser.");
      return;
    }
    if (!silent) setMessage("Requesting precise location...");
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const lat = position.coords.latitude.toFixed(7);
        const lng = position.coords.longitude.toFixed(7);
        setLatitude(lat);
        setLongitude(lng);
        setAltitude(position.coords.altitude == null ? "" : position.coords.altitude.toFixed(2));
        setAccuracy(position.coords.accuracy.toFixed(2));
        syncMapMarker(lat, lng);
        applyPrefill(lat, lng);
        setMessage(silent ? "Precise location populated. You can edit it or point a different place on the map." : "Location tagged: " + `${lat}, ${lng}`);
      },
      (error) => {
        if (!silent) setMessage(error.message);
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 }
    );
  }

  useEffect(() => {
    // Edit forms (initial provided, even as null) never auto-capture: the stored values must not be
    // silently overwritten by the editor's current device position. Capture is button-only there.
    if (initial !== undefined) return;
    capturePreciseLocation(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-card p-4 shadow-sm">
      <div>
        <h3 className="font-display font-bold text-lg text-ink-900">Location</h3>
        <p className="mt-1 text-sm text-ink-500">
          Tag where this was documented. Enter coordinates, capture a GPS fix, or drop a pin on the map. A fix or a
          pin also suggests the state and pincode — they are suggestions, so correct them if the map is wrong.
        </p>
      </div>
      <div className="flex flex-wrap justify-center gap-2">
        <button type="button" className="field-button" onClick={() => capturePreciseLocation(false)}>
          <LocateFixed className="h-4 w-4" aria-hidden />
          Use current GPS
        </button>
        <button type="button" className="field-button-secondary" onClick={() => setMapOpen((value) => !value)}>
          <MapPinned className="h-4 w-4" aria-hidden />
          Pick on map
        </button>
      </div>
      {message ? <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-500">{message}</div> : null}
      {mapOpen ? (
        maptilerKey ? (
          <div ref={mapRef} className="h-80 overflow-hidden rounded-md border border-line-200" />
        ) : (
          <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
            Add NEXT_PUBLIC_MAPTILER_API_KEY to enable map pointing. Coordinates can still be entered manually.
          </div>
        )
      ) : null}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="Latitude">
          <TextInput name="latitude" type="number" step="any" value={latitude} onChange={(event) => setLatitude(event.target.value)} placeholder="23.2599" />
        </Field>
        <Field label="Longitude">
          <TextInput name="longitude" type="number" step="any" value={longitude} onChange={(event) => setLongitude(event.target.value)} placeholder="77.4126" />
        </Field>
        <Field label="Altitude">
          <TextInput name="altitude" type="number" step="any" value={altitude} onChange={(event) => setAltitude(event.target.value)} />
        </Field>
        <Field label="Accuracy metres">
          <TextInput name="accuracy" type="number" step="any" value={accuracy} onChange={(event) => setAccuracy(event.target.value)} />
        </Field>
        <Field label="GPS place name">
          <TextInput name="placeName" value={placeName} onChange={(event) => setPlaceName(event.target.value)} />
        </Field>
        <Field label="GPS address">
          <TextInput name="locationAddress" className="lg:col-span-3" value={address} onChange={(event) => setAddress(event.target.value)} />
        </Field>
        <Field label="State">
          <Select
            name="state"
            value={stateName}
            onChange={(event) => {
              // Emptying a field hands it back to the geocoder; anything else is the researcher's
              // own answer and stays untouched by the next pin.
              humanSet.current.state = Boolean(event.target.value);
              setStateName(event.target.value);
              // The themed Dropdown is a button, so it fires no native input event for the form's
              // onInput to catch: the surrounding form's dirty flag has to be raised by hand.
              onDirty?.();
            }}
          >
            <option value="">Select state</option>
            {stateOptions.map((entry) => (
              <option key={entry}>{entry}</option>
            ))}
          </Select>
        </Field>
        <Field label="Pincode">
          <input
            ref={pincodeRef}
            name="pincode"
            className="field-input"
            type="text"
            inputMode="numeric"
            autoComplete="postal-code"
            placeholder="700001"
            value={pincode}
            aria-invalid={pincodeProblemShown && pincodeProblem ? true : undefined}
            onInvalid={() => setPincodeProblemShown(true)}
            onChange={(event) => {
              const next = event.target.value.replace(/\D/g, "").slice(0, PINCODE_LENGTH);
              humanSet.current.pincode = next.length > 0;
              setPincode(next);
              setPincodeProblemShown(false);
            }}
          />
          {pincodeProblemShown && pincodeProblem ? <p className="text-xs text-error-600">{pincodeProblem}</p> : null}
        </Field>
      </div>
      {(stateName || pincode) && (!latitude || !longitude) ? (
        // The API keeps state and pincode ON the location row, which cannot exist without a
        // coordinate — so say that here rather than letting the two answers vanish at save time.
        <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
          Add a GPS fix or a map pin: the state and pincode are stored with the coordinates, and
          without them they are not saved.
        </div>
      ) : null}
    </section>
  );
}
