"use client";

import { useCallback, useEffect, useRef } from "react";
import type * as maplibregl from "maplibre-gl";

import { PlaceSearchBox } from "@/components/forms/PlaceSearchBox";
import type { PlaceHit } from "@/lib/placeSearch";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

export function mapPickerAvailable(): boolean {
  return Boolean(maptilerKey);
}

export type MapLibre = typeof maplibregl;

let maplibreRequest: Promise<MapLibre> | null = null;

export function loadMapLibre(): Promise<MapLibre> {
  maplibreRequest ??= Promise.all([
    import("maplibre-gl"),
    import("maplibre-gl/dist/maplibre-gl.css"),
    import("@/components/forms/mapLibreWorker")
  ])
    .then(([module, , worker]) => worker.withBundledWorker(module))
    .catch((error) => {
      maplibreRequest = null;
      throw error;
    });
  return maplibreRequest;
}

const INDIA_CENTRE: [number, number] = [78.9629, 22.5937];

const PLACE_MAX_ZOOM = 14;

const PLACE_POINT_ZOOM = 13;

export function flyToPlace(map: maplibregl.Map, hit: PlaceHit): void {
  if (hit.bbox) {
    map.fitBounds(hit.bbox, { padding: 40, maxZoom: PLACE_MAX_ZOOM, essential: true });
    return;
  }
  map.flyTo({ center: [hit.lon, hit.lat], zoom: PLACE_POINT_ZOOM, essential: true });
}

export function MapPointPicker({
  lat,
  lon,
  onPick,
  onFailure,
  markerColor = "#6b21a8",
  ariaLabel
}: {
  lat: number | null;
  lon: number | null;
  onPick: (lat: string, lon: string) => void;
  onFailure?: () => void;
  markerColor?: string;
  ariaLabel: string;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const moduleRef = useRef<MapLibre | null>(null);
  const pickRef = useRef(onPick);
  useEffect(() => {
    pickRef.current = onPick;
  }, [onPick]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !maptilerKey) return;
    let cancelled = false;
    let instance: maplibregl.Map | null = null;

    loadMapLibre()
      .then((maplibre) => {
        if (cancelled) return;
        moduleRef.current = maplibre;
        const map = new maplibre.Map({
          container,
          style: `https://api.maptiler.com/maps/streets-v2/style.json?key=${maptilerKey}`,
          center: lat !== null && lon !== null ? [lon, lat] : INDIA_CENTRE,
          zoom: lat !== null && lon !== null ? 12 : 4
        });
        instance = map;
        mapRef.current = map;
        map.addControl(new maplibre.NavigationControl({ visualizePitch: true }), "top-right");
        map.on("click", (event) => {
          pickRef.current(event.lngLat.lat.toFixed(7), event.lngLat.lng.toFixed(7));
        });
      })
      .catch(() => {
        if (!cancelled) onFailure?.();
      });

    return () => {
      cancelled = true;
      markerRef.current?.remove();
      markerRef.current = null;
      instance?.remove();
      if (instance && mapRef.current === instance) mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const maplibre = moduleRef.current;
    const map = mapRef.current;
    if (!maplibre || !map) return;
    markerRef.current?.remove();
    markerRef.current = null;
    if (lat === null || lon === null) return;
    markerRef.current = new maplibre.Marker({ color: markerColor }).setLngLat([lon, lat]).addTo(map);
    map.flyTo({ center: [lon, lat], zoom: Math.max(map.getZoom(), 12), essential: true });
  }, [lat, lon, markerColor]);

  const proximity = useCallback(() => {
    const centre = mapRef.current?.getCenter();
    return centre ? { lon: centre.lng, lat: centre.lat } : null;
  }, []);

  const flyTo = useCallback((hit: PlaceHit) => {
    const map = mapRef.current;
    if (map) flyToPlace(map, hit);
  }, []);

  return (
    <div className="grid gap-2">
      <PlaceSearchBox getProximity={proximity} onChoose={flyTo} label="Search for a place to move the map" />
      <div
        ref={containerRef}
        role="application"
        aria-label={ariaLabel}
        className="h-72 w-full overflow-hidden rounded-md border border-line-200 bg-surface-50"
      />
    </div>
  );
}
