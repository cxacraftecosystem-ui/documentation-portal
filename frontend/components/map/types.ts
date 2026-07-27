/**
 * The shape of `GET /map/points`. Mirrors `backend/app/api/routes/map_points.py`; read that file's
 * header for why there are two layers and why they are never merged.
 */

import type { RecordType } from "@/components/search/SearchFilters";

/**
 * How far a drawn point may be from what the record actually meant.
 *
 * MEASURED is the only one that came off a GPS. The other three are a free-text place name resolved
 * through the backend's atlas, and the difference is the whole reason this field exists: a reader
 * who cannot tell a measured fix from a district stand-in will read the second as the first.
 */
export type MapPrecision = "MEASURED" | "TOWN" | "DISTRICT" | "STATE";

export type MapLayer = "ORIGIN" | "CAPTURE";

export type MapCounts = Record<RecordType, number>;

export type MapPoint = {
  key: string;
  layer: MapLayer;
  label: string;
  /** The administrative line under the label, or the coordinate for a measured point. */
  region: string;
  state: string | null;
  latitude: number;
  longitude: number;
  precision: MapPrecision;
  total: number;
  counts: MapCounts;
  /** ORIGIN only: every spelling of this place found in the data, longest first. */
  spellings?: string[];
  /** CAPTURE only: how many separate GPS fixes this pin folds, and how much ground they cover. */
  fixes?: number;
  spreadMetres?: number;
  medianAccuracy?: number | null;
};

/** A place that was typed but could not be resolved. Reported, never silently dropped. */
export type UnplacedPlace = {
  label: string;
  total: number;
  counts: MapCounts;
};

export type MapFocus = {
  type: RecordType;
  id: string;
  title: string;
  place: string | null;
  /** The point keys this record sits on — usually its origin, its capture fix, or both. */
  pointKeys: string[];
};

export type MapSummary = {
  records: number;
  byType: Partial<MapCounts>;
  originRecords: number;
  captureRecords: number;
  unplacedRecords: number;
  /** Buckets that have no `place` column and so can never appear in the ORIGIN layer. */
  originExcludes: RecordType[];
  captureTruncated: boolean;
};

export type MapPointsResponse = {
  scope: "all" | "filtered" | "record";
  types: RecordType[];
  points: MapPoint[];
  unplaced: UnplacedPlace[];
  focus: MapFocus | null;
  summary: MapSummary;
};

export type PointRecord = {
  type: RecordType;
  id: string;
  title: string;
  place: string | null;
  craft: string | null;
  status: string;
  createdAt: string | null;
};

export type PointRecordsResponse = {
  key: string;
  items: PointRecord[];
  total: number;
  truncated: boolean;
};

/**
 * Where a record of each type is opened. The SAME destinations the search results use, so a pin and
 * a search hit for one record never lead to two different places. Workshops are edited inline on
 * the workshops list and media has no per-record route, so both land on their list page.
 */
export const RECORD_HREF: Record<RecordType, (id: string) => string> = {
  artisans: (id) => `/artisans/${id}/edit`,
  products: (id) => `/products/${id}/edit`,
  tools: (id) => `/tools/${id}/edit`,
  workshops: () => "/workshops",
  media: () => "/media"
};

export const TYPE_NOUN: Record<RecordType, [singular: string, plural: string]> = {
  artisans: ["artisan", "artisans"],
  workshops: ["workshop", "workshops"],
  products: ["product", "products"],
  tools: ["tool", "tools"],
  media: ["media file", "media files"]
};

export function countLabel(type: RecordType, count: number): string {
  const [singular, plural] = TYPE_NOUN[type];
  return `${count} ${count === 1 ? singular : plural}`;
}
