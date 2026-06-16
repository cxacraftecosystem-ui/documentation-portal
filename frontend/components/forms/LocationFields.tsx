"use client";

import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { LocateFixed, MapPinned } from "lucide-react";

import { Field, TextInput } from "@/components/FormControls";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

export function LocationFields() {
  const mapRef = useRef<HTMLDivElement | null>(null);
  const mapInstance = useRef<maplibregl.Map | null>(null);
  const marker = useRef<maplibregl.Marker | null>(null);
  const [latitude, setLatitude] = useState("");
  const [longitude, setLongitude] = useState("");
  const [altitude, setAltitude] = useState("");
  const [accuracy, setAccuracy] = useState("");
  const [placeName, setPlaceName] = useState("");
  const [address, setAddress] = useState("");
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
    if (!mapOpen || !mapRef.current || !maptilerKey || mapInstance.current) return;
    const center: [number, number] = [Number(longitude) || 87.3105, Number(latitude) || 22.3149];
    mapInstance.current = new maplibregl.Map({
      container: mapRef.current,
      style: `https://api.maptiler.com/maps/streets-v2/style.json?key=${maptilerKey}`,
      center,
      zoom: latitude && longitude ? 14 : 4
    });
    mapInstance.current.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");
    if (latitude && longitude) {
      syncMapMarker(latitude, longitude, 14);
    }
    mapInstance.current.on("click", (event) => {
      const lat = event.lngLat.lat.toFixed(7);
      const lng = event.lngLat.lng.toFixed(7);
      setLatitude(lat);
      setLongitude(lng);
      syncMapMarker(lat, lng);
    });
  }, [latitude, longitude, mapOpen]);

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
        setMessage(silent ? "Precise location populated. You can edit it or point a different place on the map." : "Precise location captured.");
      },
      (error) => {
        if (!silent) setMessage(error.message);
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 }
    );
  }

  useEffect(() => {
    capturePreciseLocation(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <section className="grid gap-3 border-t border-[#e6dfd8] pt-4">
      <div className="flex flex-wrap gap-2">
        <button type="button" className="field-button-secondary" onClick={() => capturePreciseLocation(false)}>
          <LocateFixed className="h-4 w-4" aria-hidden />
          Use precise location
        </button>
        <button type="button" className="field-button-secondary" onClick={() => setMapOpen((value) => !value)}>
          <MapPinned className="h-4 w-4" aria-hidden />
          Point it on map
        </button>
      </div>
      {message ? <div className="rounded-md border border-[#e6dfd8] bg-field-100 px-3 py-2 text-sm text-ink-muted">{message}</div> : null}
      {mapOpen ? (
        maptilerKey ? (
          <div ref={mapRef} className="h-80 overflow-hidden rounded-md border border-[#d8d0c4]" />
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
