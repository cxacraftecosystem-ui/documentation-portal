/**
 * The shape of `GET /map/points`. Mirrors `backend/app/api/routes/map_points.py`; read that file's
 * header for why there are two layers and why they are never merged.
 */

import type { RecordType } from "@/components/search/SearchFilters";

/**
 * How far a drawn point may be from what the record actually meant.
 *
 * Two of these are MEASUREMENTS and four are lookups, and the difference is the whole reason this
 * field exists: a reader who cannot tell a measured position from a district stand-in will read the
 * second as the first.
 *
 *   SUBJECT_PIN  a researcher pointed at the subject's own place on a map. Measured.
 *   MEASURED     the device's GPS fix at the moment of recording. Measured. (The CAPTURE layer.)
 *   TOWN         a named town, resolved out of the legacy free-text place column.
 *   DISTRICT     a district, at an anchor learned from real pins inside it.
 *   STATE        only the state is known — or the district has no anchor yet. Drawn at the seat.
 *   NATION       every placed record, folded into one point at NATION level.
 */
export type MapPrecision =
  | "SUBJECT_PIN"
  | "MEASURED"
  | "TOWN"
  | "DISTRICT"
  | "STATE"
  | "NATION";

/** Where a point's coordinates came from. Mirrors `services/geography`'s SOURCE_* constants. */
export type MapSource = "SUBJECT_PIN" | "STATED_ADDRESS" | "PLACE_TEXT" | "DEVICE_FIX";

export type MapLayer = "ORIGIN" | "CAPTURE";

/**
 * The administrative unit the map groups by. Three levels, because that is what an Indian address has
 * above a village — and each is a real unit somebody could draw a border around.
 */
export const ADMIN_LEVELS = ["NATION", "STATE", "DISTRICT"] as const;
export type AdminLevel = (typeof ADMIN_LEVELS)[number];

export type MapCounts = Record<RecordType, number>;

/**
 * One entry in a point's dropdown — the same place, one administrative level down.
 *
 * WHY POINTS HAVE CHILDREN AT ALL. At NATION level the whole country is a single dot and therefore a
 * single row, and at STATE level a state is one dot and one row. "Click a pin, the list scrolls to its
 * row" has nowhere to go at those levels: the row a reader lands on is the row they already had. So each
 * point carries the level below it and the list renders that as a disclosure — the states inside the
 * nation, the districts inside the state. DISTRICT points have none, because a district is the finest
 * unit an Indian address names.
 *
 * `key` is a REAL point key at `level`, which is what makes the dropdown navigable rather than
 * decorative: handing it back with `level=<child.level>` draws exactly the pin the level toggle would
 * have drawn. See `map_points._child_shell`.
 */
export type MapPointChild = {
  key: string;
  /** The admin level this child's key belongs to — the level to switch the map to when drilling in. */
  level: AdminLevel | null;
  layer: MapLayer;
  label: string;
  region: string;
  state: string | null;
  district?: string | null;
  latitude: number;
  longitude: number;
  precision: MapPrecision;
  source?: MapSource;
  total: number;
  counts: MapCounts;
  /** CAPTURE children only, mirroring the parent's own fields. */
  fixes?: number;
  spreadMetres?: number;
};

export type MapPoint = {
  key: string;
  layer: MapLayer;
  label: string;
  /** The administrative line under the label, or the coordinate for a measured point. */
  region: string;
  state: string | null;
  /** The canonical district, when the point is one. Null at STATE and NATION level. */
  district?: string | null;
  latitude: number;
  longitude: number;
  precision: MapPrecision;
  source?: MapSource;
  total: number;
  counts: MapCounts;
  /**
   * ORIGIN only: every finer place name and every free-text spelling that folded into this point,
   * longest first. This is what makes grouping to a district lossless — a Jaipur pin still says
   * "Bagru, Sanganer" — so nothing disappears from the reading, it only moves into the panel.
   */
  places?: string[];
  /** How many of the records here are positioned by a real coordinate rather than by a lookup. */
  pinnedRecords?: number;
  /** How many reached this point through the legacy free-text column rather than a stated address. */
  fromPlaceText?: number;
  /** CAPTURE only: how many separate GPS fixes this pin folds, and how much ground they cover. */
  fixes?: number;
  spreadMetres?: number;
  medianAccuracy?: number | null;
  /**
   * The finer breakdown of this point, for the list's disclosure. Empty at DISTRICT level, and empty
   * whenever there is only ONE child — a disclosure whose content restates the row it hangs under is a
   * control that does nothing, and a reader who opens one learns to stop opening them. Absent on an API
   * that predates the field, which is why every read of it is optional-chained.
   */
  children?: MapPointChild[];
  childrenTruncated?: boolean;
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
  /**
   * False when the current filters (or the workshop scope) exclude this record, so its keys ring no
   * drawn pin. Reported rather than enforced: a focused record outside the filters used to 404 the
   * whole map. Absent on an API that predates the field, which is treated as "in scope" — the old
   * behaviour, where being out of scope was not representable.
   */
  inScope?: boolean;
};

/**
 * How much of the structured address the records in scope actually carry.
 *
 * Reported because the map's quality is now a FUNCTION of these fields: a pin sitting at a state
 * capital is not a bug in the map, it is a district nobody filled in. Showing the numbers turns "why
 * is this coarse" into something a researcher can act on.
 */
export type AddressCompleteness = {
  locations: number;
  withState: number;
  withDistrict: number;
  withPincode: number;
  withSubjectPin: number;
};

export type MapSummary = {
  records: number;
  byType: Partial<MapCounts>;
  originRecords: number;
  captureRecords: number;
  unplacedRecords: number;
  /** Buckets that have no `place` column and so reach the ORIGIN layer only via their Location. */
  originExcludes: RecordType[];
  captureTruncated: boolean;
  /** The capture layer's cluster radius at this level, in kilometres. */
  clusterKilometres?: number;
  address?: AddressCompleteness;
  /** How many districts the map has any position for, and how many real pins taught it. */
  anchoredDistricts?: number;
  anchorPins?: number;
  anchorsTruncated?: boolean;
};

export type MapPointsResponse = {
  scope: "all" | "filtered" | "record";
  /** The level this response was grouped at. */
  level?: AdminLevel;
  /** The levels the server offers, so the toggle is rendered from the server's own vocabulary. */
  levels?: AdminLevel[];
  /**
   * The level every point's `children` are keyed at — the level to switch the map to when a reader
   * drills into a dropdown. Null at DISTRICT, where there are no children. Read from the server rather
   * than re-derived here, so the client cannot hold a different idea of the ladder than the server does.
   */
  childLevel?: AdminLevel | null;
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
