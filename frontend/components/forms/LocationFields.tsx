"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import type * as maplibregl from "maplibre-gl";
import {
  CheckCircle2,
  LoaderCircle,
  LocateFixed,
  MapPinned,
  Radar,
  ScanSearch,
  TriangleAlert
} from "lucide-react";

import { Field, Select, TextInput } from "@/components/FormControls";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { RequiredMark } from "@/components/ui/RequiredMark";
import { apiFetch } from "@/lib/api";
import type { AddressReference } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import {
  EMPTY_STATED,
  prefillStillStands,
  statedPrefillFor,
  type StatedAddress,
  type StatedAddressSource
} from "@/components/forms/statedPrefill";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

let addressReferenceRequest: Promise<AddressReference> | null = null;

function loadAddressReference(): Promise<AddressReference> {
  addressReferenceRequest ??= apiFetch<AddressReference>("/reference/address")
    .then((payload) => {
      const districts = payload?.districts;
      if (districts && districts.byState) return payload;
      return {
        ...payload,
        districts: {
          source: districts?.source ?? "",
          sourceUrl: districts?.sourceUrl ?? "",
          asOf: districts?.asOf ?? "",
          listVersion: districts?.listVersion ?? 0,
          count: districts?.count ?? 0,
          byState: districts?.byState ?? {},
          normalisation: districts?.normalisation ?? { trailingWordsStripped: [], description: "" }
        }
      };
    })
    .catch((error) => {
      addressReferenceRequest = null;
      throw error;
    });
  return addressReferenceRequest;
}

type MapLibre = typeof maplibregl;

let maplibreRequest: Promise<MapLibre> | null = null;

function loadMapLibre(): Promise<MapLibre> {
  maplibreRequest ??= Promise.all([
    import("maplibre-gl"),
    import("maplibre-gl/dist/maplibre-gl.css")
  ])
    .then(([module]) => {
      module.setWorkerUrl(new URL("maplibre-gl/dist/maplibre-gl-worker.mjs", import.meta.url).toString());
      return module;
    })
    .catch((error) => {
      maplibreRequest = null;
      throw error;
    });
  return maplibreRequest;
}

const PINCODE_LENGTH = 6;

const POSTAL_ZONES: Record<string, readonly string[]> = {
  "1": ["Chandigarh", "Delhi", "Haryana", "Himachal Pradesh", "Jammu and Kashmir", "Ladakh", "Punjab"],
  "2": ["Uttar Pradesh", "Uttarakhand"],
  "3": ["Dadra and Nagar Haveli and Daman and Diu", "Gujarat", "Rajasthan"],
  "4": ["Chhattisgarh", "Goa", "Madhya Pradesh", "Maharashtra"],
  "5": ["Andhra Pradesh", "Karnataka", "Puducherry", "Telangana"],
  "6": ["Kerala", "Lakshadweep", "Puducherry", "Tamil Nadu"],
  "7": [
    "Andaman and Nicobar Islands",
    "Arunachal Pradesh",
    "Assam",
    "Manipur",
    "Meghalaya",
    "Mizoram",
    "Nagaland",
    "Odisha",
    "Sikkim",
    "Tripura",
    "West Bengal"
  ],
  "8": ["Bihar", "Jharkhand"]
};

export const OFFLINE_STATES: readonly string[] = Array.from(
  new Set(Object.values(POSTAL_ZONES).flatMap((names) => names))
).sort((a, b) => a.localeCompare(b));

export function postalZoneMismatch(state: string, pincode: string): string | null {
  if (!state || pincode.length !== PINCODE_LENGTH) return null;
  const zone = pincode[0];
  const home = Object.keys(POSTAL_ZONES).filter((digit) => POSTAL_ZONES[digit].includes(state));
  if (home.length === 0 || home.includes(zone)) return null;
  const aps = zone === "9" ? " (9 is the Army Postal Service)" : "";
  return (
    `${pincode} isn’t a ${state} pincode (they start with ${home.join(" or ")}; this starts with ${zone}${aps}).`
  );
}

export function pincodeValidationError(value: string | null | undefined): string | null {
  const digits = (value ?? "").trim();
  if (!digits) return null;
  if (!/^[0-9]+$/.test(digits)) return "Pincode must be 6 digits.";
  if (digits.length !== PINCODE_LENGTH) {
    return `Pincode must be 6 digits (has ${digits.length}).`;
  }
  if (digits[0] === "0") return "Pincodes don’t start with 0.";
  return null;
}

export type LocationInitialValues = {
  latitude?: number | string | null;
  longitude?: number | string | null;
  altitude?: number | string | null;
  accuracy?: number | string | null;
  capturedAt?: string | null;
  placeName?: string | null;
  address?: string | null;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  pincode?: string | null;
  subjectLatitude?: number | string | null;
  subjectLongitude?: number | string | null;
};

function asText(value: number | string | null | undefined) {
  return value === null || value === undefined ? "" : String(value);
}

function comparableName(value: string) {
  return value.toLowerCase().replace(/[^a-z]/g, "");
}

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

function districtKey(value: string, trailingWords: readonly string[]): string {
  const folded = comparableName(value);
  for (const word of trailingWords) {
    const tail = comparableName(word);
    if (tail && folded.length > tail.length + 2 && folded.endsWith(tail)) {
      return folded.slice(0, -tail.length);
    }
  }
  return folded;
}

function matchDistrict(text: string, districts: readonly string[], trailingWords: readonly string[]) {
  const wanted = districtKey(text, trailingWords);
  if (!wanted) return "";
  return districts.find((entry) => districtKey(entry, trailingWords) === wanted) ?? "";
}

function usablePincode(text: string): string {
  const digits = text.replace(/\D/g, "");
  return pincodeValidationError(digits) ? "" : digits;
}

type GeocodeFeature = {
  id?: string;
  text?: string;
  place_type?: string[];
  context?: Array<{ id?: string; text?: string }>;
};

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

async function reverseGeocode(lat: string, lng: string, signal: AbortSignal) {
  const response = await fetch(
    `https://api.maptiler.com/geocoding/${encodeURIComponent(lng)},${encodeURIComponent(lat)}.json` +
      `?key=${maptilerKey}&language=en`,
    { signal }
  );
  if (!response.ok) throw new Error(`Geocoder returned ${response.status}`);
  const body = (await response.json()) as { features?: GeocodeFeature[] };
  const features = body.features ?? [];
  return {
    region: pickPlace(features, "region"),
    subregion: pickPlace(features, "subregion"),
    municipality: pickPlace(features, "municipality"),
    place: pickPlace(features, "place"),
    postalCode: pickPlace(features, "postal_code")
  };
}

const PINCODE_ACCURACY_LIMIT_METRES = 1000;

const GOOD_ENOUGH_FIX_METRES = 100;

const AUTO_CAPTURE_BUDGET_MS = 60_000;

const MANUAL_CAPTURE_TIMEOUT_MS = 30_000;

const FIELD_NAMES = (fields: Array<"state" | "district" | "pincode">): string => {
  const words = fields.map((field) => (field === "pincode" ? "pincode" : field));
  if (words.length <= 1) return words[0] ?? "";
  return `${words.slice(0, -1).join(", ")} and ${words[words.length - 1]}`;
};

function accuracyLabel(metres: number) {
  return metres < 1000 ? `±${Math.round(metres)} m` : `±${(metres / 1000).toFixed(1)} km`;
}

function momentLabel(iso: string) {
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return "";
  return when.toLocaleString("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  });
}

type CaptureStatus = "idle" | "locating" | "captured" | "denied" | "unavailable" | "timeout" | "unsupported";

const LOCATION_REQUIRED_MESSAGE =
  "Location required. Pick on map or type coordinates.";

const ADDRESS_NEEDS_COORDINATE_MESSAGE =
  "State and district need coordinates. Use current GPS or drop a pin, or clear them.";

type PointAddress = {
  state: string;
  district: string;
  pincode: string;
  names: string[];
};

async function resolvePoint(lat: string, lng: string, signal: AbortSignal): Promise<PointAddress> {
  const [place, reference] = await Promise.all([
    reverseGeocode(lat, lng, signal),
    loadAddressReference().catch(() => null)
  ]);
  const names = [place.region, place.subregion, place.municipality, place.place].filter(Boolean);
  if (!reference) return { state: "", district: "", pincode: usablePincode(place.postalCode), names };
  const state = matchIndianState(place.region, reference.statesAndUnionTerritories);
  const district =
    state && reference.districts
      ? matchDistrict(
          place.subregion,
          reference.districts.byState[state] ?? [],
          reference.districts.normalisation.trailingWordsStripped
        )
      : "";
  return { state, district, pincode: usablePincode(place.postalCode), names };
}

function readPlaceText(text: string, reference: AddressReference | null) {
  const folded = comparableName(text);
  if (!folded || !reference) return { state: "", district: "" };
  const state =
    reference.statesAndUnionTerritories.find((entry) => {
      const name = comparableName(entry);
      return name.length >= 5 && folded.includes(name);
    }) ?? "";
  const byState = reference.districts?.byState ?? {};
  const searched = state ? { [state]: byState[state] ?? [] } : byState;
  for (const [owner, districts] of Object.entries(searched)) {
    for (const district of districts) {
      const name = comparableName(district);
      if (name.length >= 5 && folded.includes(name)) return { state: state || owner, district };
    }
  }
  return { state, district: "" };
}

function mentions(text: string, names: readonly string[]) {
  const folded = comparableName(text);
  return names.some((name) => {
    const wanted = comparableName(name);
    return wanted.length >= 4 && folded.includes(wanted);
  });
}

function CardNotice({
  tone,
  icon,
  children
}: {
  tone: "info" | "warn";
  icon?: React.ReactNode;
  children: React.ReactNode;
}) {
  const skin =
    tone === "warn"
      ? "border-amber-500 bg-amber-100 text-amber-800"
      : "border-line-200 bg-surface-50 text-ink-500";
  return (
    <div className={`flex items-start gap-2 rounded-md border px-3 py-2 text-sm ${skin}`}>
      {icon ? <span className="mt-0.5 shrink-0">{icon}</span> : null}
      <span>{children}</span>
    </div>
  );
}

type MapTarget = "subject" | "capture";

export function LocationFields({
  initial,
  onDirty,
  required,
  subjectLabel = "this record",
  statedPlace,
  prefillFrom
}: {
  initial?: LocationInitialValues | null;
  onDirty?: () => void;
  required?: boolean;
  subjectLabel?: string;
  statedPlace?: string | null;
  prefillFrom?: StatedAddressSource | null;
}) {
  const mapRef = useRef<HTMLDivElement | null>(null);
  const mapInstance = useRef<maplibregl.Map | null>(null);
  const subjectMarker = useRef<maplibregl.Marker | null>(null);
  const captureMarker = useRef<maplibregl.Marker | null>(null);
  const maplibreModule = useRef<MapLibre | null>(null);
  const headingId = useId();

  const initialPincode = (initial?.pincode ?? "").replace(/\D/g, "").slice(0, PINCODE_LENGTH);

  const [latitude, setLatitude] = useState(asText(initial?.latitude));
  const [longitude, setLongitude] = useState(asText(initial?.longitude));
  const [altitude, setAltitude] = useState(asText(initial?.altitude));
  const [accuracy, setAccuracy] = useState(asText(initial?.accuracy));
  const [capturedAt, setCapturedAt] = useState(initial?.capturedAt ?? "");
  const [placeName, setPlaceName] = useState(initial?.placeName ?? "");
  const [address, setAddress] = useState(initial?.address ?? "");

  const [stateName, setStateName] = useState(initial?.state ?? "");
  const [district, setDistrict] = useState(initial?.district ?? "");
  const [village, setVillage] = useState(initial?.village ?? "");
  const [pincode, setPincode] = useState(initialPincode);
  const [subjectLatitude, setSubjectLatitude] = useState(asText(initial?.subjectLatitude));
  const [subjectLongitude, setSubjectLongitude] = useState(asText(initial?.subjectLongitude));

  const [reference, setReference] = useState<AddressReference | null>(null);
  const [suggestion, setSuggestion] = useState<(PointAddress & { metres: number | null }) | null>(null);
  const [autofill, setAutofill] = useState<{
    applied: Array<"state" | "district" | "pincode">;
    previous: { state: string; district: string; pincode: string };
    mode: "explicit" | "passive";
  } | null>(null);
  const [coarseFixMetres, setCoarseFixMetres] = useState<number | null>(null);
  const [lookupFailed, setLookupFailed] = useState(false);
  const [review, setReview] = useState<{ found: PointAddress; stated: string } | null>(null);
  const [addressProblemShown, setAddressProblemShown] = useState(false);
  const [pincodeProblemShown, setPincodeProblemShown] = useState(false);
  const pincodeRef = useRef<HTMLInputElement>(null);
  const [mapTarget, setMapTarget] = useState<MapTarget | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [captureStatus, setCaptureStatus] = useState<CaptureStatus>("idle");
  const [captureSeconds, setCaptureSeconds] = useState(0);
  const [capturedMetres, setCapturedMetres] = useState<number | null>(null);
  const [provenanceOpen, setProvenanceOpen] = useState(false);
  const latitudeRef = useRef<HTMLInputElement>(null);
  const longitudeRef = useRef<HTMLInputElement>(null);
  const watchId = useRef<number | null>(null);
  const bestMetres = useRef<number | null>(null);
  const pendingLookup = useRef<{
    lat: string;
    lng: string;
    metres: number | null;
    autoApply: boolean;
  } | null>(null);
  const lookup = useRef<AbortController | null>(null);
  const explicitPending = useRef(false);

  const statedRef = useRef({ state: stateName, district, pincode });
  useEffect(() => {
    statedRef.current = { state: stateName, district, pincode };
  }, [stateName, district, pincode]);

  const autofillRef = useRef(autofill);
  useEffect(() => {
    autofillRef.current = autofill;
  }, [autofill]);

  const [statedPrefill, setStatedPrefill] = useState<{ label: string; values: StatedAddress } | null>(null);
  const statedNow: StatedAddress = { state: stateName, district, village, pincode };
  const statedNowRef = useRef(statedNow);
  useEffect(() => {
    statedNowRef.current = { state: stateName, district, village, pincode };
  }, [stateName, district, village, pincode]);
  const statedPrefillRef = useRef(statedPrefill);
  useEffect(() => {
    statedPrefillRef.current = statedPrefill;
  }, [statedPrefill]);
  const prefillKey = prefillFrom?.key ?? null;
  useEffect(() => {
    if (!prefillFrom) return;
    const device = autofillRef.current?.mode === "passive" ? autofillRef.current.applied : [];
    const next = statedPrefillFor(prefillFrom, statedNowRef.current, statedPrefillRef.current?.values ?? null, device);
    if (!next) return;
    setStateName(next.state);
    setDistrict(next.district);
    setVillage(next.village);
    setPincode(next.pincode);
    setAutofill(null);
    setStatedPrefill({ label: prefillFrom.label, values: next });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefillKey]);
  const prefillShown = statedPrefill !== null && prefillStillStands(statedNow, statedPrefill.values);
  function clearStatedPrefill() {
    setStateName(EMPTY_STATED.state);
    setDistrict(EMPTY_STATED.district);
    setVillage(EMPTY_STATED.village);
    setPincode(EMPTY_STATED.pincode);
    setStatedPrefill(null);
    onDirty?.();
  }

  const pincodeProblem = pincodeValidationError(pincode);
  const zoneProblem = postalZoneMismatch(stateName, pincode);

  const isEditForm = initial !== undefined;
  const hadStoredCoordinate = asText(initial?.latitude) !== "" && asText(initial?.longitude) !== "";
  const mandatory = required ?? (!isEditForm || hadStoredCoordinate);
  const hasCoordinate = latitude.trim() !== "" && longitude.trim() !== "";
  const hasStatedAddress = Boolean(stateName || district || village || pincode || subjectLatitude);
  const coordinateRequired = mandatory || hasStatedAddress;

  const districtOptions = useMemo(() => {
    const served = (stateName && reference?.districts?.byState?.[stateName]) || [];
    return district && !served.includes(district) ? [district, ...served] : served;
  }, [district, reference, stateName]);

  const stateOptions = useMemo(() => {
    const served = reference?.statesAndUnionTerritories ?? OFFLINE_STATES;
    return stateName && !served.includes(stateName) ? [stateName, ...served] : served;
  }, [reference, stateName]);

  const stateRequired = (required ?? (!isEditForm || Boolean(initial?.state))) && stateOptions.length > 0;
  const districtRequired =
    (required ?? (!isEditForm || Boolean(initial?.district))) && districtOptions.length > 0;
  const stateMissing = stateRequired && !stateName;
  const districtMissing = districtRequired && !district;

  useEffect(() => {
    let live = true;
    loadAddressReference()
      .then((payload) => {
        if (live) setReference(payload);
      })
      .catch(() => {
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    pincodeRef.current?.setCustomValidity(pincodeProblem ?? "");
  }, [pincodeProblem]);

  function stopWatching() {
    if (watchId.current === null) return;
    navigator.geolocation.clearWatch(watchId.current);
    watchId.current = null;
  }

  function applyGeocodedAddress(found: PointAddress, { overwrite }: { overwrite: boolean }) {
    const previous = { ...statedRef.current };
    const applied: Array<"state" | "district" | "pincode"> = [];

    const wroteState = Boolean(
      found.state && (overwrite || !previous.state) && found.state !== previous.state
    );
    if (wroteState) {
      setStateName(found.state);
      applied.push("state");
    }

    const effectiveState = wroteState ? found.state : previous.state;
    const geocodedStateStands = !found.state || found.state === effectiveState;

    if (geocodedStateStands && (overwrite || !previous.district) && found.district !== previous.district) {
      if (found.district || overwrite) {
        setDistrict(found.district);
        applied.push("district");
      }
    }
    if (geocodedStateStands && (overwrite || !previous.pincode) && found.pincode !== previous.pincode) {
      if (found.pincode || overwrite) {
        setPincode(found.pincode);
        applied.push("pincode");
      }
    }

    if (applied.length) {
      setPincodeProblemShown(false);
      setAddressProblemShown(false);
      onDirty?.();
    }
    return { applied, previous };
  }

  function undoAutofill() {
    if (!autofill) return;
    setStateName(autofill.previous.state);
    setDistrict(autofill.previous.district);
    setPincode(autofill.previous.pincode);
    setAutofill(null);
    onDirty?.();
  }

  function claimField(field: "state" | "district" | "pincode", value: string) {
    setAutofill((current) => {
      if (!current) return current;
      return {
        ...current,
        applied: current.applied.filter((name) => name !== field),
        previous: { ...current.previous, [field]: value }
      };
    });
  }

  function offerSuggestion(
    lat: string,
    lng: string,
    accuracyMetres?: number | null,
    { autoApply = false }: { autoApply?: boolean } = {}
  ) {
    const explicitStanding =
      !autoApply && (explicitPending.current || autofillRef.current?.mode === "explicit");

    const coarse = typeof accuracyMetres === "number" && accuracyMetres > PINCODE_ACCURACY_LIMIT_METRES;
    setCoarseFixMetres(coarse ? accuracyMetres : null);

    if (explicitStanding) {
      setSuggestion(null);
      return;
    }
    lookup.current?.abort();
    setSuggestion(null);
    setAutofill(null);
    setLookupFailed(false);
    if (!maptilerKey) return;
    if (autoApply) explicitPending.current = true;
    if (coarse) return;

    const controller = new AbortController();
    lookup.current = controller;
    resolvePoint(lat, lng, controller.signal)
      .then((found) => {
        if (controller.signal.aborted) return;
        if (autoApply) explicitPending.current = false;
        pendingLookup.current = null;
        if (!found.state && !found.district) return;
        const { applied, previous } = applyGeocodedAddress(found, { overwrite: autoApply });
        if (applied.length) {
          setAutofill({ applied, previous, mode: autoApply ? "explicit" : "passive" });
        }
        if (autoApply) return;
        const unfilled =
          (found.state && found.state !== previous.state && !applied.includes("state")) ||
          (found.district && found.district !== previous.district && !applied.includes("district")) ||
          (found.pincode && found.pincode !== previous.pincode && !applied.includes("pincode"));
        if (unfilled) setSuggestion({ ...found, metres: accuracyMetres ?? null });
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        if (autoApply) explicitPending.current = false;
        setLookupFailed(true);
        pendingLookup.current = { lat, lng, metres: accuracyMetres ?? null, autoApply };
      });
  }

  useEffect(() => () => lookup.current?.abort(), []);

  useEffect(() => {
    function retry() {
      const point = pendingLookup.current;
      if (!point) return;
      if (point.autoApply && (point.lat !== subjectLatitude || point.lng !== subjectLongitude)) {
        pendingLookup.current = null;
        return;
      }
      offerSuggestion(point.lat, point.lng, point.metres, { autoApply: point.autoApply });
    }
    window.addEventListener("online", retry);
    return () => window.removeEventListener("online", retry);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subjectLatitude, subjectLongitude]);

  useEffect(() => {
    if (!isEditForm || !maptilerKey) return;
    const lat = asText(initial?.latitude);
    const lng = asText(initial?.longitude);
    if (!lat || !lng) return;
    const stated = (initial?.state || statedPlace || "").trim();
    if (!stated) return;

    const controller = new AbortController();
    Promise.all([resolvePoint(lat, lng, controller.signal), loadAddressReference().catch(() => null)])
      .then(([found, payload]) => {
        if (controller.signal.aborted || !found.state) return;
        const claimed = initial?.state || readPlaceText(stated, payload).state;
        const contradicted = claimed ? claimed !== found.state : !mentions(stated, found.names);
        if (contradicted) setReview({ found, stated });
      })
      .catch(() => {
      });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function syncMarkers(zoom = 15) {
    const maplibre = maplibreModule.current;
    const map = mapInstance.current;
    if (!maplibre || !map) return;

    subjectMarker.current?.remove();
    subjectMarker.current = null;
    captureMarker.current?.remove();
    captureMarker.current = null;

    if (subjectLatitude && subjectLongitude) {
      subjectMarker.current = new maplibre.Marker({ color: "#6b21a8" })
        .setLngLat([Number(subjectLongitude), Number(subjectLatitude)])
        .addTo(map);
    }
    if (latitude && longitude) {
      captureMarker.current = new maplibre.Marker({ color: "#64748b" })
        .setLngLat([Number(longitude), Number(latitude)])
        .addTo(map);
    }

    const focus =
      mapTarget === "subject" && subjectLatitude && subjectLongitude
        ? ([Number(subjectLongitude), Number(subjectLatitude)] as [number, number])
        : latitude && longitude
          ? ([Number(longitude), Number(latitude)] as [number, number])
          : null;
    if (focus) map.flyTo({ center: focus, zoom, essential: true });
  }

  function placeSubjectPin(lat: string, lng: string) {
    setSubjectLatitude(lat);
    setSubjectLongitude(lng);
    offerSuggestion(lat, lng, null, { autoApply: true });
    onDirty?.();
  }

  function placeCapturePin(lat: string, lng: string) {
    stopWatching();
    setCaptureStatus("idle");
    setCapturedMetres(null);
    setLatitude(lat);
    setLongitude(lng);
    setAccuracy("");
    setCapturedAt("");
    offerSuggestion(lat, lng);
    onDirty?.();
  }

  useEffect(() => {
    if (!mapTarget || !mapRef.current || !maptilerKey) return;
    const container = mapRef.current;
    const start: [number, number] =
      mapTarget === "subject" && subjectLatitude && subjectLongitude
        ? [Number(subjectLongitude), Number(subjectLatitude)]
        : [Number(longitude) || 78.9629, Number(latitude) || 22.5937];
    let cancelled = false;
    let map: maplibregl.Map | null = null;

    loadMapLibre()
      .then((maplibre) => {
        if (cancelled) return;
        maplibreModule.current = maplibre;
        const instance = new maplibre.Map({
          container,
          style: `https://api.maptiler.com/maps/streets-v2/style.json?key=${maptilerKey}`,
          center: start,
          zoom: latitude && longitude ? 12 : 4
        });
        map = instance;
        mapInstance.current = instance;
        instance.addControl(new maplibre.NavigationControl({ visualizePitch: true }), "top-right");
        syncMarkers(12);
        instance.on("click", (event) => {
          const lat = event.lngLat.lat.toFixed(7);
          const lng = event.lngLat.lng.toFixed(7);
          if (mapTarget === "subject") {
            placeSubjectPin(lat, lng);
          } else {
            placeCapturePin(lat, lng);
          }
        });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        const gpuError = maplibreModule.current?.GPUInitializationError;
        setMessage(
          gpuError && error instanceof gpuError
            ? "Map unavailable in this browser. Type coordinates instead."
            : "Map didn’t load. Type coordinates instead."
        );
      });

    return () => {
      cancelled = true;
      subjectMarker.current?.remove();
      subjectMarker.current = null;
      captureMarker.current?.remove();
      captureMarker.current = null;
      map?.remove();
      if (map && mapInstance.current === map) mapInstance.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapTarget]);

  useEffect(() => {
    syncMarkers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [latitude, longitude, subjectLatitude, subjectLongitude]);

  function acceptFix(position: GeolocationPosition) {
    const lat = position.coords.latitude.toFixed(7);
    const lng = position.coords.longitude.toFixed(7);
    const metres = position.coords.accuracy;
    bestMetres.current = metres;
    setLatitude(lat);
    setLongitude(lng);
    setAltitude(position.coords.altitude == null ? "" : position.coords.altitude.toFixed(2));
    setAccuracy(metres.toFixed(2));
    setCapturedAt(new Date(position.timestamp).toISOString());
    setCapturedMetres(metres);
    setCaptureStatus("captured");
    offerSuggestion(lat, lng, metres);
  }

  function classifyError(error: GeolocationPositionError): CaptureStatus {
    if (error.code === error.PERMISSION_DENIED) return "denied";
    if (error.code === error.TIMEOUT) return "timeout";
    return "unavailable";
  }

  function startAutoCapture() {
    if (!navigator.geolocation) {
      setCaptureStatus("unsupported");
      return;
    }
    setCaptureStatus("locating");
    setCaptureSeconds(0);
    bestMetres.current = null;
    const deadline = Date.now() + AUTO_CAPTURE_BUDGET_MS;

    watchId.current = navigator.geolocation.watchPosition(
      (position) => {
        const metres = position.coords.accuracy;
        const better = bestMetres.current === null || metres < bestMetres.current;
        if (better) acceptFix(position);
        if (metres <= GOOD_ENOUGH_FIX_METRES || Date.now() >= deadline) stopWatching();
      },
      (error) => {
        stopWatching();
        if (bestMetres.current !== null) return;
        setCaptureStatus(classifyError(error));
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: AUTO_CAPTURE_BUDGET_MS }
    );

    window.setTimeout(() => {
      if (watchId.current === null) return;
      stopWatching();
      if (bestMetres.current === null) setCaptureStatus("timeout");
    }, AUTO_CAPTURE_BUDGET_MS);
  }

  function capturePreciseLocation() {
    stopWatching();
    if (!navigator.geolocation) {
      setCaptureStatus("unsupported");
      setMessage("This browser can’t get a precise location.");
      return;
    }
    setMessage(null);
    setCaptureStatus("locating");
    setCaptureSeconds(0);
    bestMetres.current = null;
    navigator.geolocation.getCurrentPosition(
      (position) => {
        acceptFix(position);
        onDirty?.();
      },
      (error) => {
        setCaptureStatus(classifyError(error));
        setMessage(error.message);
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: MANUAL_CAPTURE_TIMEOUT_MS }
    );
  }

  useEffect(() => {
    if (isEditForm) return;
    startAutoCapture();
    return stopWatching;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (captureStatus !== "locating") return;
    const tick = window.setInterval(() => setCaptureSeconds((value) => value + 1), 1000);
    return () => window.clearInterval(tick);
  }, [captureStatus]);

  useEffect(() => {
    const problem = !coordinateRequired || hasCoordinate
      ? ""
      : mandatory
        ? LOCATION_REQUIRED_MESSAGE
        : ADDRESS_NEEDS_COORDINATE_MESSAGE;
    latitudeRef.current?.setCustomValidity(problem);
    longitudeRef.current?.setCustomValidity(problem);
  }, [coordinateRequired, mandatory, hasCoordinate]);

  const captureRefused =
    captureStatus === "denied" ||
    captureStatus === "unavailable" ||
    captureStatus === "timeout" ||
    captureStatus === "unsupported";

  useEffect(() => {
    if (!coordinateRequired || hasCoordinate) return;
    if (captureRefused || isEditForm || hasStatedAddress) setProvenanceOpen(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [coordinateRequired, hasCoordinate, captureRefused, hasStatedAddress]);

  function editCoordinate(setter: (next: string) => void, next: string) {
    stopWatching();
    if (captureStatus === "locating") setCaptureStatus("idle");
    setter(next);
  }

  function acceptSuggestion() {
    if (!suggestion) return;
    setStateName(suggestion.state);
    setDistrict(suggestion.district);
    setPincode(suggestion.pincode);
    setPincodeProblemShown(false);
    setSuggestion(null);
    setAddressProblemShown(false);
    setAutofill(null);
    onDirty?.();
  }

  const captureSummary = hasCoordinate
    ? [
        `${Number(latitude).toFixed(4)}, ${Number(longitude).toFixed(4)}`,
        capturedMetres !== null
          ? accuracyLabel(capturedMetres)
          : accuracy
            ? accuracyLabel(Number(accuracy))
            : "",
        capturedAt ? momentLabel(capturedAt) : ""
      ]
        .filter(Boolean)
        .join(" · ")
    : "No coordinates yet";

  const mapPanel = maptilerKey ? (
    <div ref={mapRef} className="h-80 overflow-hidden rounded-md border border-line-200" />
  ) : (
    <CardNotice tone="info">
      Map unavailable. Type coordinates instead.
    </CardNotice>
  );

  return (
    <div className="grid gap-3">
      <section
        aria-labelledby={headingId}
        className="grid gap-3 rounded-lg border border-line-200 bg-card p-4 shadow-sm"
      >
        <div>
          <h3 id={headingId} className="font-display font-bold text-lg text-ink-900">
            Location of {subjectLabel}
            <RequiredMark when={stateRequired} />
          </h3>
          <p className="mt-1 text-sm text-ink-500">
            Where {subjectLabel} is. Pick on the map to fill the address.
          </p>
        </div>

        {prefillShown && statedPrefill ? (
          <div
            data-testid="stated-prefill"
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-sm text-purple-950"
          >
            <span>From {statedPrefill.label}&rsquo;s record.</span>
            <button
              type="button"
              className="field-button-secondary min-h-8 px-3 py-1 text-xs"
              onClick={clearStatedPrefill}
            >
              Clear
            </button>
          </div>
        ) : null}

        {autofill && autofill.applied.length ? (
          <div className="grid gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2.5 text-sm text-purple-950">
            <p className="flex items-start gap-2">
              <Radar className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
              <span>
                {autofill.mode === "explicit"
                  ? "Filled in from the place you pointed at:"
                  : "Filled empty fields from this device’s location:"}{" "}
                <strong className="font-semibold">{FIELD_NAMES(autofill.applied)}</strong>. Please check it.
              </span>
            </p>
            <div>
              <button
                type="button"
                className="rounded-md border border-purple-300 bg-white px-3 py-1.5 text-xs font-semibold text-purple-800 transition-colors hover:bg-purple-100"
                onClick={undoAutofill}
              >
                Undo
              </button>
            </div>
          </div>
        ) : null}

        {review && (!stateName || stateName === (initial?.state ?? "")) ? (
          <CardNotice tone="warn" icon={<ScanSearch className="h-4 w-4" aria-hidden />}>
            <strong className="font-semibold">Check the place.</strong> Coordinates say{" "}
            <strong className="font-semibold">
              {[review.found.district, review.found.state].filter(Boolean).join(", ")}
            </strong>
            ; the record says &ldquo;{review.stated}&rdquo;. Set the state and district if you know them.
          </CardNotice>
        ) : null}

        {suggestion ? (
          <div
            data-location-suggestion
            className="grid gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2.5 text-sm text-purple-950"
          >
            <p className="flex items-start gap-2">
              <Radar className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
              <span>
                This device is in{" "}
                <strong className="font-semibold">
                  {[suggestion.district, suggestion.state].filter(Boolean).join(", ")}
                </strong>
                {suggestion.metres !== null ? ` (${accuracyLabel(suggestion.metres)})` : ""}. Use it as the location of{" "}
                {subjectLabel}?
              </span>
            </p>
            <ButtonRow align="start">
              <button type="button" className="field-button" onClick={acceptSuggestion}>
                Yes, use this
              </button>
              <button
                type="button"
                className="inline-flex min-h-10 items-center justify-center rounded-md border border-purple-300 bg-white px-4 py-2 text-sm font-medium text-purple-800 transition hover:bg-purple-100"
                onClick={() => setSuggestion(null)}
              >
                No, {subjectLabel} is elsewhere
              </button>
            </ButtonRow>
          </div>
        ) : null}

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="State" required={stateRequired}>
            <Select
              name="state"
              value={stateName}
              required={stateRequired}
              onInvalid={() => setAddressProblemShown(true)}
              onChange={(event) => {
                setStateName(event.target.value);
                setDistrict("");
                setAddressProblemShown(false);
                claimField("state", event.target.value);
                claimField("district", "");
                onDirty?.();
              }}
            >
              <option value="">Select state</option>
              {stateOptions.map((entry) => (
                <option key={entry}>{entry}</option>
              ))}
            </Select>
            {addressProblemShown && stateMissing ? (
              <p className="text-xs text-error-600">Choose the state {subjectLabel} is in.</p>
            ) : null}
          </Field>
          <Field label="District" required={districtRequired}>
            <Select
              name="district"
              value={district}
              required={districtRequired}
              disabled={!stateName}
              onInvalid={() => setAddressProblemShown(true)}
              onChange={(event) => {
                setDistrict(event.target.value);
                setAddressProblemShown(false);
                claimField("district", event.target.value);
                onDirty?.();
              }}
            >
              <option value="">{stateName ? "Select district" : "Choose a state first"}</option>
              {districtOptions.map((entry) => (
                <option key={entry}>{entry}</option>
              ))}
            </Select>
            {addressProblemShown && districtMissing ? (
              <p className="text-xs text-error-600">Choose the district within {stateName}.</p>
            ) : null}
            {stateName && reference && districtOptions.length === 0 ? (
              <p className="text-xs text-ink-500">
                No districts are listed for {stateName} — save without one and report the gap.
              </p>
            ) : null}
            {stateName && !reference && districtOptions.length === 0 ? (
              <p className="text-xs text-ink-500">
                Districts need a connection. You can save with the state alone.
              </p>
            ) : null}
          </Field>
          <DictatedTextInput
            name="village"
            label="Village or place"
            titleCased
            value={village}
            placeholder="Bagru"
            explainWhenUnavailable={false}
            onChange={(next) => {
              setVillage(next);
              onDirty?.();
            }}
          />
          <Field label="Pincode">
            <input
              ref={pincodeRef}
              name="pincode"
              className="field-input"
              type="text"
              inputMode="numeric"
              autoComplete="postal-code"
              placeholder="303007"
              value={pincode}
              aria-invalid={pincodeProblemShown && pincodeProblem ? true : undefined}
              onInvalid={() => setPincodeProblemShown(true)}
              onChange={(event) => {
                const next = event.target.value.replace(/\D/g, "").slice(0, PINCODE_LENGTH);
                setPincode(next);
                setPincodeProblemShown(false);
                claimField("pincode", next);
              }}
            />
            {pincodeProblemShown && pincodeProblem ? <p className="text-xs text-error-600">{pincodeProblem}</p> : null}
          </Field>
        </div>

        {zoneProblem ? (
          <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
            {zoneProblem}
          </CardNotice>
        ) : null}

        {coarseFixMetres !== null ? (
          <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
            Location only accurate to {accuracyLabel(coarseFixMetres)} — choose the district yourself.
          </CardNotice>
        ) : null}

        {lookupFailed ? (
          <CardNotice tone="info">
            Couldn’t look up this place. Coordinates are still saved.
          </CardNotice>
        ) : null}

        <div className="grid gap-2">
          <input type="hidden" name="subjectLatitude" value={subjectLatitude} />
          <input type="hidden" name="subjectLongitude" value={subjectLongitude} />
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="field-button-secondary"
              aria-expanded={mapTarget === "subject"}
              onClick={() => setMapTarget((value) => (value === "subject" ? null : "subject"))}
            >
              <MapPinned className="h-4 w-4" aria-hidden />
              {subjectLatitude ? "Move the pin" : "Point to the exact place (optional)"}
            </button>
            {subjectLatitude && subjectLongitude ? (
              <>
                <span className="text-sm text-ink-500">
                  Pinned at {Number(subjectLatitude).toFixed(4)}, {Number(subjectLongitude).toFixed(4)}
                </span>
                <button
                  type="button"
                  className="text-sm font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2"
                  onClick={() => {
                    setSubjectLatitude("");
                    setSubjectLongitude("");
                    onDirty?.();
                  }}
                >
                  Remove pin
                </button>
              </>
            ) : null}
          </div>
          {mapTarget === "subject" ? (
            <>
              <p className="text-sm text-ink-500">
                Tap where {subjectLabel} is. Purple is {subjectLabel}; grey is this device.
              </p>
              {mapPanel}
            </>
          ) : null}
        </div>
      </section>

      <section className="rounded-lg border border-line-200 bg-card shadow-sm">
        <details
          open={provenanceOpen}
          onToggle={(event) => setProvenanceOpen((event.currentTarget as HTMLDetailsElement).open)}
        >
          <summary className="cursor-pointer list-none px-4 py-3 text-sm text-ink-500 [&::-webkit-details-marker]:hidden">
            <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <LocateFixed className="h-4 w-4 shrink-0" aria-hidden />
              <span className="font-medium text-ink-700">Captured at</span>
              <span className="text-ink-500">— where this device was, recorded automatically</span>
              <span aria-live="polite" className="font-mono text-xs text-ink-500">
                {captureSummary}
              </span>
            </span>
          </summary>

          <div className="grid gap-3 border-t border-line-200 px-4 py-3">
            <p className="text-sm text-ink-500">
              Where this device was when recording. Not used as the address.
            </p>

            <div aria-live="polite" className="grid gap-2 empty:hidden">
              {captureStatus === "locating" ? (
                <CardNotice
                  tone="info"
                  icon={<LoaderCircle className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />}
                >
                  Finding this device&apos;s location… {captureSeconds}s.{" "}
                  {captureSeconds >= 8
                    ? "Can take a minute indoors — keep going."
                    : "Carry on filling the form."}
                </CardNotice>
              ) : null}
              {captureStatus === "captured" && capturedMetres !== null ? (
                <CardNotice tone="info" icon={<CheckCircle2 className="h-4 w-4 text-success-600" aria-hidden />}>
                  Device location: {latitude}, {longitude} ({accuracyLabel(capturedMetres)}).
                </CardNotice>
              ) : null}
              {captureStatus === "denied" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  Location is blocked. Use <strong>Pick on map</strong>, or allow location and press{" "}
                  <strong>Use current GPS</strong>.
                </CardNotice>
              ) : null}
              {captureStatus === "unavailable" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  No location available. Use <strong>Pick on map</strong> or type coordinates.
                </CardNotice>
              ) : null}
              {captureStatus === "timeout" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  No fix yet. Step outside and press <strong>Use current GPS</strong>, or use{" "}
                  <strong>Pick on map</strong>.
                </CardNotice>
              ) : null}
              {captureStatus === "unsupported" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  No location support. Use <strong>Pick on map</strong> or type coordinates.
                </CardNotice>
              ) : null}
              {message ? <CardNotice tone="info">{message}</CardNotice> : null}
            </div>

            <ButtonRow align="start">
              <button type="button" className="field-button" onClick={() => capturePreciseLocation()}>
                <LocateFixed className="h-4 w-4" aria-hidden />
                Use current GPS
              </button>
              <button
                type="button"
                className="field-button-secondary"
                aria-expanded={mapTarget === "capture"}
                onClick={() => setMapTarget((value) => (value === "capture" ? null : "capture"))}
              >
                <MapPinned className="h-4 w-4" aria-hidden />
                Pick on map
              </button>
            </ButtonRow>
            {mapTarget === "capture" ? mapPanel : null}

            <input type="hidden" name="capturedAt" value={capturedAt} />
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Field label="Latitude" required={coordinateRequired}>
                <input
                  ref={latitudeRef}
                  name="latitude"
                  className="field-input"
                  type="number"
                  step="any"
                  required={coordinateRequired}
                  value={latitude}
                  onChange={(event) => editCoordinate(setLatitude, event.target.value)}
                  placeholder="23.2599"
                />
              </Field>
              <Field label="Longitude" required={coordinateRequired}>
                <input
                  ref={longitudeRef}
                  name="longitude"
                  className="field-input"
                  type="number"
                  step="any"
                  required={coordinateRequired}
                  value={longitude}
                  onChange={(event) => editCoordinate(setLongitude, event.target.value)}
                  placeholder="77.4126"
                />
              </Field>
              <Field label="Altitude">
                <TextInput
                  name="altitude"
                  type="number"
                  step="any"
                  value={altitude}
                  onChange={(event) => setAltitude(event.target.value)}
                />
              </Field>
              <Field label="Accuracy metres">
                <TextInput
                  name="accuracy"
                  type="number"
                  step="any"
                  value={accuracy}
                  onChange={(event) => setAccuracy(event.target.value)}
                />
              </Field>
              <Field label="GPS place name">
                <TextInput name="placeName" value={placeName} onChange={(event) => setPlaceName(event.target.value)} />
              </Field>
              <Field label="GPS address">
                <TextInput
                  name="locationAddress"
                  className="lg:col-span-3"
                  value={address}
                  onChange={(event) => setAddress(event.target.value)}
                />
              </Field>
            </div>

            {!mandatory && !hasCoordinate && !hasStatedAddress ? (
              <CardNotice tone="info">
                Coordinates optional. Only press <strong>Use current GPS</strong> if you’re at the place.
              </CardNotice>
            ) : null}
            {!mandatory && !hasCoordinate && hasStatedAddress ? (
              <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                {ADDRESS_NEEDS_COORDINATE_MESSAGE}
              </CardNotice>
            ) : null}
          </div>
        </details>
      </section>
    </div>
  );
}
