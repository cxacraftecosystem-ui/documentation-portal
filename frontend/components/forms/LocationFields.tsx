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
import { apiFetch } from "@/lib/api";
import type { AddressReference } from "@/lib/types";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

/**
 * TWO PLACES, NOT ONE, and the whole of this file is that sentence.
 *
 * Read against the live database, twice: all fifteen artisans that carry a location sit between
 * 22.313 and 22.315 N, 87.309 and 87.313 E — Kharagpur, West Bengal. The places their researchers
 * typed are Bagru, Balotra, Kutch, Rudraprayag, Ballupur, Sanganer and Kappaladoddi, none of them
 * inside 1,500 km of that point. The coordinates are not a hardcoded constant and not a bug: they
 * jitter naturally and carry genuine accuracy radii from 26 m to 2,506 m. They are real GPS fixes
 * OF THE DESK THE RECORD WAS TYPED AT — ordinary, reasonable behaviour that the form had no way to
 * express, so the fix was read as the artisan's address and the researchers hand-encoded the real
 * village and district into a free-text box because there was nowhere else to put them.
 *
 * So this card renders two groups, and a reader has to be able to tell them apart at a glance:
 *
 *   STATED ADDRESS (first, white, purple-accented, required). State, district, village, pincode and
 *     an optional pin. A statement by the researcher about the SUBJECT. NOTHING on this device ever
 *     writes it. The geocoder may offer a value; a person accepts it.
 *
 *   CAPTURED AT (second, tinted, dashed, collapsed). Coordinates, radius, time. Where the DEVICE
 *     was. Filled automatically, on mount, with nothing clicked. Never described as the subject's
 *     location.
 *
 * The rule that follows from the split, and the one to keep when editing this file: the automatic
 * capture may write into the second group and may never write into the first.
 */

/**
 * The canonical state list, its districts, and the pincode rule — fetched once per page load from
 * the API that validates against them (`GET /reference/address`, backed by
 * backend/app/services/address.py).
 *
 * Deliberately NOT constants in this file. A list hard-coded here would be a second copy of the
 * server's, and the day the two disagree is the day a researcher picks a district the API refuses.
 * That argument is far stronger for districts than it ever was for states: 795 names that change
 * several times a year, and only correct PER STATE. The payload is a pure constant server-side, so
 * one request per page load is the whole cost; a failure is not cached, so the next form that
 * mounts asks again.
 *
 * THE ONE THING IT IS NOT ALLOWED TO BE IS THE ONLY SOURCE OF THE STATE LIST — see OFFLINE_STATES.
 * This is still where the names come from when the request lands; it is no longer what stands
 * between a researcher with no signal and a saved record.
 */
let addressReferenceRequest: Promise<AddressReference> | null = null;

/**
 * The address reference, with `districts` guaranteed present.
 *
 * The type says `districts` is required and the DEPLOYED api does not send it yet — the key ships
 * with this change. A response is data, not a contract, so trusting the annotation crashed the
 * artisan form outright: `reference.districts.byState` on an object with no `districts`. Normalising
 * once here is what keeps that from being five separate optional chains scattered through the
 * callers, each of which could be forgotten independently.
 *
 * An empty `byState` is the honest degraded state: every state offers no districts, the dropdown
 * says so, and the form still saves. It repairs itself the moment the backend is deployed, with no
 * client change.
 */
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

/**
 * MapLibre, fetched the first time somebody actually opens the map.
 *
 * It is by far the largest thing this app can load — 1015 KB of the production build, four and a
 * half times the next biggest chunk — and a static import put all of it on the critical path of
 * every page that renders a form with a location card (/media, /questionnaire, /workshops and the
 * new/edit routes), even though the map is behind a toggle that starts closed and that most records
 * never need. Field work happens on rural connections where that is the difference between a form
 * appearing and a form not appearing.
 *
 * Same shape as the GSAP import in components/guide: a type-only import above keeps the annotations
 * honest and compiles to nothing, and the runtime module arrives on demand. The promise is cached
 * so reopening the panel — or a second location card on the same page — does not re-fetch, and a
 * failure is not cached, so the next open tries again instead of leaving a permanently dead button.
 */
type MapLibre = typeof maplibregl;

let maplibreRequest: Promise<MapLibre> | null = null;

function loadMapLibre(): Promise<MapLibre> {
  maplibreRequest ??= Promise.all([
    import("maplibre-gl"),
    // The stylesheet ships separately; without it the canvas renders but every control is unstyled.
    import("maplibre-gl/dist/maplibre-gl.css")
  ])
    .then(([module]) => module)
    .catch((error) => {
      maplibreRequest = null;
      throw error;
    });
  return maplibreRequest;
}

/** Indian PIN codes are six digits and never begin with 0 (the first digit is the postal zone). */
const PINCODE_LENGTH = 6;

/**
 * Postal zone (the first digit of a PIN) to the states and union territories that zone serves.
 *
 * This is what makes a pincode checkable at all without a table of every code in India: the leading
 * digit is assigned geographically, so a code and a state can contradict each other in a way that is
 * provable rather than suspected. A Rajasthan code under Uttarakhand is not a maybe.
 *
 * Puducherry appears twice on purpose — Yanam sits inside Andhra Pradesh and takes a zone-5 code
 * while the rest of the territory is zone 6, so a single zone per state would flag real addresses.
 * Zone 9 is absent for the same reason it must be: it is the Army Postal Service, tied to no
 * civilian state, so every 9 against a state on this list is a genuine disagreement.
 *
 * The union of these lists is exactly the 36 names `services/address.py` serves, which is what lets
 * an unrecognised name below mean "the server's list has moved on" rather than "wrong".
 */
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

/**
 * The 36 states and union territories, offline, and the reason a record can be saved with no signal.
 *
 * WHAT WENT WRONG WITHOUT IT. The state dropdown was fed only by `GET /reference/address`, and it is
 * REQUIRED on every new record. On a dropped connection that fetch fails, so the list arrived empty
 * and the dropdown offered its placeholder and nothing else — a required closed list with no
 * members. Native constraint validation then refuses the submit, `saveOrQueue` is never reached, and
 * the IndexedDB outbox that exists precisely so a half-finished interview survives no signal never
 * sees the record. The interview and its photographs die with the tab. That is every one of the six
 * forms carrying this card, /media included, and offline capture is the whole promise of the app.
 *
 * The district dropdown was already guarded against exactly this — it stands down from required when
 * it has nothing to offer (see `districtRequired`) — and the state was not.
 *
 * WHY A LOCAL LIST RATHER THAN A KINDER FAILURE. Standing the state down the way the district does
 * would let the record save, but it would also let a researcher WITH a connection skip the field
 * during any hiccup, and the state is the one part of the address the whole dataset is grouped by.
 * The list is 36 entries that change on the order of once a decade, so there is nothing to be gained
 * by making them wait for a network round trip to answer a question they can answer from where they
 * are standing. The district genuinely cannot be bundled — 795 names, revised several times a year,
 * meaningful only per state — which is why the two are treated differently and always will be.
 *
 * WHY THIS IS NOT A SECOND COPY, which is the objection the comment above rightly raises. It is not
 * a new list; it is the one already in POSTAL_ZONES, read out. Those names are load-bearing already
 * — `postalZoneMismatch` accuses a researcher's pincode of being wrong against them — and the table
 * above states the invariant this depends on: the union of its rows is exactly the 36 names
 * `services/address.py` serves, verified against the live payload and pinned by
 * e2e/location-offline-state.spec.ts. So there is one list in this file, not two, and it cannot
 * drift from the zone check that sits beside it. When the fetch lands, the served list takes over
 * regardless.
 */
export const OFFLINE_STATES: readonly string[] = Array.from(
  new Set(Object.values(POSTAL_ZONES).flatMap((names) => names))
).sort((a, b) => a.localeCompare(b));

/**
 * The reason `state` and `pincode` cannot both be right, or null when they agree (or when either is
 * missing, or the state is a name this table does not know — an unrecognised name means the server's
 * list has outgrown the table, and guessing against it would flag correct addresses).
 *
 * Advisory, never blocking. It is a zone check, not a lookup of the real code, so it can only prove
 * a contradiction — and a field app that refuses to save a record over a heuristic is a field app
 * that loses the day's work at the edge of coverage.
 */
export function postalZoneMismatch(state: string, pincode: string): string | null {
  if (!state || pincode.length !== PINCODE_LENGTH) return null;
  const zone = pincode[0];
  const home = Object.keys(POSTAL_ZONES).filter((digit) => POSTAL_ZONES[digit].includes(state));
  if (home.length === 0 || home.includes(zone)) return null;
  const aps = zone === "9" ? " (9 is the Army Postal Service)" : "";
  return (
    `${pincode} does not match ${state}: ${state} pincodes start with ${home.join(" or ")}, ` +
    `and this one starts with ${zone}${aps}. Re-check the state or the pincode — one of the two is wrong.`
  );
}

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
  /** PROVENANCE: where the device was. Never the subject's address — see the file header. */
  latitude?: number | string | null;
  longitude?: number | string | null;
  altitude?: number | string | null;
  accuracy?: number | string | null;
  capturedAt?: string | null;
  placeName?: string | null;
  address?: string | null;
  /** STATED: the researcher's answer about the subject. Columns on Location. */
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

/**
 * A district name folded to the key both sides of a comparison can meet on, with one trailing
 * administrative word removed.
 *
 * MapTiler is inconsistent about that word in a way no amount of care at the call site fixes: the
 * same field returns "Jammu district" lowercase and "Akola District" capitalised. The words to strip
 * are NOT hard-coded here — they arrive in the reference payload
 * (`districts.normalisation.trailingWordsStripped`), which is the same list
 * backend/app/services/address.py folds with, so a client and the validator that will judge its
 * answer cannot disagree about what "Akola District" means.
 */
function districtKey(value: string, trailingWords: readonly string[]): string {
  const folded = comparableName(value);
  for (const word of trailingWords) {
    const tail = comparableName(word);
    // The guard matters: without it a district genuinely named after one of these words would be
    // folded away to nothing and then match everything.
    if (tail && folded.length > tail.length + 2 && folded.endsWith(tail)) {
      return folded.slice(0, -tail.length);
    }
  }
  return folded;
}

/** The entry of `districts` that `text` names, or "" — same closed-list discipline as the state. */
function matchDistrict(text: string, districts: readonly string[], trailingWords: readonly string[]) {
  const wanted = districtKey(text, trailingWords);
  if (!wanted) return "";
  return districts.find((entry) => districtKey(entry, trailingWords) === wanted) ?? "";
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

/** The name of the first `kind` ("region", "subregion") found, most specific feature first. */
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

/**
 * Ask MapTiler what is at these coordinates. Throws on anything the caller should ignore.
 *
 * WHICH FIELD IS THE DISTRICT, because the natural guess is wrong and was measured to be wrong. In
 * MapTiler's Indian hierarchy `region` is the STATE, `subregion` is the DISTRICT, `county` is the
 * TEHSIL and `municipality` is a city corporation. `county` is the trap: it answers "Sanganer
 * Tehsil" for Bagru, "Bhuj Taluka" for Kutch, "Joshimath Tehsil" for Rudraprayag — all real place
 * names, all the wrong administrative level, and all plausible enough to be saved. Tested against
 * sixteen real coordinates, `subregion` was right fifteen times and empty once, for a test point in
 * the Bay of Bengal that had no answer to give.
 *
 * `subregion` also survives where `postal_code` does not: 57 of 60 sampled rural Indian points
 * return no postcode at all, and every one of them still returns a district. That asymmetry is the
 * reason district is a requirable field and pincode is not.
 *
 * `municipality` and `place` are read only for the review flag below, where the question is "does
 * anything the geocoder says appear in what the researcher wrote", not "what shall we suggest".
 */
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

/**
 * The accuracy radius past which a fix cannot be allowed to name a place.
 *
 * A satellite fix is good to tens of metres. A browser with no satellite lock — indoors, in a
 * workshop, on a laptop — silently falls back to Wi-Fi, the mobile network, or the IP address, and
 * reports kilometres while returning coordinates that look every bit as precise. One of the fifteen
 * live records carries a 2,506 m radius. Rural districts and PIN areas are a few kilometres across,
 * so past a kilometre the geocoder is choosing between neighbours on the strength of an error term.
 *
 * WHAT THE LIMIT NOW GOVERNS. Nothing is auto-filled from a fix any more, so this is no longer the
 * line between "written" and "not written" — it is the line between OFFERED and NOT OFFERED. Above
 * it no suggestion appears at all, because a one-tap "yes" over a district picked out of a 2.5 km
 * circle is precisely as wrong as writing it in silently, and rather easier to click. The
 * coordinates themselves are still KEPT — see the coarse-fix notice for why losing them would be
 * worse — and named, with their radius, in words rather than only as a number in a box.
 */
const PINCODE_ACCURACY_LIMIT_METRES = 1000;

/**
 * The radius at which the automatic capture stops waiting for something better.
 *
 * `watchPosition` reports the first thing the device has (usually a Wi-Fi or cell estimate, within a
 * second) and then re-reports as the satellites lock, which is what lets the card fill itself in
 * immediately and still end up with a real reading. Something has to end the watch, though, or a
 * phone in a courtyard streams updates for as long as the form is open and burns the battery a
 * day of fieldwork depends on. A hundred metres is a genuine satellite lock by any reading, and the
 * difference between 90 m and 12 m does not change a single thing anybody does with this record.
 */
const GOOD_ENOUGH_FIX_METRES = 100;

/**
 * How long the automatic capture keeps hunting before it settles for the best it saw.
 *
 * A cold GPS start inside a workshop with a tin roof genuinely takes thirty to sixty seconds, so a
 * shorter budget would routinely file the network estimate as the answer when a real fix was ten
 * seconds away. Nothing waits on this: the coordinates from the first callback are already in the
 * boxes and the rest of the form was never blocked.
 */
const AUTO_CAPTURE_BUDGET_MS = 60_000;

/** How long the one-shot "Use current GPS" button waits. Shorter — somebody is standing there. */
const MANUAL_CAPTURE_TIMEOUT_MS = 30_000;

/**
 * "state, district and pincode" — the fields an automatic write touched, as a sentence.
 *
 * Named rather than counted. "3 fields were filled in" tells a researcher to go and hunt for them;
 * naming them tells them where to look, which is the whole value of announcing the write at all.
 */
const FIELD_NAMES = (fields: Array<"state" | "district" | "pincode">): string => {
  const words = fields.map((field) => (field === "pincode" ? "pincode" : field));
  if (words.length <= 1) return words[0] ?? "";
  return `${words.slice(0, -1).join(", ")} and ${words[words.length - 1]}`;
};

/** "±40 m" / "±12 km" — the radius as a field researcher would say it. */
function accuracyLabel(metres: number) {
  return metres < 1000 ? `±${Math.round(metres)} m` : `±${(metres / 1000).toFixed(1)} km`;
}

/** "27 Jul 2026, 03:12" — the capture time as the summary line reads it out. */
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

/**
 * Where the automatic capture has got to. Rendered, always — an auto-filled field that fills itself
 * in silence is the thing this card must not be.
 *
 * `denied`, `unavailable` and `timeout` are the three ways a device says no, kept apart because the
 * researcher's next move differs in each: unblock the browser, turn location on, or go outside. All
 * three end in the same place — the map pin — which is why none of them can be allowed to stop a
 * save. See LOCATION_REQUIRED_MESSAGE.
 */
type CaptureStatus = "idle" | "locating" | "captured" | "denied" | "unavailable" | "timeout" | "unsupported";

/**
 * What the browser refuses to submit over when the capture coordinate is missing.
 *
 * It names the fallback rather than the failure, because by the time anybody reads this the fix has
 * already not happened and telling them so again helps nobody.
 */
const LOCATION_REQUIRED_MESSAGE =
  "A location is required. The form tries to capture one by itself when it opens — if that did not " +
  "work, use 'Pick on map' to drop a pin, or type the coordinates in.";

/**
 * The same demand, made of a record that did not otherwise have to meet it, and the reason is
 * plumbing rather than principle.
 *
 * The state, district, village and pincode are COLUMNS ON THE LOCATION ROW, and `locationFromForm`
 * cannot build that row without a coordinate — so on one of the legacy records, which saves happily
 * with no coordinate at all, an address typed into the fields above would be dropped on the way out
 * with nothing said. Blocking here is the only version of this that does not lose the researcher's
 * work: the alternative is a save that appears to succeed and quietly discards the one thing they
 * opened the record to add.
 */
const ADDRESS_NEEDS_COORDINATE_MESSAGE =
  "The state and district are stored with the coordinates, so this record needs one before they " +
  "can be saved. Press 'Use current GPS' or drop a pin under 'Captured at' — or clear the address " +
  "fields to save the record as it was.";

/** What a point says about itself, once both closed lists have had their say. */
type PointAddress = {
  state: string;
  district: string;
  pincode: string;
  /** Everything the geocoder named, for the review flag's "did the researcher write any of this". */
  names: string[];
};

/**
 * Resolve a point against the API's own lists: the region against the served state list, the
 * subregion against that state's districts, the postal code against the pincode format. Anything
 * that will not resolve comes back "" — the lists are closed, and a value the API would reject is
 * worse than no answer.
 *
 * "" is a real answer and the caller must treat it as one. It is the common case, not the edge one:
 * 95% of sampled rural points in the states this repository covers carry no postal code at all.
 */
async function resolvePoint(lat: string, lng: string, signal: AbortSignal): Promise<PointAddress> {
  const [place, reference] = await Promise.all([
    reverseGeocode(lat, lng, signal),
    loadAddressReference().catch(() => null)
  ]);
  const names = [place.region, place.subregion, place.municipality, place.place].filter(Boolean);
  if (!reference) return { state: "", district: "", pincode: usablePincode(place.postalCode), names };
  const state = matchIndianState(place.region, reference.statesAndUnionTerritories);
  // No district list means an API that predates it; offer the state alone rather than nothing.
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

/**
 * The state and district hiding in a free-text place box, or nulls.
 *
 * Needed only because of how the data got here. The fifteen records that predate the stated address
 * have their real location nowhere but in prose — "Bagru, Jaipur, Rajasthan", "Kutch, Gujrat",
 * "Rudraprayag, Dehradun" — so the review flag has nothing else to compare a coordinate against.
 *
 * READ-ONLY, and that is the whole contract. Nothing this returns is ever written to a field or
 * offered as a suggestion. It exists to decide whether to show a sentence, and the sentence asks a
 * researcher to make the call. Which is exactly right, because the strings above cannot be parsed
 * reliably and this function does not pretend otherwise: "Kutch, Gujrat" matches neither list
 * (the canonical district is Kachchh and the state is misspelt), and "Rudraprayag, Dehradun" names
 * two different districts of Uttarakhand with no way to tell which one was meant.
 */
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

/**
 * Does anything the geocoder named appear in what the researcher wrote?
 *
 * The review flag's escape hatch, and it needs one: a record whose place box says "Kharagpur" and
 * whose coordinates are in Kharagpur has no stated STATE to compare, so without this it would be
 * flagged for a disagreement it does not have. Four characters is the floor — shorter than that and
 * a fragment matches by accident.
 */
function mentions(text: string, names: readonly string[]) {
  const folded = comparableName(text);
  return names.some((name) => {
    const wanted = comparableName(name);
    return wanted.length >= 4 && folded.includes(wanted);
  });
}

/**
 * A notice inside the location card. Two tones only: `info` for what is happening, `warn` for what
 * the researcher has to do something about.
 *
 * `amber-100` behind `amber-800` rather than the repo's usual `bg-amber-50` pairing: 50 and 200 are
 * not in this project's amber ramp (tailwind.config.ts defines 100/500/800), so that pairing leaves
 * dark-brown text on whatever the card is — invisible on the dark theme. These warnings are the only
 * thing standing between a 14 km network estimate and a research record, so they have to be legible
 * in both themes, which a fixed light chip is.
 */
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

/** Which of the two coordinate pairs the open map is pointing at. Never both at once. */
type MapTarget = "subject" | "capture";

/**
 * The location card: a STATED ADDRESS the researcher owns, and a CAPTURED AT the device owns.
 *
 * Pass `initial` on edit forms (the record's stored location, or null when it has none): the fields
 * are pre-filled and the device location is NOT auto-captured. Omitting `initial` (new-record forms)
 * starts the automatic capture on mount — into the provenance group only.
 *
 * WHAT "MANDATORY" MEANS, because it is the decision the rest of this file hangs off, and it now has
 * two halves.
 *
 * A COORDINATE, unchanged. A record cannot be saved without one, and it can be saved without the GPS
 * ever having worked: a pin dropped on the map, or two numbers typed in, satisfy the requirement
 * exactly as a satellite fix does. The alternative — "the fix must have succeeded" — hands the veto
 * over a day of fieldwork to a permission prompt somebody tapped Block on last month, a laptop with
 * no GPS radio, and a tin roof. A missing coordinate is a gap in a dataset; a rejected save is an
 * interview that never got written down, and the second is not recoverable.
 *
 * A STATE AND A DISTRICT, new, and the reason this file was rewritten. Both come from a closed list,
 * so a researcher with no signal can answer them from memory of where they are standing — which is
 * the test every mandatory field here has to pass, and the test the pincode still fails, which is
 * why the pincode is still optional. The two pass it differently and the difference is the whole of
 * the offline story: the 36 states are BUNDLED (OFFLINE_STATES), so that dropdown is answerable with
 * no network at all, while the 795 districts cannot be and the district therefore stands down from
 * required whenever its list has not arrived. A field is only allowed to be mandatory here if it is
 * answerable here. Required on NEW records; on an
 * EDIT they are required only if the record already carries them, so the sixteen artisans recorded
 * before these fields existed stay editable and saveable by the person best placed to fix them.
 */
export function LocationFields({
  initial,
  onDirty,
  required,
  subjectLabel = "this record",
  statedPlace
}: {
  initial?: LocationInitialValues | null;
  /** Raise the surrounding form's unsaved-changes flag; see the state dropdown below for why. */
  onDirty?: () => void;
  /**
   * Override the rules worked out below. Only pass this for a surface where a location genuinely is
   * not part of the record; the default is what every record form wants.
   */
  required?: boolean;
  /**
   * Who the stated address is about, for the heading. "the artisan" on the artisan form; the default
   * reads correctly on the five forms whose subject is the record itself.
   */
  subjectLabel?: string;
  /**
   * The record's free-text place box, READ ONLY, for the review flag. On the fifteen legacy records
   * this is the only place the real location exists ("Bagru, Jaipur, Rajasthan"), so without it a
   * coordinate in West Bengal has nothing to disagree with.
   */
  statedPlace?: string | null;
}) {
  const mapRef = useRef<HTMLDivElement | null>(null);
  const mapInstance = useRef<maplibregl.Map | null>(null);
  const subjectMarker = useRef<maplibregl.Marker | null>(null);
  const captureMarker = useRef<maplibregl.Marker | null>(null);
  // The loaded module, kept so the marker sync can build a Marker without re-awaiting the import.
  const maplibreModule = useRef<MapLibre | null>(null);
  const headingId = useId();

  const initialPincode = (initial?.pincode ?? "").replace(/\D/g, "").slice(0, PINCODE_LENGTH);

  // ---- CAPTURED AT: the device's own answer. Written automatically, never by the group below. ----
  const [latitude, setLatitude] = useState(asText(initial?.latitude));
  const [longitude, setLongitude] = useState(asText(initial?.longitude));
  const [altitude, setAltitude] = useState(asText(initial?.altitude));
  const [accuracy, setAccuracy] = useState(asText(initial?.accuracy));
  const [capturedAt, setCapturedAt] = useState(initial?.capturedAt ?? "");
  const [placeName, setPlaceName] = useState(initial?.placeName ?? "");
  const [address, setAddress] = useState(initial?.address ?? "");

  // ---- STATED ADDRESS: the researcher's answer. No automatic writer exists for any of these. ----
  const [stateName, setStateName] = useState(initial?.state ?? "");
  const [district, setDistrict] = useState(initial?.district ?? "");
  const [village, setVillage] = useState(initial?.village ?? "");
  const [pincode, setPincode] = useState(initialPincode);
  const [subjectLatitude, setSubjectLatitude] = useState(asText(initial?.subjectLatitude));
  const [subjectLongitude, setSubjectLongitude] = useState(asText(initial?.subjectLongitude));

  const [reference, setReference] = useState<AddressReference | null>(null);
  // What the current point would suggest, for the cases the auto-fill below deliberately does not
  // cover — a passive fix landing on top of an answer somebody already typed.
  const [suggestion, setSuggestion] = useState<(PointAddress & { metres: number | null }) | null>(null);
  /**
   * WHAT WAS JUST FILLED IN FOR THE RESEARCHER, and what it replaced.
   *
   * Picking a location now WRITES the state, district and pincode rather than offering them. That is
   * what was asked for, and it is the right behaviour for an explicit act — dropping a pin on a place
   * IS a statement about that place, and making somebody confirm their own action twice is friction
   * that gets clicked through rather than read.
   *
   * But an automatic write with no visible trace is exactly the bug the suggestion flow was built to
   * fix (a Bagru pincode saved onto a Dehradun record, because 95% of rural points return no postal
   * code and the stale value survived). So the write is loud instead of silent: this state drives a
   * notice naming every field that changed, and Undo puts back precisely what was there before.
   * `previous` is the whole point — an undo that cleared the fields instead of restoring them would
   * destroy a typed answer just as thoroughly as the silent overwrite did.
   */
  const [autofill, setAutofill] = useState<{
    applied: Array<"state" | "district" | "pincode">;
    previous: { state: string; district: string; pincode: string };
    /** EXPLICIT: the researcher pointed at a place. PASSIVE: a GPS fix arrived on its own. */
    mode: "explicit" | "passive";
  } | null>(null);
  // Set when the last fix was too coarse for the geocoder to be allowed to name a district.
  const [coarseFixMetres, setCoarseFixMetres] = useState<number | null>(null);
  // Set when the lookup itself failed — offline is the normal weather here, and a suggestion that
  // never arrives with no explanation reads as the form having decided the answer was nothing.
  const [lookupFailed, setLookupFailed] = useState(false);
  // The stored coordinate disagreeing with the stored place. Computed once, on edit forms, and
  // never acted on: see `reviewStoredPoint`.
  const [review, setReview] = useState<{ found: PointAddress; stated: string } | null>(null);
  // Raised the first time the browser refuses the form, so a half-filled card is not narrated back
  // as a list of errors before anybody has finished with it (Aadhaar field parity).
  const [addressProblemShown, setAddressProblemShown] = useState(false);
  const [pincodeProblemShown, setPincodeProblemShown] = useState(false);
  const pincodeRef = useRef<HTMLInputElement>(null);
  const [mapTarget, setMapTarget] = useState<MapTarget | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [captureStatus, setCaptureStatus] = useState<CaptureStatus>("idle");
  // Seconds the current hunt has been running, so "Locating" is visibly a process and not a stuck
  // spinner. A cold fix under a workshop roof takes most of a minute and looks identical to a hang.
  const [captureSeconds, setCaptureSeconds] = useState(0);
  // The radius of the fix now in the boxes, for the line that reports it back.
  const [capturedMetres, setCapturedMetres] = useState<number | null>(null);
  const [provenanceOpen, setProvenanceOpen] = useState(false);
  const latitudeRef = useRef<HTMLInputElement>(null);
  const longitudeRef = useRef<HTMLInputElement>(null);
  // The open `watchPosition`, and the best radius it has produced so far. Both refs rather than
  // state: the watch's own callback reads them, and a re-render per satellite update would fight the
  // researcher typing into the boxes underneath.
  const watchId = useRef<number | null>(null);
  const bestMetres = useRef<number | null>(null);
  // The point whose lookup failed, kept so it can be retried when the network returns. Coordinates
  // first, names later, is the normal order of events in the field, not the exception.
  const pendingLookup = useRef<{
    lat: string;
    lng: string;
    metres: number | null;
    /** Whether the point came from an explicit act, so the retry behaves the way the original would. */
    autoApply: boolean;
  } | null>(null);
  const lookup = useRef<AbortController | null>(null);
  /**
   * Whether an EXPLICIT lookup has gone out and not yet come back.
   *
   * An explicit write stands from the moment the researcher asks for it, not from the moment it lands.
   * A rural reverse geocode takes seconds and `watchPosition` re-reports about once a second, so
   * without this a satellite update arriving in that window aborts the pin's lookup — silently, since
   * an aborted request raises no failure notice and files no retry — and the pin the researcher just
   * dropped never fills anything in. The `autofill`-based guard cannot cover it: `autofill` is only set
   * once the response has been applied.
   */
  const explicitPending = useRef(false);

  /**
   * A live mirror of the three stated fields the geocoder may write.
   *
   * `applyGeocodedAddress` runs inside a promise callback created when the lookup went out, and a
   * rural lookup can take seconds — during which the researcher may have typed into these boxes. The
   * closure's copies would be stale, so the comparison ("is this already answered?") and the undo
   * snapshot both read this instead.
   */
  const statedRef = useRef({ state: stateName, district, pincode });
  // Synced in an effect rather than during render (a render-phase ref write is a lint error and a
  // concurrent-rendering hazard). An effect is sufficient here and provably so: the only reader is a
  // promise callback resolving a network response, which is a macrotask, and React has always flushed
  // its effects for a commit before the next macrotask runs. So by the time a lookup returns, this
  // mirror is current with the last keystroke.
  useEffect(() => {
    statedRef.current = { state: stateName, district, pincode };
  }, [stateName, district, pincode]);

  /**
   * A mirror of {@link autofill}, for the same reason as `statedRef` and one more.
   *
   * `offerSuggestion` is called from `watchPosition`'s callback, which fires roughly once a second
   * for as long as the automatic capture is hunting. Those calls hold whatever closure existed when
   * the watch was opened, so the live value has to come from a ref — and it is needed there to
   * enforce the rule below, that a satellite update must not wipe out the record of a write the
   * researcher explicitly asked for.
   */
  const autofillRef = useRef(autofill);
  useEffect(() => {
    autofillRef.current = autofill;
  }, [autofill]);

  const pincodeProblem = pincodeValidationError(pincode);
  const zoneProblem = postalZoneMismatch(stateName, pincode);

  /**
   * Whether the researcher is standing where this happened, as far as this component can tell.
   *
   * `initial === undefined` is a new record and the answer is yes. Anything else is an edit form,
   * and an edit form is as likely to be open on a desk in another state a week later — which is why
   * an edit NEVER auto-captures. Stamping the editor's chair onto a record documented in Bagru is
   * the failure this whole file exists to end, and it would arrive by this door as readily as by
   * the one that has been shut.
   */
  const isEditForm = initial !== undefined;
  /** A stored coordinate. `initial === null`, or a location row with no coordinate, is neither. */
  const hadStoredCoordinate = asText(initial?.latitude) !== "" && asText(initial?.longitude) !== "";
  /**
   * Enforced on every new record, and on an edit of a record that already HAS a location — where the
   * rule costs nothing and stops a stored coordinate being quietly emptied.
   *
   * NOT enforced when editing one of the records that predate the requirement. The sixteenth artisan
   * must still be correctable, and a researcher who opened it to fix a phone number cannot conjure a
   * coordinate for a workshop they are not standing in.
   */
  const mandatory = required ?? (!isEditForm || hadStoredCoordinate);
  const hasCoordinate = latitude.trim() !== "" && longitude.trim() !== "";
  /** Anything at all in the stated group. See ADDRESS_NEEDS_COORDINATE_MESSAGE for what it costs. */
  const hasStatedAddress = Boolean(stateName || district || village || pincode || subjectLatitude);
  const coordinateRequired = mandatory || hasStatedAddress;

  const districtOptions = useMemo(() => {
    // Optional at every hop, not just the first. `reference?.districts.byState` guards only
    // `reference`, so a payload from a server that has the endpoint but not yet the districts key
    // — which is precisely the deployed API right now — threw and took the whole artisan form down
    // with it. A missing district list must degrade to "no options", never to a crash: the form
    // still has to be usable while the backend catches up.
    const served = (stateName && reference?.districts?.byState?.[stateName]) || [];
    // The record's own value goes at the front until the list arrives, or an edit form would show
    // "Select district" over a district the record really holds — which reads as "not answered" and
    // invites the researcher to answer it again.
    return district && !served.includes(district) ? [district, ...served] : served;
  }, [district, reference, stateName]);

  /**
   * The state dropdown's options: the served list when it has arrived, the bundled one until then,
   * with the record's own value kept at the front if it is in neither. Same reason as
   * `districtOptions` for the record's own value; see OFFLINE_STATES for why the fallback is a list
   * rather than an empty array.
   *
   * The served list wins whenever it exists, so the day the register changes the deployed API is
   * still the authority and no client needs shipping.
   */
  const stateOptions = useMemo(() => {
    const served = reference?.statesAndUnionTerritories ?? OFFLINE_STATES;
    return stateName && !served.includes(stateName) ? [stateName, ...served] : served;
  }, [reference, stateName]);

  /**
   * Required on a new record; on an edit, required only if the record already carries the value.
   *
   * THE ASYMMETRY IS THE POINT, and it is the same one the coordinate rule makes. All fifteen live
   * locations have a NULL state and a NULL district. Demanding them on an edit would mean a
   * researcher who opened one of those records to correct a phone number could not save it until
   * they had also decided, possibly from a desk 1,500 km away, which district the artisan lives in —
   * so they would either abandon the correction or invent an answer, and invented data is worse than
   * absent data. Requiring them where the record already has them costs nothing and stops a stored
   * value being emptied by a save. The server draws the line in the same place, on the same
   * reasoning: `require_location` in backend/app/schemas/common.py enforces the pair on CREATE and
   * `forbid_clearing_location` deliberately does not on UPDATE.
   */
  const stateRequired = (required ?? (!isEditForm || Boolean(initial?.state))) && stateOptions.length > 0;
  /**
   * ...and never while there is nothing to choose from. A required dropdown with no options is a
   * form nobody can submit, and an empty district list means the reference fetch has not landed —
   * which on a field connection is a normal minute of the morning, not a fault.
   *
   * The same clause now guards the state, where it is a floor rather than the fix: OFFLINE_STATES
   * means `stateOptions` is never empty, so it should never fire. It is here because the invariant
   * is what matters — this card never demands an answer it is not offering — and a later change
   * that narrowed or dropped the bundled list would otherwise reintroduce a lost interview in
   * silence, which is exactly how the state came to be missing this guard while the district had it.
   */
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
        // Offline, or the endpoint is unhappy, which out here is a normal minute of the morning.
        // Swallowed on purpose: the state dropdown falls back to OFFLINE_STATES and stays
        // answerable, the district stands down from required, and the record still saves — into the
        // outbox if that is all there is. Nothing about a failed reference fetch may cost a record.
      });
    return () => {
      live = false;
    };
  }, []);

  // Native constraint validation is what actually stops the submit, so the browser scrolls to this
  // box and names the problem rather than the save failing somewhere out of sight.
  useEffect(() => {
    pincodeRef.current?.setCustomValidity(pincodeProblem ?? "");
  }, [pincodeProblem]);

  /**
   * Close the satellite watch. Called by every path that ends the hunt — a good enough fix, the
   * budget running out, a refusal, and a researcher who has just said where the device is by
   * pinning it or typing it, whose answer must not be overwritten by the next satellite update.
   */
  function stopWatching() {
    if (watchId.current === null) return;
    navigator.geolocation.clearWatch(watchId.current);
    watchId.current = null;
  }

  /**
   * Ask a point what it would suggest, and hold the answer for a human to accept or refuse.
   *
   * THE STALENESS RULE, which is the bug that used to live in this function's ancestor. The old code
   * wrote the geocoder's reply into the state and pincode boxes directly, and only `if (code)` — so
   * a point with no postal code left the PREVIOUS point's code sitting in the box while the state,
   * which almost always resolves, moved on without it. Two artisans into a morning that is a
   * Rajasthan pincode filed under Uttarakhand, saved by a researcher who watched the form fill
   * itself in and had no reason to doubt it. It was not a corner case either: 95% of sampled rural
   * points return no postal code at all, so the stale value was the usual outcome.
   *
   * Two things kill it here. First, no box is written at all — the reply becomes a SUGGESTION, and a
   * suggestion nobody accepted changes nothing. Second, the suggestion is cleared when the request
   * GOES OUT rather than when it comes back, because the seconds in between are exactly when a rural
   * connection leaves the last place's offer sitting under this place's coordinates, and a researcher
   * tapping "Use this" does not wait.
   */
  /**
   * WRITE the geocoded address into the stated fields, and record what it replaced.
   *
   * `overwrite` is the whole difference between the two callers, and it is not a preference:
   *
   *   * EXPLICIT (`overwrite: true`) — the researcher just pointed at a place, or pressed "Use current
   *     GPS". That is a request for THIS place's address, so a value describing the place before it is
   *     not worth more than the one they asked for. Every field is written, INCLUDING a blank pincode,
   *     because leaving the previous point's PIN under the new point's coordinates is exactly how a
   *     Bagru pincode ended up on a Dehradun record.
   *   * PASSIVE (`overwrite: false`) — a fix arrived on its own while the form was open. Only EMPTY
   *     fields are filled. A researcher typing the district while the GPS is still hunting must not
   *     have their answer overwritten a second later by a satellite.
   *
   * Returns the fields it actually changed, so the caller can decide whether there is anything to say.
   */
  function applyGeocodedAddress(found: PointAddress, { overwrite }: { overwrite: boolean }) {
    // THE REF, NOT THE CLOSURE. This runs inside a promise callback that was created when the lookup
    // went out, and a rural lookup can take seconds — during which the researcher may well have typed
    // into these very boxes. Reading the closure's copies would compare against, and restore, values
    // that were already stale, which is the silent-overwrite bug wearing a different hat.
    const previous = { ...statedRef.current };
    const applied: Array<"state" | "district" | "pincode"> = [];

    const wroteState = Boolean(
      found.state && (overwrite || !previous.state) && found.state !== previous.state
    );
    if (wroteState) {
      setStateName(found.state);
      applied.push("state");
    }

    /**
     * THE STATE THE BOX WILL ACTUALLY HOLD once the write above has, or has not, happened — and every
     * write below has to agree with it.
     *
     * A district is only meaningful INSIDE its state: "Bilaspur" is a district of Chhattisgarh and a
     * different one of Himachal Pradesh, and the API validates the pair and rejects a mismatch. So the
     * three writes cannot be independent, which is what they were.
     *
     * THE CASE THAT BROKE. A passive fix at a point the geocoder reads as Gujarat/Kachchh, on a form
     * where the researcher has already typed Rajasthan and left the district blank. The state is NOT
     * written (it is not empty, and passive never overwrites) — but the district branch only looked at
     * `previous.district`, saw it empty, and wrote "Kachchh". The form then held Rajasthan + Kachchh: a
     * pair that exists nowhere, that the researcher never entered, and that fails on save with an error
     * about a district they did not choose. The pincode had the same shape through
     * `postalZoneMismatch`, which would then accuse a correct pincode of disagreeing with a state.
     *
     * Requiring agreement is the whole fix: a district resolved against one state is written only where
     * that state is the one standing.
     */
    const effectiveState = wroteState ? found.state : previous.state;
    const geocodedStateStands = !found.state || found.state === effectiveState;

    // Written even when BLANK on the explicit path — a district belonging to the previous state is
    // worse than none, and the state above may have just changed underneath it.
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

  /** Put back exactly what was there before the last automatic write. */
  function undoAutofill() {
    if (!autofill) return;
    setStateName(autofill.previous.state);
    setDistrict(autofill.previous.district);
    setPincode(autofill.previous.pincode);
    setAutofill(null);
    onDirty?.();
  }

  /**
   * A HUMAN HAS TAKEN OVER ONE OF THE THREE BOXES the geocoder wrote.
   *
   * Two things then have to change together, and neither was happening. The notice must stop NAMING
   * that box — it says "filled in from the place you pointed at: state, district and pincode", which
   * becomes false the moment somebody edits one of them. And Undo must stop WRITING to it: restoring
   * the pre-write snapshot would throw away the value the researcher has just typed, which is the same
   * silent overwrite the whole announce-and-undo mechanism exists to prevent, arriving by the one
   * control that was supposed to be the safe way out.
   *
   * Both are fixed by moving the field's snapshot UP to what the human typed. Undo then restores it to
   * itself — a no-op for the claimed box, still correct for the ones nobody has touched — and the
   * notice drops the name because `applied` no longer lists it.
   *
   * THE RECORD SURVIVES with an empty `applied` rather than being cleared outright, because `mode` is
   * what tells a passive GPS fix to stand down (see `explicitStanding`). Typing a district must not
   * hand the three boxes back to the satellites.
   */
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

  /**
   * Look up what is at these coordinates and, on the explicit paths, WRITE it.
   *
   * WHY THE SUGGESTION SURVIVES AT ALL. It is now the PASSIVE fallback: when a fix arrives on its own
   * and the fields it would fill are already answered, there is nothing to write and something worth
   * saying, so the offer is what appears. The two paths are complementary rather than alternatives.
   *
   * WHAT HAS NOT CHANGED. The offer — and now the write — is cleared when the request GOES OUT rather
   * than when it comes back, because the seconds in between are exactly when a rural connection leaves
   * the last place's answer sitting under this place's coordinates.
   */
  function offerSuggestion(
    lat: string,
    lng: string,
    accuracyMetres?: number | null,
    { autoApply = false }: { autoApply?: boolean } = {}
  ) {
    /**
     * AN EXPLICIT WRITE OUTRANKS A PASSIVE LOOKUP, and this is the guard that makes it so.
     *
     * `watchPosition` re-reports as the satellites lock — roughly once a second while the automatic
     * capture is hunting — and every report reaches here through `acceptFix`. Without this, a
     * researcher who dropped a pin on the subject's place mid-hunt would watch the next satellite
     * update silently clear the "filled in from the place you pointed at" notice, taking its Undo with
     * it. Their answer would survive in the boxes (a passive write only fills EMPTY ones) but their
     * ability to reverse it would not, which is precisely the half-visible behaviour this whole
     * mechanism exists to avoid.
     *
     * So while an explicit act owns these three boxes, a passive fix stands down entirely: it does not
     * clear the notice, does not abort the lookup in flight, does not write, and does not look up. The
     * pin has already answered those boxes and a fix taken somewhere else has no business
     * second-guessing it — and there is nothing left to offer either, because the offer's only purpose
     * is to surface what the passive path was not allowed to write.
     */
    // "An explicit act owns these three boxes" — true from the moment one is REQUESTED (`explicitPending`)
    // until it is undone or superseded (`autofill.mode`). Both halves are needed: the ref covers the
    // seconds a rural lookup is in flight, the notice covers everything after it lands.
    const explicitStanding =
      !autoApply && (explicitPending.current || autofillRef.current?.mode === "explicit");

    // A PASSIVE FIX MUST NOT ABORT AN EXPLICIT LOOKUP. This abort is what stops the previous point's
    // answer landing under this point's coordinates, so it has to stay — but only for a call that
    // outranks what is in flight. Letting a satellite update cancel the pin's lookup was the loudest
    // version of this bug: the researcher pointed at a place and nothing whatsoever happened.
    // A map pin carries no accuracy because it needs none — the researcher pointed at the place.
    // Recorded BEFORE the stand-down below, so the "this fix was too coarse to name a place" notice
    // always describes the newest fix rather than an older one it happens to be sitting under.
    const coarse = typeof accuracyMetres === "number" && accuracyMetres > PINCODE_ACCURACY_LIMIT_METRES;
    setCoarseFixMetres(coarse ? accuracyMetres : null);

    if (explicitStanding) {
      // The offer is stale even though the write is not: it described a point nobody is asking about
      // any more. Everything else is left exactly as the explicit act left it — including the lookup
      // still in flight, which is the whole point.
      setSuggestion(null);
      return;
    }
    lookup.current?.abort();
    setSuggestion(null);
    setAutofill(null);
    setLookupFailed(false);
    if (!maptilerKey) return;
    if (autoApply) explicitPending.current = true;
    // A fix wider than a district cannot choose between neighbouring districts, so it is not invited
    // to try — and it is certainly not invited to WRITE. Rural districts and PIN areas are a few
    // kilometres across, so past a kilometre the geocoder is picking a neighbour on the strength of an
    // error term. Above the line there is no offer and no write, only the notice explaining the
    // silence. This limit is the one thing the auto-fill must not soften.
    if (coarse) return;

    const controller = new AbortController();
    lookup.current = controller;
    resolvePoint(lat, lng, controller.signal)
      .then((found) => {
        if (controller.signal.aborted) return;
        if (autoApply) explicitPending.current = false;
        pendingLookup.current = null;
        // Nothing to offer is a complete answer, and it stays visible as an absence rather than as
        // the last point's suggestion.
        if (!found.state && !found.district) return;
        const { applied, previous } = applyGeocodedAddress(found, { overwrite: autoApply });
        if (applied.length) {
          setAutofill({ applied, previous, mode: autoApply ? "explicit" : "passive" });
        }
        if (autoApply) return;
        // PASSIVE ONLY. Anything the passive path could not fill — because it was already answered —
        // is still worth OFFERING, so a disagreement between the fix and the typed answer is visible
        // rather than swallowed. On the explicit path there is nothing left to offer: everything was
        // written, and the notice says so.
        const unfilled =
          (found.state && found.state !== previous.state && !applied.includes("state")) ||
          (found.district && found.district !== previous.district && !applied.includes("district")) ||
          (found.pincode && found.pincode !== previous.pincode && !applied.includes("pincode"));
        if (unfilled) setSuggestion({ ...found, metres: accuracyMetres ?? null });
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        // Released even on failure, or one offline pin drop would lock out every passive fix for the
        // rest of the form's life. The pending POINT is what carries the intent forward, below.
        if (autoApply) explicitPending.current = false;
        setLookupFailed(true);
        // Hold the point rather than the failure. A GPS fix needs no network and a reverse geocode
        // needs one, so out in a village the coordinates land and the names do not — which is the
        // normal sequence here, not a fault. Retried below the moment there is signal again.
        //
        // The MODE is held too. A pin the researcher dropped before losing signal is still an explicit
        // statement about that place when the network comes back ten minutes later; downgrading the
        // retry to a passive offer would silently make the offline path behave differently from the
        // online one for the same action.
        pendingLookup.current = { lat, lng, metres: accuracyMetres ?? null, autoApply };
      });
  }

  useEffect(() => () => lookup.current?.abort(), []);

  /**
   * Coordinates first, names later: re-ask for the point that was looked up offline, as soon as the
   * browser says there is a connection.
   */
  useEffect(() => {
    function retry() {
      const point = pendingLookup.current;
      if (!point) return;
      /**
       * AN EXPLICIT REPLAY IS AUTHORISED BY THE PIN, so it has to still BE the pin.
       *
       * "Remove pin" clears the coordinates and leaves this ref standing, so without this check the
       * sequence — go offline, drop a pin, remove it, regain signal — replays an OVERWRITE of the
       * state, district and pincode minutes later, from a point nobody is pointing at any more. The
       * write would be announced, which is the promise this card makes, but announced is not the same
       * as asked for.
       *
       * The coordinates are compared as the strings the pin wrote, which is exactly what
       * `placeSubjectPin` stored — so this is an identity test, not a distance one.
       */
      if (point.autoApply && (point.lat !== subjectLatitude || point.lng !== subjectLongitude)) {
        pendingLookup.current = null;
        return;
      }
      offerSuggestion(point.lat, point.lng, point.metres, { autoApply: point.autoApply });
    }
    window.addEventListener("online", retry);
    return () => window.removeEventListener("online", retry);
    // Re-bound when the subject pin moves, so `retry` always compares against the pin standing NOW.
    // Everything else `offerSuggestion` touches it reads through a ref or writes through a setter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subjectLatitude, subjectLongitude]);

  /**
   * THE REVIEW FLAG, and the one thing it must never do is fix anything.
   *
   * On an edit form with a stored coordinate, ask the geocoder where that coordinate actually is and
   * compare the answer with what the record says about itself — the stated state if it has one, and
   * otherwise the free-text place box, which on the fifteen legacy records is the only place the
   * real location exists. Where they disagree, say so, and stop.
   *
   * WHY IT ONLY RUNS ON EDITS. On a NEW record a disagreement is the expected, correct state of
   * affairs: a researcher at a desk in Kharagpur documenting a Bagru artisan SHOULD have West Bengal
   * coordinates and a Rajasthan address, and flagging that would be flagging the feature. What is
   * worth surfacing is the fifteen records where nobody was ever asked the question, and the place
   * to surface it is the form the researcher who was there can correct.
   *
   * NO WRITE, NO BACKFILL, NO MIGRATION. This changes not one stored value. It shows a sentence.
   */
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
        // A state on both sides that disagrees is proof. No state on the stated side is only a
        // question, and it is asked rather than asserted — unless something the geocoder named
        // appears in the researcher's own words, in which case the two agree well enough.
        const contradicted = claimed ? claimed !== found.state : !mentions(stated, found.names);
        if (contradicted) setReview({ found, stated });
      })
      .catch(() => {
        // Offline. The flag is a review aid, not a gate; it can wait for a connection.
      });
    return () => controller.abort();
    // Runs once per mounted edit form, against the values the record arrived with.
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

    // Purple is the action colour and the stated address is the thing the researcher acts on; the
    // device fix is slate, because it is a fact about the recording rather than a claim about the
    // subject. Seeing the two on one canvas, sometimes 1,500 km apart, is the clearest statement of
    // the difference this card can make.
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

  /**
   * The researcher has pointed at the subject's place. This is a STATEMENT, so it goes in the stated
   * group's own columns and touches nothing the device wrote.
   *
   * IT NOW ALSO FILLS IN THE STATE, DISTRICT AND PINCODE, overwriting whatever was there. That is the
   * one path where overwriting is unambiguously right: the pin is a direct assertion about where the
   * subject is, made by a person, on a map, deliberately. Asking them to then confirm the state and
   * district implied by their own pin is a second question about the same answer, and a second
   * question about the same answer gets clicked through rather than read.
   *
   * The write is announced and undoable — see `autofill` — because an automatic write nobody can see
   * is how a Bagru pincode ended up on a Dehradun record. Announced and reversible is a different
   * thing from silent.
   *
   * A hand-placed pin carries no accuracy, so the coarse-fix guard does not apply: the researcher
   * pointed at the place, and a pointer has no error radius to disqualify it.
   */
  function placeSubjectPin(lat: string, lng: string) {
    setSubjectLatitude(lat);
    setSubjectLongitude(lng);
    offerSuggestion(lat, lng, null, { autoApply: true });
    onDirty?.();
  }

  /**
   * The researcher has said where the recording happened, because the device could not. Ends the
   * hunt — nothing the satellites say afterwards outranks it — and clears the two values that
   * described the device's own reading, since a hand-placed point measured nothing and happened at
   * no particular instant.
   */
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
    // Closing the panel (or unmounting the form) before the download lands must not leave a map
    // attached to a container React has already taken back.
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
      .catch(() => {
        if (!cancelled) setMessage("The map could not be loaded. You can still type or tag coordinates.");
      });

    return () => {
      // Destroy the instance when the panel closes/unmounts so reopening re-initialises cleanly.
      cancelled = true;
      subjectMarker.current?.remove();
      subjectMarker.current = null;
      captureMarker.current?.remove();
      captureMarker.current = null;
      map?.remove();
      if (map && mapInstance.current === map) mapInstance.current = null;
    };
    // Recreate only when the target changes; the coordinates are read at open time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapTarget]);

  // Keep whichever markers exist in step with the boxes, without rebuilding the map.
  useEffect(() => {
    syncMarkers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [latitude, longitude, subjectLatitude, subjectLongitude]);

  /**
   * Put a fix in the provenance boxes, AND NOWHERE ELSE. This function is the one the finding is
   * about: its ancestor reached into the address fields, and the state, district and village above
   * are now beyond anything it can touch.
   *
   * Every field the device answered is REPLACED, including the ones it answered with nothing: an
   * altitude the last fix had and this one does not must not be left standing under this one's
   * coordinates. A value that arrived by itself and then failed to move is the most convincing kind
   * of wrong.
   */
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
    // Deliberately NOT raising the form's dirty flag. The automatic capture is the only writer here
    // that no human asked for, and a blank new form that announces unsaved work before anybody has
    // typed a character trains researchers to click through the leave-guard — which is the dialog
    // that has to still mean something an hour later when there IS an interview in the form. The
    // button and the map pin raise it at their own call sites.
  }

  /** The browser's refusal, sorted into the three things the researcher can actually do about it. */
  function classifyError(error: GeolocationPositionError): CaptureStatus {
    if (error.code === error.PERMISSION_DENIED) return "denied";
    if (error.code === error.TIMEOUT) return "timeout";
    return "unavailable";
  }

  /**
   * Ask the device where it is, the moment the form opens, and keep asking until the answer is good.
   *
   * WHY A WATCH RATHER THAN `getCurrentPosition`. One shot forces a choice between two bad options:
   * a short timeout that files the Wi-Fi estimate as the answer, or a long one that leaves the card
   * empty and silent for the thirty to sixty seconds a cold GPS start really takes under a workshop
   * roof. A watch has neither problem — the first callback (usually inside a second) fills the boxes
   * so nothing is ever lost, and each later one REPLACES it as the satellites lock. Nothing blocks:
   * the rest of the form was interactive before this function was called and stays that way.
   *
   * Only a strictly better radius is accepted, so the network estimate cannot land on top of the
   * satellite fix that has already superseded it — `watchPosition` does not promise monotone
   * improvement, and on a phone switching between towers it genuinely does get worse again.
   */
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
        // Stop on a real satellite lock, or when the budget is spent and this is the best there was.
        if (metres <= GOOD_ENOUGH_FIX_METRES || Date.now() >= deadline) stopWatching();
      },
      (error) => {
        stopWatching();
        // A refusal that arrives after a fix already landed changes nothing about the fix; the
        // coordinates stand and the card keeps reporting them.
        if (bestMetres.current !== null) return;
        setCaptureStatus(classifyError(error));
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: AUTO_CAPTURE_BUDGET_MS }
    );

    // The watch's own `timeout` only fires when NOTHING arrives; a stream of coarse updates keeps it
    // alive indefinitely, which is the case the budget is actually for.
    window.setTimeout(() => {
      if (watchId.current === null) return;
      stopWatching();
      if (bestMetres.current === null) setCaptureStatus("timeout");
    }, AUTO_CAPTURE_BUDGET_MS);
  }

  /** "Use current GPS": one shot, because somebody is standing there waiting for it. */
  function capturePreciseLocation() {
    stopWatching();
    if (!navigator.geolocation) {
      setCaptureStatus("unsupported");
      setMessage("Precise location is not supported by this browser.");
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
    // Edit forms (initial provided, even as null) never auto-capture: the stored values must not be
    // silently overwritten by the editor's current device position, and an edit is as likely to be
    // happening at a desk a week later as in the workshop. Capture is button-only there.
    if (isEditForm) return;
    startAutoCapture();
    return stopWatching;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The elapsed counter. Only runs while something is actually being waited for.
  useEffect(() => {
    if (captureStatus !== "locating") return;
    const tick = window.setInterval(() => setCaptureSeconds((value) => value + 1), 1000);
    return () => window.clearInterval(tick);
  }, [captureStatus]);

  /**
   * The coordinate rule, told to the browser, so the save stops at this card with a sentence worth
   * reading instead of failing somewhere out of sight — the same mechanism the pincode uses.
   *
   * Set on BOTH coordinate boxes: either one alone is not a location, and the browser then scrolls
   * to and focuses whichever is empty.
   */
  useEffect(() => {
    const problem = !coordinateRequired || hasCoordinate
      ? ""
      : mandatory
        ? LOCATION_REQUIRED_MESSAGE
        : ADDRESS_NEEDS_COORDINATE_MESSAGE;
    latitudeRef.current?.setCustomValidity(problem);
    longitudeRef.current?.setCustomValidity(problem);
  }, [coordinateRequired, mandatory, hasCoordinate]);

  /**
   * Open the provenance panel when the researcher has to do something in it, and only then.
   *
   * Collapsed is right when the capture worked, because there is nothing to decide and a reader who
   * mistakes it for the address is the failure this card is preventing. Collapsed is wrong when the
   * device has already refused: the coordinate is still required, the way out is inside the panel,
   * and a form that hides the control it is blocking on reads as broken.
   *
   * The condition is fussier than "no coordinate yet" for a reason found in the browser: a new form
   * has no coordinate for the second or two before the first fix lands, so the looser rule flung the
   * panel open on every single page load and then left it open, which is the opposite of what the
   * split is for. So it waits for the device to have actually finished saying no — or for the two
   * cases where nothing is coming at all: an edit form, which never auto-captures, and a stated
   * address typed onto a record that has no coordinate to hang it from.
   *
   * It opens the panel; it does not hold it open. A researcher who deals with it can fold it away.
   */
  const captureRefused =
    captureStatus === "denied" ||
    captureStatus === "unavailable" ||
    captureStatus === "timeout" ||
    captureStatus === "unsupported";

  useEffect(() => {
    if (!coordinateRequired || hasCoordinate) return;
    if (captureRefused || isEditForm || hasStatedAddress) setProvenanceOpen(true);
    // isEditForm is fixed for the lifetime of the component.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [coordinateRequired, hasCoordinate, captureRefused, hasStatedAddress]);

  /** A human touching either box owns the coordinate, so the satellite watch stops overwriting it. */
  function editCoordinate(setter: (next: string) => void, next: string) {
    stopWatching();
    if (captureStatus === "locating") setCaptureStatus("idle");
    setter(next);
  }

  /**
   * The researcher said yes to a suggestion the automatic write did not cover.
   *
   * It is no longer the only path by which a geocoder's answer reaches a field — see
   * `applyGeocodedAddress` — but it is still the only path by which one OVERWRITES an answer somebody
   * typed. That asymmetry is deliberate: a pin drop may overwrite because the pin IS the statement,
   * and a passive GPS fix may not, so what the fix would have said is offered here instead.
   *
   * THE WHOLE ADDRESS, INCLUDING THE PARTS THAT ARE EMPTY. "Use this place's address" means this
   * place's address, so a point with no postal code clears the pincode box rather than leaving the
   * last point's six digits standing under this point's state — which is the original bug wearing
   * its last available disguise. It reached this function while the accept path only wrote the
   * pincode `if (suggestion.pincode)`, which is character for character the `if (code)` that put a
   * Bagru PIN on a Dehradun record. It is not a rare shape either: 95% of sampled rural points in
   * these states carry no postal code, so "no answer" is the usual answer.
   *
   * A typed pincode is lost when this is pressed, and that is the right trade. The button is an
   * explicit request for a different place's address; a value from the place before it is not worth
   * more than the one the researcher just asked for, and the box is one keystroke from being right.
   */
  function acceptSuggestion() {
    if (!suggestion) return;
    setStateName(suggestion.state);
    setDistrict(suggestion.district);
    setPincode(suggestion.pincode);
    setPincodeProblemShown(false);
    setSuggestion(null);
    setAddressProblemShown(false);
    // The "we filled this in for you" notice described the PASSIVE write this has just superseded.
    // Leaving it up would offer an Undo that restores values from two edits ago.
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
      Add NEXT_PUBLIC_MAPTILER_API_KEY to enable map pointing. Coordinates can still be entered manually.
    </CardNotice>
  );

  return (
    <div className="grid gap-3">
      {/*
        GROUP ONE — the researcher's statement about the subject. White card, purple accent, first
        on the page and required, because it is the answer the dataset is actually built from.
      */}
      <section
        aria-labelledby={headingId}
        className="grid gap-3 rounded-lg border border-line-200 bg-card p-4 shadow-sm"
      >
        <div>
          <h3 id={headingId} className="font-display font-bold text-lg text-ink-900">
            Location of {subjectLabel}
            {stateRequired ? " *" : ""}
          </h3>
          <p className="mt-1 text-sm text-ink-500">
            Where {subjectLabel} is. This is what the map, the exports and the research dataset use.{" "}
            <strong className="font-semibold text-ink-700">
              Pointing at a place on the map fills the state, district and pincode in for you
            </strong>{" "}
            — and says so, so you can put it back. A GPS fix fills in only what is still blank, because the
            device is very often at a desk in another state from {subjectLabel}.
          </p>
        </div>

        {/*
          WHAT WAS JUST WRITTEN, and how to undo it. A form that fills itself in silently is the bug the
          suggestion flow below was built to fix — a Bagru pincode saved onto a Dehradun record, because
          95% of rural points return no postal code and the stale value survived. Filling in
          automatically is fine; doing it invisibly is not. So every automatic write names the fields it
          touched and offers exactly one button that restores what was there.
        */}
        {/* Hidden once every box it wrote has been claimed by a human. `autofill` itself survives that
            (it is what keeps a passive GPS fix standing down), but a notice naming nothing and
            offering an Undo that would restore nothing is noise over the researcher's own answer. */}
        {autofill && autofill.applied.length ? (
          <div className="grid gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2.5 text-sm text-purple-950">
            <p className="flex items-start gap-2">
              <Radar className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
              <span>
                {autofill.mode === "explicit"
                  ? "Filled in from the place you pointed at:"
                  : "Filled in from this device's location (only the boxes that were empty):"}{" "}
                <strong className="font-semibold">{FIELD_NAMES(autofill.applied)}</strong>. Check it, and
                change anything that is wrong — you know this place and the geocoder does not.
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
          /*
           * The fifteen records, shown to the one person who can settle them. It states both
           * readings and asks; it does not choose, it does not pre-fill the dropdowns below, and it
           * writes nothing. Hidden the moment the researcher answers, because at that point they
           * have acted on it and repeating it would just be noise over their own answer.
           */
          <CardNotice tone="warn" icon={<ScanSearch className="h-4 w-4" aria-hidden />}>
            <strong className="font-semibold">Needs review — the coordinates and the recorded place disagree.</strong>{" "}
            The coordinates saved on this record are in{" "}
            <strong className="font-semibold">
              {[review.found.district, review.found.state].filter(Boolean).join(", ")}
            </strong>
            , but the place recorded is &ldquo;{review.stated}&rdquo;. Those coordinates are where the device was when
            the record was made, not where {subjectLabel} is — which is exactly the gap these fields now close. Nothing
            has been changed and nothing will be until you save. Set the state and district below if you know them.
          </CardNotice>
        ) : null}

        {suggestion ? (
          /*
           * THE GEOCODER SUGGESTS, THE RESEARCHER DECIDES. On site this is one tap and the address
           * is right; at a desk in Kharagpur it is one tap the other way and the address stays
           * empty for the person who knows it. Neither outcome is available if the form has already
           * decided, which is what the previous version did and why fifteen records say Kharagpur.
           */
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
            <div className="flex flex-wrap gap-2">
              <button type="button" className="field-button" onClick={acceptSuggestion}>
                Yes, use this
              </button>
              {/*
                Literal colours, not `field-button-secondary`. That class is `bg-card` over
                `text-ink-900`, and both invert with the theme while the chip they sit on does not —
                so in dark mode a themed button lands as a near-black pill on a pale lavender card.
                Same reasoning as CardNotice: a fixed light chip needs fixed dark ink.
              */}
              <button
                type="button"
                className="inline-flex min-h-10 items-center justify-center rounded-md border border-purple-300 bg-white px-4 py-2 text-sm font-medium text-purple-800 transition hover:bg-purple-100"
                onClick={() => setSuggestion(null)}
              >
                No, {subjectLabel} is elsewhere
              </button>
            </div>
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
                // A district only means anything inside its own state, so changing the state
                // invalidates the answer below it. Cleared rather than kept: a Rajasthan district
                // left standing under Uttarakhand is the staleness bug wearing a different hat, and
                // the server would reject it anyway with a message about the wrong state.
                setDistrict("");
                setAddressProblemShown(false);
                // Both boxes are now the researcher's, so neither may be named by the auto-fill notice
                // or restored by its Undo. The district goes too because this handler just cleared it.
                claimField("state", event.target.value);
                claimField("district", "");
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
              // An empty dropdown with nothing said about it reads as a broken form, and a
              // researcher who thinks the form is broken stops filling it in. The 795 district names
              // are the one part of this card that genuinely needs the network — say so, say the
              // record saves anyway, and let them get on with the interview.
              <p className="text-xs text-ink-500">
                The district list has not loaded — it needs a connection, unlike the states. The state above is enough
                to save this record; add the district when there is signal.
              </p>
            ) : null}
          </Field>
          <Field label="Village or place">
            <TextInput
              name="village"
              value={village}
              placeholder="Bagru"
              onChange={(event) => setVillage(event.target.value)}
            />
          </Field>
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
          // Surfaced, never enforced. The zone digit can prove the pair wrong but never prove it
          // right, so this names the contradiction and leaves the researcher — who was standing
          // there — to say which half of it is the mistake.
          <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
            {zoneProblem}
          </CardNotice>
        ) : null}

        {coarseFixMetres !== null ? (
          /*
           * The coarse fix, named, and the reason no suggestion appeared. The coordinates ARE kept:
           * dropping them would leave the record with no location at all over a radius nobody would
           * otherwise have noticed, and a rough position beats none. What must not happen is a
           * district arriving from a 2.5 km circle with a one-tap Yes beside it.
           */
          <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
            No district was suggested: this device&apos;s fix is only accurate to{" "}
            {accuracyLabel(coarseFixMetres)}, which is its network estimate of where it is rather than a satellite
            reading, and a circle that wide covers more than one district. The coordinates have been kept with their
            radius, so nothing is lost. Choose the state and district yourself, or step outside for a real fix.
          </CardNotice>
        ) : null}

        {lookupFailed ? (
          <CardNotice tone="info">
            Could not look up where this device is — no suggestion this time. The coordinates are saved either way, and
            the lookup is retried by itself when there is signal again. The two fields above are yours to fill in
            regardless; they need no network.
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
                  className="text-sm font-medium text-purple-700 underline underline-offset-2"
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
                Tap the workshop, the house or the village on the map. This pin is stored separately from the device
                coordinates below — the purple marker is {subjectLabel}, the grey one is where this record was made.
              </p>
              {mapPanel}
            </>
          ) : null}
        </div>
      </section>

      {/*
        GROUP TWO — provenance. Collapsed, and second, because the one thing it must never be
        mistaken for is the answer above.

        It carries the SAME card treatment as every other section in these forms — solid line-200
        border, white surface, small purple-tinted shadow, the shape MediaCaptureField and the
        stated-address group above both use. It was a dashed, tinted panel, which read as a
        placeholder or a drop zone rather than as a card: nothing else in the app has a dashed
        border, so "different" landed as "unfinished" instead of "secondary".

        The hierarchy is carried by order and by disclosure — stated address first and open, this
        one second and folded — rather than by a decoration. Both cards are the plain card, because
        every other section in these forms is too, and a form whose sections all look alike is one
        the eye can scan.
      */}
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
              {/*
                The announcement, and it has to live HERE rather than only in the notices inside.
                When the capture works the panel stays folded, so this line is the whole of what a
                researcher sees of a field that filled itself in — and a live region inside a closed
                <details> is display:none, which is a live region a screen reader never reads.
              */}
              <span aria-live="polite" className="font-mono text-xs text-ink-500">
                {captureSummary}
              </span>
            </span>
          </summary>

          <div className="grid gap-3 border-t border-line-200 px-4 py-3">
            <p className="text-sm text-ink-500">
              Provenance, not an address. These values say where the device was and how well it knew, so that anybody
              reading this record later can judge it. They are never used as the location of {subjectLabel}.
            </p>

            {/*
              Announced rather than merely rendered: the whole hazard of a field that fills itself in
              is that nobody notices it did.
            */}
            <div aria-live="polite" className="grid gap-2 empty:hidden">
              {captureStatus === "locating" ? (
                <CardNotice
                  tone="info"
                  icon={<LoaderCircle className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />}
                >
                  Finding this device&apos;s location… {captureSeconds}s.{" "}
                  {captureSeconds >= 8
                    ? "A first satellite fix can take up to a minute indoors — carry on filling the form, the coordinates will drop in on their own."
                    : "Carry on filling the form."}
                </CardNotice>
              ) : null}
              {captureStatus === "captured" && capturedMetres !== null ? (
                <CardNotice tone="info" icon={<CheckCircle2 className="h-4 w-4 text-success-600" aria-hidden />}>
                  This device&apos;s location was captured automatically: {latitude}, {longitude} (
                  {accuracyLabel(capturedMetres)}). That is where you are, not where {subjectLabel} is — state that
                  above.
                </CardNotice>
              ) : null}
              {captureStatus === "denied" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  This browser is blocking location, so nothing could be captured
                  {mandatory ? ", and a coordinate is required" : ""}. Use <strong>Pick on map</strong> below to drop a
                  pin where you are — that satisfies it exactly as a GPS fix does. To use the GPS instead, allow
                  location for this site in the address bar and press <strong>Use current GPS</strong>. The state and
                  district above are unaffected: they never came from the GPS.
                </CardNotice>
              ) : null}
              {captureStatus === "unavailable" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  This device could not produce a location — location services are usually switched off, or there is no
                  GPS hardware (common on a desktop). Drop a pin with <strong>Pick on map</strong>, or type the
                  coordinates in below.
                </CardNotice>
              ) : null}
              {captureStatus === "timeout" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  No fix after a minute of trying — thick walls and tin roofs do this. Step outside and press{" "}
                  <strong>Use current GPS</strong>, or drop a pin with <strong>Pick on map</strong>.
                </CardNotice>
              ) : null}
              {captureStatus === "unsupported" ? (
                <CardNotice tone="warn" icon={<TriangleAlert className="h-4 w-4" aria-hidden />}>
                  This browser has no location support at all. Drop a pin with <strong>Pick on map</strong>, or type the
                  coordinates in below.
                </CardNotice>
              ) : null}
              {message ? <CardNotice tone="info">{message}</CardNotice> : null}
            </div>

            <div className="flex flex-wrap gap-2">
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
            </div>
            {mapTarget === "capture" ? mapPanel : null}

            <input type="hidden" name="capturedAt" value={capturedAt} />
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {/*
                Raw inputs rather than `TextInput`, for the same reason the pincode above is one:
                these two need a ref so `setCustomValidity` can carry the requirement, and the
                browser's own constraint validation is what actually stops the submit and scrolls
                the researcher here.
              */}
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
              /*
               * The legacy row. This record predates the requirement, so it saves without a
               * coordinate — but a researcher who IS at the place should be told they can close the
               * gap while they are here, and one who is at a desk should be told not to.
               */
              <CardNotice tone="info">
                This record was created before a coordinate was required, so it still saves without one. If you are at
                the place right now, press <strong>Use current GPS</strong> to fill the gap — do not add one from
                somewhere else.
              </CardNotice>
            ) : null}
            {!mandatory && !hasCoordinate && hasStatedAddress ? (
              // Not a rule anybody chose — see ADDRESS_NEEDS_COORDINATE_MESSAGE. Said here, in the
              // panel that can satisfy it, as well as in the browser's own bubble on the coordinate.
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
