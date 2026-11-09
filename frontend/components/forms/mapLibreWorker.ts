import type * as maplibregl from "maplibre-gl";

export function withBundledWorker(module: typeof maplibregl): typeof maplibregl {
  module.setWorkerUrl(new URL("maplibre-gl/dist/maplibre-gl-worker.mjs", import.meta.url).toString());
  return module;
}
