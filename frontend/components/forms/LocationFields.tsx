"use client";

import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { LocateFixed, MapPinned } from "lucide-react";

import { Field, TextInput } from "@/components/FormControls";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

/** Initial location values for edit forms (matches the API's Location payload). */
export type LocationInitialValues = {
  latitude?: number | string | null;
  longitude?: number | string | null;
  altitude?: number | string | null;
  accuracy?: number | string | null;
  placeName?: string | null;
  address?: string | null;
};

function asText(value: number | string | null | undefined) {
  return value === null || value === undefined ? "" : String(value);
}

/**
 * "Location" card (Android `LocationEditor` parity): two centered actions — "Use current GPS"
 * (primary) and "Pick on map" (secondary) — with the coordinate/place fields below. Pass `initial`
 * on edit forms (the record's stored location, or null when it has none): the fields are pre-filled
 * and the device location is NOT auto-captured on mount — capture then only happens on the explicit
 * "Use current GPS" button. Omitting `initial` (new-record forms) keeps the silent auto-capture on
 * mount.
 */
export function LocationFields({ initial }: { initial?: LocationInitialValues | null }) {
  const mapRef = useRef<HTMLDivElement | null>(null);
  const mapInstance = useRef<maplibregl.Map | null>(null);
  const marker = useRef<maplibregl.Marker | null>(null);
  const [latitude, setLatitude] = useState(asText(initial?.latitude));
  const [longitude, setLongitude] = useState(asText(initial?.longitude));
  const [altitude, setAltitude] = useState(asText(initial?.altitude));
  const [accuracy, setAccuracy] = useState(asText(initial?.accuracy));
  const [placeName, setPlaceName] = useState(initial?.placeName ?? "");
  const [address, setAddress] = useState(initial?.address ?? "");
  const [mapOpen, setMapOpen] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

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
        <p className="mt-1 text-sm text-ink-500">Tag where this was documented. Enter coordinates, capture a GPS fix, or drop a pin on the map.</p>
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
      </div>
    </section>
  );
}
