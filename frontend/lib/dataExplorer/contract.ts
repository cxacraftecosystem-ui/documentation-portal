export type TranscriptFileLink = { name: string; format: string; track: string; timestamps: boolean; url: string };

export type TreeEntry = {
  name: string;
  path: string;
  kind: "folder" | "file";
  recordType?: string;
  mediaType?: string;
  mediaId?: string;
  originalFilename?: string | null;
  url?: string | null;
  mimeType?: string | null;
  sizeBytes?: number | string | null;
  transcriptAvailable?: boolean;
  transcriptFiles?: TranscriptFileLink[];
  content?: string | null;
  transcriptPath?: string | null;
  thumbnailPath?: string | null;
  previewPath?: string | null;
  downloadPath?: string | null;
  reviewStatus?: string | null;
  capturedAt?: string | null;
  count?: number | null;
  bytes?: number | null;
};

export type Crumb = { name: string; path: string };

export type InfoField = { label: string; value: string };

export type FolderInfo = { title: string; fields: InfoField[] };

export type Taxonomy = { id: string; name: string; path: string; description: string; default: boolean };

export type TreeResponse = {
  path: string;
  crumbs?: Crumb[];
  entries: TreeEntry[];
  info?: FolderInfo | null;
  truncated?: boolean;
  nextCursor?: string | null;
  total?: number | null;
  taxonomies?: Taxonomy[];
  taxonomy?: string | null;
  designWorkshopsVisible?: boolean;
  designWorkshopsDownloadable?: boolean;
};

export type ManifestFile = {
  path: string;
  url?: string | null;
  originalPath?: string | null;
  originalFilename?: string | null;
  content?: string | null;
  mediaId?: string | null;
  mediaType?: string | null;
  typeToken?: string | null;
  convertToMp4?: boolean;
  convertedUrl?: string | null;
  sizeBytes?: number | string | null;
  sha256?: string | null;
  originalSha256?: string | null;
};

export type Manifest = {
  files: ManifestFile[];
  totalFiles: number;
  totalMedia: number;
  truncated: boolean;
  skippedMedia?: number;
};

export const INCLUDE_KEYS = ["text", "images", "videos", "audios", "transcripts", "documents", "other"] as const;

export type IncludeKey = (typeof INCLUDE_KEYS)[number];

export const INCLUDE_LABELS: Record<IncludeKey, string> = {
  text: "Text",
  images: "Images",
  videos: "Videos",
  audios: "Audios",
  transcripts: "Transcripts",
  documents: "Documents",
  other: "Other files"
};

export type TypeTally = { files: number; bytes: number };

export type Stats = {
  files: number;
  bytes: number;
  unknownSizeFiles: number;
  byType: Partial<Record<IncludeKey, TypeTally>>;
  truncated: boolean;
};

export type ReportSheet = {
  name: string;
  color?: string | null;
  columns: string[];
  rows: Array<Array<string | number | null>>;
  truncated?: boolean;
  preview?: boolean;
  capped?: boolean;
  totalRows?: number | null;
  truncatedNote?: string | null;
  group?: string | null;
  matchedRows?: number | null;
  rowNumbers?: number[] | null;
};

export type ReportResponse = {
  sheets: ReportSheet[];
  previewRows?: number;
  designWorkshopsVisible?: boolean;
  designWorkshopsDownloadable?: boolean;
  query?: string | null;
  builtAt?: string | null;
  cached?: boolean;
};

export type ReportLocale = "en" | "hi";

export type ReportOptions = {
  sheets: string[] | null;
  answers: "original" | "english" | "both";
  rows: "all" | "filtered";
  links: boolean;
  thumbnails: boolean;
  locale: ReportLocale;
  extras: Record<string, boolean>;
};

export type ReportSheetInfo = { key: string; name: string; plugin?: string | null; count: number | null; files?: number | null; group?: string | null; description?: string | null; record?: boolean };

export type ReportExtra = { key: string; title: string };

export type ReportSheetList = { path: string; sheets: ReportSheetInfo[]; extras: ReportExtra[]; locales: string[]; records?: number | null };

export type ReportStatusFilter = { active: boolean; label: string };

export type ReportBundle = {
  id: string;
  label: string;
  path: string | null;
  createdAt: string | null;
  fileCount: number;
  bytes: number;
  listedCount: number;
  hiddenCount: number;
  mediaIds?: string[];
  manifestPath?: string | null;
  chunk?: number | null;
};

export const DEFAULT_REPORT_OPTIONS: ReportOptions = { sheets: null, answers: "both", rows: "all", links: true, thumbnails: false, locale: "en", extras: {} };

export function withReportDefaults(options: Partial<ReportOptions> | null | undefined): ReportOptions {
  return {
    ...DEFAULT_REPORT_OPTIONS,
    ...(options ?? {}),
    locale: options?.locale === "hi" ? "hi" : "en",
    extras: options?.extras && typeof options.extras === "object" ? { ...options.extras } : {}
  };
}

export type SavedViewState = { view: "files" | "tables" | "transcripts"; sheet: string; q: string; filters: Record<string, string> };

export type SavedView = {
  id: string;
  name: string;
  path: string;
  state: SavedViewState;
  shared: boolean;
  mine: boolean;
  ownerName: string | null;
  createdAt: string;
  updatedAt: string;
};

export type FolderVisit = { path: string; visitedAt: string; previousVisitAt: string | null; newFiles: number; newTranscripts: number };

export type CoverageState = "complete" | "partial" | "missing";

export type WorkshopOverview = {
  path: string;
  title: string;
  subtitle: string | null;
  totals: {
    files: number;
    bytes: number;
    fields: { filled: number; total: number };
    transcripts: { total: number; ready: number; translating: number; failed: number };
    review: { approved: number; pending: number; total: number };
  };
  sections: { key: string; label: string }[];
  artisans: {
    id: string;
    name: string;
    craft: string | null;
    path: string;
    fields: { filled: number; total: number };
    media: { files: number; bytes: number };
    transcripts: number;
    review: string | null;
    sections: { key: string; state: CoverageState; missing: string[]; href?: string | null }[];
  }[];
  truncated: boolean;
};

export type TranscriptText = { mediaId: string; name?: string | null; content: string | null; language?: string | null };

export type TranscriptLanguage = { code: string; label: string; count?: number };

export type BrowseItem = {
  id: string;
  name: string;
  subtitle?: string | null;
  createdAt?: string | null;
  status?: string | null;
  mediaCount?: number | null;
};

export type BrowsePage = {
  items: BrowseItem[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
  truncated: boolean;
};

export const MANIFEST_TOTAL_HEADER = "x-dataset-total";
export const MANIFEST_MEDIA_HEADER = "x-dataset-media";
export const MANIFEST_TRUNCATED_HEADER = "x-dataset-truncated";
export const MANIFEST_SKIPPED_HEADER = "x-dataset-skipped";
export const CONVERSION_HEADER = "x-conversion";

export const LIMITS = {
  treePageMax: 500,
  manifestMaxPaths: 50,
  manifestMaxMediaIds: 2000,
  browsePageSize: 50,
  reportPreviewRows: 200,
  thumbnailSize: 256,
  parallelFetches: 4,
  parallelConversions: 1
} as const;

export function sizeOf(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === "") return null;
  const size = Number(value);
  return Number.isFinite(size) && size >= 0 ? size : null;
}

const TRANSCRIPT_EXTENSIONS = /\.(md|docx|srt|vtt|txt)$/i;

function isIncludeKey(value: unknown): value is IncludeKey {
  return typeof value === "string" && (INCLUDE_KEYS as readonly string[]).includes(value);
}

export function includeKeyOf(file: Pick<ManifestFile, "content" | "mediaType" | "path" | "mediaId" | "typeToken">): IncludeKey {
  if (isIncludeKey(file.typeToken)) return file.typeToken;
  const type = (file.mediaType ?? "").toUpperCase();
  if (type === "TRANSCRIPT") return "transcripts";
  if (file.content != null) return file.mediaId && TRANSCRIPT_EXTENSIONS.test(file.path) ? "transcripts" : "text";
  switch (type) {
    case "IMAGE":
      return "images";
    case "VIDEO":
      return "videos";
    case "AUDIO":
      return "audios";
    case "PDF":
    case "DOCUMENT":
      return "documents";
    default:
      return "other";
  }
}

const encoder = new TextEncoder();

export function expectedBytes(file: ManifestFile): number | null {
  if (file.content != null) return encoder.encode(file.content).length;
  return sizeOf(file.sizeBytes);
}

export function mediaPath(mediaId: string, leaf: "download" | "preview" | "thumbnail" | "transcript"): string {
  return `/data/media/${encodeURIComponent(mediaId)}/${leaf}`;
}
