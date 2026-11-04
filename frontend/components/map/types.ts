import type { RecordType } from "@/components/search/SearchFilters";
import { recordViewHref } from "@/lib/recordView";

export type MapPrecision =
  | "SUBJECT_PIN"
  | "MEASURED"
  | "TOWN"
  | "DISTRICT"
  | "STATE"
  | "NATION";

export type MapSource = "SUBJECT_PIN" | "STATED_ADDRESS" | "PLACE_TEXT" | "DEVICE_FIX";

export type MapLayer = "ORIGIN" | "CAPTURE";

export const ADMIN_LEVELS = ["NATION", "STATE", "DISTRICT"] as const;
export type AdminLevel = (typeof ADMIN_LEVELS)[number];

export type MapCounts = Record<RecordType, number>;

export type MapPointChild = {
  key: string;
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
  fixes?: number;
  spreadMetres?: number;
};

export type MapPoint = {
  key: string;
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
  places?: string[];
  pinnedRecords?: number;
  fromPlaceText?: number;
  fixes?: number;
  spreadMetres?: number;
  medianAccuracy?: number | null;
  children?: MapPointChild[];
  childrenTruncated?: boolean;
};

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
  pointKeys: string[];
  inScope?: boolean;
};

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
  originExcludes: RecordType[];
  captureTruncated: boolean;
  clusterKilometres?: number;
  address?: AddressCompleteness;
  anchoredDistricts?: number;
  anchorPins?: number;
  anchorsTruncated?: boolean;
};

export type MapPointsResponse = {
  scope: "all" | "filtered" | "record";
  level?: AdminLevel;
  levels?: AdminLevel[];
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

export const RECORD_HREF: Record<RecordType, (id: string) => string> = {
  artisans: (id) => recordViewHref("artisan", id)!,
  products: (id) => recordViewHref("product", id)!,
  tools: (id) => recordViewHref("tool", id)!,
  workshops: (id) => recordViewHref("workshop", id)!,
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
