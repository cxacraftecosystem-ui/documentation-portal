export type CoverageLevel = "STATE" | "DISTRICT";

export type PolygonSource = "OWN" | "PARENT" | "STATE";

export type CoverageCounts = {
  workshops: number;
  artisans: number;
  crafts: number;
  products: number;
  tools: number;
  media: number;
  designWorkshops?: number;
  plannedWorkshops?: number;
};

export type CoverageUnit = {
  stateLgd: number;
  districtLgd: number | null;
  state: string;
  district: string | null;
  polygon: PolygonSource;
  parentLgd: number | null;
  counts: CoverageCounts;
  peer: Record<string, number> | null;
  craftCodes: string[];
  clusters: string[];
};

export type CoverageMarker = {
  kind: "DESIGN_WORKSHOP" | "PLANNED" | "CLUSTER" | "WORKSHOP";
  id: string;
  title: string;
  clusterName: string | null;
  craftCode: string | null;
  stateLgd: number;
  districtLgd: number | null;
  lon: number;
  lat: number;
  precision: "SUBJECT_PIN" | "DISTRICT_CENTROID";
  href: string;
};

export type GapDistrict = { lgd: number | null; name: string };

export type CoverageGaps = {
  craftsNeverDocumented: Array<{ code: string; name: string }>;
  districtsWithoutWorkshop: {
    count: number;
    byState: Array<{ stateLgd: number; state: string; total: number; without: GapDistrict[] }>;
  };
  statesWithoutWorkshop: Array<{ lgd: number; name: string }>;
};

export type UnmatchedItem = {
  model: "DesignWorkshop" | "AnnualPlanEntry" | "Location" | "Workshop";
  rawState: string | null;
  rawDistrict: string | null;
  rows: number;
  suggestion: { stateLgd: number; districtLgd: number | null; reason: string } | null;
};

export type CoverageResponse = {
  asOf: string;
  register: { lgdAsOf: string; listVersion: number; districtCount: number; stateCount: number };
  boundaries: { shapesVersion: string | null; depiction: string };
  filters: {
    level: CoverageLevel;
    craftCode: string[];
    scheme: string[];
    fy: number[];
    stateLgd: number | null;
  };
  catalogue: {
    crafts: Array<{ code: string; name: string }>;
    schemes: Array<{ value: string; label: string }>;
    years: Array<{ fy: number; label: string }>;
  };
  units: CoverageUnit[];
  markers: CoverageMarker[];
  markersTruncated: boolean;
  gaps: CoverageGaps;
  unmatched: { count: number; sample?: UnmatchedItem[] };
  truncated: boolean;
};

export type CoverageBucket = "workshops" | "artisans" | "products" | "tools" | "media";

export type UnitRecordsResponse = {
  unit: Omit<CoverageUnit, "counts" | "peer" | "craftCodes" | "clusters">;
  buckets: Partial<
    Record<
      CoverageBucket,
      {
        total: number;
        items: Array<{ id: string; title: string; href: string; craftCode: string | null; fy: number | null }>;
        nextCursor: string | null;
      }
    >
  >;
  peerHref: string | null;
};

export type UnmatchedResponse = { items: UnmatchedItem[]; total: number };

export type CoverageMetric = "workshops" | "artisans" | "all";

export type CoverageQuery = {
  level: CoverageLevel;
  craftCode: string[];
  scheme: string[];
  fy: number[];
  stateLgd: number | null;
};
