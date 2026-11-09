import { ApiError } from "@/lib/api";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

export function placeSearchAvailable(): boolean {
  return Boolean(maptilerKey);
}

export type PlaceHit = {
  id: string;
  name: string;
  context: string;
  lon: number;
  lat: number;
  bbox?: [number, number, number, number];
};

const COUNTRY = "in";

const LIMIT = 8;

export const MIN_QUERY_LENGTH = 3;

type GeocodeFeature = {
  id?: string;
  text?: string;
  place_name?: string;
  center?: [number, number];
  bbox?: [number, number, number, number];
};

function describe(feature: GeocodeFeature): { name: string; context: string } {
  const name = (feature.text ?? "").trim();
  const full = (feature.place_name ?? "").trim();
  if (!name) return { name: full, context: "" };
  if (!full || full === name) return { name, context: "" };
  return { name, context: full.startsWith(`${name},`) ? full.slice(name.length + 1).trim() : full };
}

export async function searchPlaces(
  query: string,
  { signal, proximity }: { signal: AbortSignal; proximity?: { lon: number; lat: number } | null }
): Promise<PlaceHit[]> {
  if (!maptilerKey) {
    throw new ApiError(503, "Place search isn't available right now. Move the map or type the coordinates.", null);
  }

  const parameters = new URLSearchParams({
    key: maptilerKey,
    country: COUNTRY,
    language: "en",
    limit: String(LIMIT),
    autocomplete: "true"
  });
  if (proximity) parameters.set("proximity", `${proximity.lon},${proximity.lat}`);

  const response = await fetch(
    `https://api.maptiler.com/geocoding/${encodeURIComponent(query)}.json?${parameters.toString()}`,
    { signal }
  );
  if (!response.ok) {
    throw new ApiError(response.status, "Place search did not respond. Try again.", null);
  }

  let body: { features?: GeocodeFeature[] };
  try {
    body = (await response.json()) as { features?: GeocodeFeature[] };
  } catch {
    throw new ApiError(502, "Place search did not respond. Try again.", null);
  }

  return (body.features ?? [])
    .filter((feature): feature is GeocodeFeature & { center: [number, number] } =>
      Array.isArray(feature.center) && Number.isFinite(feature.center[0]) && Number.isFinite(feature.center[1])
    )
    .map((feature, index) => {
      const { name, context } = describe(feature);
      return {
        id: feature.id ?? `hit-${index}`,
        name,
        context,
        lon: feature.center[0],
        lat: feature.center[1],
        ...(feature.bbox && feature.bbox.length === 4 ? { bbox: feature.bbox } : {})
      };
    });
}

function isUnreachable(error: unknown): boolean {
  return !(error instanceof ApiError) && !(error instanceof DOMException && error.name === "AbortError");
}

function isTransient(error: unknown): boolean {
  return error instanceof ApiError && (error.status >= 500 || error.status === 408 || error.status === 429);
}

export function describeSearchFailure(error: unknown): string {
  if (isUnreachable(error)) {
    return "No connection, so places cannot be looked up right now. The map and the coordinate boxes still work.";
  }
  if (isTransient(error)) {
    return "Place search is not responding just now. Try again in a moment.";
  }
  return "Place search isn't available right now. Move the map or type the coordinates.";
}
