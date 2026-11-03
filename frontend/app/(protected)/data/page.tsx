"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  AudioLines,
  Brush,
  ChevronRight,
  ClipboardList,
  Database,
  Download,
  File as FileIcon,
  FileImage,
  FileSpreadsheet,
  FileText,
  Film,
  Folder,
  FolderOpen,
  GitBranch,
  HardDrive,
  Layers,
  Lock,
  Package,
  Eye,
  Pencil,
  RefreshCw,
  Search,
  Table2,
  User,
  UserCircle,
  UsersRound,
  Wrench
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { TranscriptDataPanel } from "@/components/transcript/TranscriptDataPanel";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { useAuth } from "@/components/AuthProvider";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { ComboBox, Dropdown } from "@/components/ui/Dropdown";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { listCut, type ListCut } from "@/components/data/cappedList";
import { DataSearchPanel } from "@/components/data/DataSearchPanel";
import { useWorkshopScope, WorkshopScopeSelect } from "@/components/WorkshopScopeSelect";
import { API_BASE, apiFetch, buildQuery, listResource } from "@/lib/api";
import { bytes, formatDateTime } from "@/lib/format";
import { canDownloadDataset } from "@/lib/permissions";
import { canUpdateRecord, recordViewHref } from "@/lib/recordView";
import { saveBlobToDevice } from "@/lib/saveToDevice";
import type {
  Artisan,
  Craft,
  MediaFile,
  ProductDocumentation,
  QuestionnaireInterview,
  ToolDocumentation,
  Workshop
} from "@/lib/types";
import { Toggle } from "@/components/ui/Toggle";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { DATA_STATUS_OPTIONS, DEFAULT_DATA_STATUS, dataStatusLabel, statusQuery } from "@/lib/review";
import { exportFilename } from "@/lib/naming";
import { fileNameFromDisposition } from "@/components/questionnaires/workbookApi";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

type RecordType = "workshop" | "artisan" | "product" | "tool" | "process" | "interview" | "user" | "category";

type TreeEntry = {
  name: string;
  path: string;
  kind: "folder" | "file";
  recordType?: RecordType | string;
  mediaType?: string;
  mediaId?: string;
  url?: string | null;
  sizeBytes?: number | string | null;
  transcriptAvailable?: boolean;
  transcriptFiles?: { name: string; format: string; track: string; timestamps: boolean; url: string }[];
  content?: string | null;
};

type Crumb = { name: string; path: string };

type InfoField = { label: string; value: string };

type FolderInfo = { title: string; fields: InfoField[] };

type Taxonomy = {
  id: string;
  name: string;
  path: string;
  description: string;
  default: boolean;
};

type TreeResponse = {
  path: string;
  crumbs?: Crumb[];
  entries: TreeEntry[];
  info?: FolderInfo | null;
  truncated?: boolean;
  taxonomies?: Taxonomy[];
  taxonomy?: string | null;
};

type ManifestFile = {
  path: string;
  url?: string | null;
  originalPath?: string | null;
  content?: string | null;
  mediaId?: string | null;
  mediaType?: string | null;
  convertToMp4?: boolean;
};

type ManifestResponse = { files: ManifestFile[]; totalFiles: number; totalMedia: number; truncated?: boolean };

type DownloadFailure = { path: string; reason: string };

type DownloadProgress = { done: number; total: number; current: string; phase: "fetching" | "zipping" };

const INCLUDE_OPTIONS = [
  { key: "text", label: "Text" },
  { key: "images", label: "Images" },
  { key: "videos", label: "Videos" },
  { key: "audios", label: "Audios" },
  { key: "transcripts", label: "Transcripts" },
  { key: "documents", label: "Documents" },
  { key: "other", label: "Other files" }
] as const;

type IncludeKey = (typeof INCLUDE_OPTIONS)[number]["key"];

const RECORD_ICONS: Record<string, typeof Folder> = {
  workshop: UsersRound,
  craft: Brush,
  artisan: User,
  product: Package,
  tool: Wrench,
  process: GitBranch,
  interview: ClipboardList,
  user: UserCircle,
  category: Folder
};

function recordIcon(recordType?: string) {
  return (recordType && RECORD_ICONS[recordType]) || Folder;
}

function fileIcon(entry: TreeEntry) {
  switch (entry.mediaType) {
    case "IMAGE":
      return <FileImage className="h-4 w-4" aria-hidden />;
    case "VIDEO":
      return <Film className="h-4 w-4" aria-hidden />;
    case "AUDIO":
      return <AudioLines className="h-4 w-4" aria-hidden />;
    case "PDF":
    case "DOCUMENT":
      return <FileText className="h-4 w-4" aria-hidden />;
    default:
      return entry.content != null ? <FileText className="h-4 w-4" aria-hidden /> : <FileIcon className="h-4 w-4" aria-hidden />;
  }
}

function mp4Path(path: string) {
  return `${path.replace(/\.[^./\\]+$/, "")}.mp4`;
}

function zipFilename(folderName: string) {
  return exportFilename("Dataset", "Export", "zip", { subject: folderName });
}

function stem(name: string) {
  return name.replace(/\.[^.]+$/, "");
}

function TranscriptMarkdown({ children }: { children: string }) {
  return (
    <div
      className="max-h-80 overflow-y-auto rounded-md border border-line-200 bg-surface-50 p-4 text-sm leading-6 text-ink-700"
      tabIndex={0}
      role="region"
      aria-label="Transcript"
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ children }) => <h3 className="mb-2 mt-4 font-display text-base font-bold text-ink-900 first:mt-0">{children}</h3>,
          h2: ({ children }) => <h4 className="mb-2 mt-4 font-display text-sm font-bold text-ink-900 first:mt-0">{children}</h4>,
          h3: ({ children }) => <h5 className="mb-1.5 mt-3 font-display text-sm font-semibold text-ink-900 first:mt-0">{children}</h5>,
          h4: ({ children }) => <h6 className="mb-1.5 mt-3 font-display text-sm font-semibold text-ink-700 first:mt-0">{children}</h6>,
          p: ({ children }) => <p className="my-2 first:mt-0 last:mb-0">{children}</p>,
          strong: ({ children }) => <strong className="font-semibold text-ink-900">{children}</strong>,
          hr: () => <hr className="my-3 border-line-200" />,
          ul: ({ children }) => <ul className="my-2 list-disc space-y-1 pl-5">{children}</ul>,
          ol: ({ children }) => <ol className="my-2 list-decimal space-y-1 pl-5">{children}</ol>,
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noreferrer" className="font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2">
              {children}
            </a>
          ),
          blockquote: ({ children }) => <blockquote className="my-2 border-l-2 border-purple-300 pl-3 text-ink-500">{children}</blockquote>,
          code: ({ children }) => <code className="rounded bg-purple-50 px-1 py-0.5 text-[0.85em] text-purple-800">{children}</code>,
          pre: ({ children }) => (
            <pre className="my-2 overflow-x-auto rounded-md border border-line-200 bg-card p-3 text-xs leading-5">{children}</pre>
          ),
          table: ({ children }) => (
            <div className="my-2 overflow-x-auto">
              <table className="w-full border-collapse text-xs">{children}</table>
            </div>
          ),
          th: ({ children }) => <th className="border border-line-200 bg-surface-50 px-2 py-1 text-left font-semibold text-ink-900">{children}</th>,
          td: ({ children }) => <td className="border border-line-200 px-2 py-1 align-top">{children}</td>
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}

type ReportSheet = {
  name: string;
  color?: string | null;
  columns: string[];
  rows: Array<Array<string | number | null>>;
  truncated?: boolean;
  preview?: boolean;
  capped?: boolean;
  totalRows?: number | null;
  truncatedNote?: string | null;
};

type ReportResponse = {
  sheets: ReportSheet[];
  previewRows?: number;
};

function sheetCutNote(sheet: ReportSheet): string | null {
  const shown = sheet.rows.length - (sheet.capped ? 1 : 0);
  if (sheet.capped) {
    const lead = sheet.preview ? `Showing the first ${shown}. ` : "";
    return lead + (sheet.truncatedNote ?? "Too long to show in full. Open a narrower folder.");
  }
  if (!sheet.preview) return sheet.truncated ? (sheet.truncatedNote ?? null) : null;
  const of = typeof sheet.totalRows === "number" ? ` of ${sheet.totalRows.toLocaleString()}` : "";
  return `Preview: first ${shown}${of} rows. The download has every row.`;
}

function sheetCount(sheet: ReportSheet): string {
  if (typeof sheet.totalRows === "number") return sheet.totalRows.toLocaleString();
  return sheet.preview ? `${sheet.rows.length}+` : String(sheet.rows.length);
}

async function reportRefusal(response: Response, fallback: string): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: unknown };
    return typeof body.detail === "string" && body.detail.trim() ? body.detail : fallback;
  } catch {
    return fallback;
  }
}

function sheetColor(raw?: string | null) {
  const fallback = "#58068c";
  if (!raw) return fallback;
  const value = raw.trim();
  if (value.startsWith("#")) return value;
  if (/^[0-9A-Fa-f]{8}$/.test(value)) return `#${value.slice(2)}`;
  if (/^[0-9A-Fa-f]{6}$/.test(value)) return `#${value}`;
  return value;
}

function pillTextColor(cssHex: string) {
  const match = /^#([0-9A-Fa-f]{6})$/.exec(cssHex);
  if (!match) return "#ffffff";
  const r = parseInt(match[1].slice(0, 2), 16);
  const g = parseInt(match[1].slice(2, 4), 16);
  const b = parseInt(match[1].slice(4, 6), 16);
  return (r * 299 + g * 587 + b * 114) / 1000 > 160 ? "#1e1b2e" : "#ffffff";
}

function reportFilename(folderName: string, disposition: string | null) {
  return (
    fileNameFromDisposition(disposition) ??
    exportFilename("Dataset", "Report", "xlsx", { subject: folderName === "Repository" ? "All Records" : folderName })
  );
}

function DataTablesPanel({
  path,
  folderName,
  status,
  defaultOpen = true
}: {
  path: string;
  folderName: string;
  status?: string;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [activeSheet, setActiveSheet] = useState(0);
  const [filter, setFilter] = useState("");

  const wholeRepository = path.split("/").filter(Boolean).length <= 1;
  const [requested, setRequested] = useState(!wholeRepository);
  const inFlight = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    if (inFlight.current) return;
    const abort = new AbortController();
    inFlight.current = abort;
    setLoading(true);
    setError(null);
    try {
      const next = await apiFetch<ReportResponse>(`/data/report${buildQuery({ path, format: "json", status })}`, {
        signal: abort.signal
      });
      if (abort.signal.aborted) return;
      setReport(next);
      setActiveSheet(0);
      setFilter("");
    } catch (err) {
      if (abort.signal.aborted) return;
      setError(err instanceof Error ? err.message : "Unable to load the data tables for this folder");
    } finally {
      if (inFlight.current === abort) inFlight.current = null;
      if (!abort.signal.aborted) setLoading(false);
    }
  }, [path, status]);

  useEffect(() => {
    if (open && requested && !report) void load();
  }, [open, requested, report, load]);

  useEffect(
    () => () => {
      inFlight.current?.abort();
      inFlight.current = null;
    },
    []
  );

  function requestTables() {
    if (requested) void load();
    else setRequested(true);
  }

  const sheets = useMemo(
    () => (report?.sheets ?? []).filter((sheet) => sheet.rows.length > 0),
    [report]
  );
  const sheet = sheets[activeSheet];

  const rows = useMemo(() => {
    if (!sheet) return [];
    const needle = filter.trim().toLowerCase();
    if (!needle) return sheet.rows;
    return sheet.rows.filter((row) =>
      row.some((cell) => String(cell ?? "").toLowerCase().includes(needle))
    );
  }, [sheet, filter]);

  function toggle() {
    setOpen((previous) => !previous);
  }

  async function downloadXlsx() {
    setDownloading(true);
    setError(null);
    try {
      const response = await fetch(`${API_BASE}/api/data/report${buildQuery({ path, format: "xlsx", status })}`);
      if (!response.ok) throw new Error(await reportRefusal(response, "The report didn’t download. Try again."));
      const blob = await response.blob();
      saveBlobToDevice(blob, reportFilename(folderName, response.headers.get("content-disposition")));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to download the report");
    } finally {
      setDownloading(false);
    }
  }

  return (
    <section className="panel overflow-hidden">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <button type="button" className="flex min-w-0 flex-1 items-center gap-2 text-left" onClick={toggle} aria-expanded={open}>
          <motion.span animate={{ rotate: open ? 90 : 0 }} transition={{ duration: 0.15 }} className="grid place-items-center">
            <ChevronRight className="h-4 w-4 text-ink-500" aria-hidden />
          </motion.span>
          <Table2 className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
          <span className="font-display font-bold text-ink-900">Data tables</span>
          <span className="truncate text-xs text-ink-500">Spreadsheet view of everything under {folderName}</span>
        </button>
        <button type="button" className="field-button" disabled={downloading} onClick={downloadXlsx}>
          <FileSpreadsheet className="h-4 w-4" aria-hidden />
          {downloading ? "Preparing report…" : "Download report (.xlsx)"}
        </button>
      </div>
      {error ? <div className="border-t border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700">{error}</div> : null}
      <AnimatePresence initial={false}>
        {open ? (
          <motion.div
            key="report"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.18 }}
            className="overflow-hidden"
          >
            <div className="border-t border-line-200">
              {loading ? (
                <div className="flex items-center gap-2 p-4 text-sm text-ink-500">
                  <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden />
                  Building data tables…
                </div>
              ) : !report ? (
                <div className="grid justify-items-center gap-3 p-6 text-center">
                  <p className="text-sm text-ink-500">
                    {wholeRepository ? "Every record in the repository. Load a preview when you need it." : "Tables didn't load."}
                  </p>
                  <ButtonRow>
                    <button type="button" className="field-button" onClick={requestTables}>
                      <Table2 className="h-4 w-4" aria-hidden />
                      {error ? "Try again" : "Load tables"}
                    </button>
                  </ButtonRow>
                </div>
              ) : sheets.length === 0 ? (
                <div className="p-4 text-sm text-ink-500">No tabular data under this folder.</div>
              ) : (
                <>
                  <div
                    className="flex gap-1 overflow-x-auto border-b border-line-200 bg-surface-50 px-2 py-2"
                    role="tablist"
                    aria-label="Data sheets"
                  >
                    {sheets.map((entry, index) => {
                      const color = sheetColor(entry.color);
                      const active = index === activeSheet;
                      return (
                        <button
                          key={entry.name}
                          type="button"
                          role="tab"
                          aria-selected={active}
                          onClick={() => {
                            setActiveSheet(index);
                            setFilter("");
                          }}
                          className={`inline-flex shrink-0 items-center gap-2 rounded-md px-3 py-1.5 text-xs font-semibold transition pointer-coarse:min-h-11 ${
                            active
                              ? "bg-card text-ink-900 shadow-sm"
                              : "text-ink-500 hover:bg-card/60 hover:text-ink-900"
                          }`}
                        >
                          <span
                            className="h-2.5 w-2.5 shrink-0 rounded-full"
                            style={{ backgroundColor: color }}
                            aria-hidden
                          />
                          {entry.name}
                          <span className="text-ink-500">{sheetCount(entry)}</span>
                        </button>
                      );
                    })}
                  </div>

                  {sheet ? (
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
                        <label className="relative flex min-w-0 flex-1 items-center">
                          <Search
                            className="pointer-events-none absolute left-3 h-3.5 w-3.5 text-ink-300"
                            aria-hidden
                          />
                          <input
                            type="search"
                            value={filter}
                            onChange={(event) => setFilter(event.target.value)}
                            placeholder={`Filter ${sheet.name.toLowerCase()}…`}
                            aria-label={`Filter rows in ${sheet.name}`}
                            className="field-input py-1.5 pl-8 text-sm"
                          />
                        </label>
                        <span className="text-xs text-ink-500">
                          {rows.length === sheet.rows.length
                            ? `${sheet.rows.length} row${sheet.rows.length === 1 ? "" : "s"}`
                            : `${rows.length} of ${sheet.rows.length} rows`}
                          {" · "}
                          {sheet.columns.length} columns
                        </span>
                      </div>

                      {sheetCutNote(sheet) ? (
                        <p className="mx-4 mb-3 rounded-md bg-amber-100 px-3 py-2 text-xs text-amber-800">
                          {sheetCutNote(sheet)}
                        </p>
                      ) : null}

                      <div
                        className="max-h-128 overflow-auto border-t border-line-200"
                        tabIndex={0}
                        role="region"
                        aria-label={`${sheet.name} records`}
                      >
                        <table className="w-full text-left text-sm">
                          <thead className="sticky top-0 z-10 bg-surface-50 text-xs uppercase text-ink-500 shadow-sm">
                            <tr>
                              <th className="w-12 px-3 py-2 text-right font-bold text-ink-500">#</th>
                              {sheet.columns.map((column, index) => (
                                <th
                                  key={`${column}-${index}`}
                                  scope="col"
                                  className="resize-x cursor-col-resize overflow-hidden whitespace-nowrap px-3 py-2 font-bold text-ink-900"
                                  style={{ minWidth: 96 }}
                                >
                                  {column}
                                </th>
                              ))}
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-line-200">
                            {rows.length === 0 ? (
                              <tr>
                                <td
                                  colSpan={sheet.columns.length + 1}
                                  className="px-3 py-6 text-center text-sm text-ink-500"
                                >
                                  No rows match “{filter}”.
                                </td>
                              </tr>
                            ) : (
                              rows.map((row, rowIndex) => (
                                <tr key={rowIndex} className="align-top hover:bg-purple-50/40">
                                  <td className="px-3 py-2 text-right text-xs text-ink-500">
                                    {rowIndex + 1}
                                  </td>
                                  {sheet.columns.map((_column, cellIndex) => {
                                    const value = row[cellIndex];
                                    return (
                                      <td key={cellIndex} className="max-w-md px-3 py-2 text-ink-700">
                                        <div className="max-h-32 overflow-y-auto whitespace-pre-line wrap-break-word">
                                          {value === null || value === undefined || value === ""
                                            ? "-"
                                            : String(value)}
                                        </div>
                                      </td>
                                    );
                                  })}
                                </tr>
                              ))
                            )}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  ) : null}
                </>
              )}
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </section>
  );
}

type BrowseRow = { id: string; name: string; creator: string; createdAt?: string };

type WithCreator = { createdBy?: { name?: string | null } | null };

function sortRecent<T extends { createdAt?: string }>(items: T[]) {
  return [...items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
}

type BrowseResult = {
  rows: BrowseRow[];
  cut: ListCut | null;
};

type BrowseScope = {
  workshopIds: string | undefined;
};

type BrowseTypeDef = {
  label: string;
  linkedType: string;
  editHref: (id: string) => string;
  scoped?: boolean;
  load: (scope: BrowseScope) => Promise<BrowseResult>;
};

const BROWSE_TYPES: Record<string, BrowseTypeDef> = {
  artisan: {
    label: "Artisans",
    linkedType: "artisan",
    editHref: (id) => `/artisans/${id}/edit`,
    load: async () => ({
      rows: sortRecent((await listResource<Artisan>("/artisans", { pageSize: 100 })).items).map((x) => ({
        id: x.id,
        name: `${x.name}${x.place ? ` · ${x.place}` : ""}`,
        creator: x.createdBy?.name ?? "-",
        createdAt: x.createdAt
      })),
      cut: null
    })
  },
  product: {
    label: "Products",
    linkedType: "product",
    editHref: (id) => `/products/${id}/edit`,
    load: async () => ({
      rows: sortRecent((await listResource<ProductDocumentation>("/products", { pageSize: 100 })).items).map((x) => ({
        id: x.id,
        name: `${x.productName} · ${x.artisanName}`,
        creator: x.createdBy?.name ?? "-",
        createdAt: x.createdAt
      })),
      cut: null
    })
  },
  tool: {
    label: "Tools",
    linkedType: "tool",
    editHref: (id) => `/tools/${id}/edit`,
    load: async () => ({
      rows: sortRecent((await listResource<ToolDocumentation>("/tools", { pageSize: 100 })).items).map((x) => ({
        id: x.id,
        name: `${x.toolkitName} · ${x.artisanName}`,
        creator: x.createdBy?.name ?? "-",
        createdAt: x.createdAt
      })),
      cut: null
    })
  },
  workshop: {
    label: "Workshops",
    linkedType: "workshop",
    editHref: (id) => `/workshops?edit=${id}`,
    load: async () => ({
      rows: sortRecent((await listResource<Workshop>("/workshops", { pageSize: 100 })).items).map((x) => ({
        id: x.id,
        name: workshopDisplayTitle(x),
        creator: x.createdBy?.name ?? "-",
        createdAt: x.createdAt
      })),
      cut: null
    })
  },
  craft: {
    label: "Crafts",
    linkedType: "craft",
    editHref: (id) => `/crafts?edit=${id}`,
    load: async () => ({
      rows: sortRecent((await listResource<Craft & WithCreator>("/crafts", { pageSize: 500 })).items).map((x) => ({
        id: x.id,
        name: x.place ? `${x.name} · ${x.place}` : x.name,
        creator: x.createdBy?.name ?? "-",
        createdAt: x.createdAt
      })),
      cut: null
    })
  },
  questionnaire: {
    label: "Questionnaire",
    linkedType: "questionnaire",
    editHref: (id) => `/questionnaire?edit=${id}`,
    scoped: true,
    load: async ({ workshopIds }) => {
      const page = await listResource<QuestionnaireInterview>("/questionnaire/interviews", {
        pageSize: 100,
        workshopIds
      });
      return {
        rows: sortRecent(page.items).map((x) => ({
          id: x.id,
          name: x.title?.trim() || "Untitled interview",
          creator: x.createdBy?.name ?? "-",
          createdAt: x.createdAt
        })),
        cut: listCut(page, "interviews")
      };
    }
  },
  process: {
    label: "Processes",
    linkedType: "process",
    editHref: (id) => `/processes?edit=${id}`,
    load: async () => ({
      rows: sortRecent(
        (
          await listResource<{ id: string; name: string; createdAt?: string } & WithCreator>("/processes", {
            pageSize: 100
          })
        ).items
      ).map((x) => ({
        id: x.id,
        name: x.name,
        creator: x.createdBy?.name ?? "-",
        createdAt: x.createdAt
      })),
      cut: null
    })
  },
  media: {
    label: "Media",
    linkedType: "media",
    editHref: () => "/media",
    load: async () => ({
      rows: sortRecent((await listResource<MediaFile>("/media", { pageSize: 100 })).items).map((x) => ({
        id: x.id,
        name: [x.originalFilename?.trim() || "Media", x.mediaType].filter(Boolean).join(" · "),
        creator: x.uploadedBy?.name ?? "-",
        createdAt: x.createdAt
      })),
      cut: null
    })
  }
};

const BROWSE_TYPE_OPTIONS = Object.entries(BROWSE_TYPES).map(([value, def]) => ({ value, label: def.label }));

function mediaTypeIcon(mediaType?: string | null) {
  switch (mediaType) {
    case "IMAGE":
      return <FileImage className="h-4 w-4" aria-hidden />;
    case "VIDEO":
      return <Film className="h-4 w-4" aria-hidden />;
    case "AUDIO":
      return <AudioLines className="h-4 w-4" aria-hidden />;
    default:
      return <FileText className="h-4 w-4" aria-hidden />;
  }
}

function BrowseByTypePanel() {
  const { user } = useAuth();
  const [typeKey, setTypeKey] = useState("");
  const [rows, setRows] = useState<BrowseRow[] | null>(null);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [loading, setLoading] = useState(false);
  const [recordId, setRecordId] = useState("");
  const [recordMedia, setRecordMedia] = useState<MediaFile[] | null>(null);
  const [mediaLoading, setMediaLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [downloadingDataset, setDownloadingDataset] = useState(false);
  const [datasetNote, setDatasetNote] = useState<string | null>(null);

  const scope = useWorkshopScope();

  const def = typeKey ? BROWSE_TYPES[typeKey] : null;
  const scopeValue = def?.scoped ? scope.queryValue : undefined;
  const scopeSettling = Boolean(def?.scoped) && scope.settling;

  useEffect(() => {
    setRows(null);
    setCut(null);
    setRecordId("");
    setRecordMedia(null);
    if (!typeKey) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    if (scopeSettling) return;
    BROWSE_TYPES[typeKey]
      .load({ workshopIds: scopeValue })
      .then((loaded) => {
        if (cancelled) return;
        setRows(loaded.rows);
        setCut(loaded.cut);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Unable to load records");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [typeKey, scopeValue, scopeSettling]);

  useEffect(() => {
    setRecordMedia(null);
    if (!recordId || !def) return;
    let cancelled = false;
    setMediaLoading(true);
    listResource<MediaFile>("/media", { linkedRecordType: def.linkedType, linkedRecordId: recordId, pageSize: 100 })
      .then((page) => {
        if (!cancelled) setRecordMedia(sortRecent(page.items));
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Unable to load this record's media");
      })
      .finally(() => {
        if (!cancelled) setMediaLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordId, typeKey]);

  async function downloadDataset() {
    setDownloadingDataset(true);
    setDatasetNote(null);
    setError(null);
    try {
      const manifest = await apiFetch<{
        files: Array<{ path: string; url?: string | null; content?: string | null }>;
        totalFiles: number;
        totalMedia: number;
        truncated?: boolean;
      }>("/export/dataset");
      const { default: JSZip } = await import("jszip");
      const zip = new JSZip();
      const failed: string[] = [];
      let done = 0;
      for (const file of manifest.files) {
        done += 1;
        setDatasetNote(`Preparing download… ${done}/${manifest.files.length}`);
        if (file.content != null) {
          zip.file(file.path, file.content);
          continue;
        }
        if (!file.url) {
          failed.push(file.path);
          continue;
        }
        try {
          const response = await fetch(file.url);
          if (!response.ok) throw new Error("A file couldn’t be downloaded. Try again.");
          zip.file(file.path, await response.blob());
        } catch {
          failed.push(file.path);
        }
      }
      if (failed.length) {
        zip.file(
          "_failed-downloads.txt",
          `Not downloaded:\n\n${failed.join("\n")}`
        );
      }
      const blob = await zip.generateAsync({ type: "blob" });
      saveBlobToDevice(blob, exportFilename("Dataset", "Export", "zip", { subject: "All Records" }));
      const capNote = manifest.truncated
        ? " Not every record is included."
        : "";
      setDatasetNote(
        (failed.length
          ? `Saved ${manifest.files.length - failed.length} of ${manifest.files.length} files — ${failed.length} failed.`
          : `Archive saved — ${manifest.files.length} file${manifest.files.length === 1 ? "" : "s"}.`) + capNote
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to download the dataset");
    } finally {
      setDownloadingDataset(false);
    }
  }

  const recordOptions = (rows ?? []).map((row) => ({ value: row.id, label: row.name }));
  const selectedRow = recordId ? rows?.find((row) => row.id === recordId) : undefined;

  return (
    <section className="panel mb-5 p-4">
      {def?.scoped ? <WorkshopScopeSelect scope={scope} label="Workshops in this list" className="mb-3" /> : null}
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-56 flex-1">
          <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-500">Record type</div>
          <Dropdown
            value={typeKey}
            onChange={setTypeKey}
            options={BROWSE_TYPE_OPTIONS}
            placeholder="Browse by type…"
            ariaLabel="Record type"
            advanceOnSelect={false}
          />
        </div>
        {typeKey ? (
          <div className="min-w-64 flex-1">
            <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-500">Narrow to one record (optional)</div>
            <ComboBox
              options={recordOptions}
              value={recordId}
              onChange={setRecordId}
              placeholder={loading ? "Loading…" : recordOptions.length === 0 ? "No records" : "Type to filter records"}
            />
            <CappedListNotice cuts={[cut]} />
          </div>
        ) : null}
        <button type="button" className="field-button ml-auto" disabled={downloadingDataset} onClick={downloadDataset}>
          <Download className="h-4 w-4" aria-hidden />
          {downloadingDataset ? "Preparing dataset…" : "Download dataset"}
        </button>
      </div>
      {datasetNote ? <div className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{datasetNote}</div> : null}
      {error ? <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      {typeKey && !recordId ? (
        loading ? (
          <div className="mt-4 flex items-center gap-2 text-sm text-ink-500">
            <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden />
            Loading {def?.label.toLowerCase()}…
          </div>
        ) : (rows ?? []).length === 0 ? (
          <div className="mt-4">
            <EmptyState title={`No ${def?.label.toLowerCase()} yet`} />
          </div>
        ) : (
          <div
            className="mt-4 max-h-96 overflow-auto rounded-md border border-line-200"
            tabIndex={0}
            role="region"
            aria-label={`${def?.label ?? "Records"} list`}
          >
            <table className="w-full min-w-[560px] text-left text-sm">
              <thead className="sticky top-0 bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th className="px-4 py-2.5">Name</th>
                  <th className="px-4 py-2.5">Creator</th>
                  <th className="px-4 py-2.5">Date</th>
                  <th className="px-4 py-2.5 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {(rows ?? []).map((row) => {
                  const viewHref = recordViewHref(typeKey, row.id);
                  const showLink = !viewHref || canUpdateRecord(user, typeKey);
                  const cells = (
                    <>
                      <td className="px-4 py-2.5">
                        {viewHref ? <RecordTitleLink href={viewHref}>{row.name}</RecordTitleLink> : <span className="font-medium text-ink-900">{row.name}</span>}
                      </td>
                      <td className="px-4 py-2.5 text-ink-700">{row.creator}</td>
                      <td className="px-4 py-2.5 text-ink-700">{formatDateTime(row.createdAt)}</td>
                      <td className="px-4 py-2.5 text-right">
                        {showLink ? (
                          <Link
                            href={def?.editHref(row.id) ?? "#"}
                            className="inline-flex items-center gap-1 text-sm font-semibold text-purple-700 dark:text-purple-300 hover:underline"
                          >
                            <Pencil className="h-3.5 w-3.5" aria-hidden />
                            {viewHref ? "Update" : "Open"}
                          </Link>
                        ) : null}
                      </td>
                    </>
                  );
                  return viewHref ? (
                    <OpenableRow key={row.id} href={viewHref}>
                      {cells}
                    </OpenableRow>
                  ) : (
                    <tr key={row.id}>{cells}</tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )
      ) : null}

      {recordId && selectedRow ? (
        <div className="mt-4 grid gap-3">
          <div className="flex flex-wrap items-center gap-3 rounded-md border border-line-200 bg-surface-50 px-4 py-3">
            <div className="min-w-0 flex-1">
              <div className="font-display font-semibold text-ink-900">{selectedRow.name}</div>
              <div className="text-xs text-ink-500">
                {selectedRow.creator !== "-" ? `${selectedRow.creator} · ` : ""}
                {formatDateTime(selectedRow.createdAt)}
              </div>
            </div>
            {recordViewHref(typeKey, recordId) ? (
              <Link
                href={recordViewHref(typeKey, recordId)!}
                className="inline-flex items-center gap-1 text-sm font-semibold text-purple-700 dark:text-purple-300 hover:underline"
              >
                <Eye className="h-3.5 w-3.5" aria-hidden />
                View record
              </Link>
            ) : null}
            {!recordViewHref(typeKey, recordId) || canUpdateRecord(user, typeKey) ? (
              <Link
                href={def?.editHref(recordId) ?? "#"}
                className="inline-flex items-center gap-1 text-sm font-semibold text-purple-700 dark:text-purple-300 hover:underline"
              >
                <Pencil className="h-3.5 w-3.5" aria-hidden />
                {recordViewHref(typeKey, recordId) ? "Update" : "Open"}
              </Link>
            ) : null}
          </div>
          {mediaLoading ? (
            <div className="flex items-center gap-2 text-sm text-ink-500">
              <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden />
              Loading media…
            </div>
          ) : (recordMedia ?? []).length === 0 ? (
            <div className="text-sm text-ink-500">No media linked to this record.</div>
          ) : (
            <ul className="divide-y divide-line-200 rounded-md border border-line-200">
              {(recordMedia ?? []).map((item) => (
                <li key={item.id} className="px-4 py-3">
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700">
                      {mediaTypeIcon(item.mediaType)}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="break-all text-sm font-medium text-ink-900">{item.originalFilename}</div>
                      <div className="text-xs text-ink-500">
                        {[item.mediaType, bytes(item.sizeBytes), formatDateTime(item.createdAt)].filter(Boolean).join(" · ")}
                      </div>
                    </div>
                    {item.url ? (
                      <a href={item.url} target="_blank" rel="noreferrer" className="text-sm font-semibold text-purple-700 hover:underline">
                        Open
                      </a>
                    ) : null}
                    {item.mediaType === "IMAGE" && item.url ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={item.url}
                        alt={item.originalFilename}
                        loading="lazy"
                        className="h-16 w-16 rounded-md border border-line-200 object-cover"
                      />
                    ) : null}
                  </div>
                  {item.mediaType === "AUDIO" && item.url ? <AudioPlayer src={item.url} className="mt-2 max-w-md" /> : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  );
}

function TaxonomySwitcher({
  taxonomies,
  active,
  onSelect
}: {
  taxonomies: Taxonomy[];
  active: string | null;
  onSelect: (path: string) => void;
}) {
  if (taxonomies.length === 0) return null;
  const current = taxonomies.find((t) => t.id === active);
  return (
    <section className="panel p-4">
      <div className="flex items-center gap-2">
        <Layers className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        <h2 className="font-display text-sm font-bold text-ink-900">Organise by</h2>
      </div>
      <div className="mt-3 flex flex-wrap gap-2" role="tablist" aria-label="Data organisation">
        {taxonomies.map((taxonomy) => {
          const selected = taxonomy.id === active;
          return (
            <button
              key={taxonomy.id}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => onSelect(taxonomy.path)}
              className={`rounded-md border px-3 py-2 text-sm font-medium transition ${
                selected
                  ? "border-purple-700 bg-purple-700 text-white shadow-cta"
                  : "border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
              }`}
            >
              {taxonomy.name}
            </button>
          );
        })}
      </div>
      <p className="mt-3 text-xs leading-5 text-ink-500">
        {current?.description ?? "Choose how to group the records."}
      </p>
    </section>
  );
}

function RecordInfoCard({ info }: { info: FolderInfo }) {
  const fields = (info.fields ?? []).filter((field) => field.value != null && String(field.value).trim() !== "");
  if (fields.length === 0) return null;
  return (
    <section className="panel overflow-hidden">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-5 py-4">
        <h2 className="font-display text-lg font-bold text-ink-900">{info.title}</h2>
        <span className="text-xs text-ink-500">
          {fields.length} recorded field{fields.length === 1 ? "" : "s"}
        </span>
      </div>
      <table className="w-full table-fixed border-collapse border-t border-line-200 text-left text-sm">
        <colgroup>
          <col className="w-[38%] sm:w-56" />
          <col />
        </colgroup>
        <thead className="bg-surface-50 text-xs uppercase tracking-wide text-ink-500">
          <tr>
            <th scope="col" className="border-r border-line-200 px-5 py-2 font-bold text-ink-900">
              Field
            </th>
            <th scope="col" className="px-5 py-2 font-bold text-ink-900">
              Value
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line-200">
          {fields.map((field, index) => (
            <tr key={`${field.label}-${index}`} className="align-top">
              <th
                scope="row"
                className="wrap-break-word border-r border-line-200 bg-surface-50 px-5 py-2.5 text-left font-semibold text-ink-900"
              >
                {field.label}
              </th>
              <td className="px-5 py-2.5 text-ink-700">
                <div className="max-h-72 overflow-y-auto whitespace-pre-line wrap-break-word leading-6">{field.value}</div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

type TreeState = {
  treeByPath: Record<string, TreeResponse>;
  expandedPaths: Set<string>;
  loadingPaths: Set<string>;
  selectedPath: string;
  onToggle: (path: string) => void;
  onSelect: (path: string) => void;
};

function TreeBranch({ parentPath, depth, state }: { parentPath: string; depth: number; state: TreeState }) {
  const tree = state.treeByPath[parentPath];
  if (!tree) {
    if (!state.loadingPaths.has(parentPath)) return null;
    return (
      <div className="flex items-center gap-2 py-1 text-xs text-ink-500" style={{ paddingLeft: depth * 16 + 8 }}>
        <RefreshCw className="h-3 w-3 animate-spin" aria-hidden />
        Loading…
      </div>
    );
  }
  const folders = tree.entries.filter((entry) => entry.kind === "folder");
  if (folders.length === 0) return null;
  return (
    <ul className="space-y-0.5">
      {folders.map((folder) => {
        const expanded = state.expandedPaths.has(folder.path);
        const selected = state.selectedPath === folder.path;
        const Icon = folder.recordType ? recordIcon(folder.recordType) : expanded ? FolderOpen : Folder;
        return (
          <li key={folder.path}>
            <div
              className={`flex items-center gap-1 rounded-md pr-2 text-sm transition-colors ${
                selected ? "bg-purple-100 font-semibold text-purple-800" : "text-ink-700 hover:bg-purple-50"
              }`}
              style={{ paddingLeft: depth * 16 }}
            >
              <button
                type="button"
                aria-label={expanded ? `Collapse ${folder.name}` : `Expand ${folder.name}`}
                className="grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-purple-100"
                onClick={() => state.onToggle(folder.path)}
              >
                <motion.span animate={{ rotate: expanded ? 90 : 0 }} transition={{ duration: 0.15 }} className="grid place-items-center">
                  <ChevronRight className="h-3.5 w-3.5" aria-hidden />
                </motion.span>
              </button>
              <button type="button" className="flex min-w-0 flex-1 items-center gap-1.5 py-1 text-left pointer-coarse:min-h-11" onClick={() => state.onSelect(folder.path)}>
                <Icon className="h-4 w-4 shrink-0 text-purple-700" aria-hidden />
                <span className="truncate">{folder.name}</span>
              </button>
            </div>
            <AnimatePresence initial={false}>
              {expanded ? (
                <motion.div
                  key="children"
                  initial={{ height: 0, opacity: 0 }}
                  animate={{ height: "auto", opacity: 1 }}
                  exit={{ height: 0, opacity: 0 }}
                  transition={{ duration: 0.18 }}
                  className="overflow-hidden"
                >
                  <TreeBranch parentPath={folder.path} depth={depth + 1} state={state} />
                </motion.div>
              ) : null}
            </AnimatePresence>
          </li>
        );
      })}
    </ul>
  );
}

export default function DataBrowserPage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = canDownloadDataset(user);

  const [treeByPath, setTreeByPath] = useState<Record<string, TreeResponse>>({});
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(new Set([""]));
  const [loadingPaths, setLoadingPaths] = useState<Set<string>>(new Set());
  const [selectedPath, setSelectedPath] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [includes, setIncludes] = useState<Record<IncludeKey, boolean>>({
    text: true,
    images: true,
    videos: true,
    audios: true,
    transcripts: true,
    documents: true,
    other: true
  });
  const [downloading, setDownloading] = useState(false);
  const [progress, setProgress] = useState<DownloadProgress | null>(null);
  const [failures, setFailures] = useState<DownloadFailure[]>([]);
  const [downloadNote, setDownloadNote] = useState<string | null>(null);

  const [transcriptsByFolder, setTranscriptsByFolder] = useState<Record<string, ManifestFile[]>>({});
  const [transcriptLoading, setTranscriptLoading] = useState<Set<string>>(new Set());
  const [openTranscripts, setOpenTranscripts] = useState<Set<string>>(new Set());
  const [openInline, setOpenInline] = useState<Set<string>>(new Set());
  const [reviewStatus, setReviewStatus] = useState<string>(DEFAULT_DATA_STATUS);
  const statusParam = statusQuery(reviewStatus);

  const loadFolder = useCallback(async (path: string) => {
    setLoadingPaths((prev) => new Set(prev).add(path));
    try {
      const response = await apiFetch<TreeResponse>(`/data/tree${buildQuery({ path, status: statusParam })}`);
      setTreeByPath((prev) => ({ ...prev, [path]: response }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load folder");
    } finally {
      setLoadingPaths((prev) => {
        const next = new Set(prev);
        next.delete(path);
        return next;
      });
    }
  }, [statusParam]);

  useEffect(() => {
    if (!authLoading && permitted) loadFolder("");
  }, [authLoading, permitted, loadFolder]);

  function changeReviewStatus(next: string) {
    if (next === reviewStatus) return;
    setTreeByPath({});
    setTranscriptsByFolder({});
    setReviewStatus(next);
  }

  useEffect(() => {
    if (!permitted || selectedPath === "") return;
    loadFolder(selectedPath);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadFolder]);

  function toggleFolder(path: string) {
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
    if (!(path in treeByPath)) loadFolder(path);
  }

  function selectFolder(path: string) {
    setSelectedPath(path);
    setOpenTranscripts(new Set());
    setOpenInline(new Set());
    setExpandedPaths((prev) => new Set(prev).add(path));
    if (!(path in treeByPath)) loadFolder(path);
  }

  function revealPath(path: string) {
    const segments = path.split("/").filter(Boolean);
    const ancestors: string[] = [""];
    for (let i = 1; i <= segments.length; i += 1) ancestors.push(segments.slice(0, i).join("/"));
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      ancestors.forEach((ancestor) => next.add(ancestor));
      return next;
    });
    ancestors.forEach((ancestor) => {
      if (!(ancestor in treeByPath)) loadFolder(ancestor);
    });
    setSelectedPath(path);
    setOpenTranscripts(new Set());
    setOpenInline(new Set());
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  }

  const taxonomies = useMemo<Taxonomy[]>(
    () => treeByPath[selectedPath]?.taxonomies ?? treeByPath[""]?.taxonomies ?? [],
    [treeByPath, selectedPath]
  );
  const activeTaxonomy = treeByPath[selectedPath]?.taxonomy ?? null;

  const landedRef = useRef(false);
  useEffect(() => {
    if (landedRef.current) return;
    const fallback = treeByPath[""]?.taxonomies?.find((taxonomy) => taxonomy.default);
    if (!fallback) return;
    landedRef.current = true;
    selectFolder(fallback.path);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [treeByPath]);

  function toggleInline(path: string) {
    setOpenInline((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  async function toggleTranscript(entry: TreeEntry) {
    const folder = selectedPath;
    let opening = false;
    setOpenTranscripts((prev) => {
      const next = new Set(prev);
      if (next.has(entry.path)) next.delete(entry.path);
      else {
        next.add(entry.path);
        opening = true;
      }
      return next;
    });
    if (!(folder in transcriptsByFolder)) {
      setTranscriptLoading((prev) => new Set(prev).add(folder));
      try {
        const manifest = await apiFetch<ManifestResponse>(`/data/manifest${buildQuery({ path: folder, include: "transcripts", status: statusParam })}`);
        setTranscriptsByFolder((prev) => ({ ...prev, [folder]: manifest.files }));
      } catch (err) {
        if (opening) setError(err instanceof Error ? err.message : "Unable to load transcripts");
      } finally {
        setTranscriptLoading((prev) => {
          const next = new Set(prev);
          next.delete(folder);
          return next;
        });
      }
    }
  }

  function transcriptFor(entry: TreeEntry): string | null {
    const files = transcriptsByFolder[selectedPath];
    if (!files) return null;
    const byId = entry.mediaId ? files.find((file) => file.mediaId === entry.mediaId && file.content != null) : undefined;
    if (byId?.content != null) return byId.content;
    const nameStem = stem(entry.name);
    const byStem = files.find((file) => file.content != null && file.path.includes(nameStem));
    return byStem?.content ?? null;
  }

  async function downloadFolder() {
    const include = INCLUDE_OPTIONS.filter((option) => includes[option.key])
      .map((option) => option.key)
      .join(",");
    if (!include) {
      setError("Pick at least one content type.");
      return;
    }
    setDownloading(true);
    setFailures([]);
    setDownloadNote(null);
    setError(null);
    try {
      const manifest = await apiFetch<ManifestResponse>(`/data/manifest${buildQuery({ path: selectedPath, include, status: statusParam })}`);
      if (manifest.files.length === 0) {
        setDownloadNote("Nothing matches these filters.");
        setProgress(null);
        return;
      }
      const { default: JSZip } = await import("jszip");
      const zip = new JSZip();
      const failed: DownloadFailure[] = [];
      let done = 0;
      for (const file of manifest.files) {
        setProgress({ done, total: manifest.files.length, current: file.path, phase: "fetching" });
        try {
          if (file.content != null) {
            zip.file(file.path, file.content);
          } else if (file.convertToMp4 && file.mediaId) {
            const response = await fetch(`${API_BASE}/api/data/media/${file.mediaId}/download?format=mp4`);
            if (response.ok) {
              zip.file(mp4Path(file.path), await response.blob());
            } else if (file.url) {
              const original = await fetch(file.url);
              if (!original.ok) {
                throw new Error("The video couldn’t be downloaded. Try again.");
              }
              zip.file(file.originalPath ?? file.path, await original.blob());
            } else {
              throw new Error("The video couldn’t be downloaded. Try again.");
            }
          } else if (file.url) {
            const response = await fetch(file.url);
            if (!response.ok) throw new Error("The file couldn’t be downloaded. Try again.");
            zip.file(file.path, await response.blob());
          } else {
            throw new Error("There was nothing to download for this file.");
          }
        } catch (err) {
          failed.push({ path: file.path, reason: err instanceof Error ? err.message : "Fetch failed" });
        }
        done += 1;
      }
      if (failed.length) {
        zip.file(
          "_failed-downloads.txt",
          `Not downloaded:\n\n${failed.map((f) => `${f.path} — ${f.reason}`).join("\n")}`
        );
      }
      setProgress({ done, total: manifest.files.length, current: "Packaging archive…", phase: "zipping" });
      const blob = await zip.generateAsync({ type: "blob" }, (metadata) => {
        setProgress({
          done,
          total: manifest.files.length,
          current: `Packaging archive… ${Math.round(metadata.percent)}%`,
          phase: "zipping"
        });
      });
      saveBlobToDevice(
        blob,
        zipFilename(treeByPath[selectedPath]?.crumbs?.at(-1)?.name ?? (selectedPath.split("/").filter(Boolean).pop() || "All Records"))
      );
      setFailures(failed);
      const truncatedNote = manifest.truncated
        ? " Too many files — download subfolders for the rest."
        : "";
      setDownloadNote(
        (failed.length
          ? `Saved ${manifest.files.length - failed.length} of ${manifest.files.length} files — ${failed.length} failed (listed below).`
          : `Archive saved — ${manifest.files.length} file${manifest.files.length === 1 ? "" : "s"}.`) + truncatedNote
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to download this folder");
    } finally {
      setProgress(null);
      setDownloading(false);
    }
  }

  const currentTree = treeByPath[selectedPath];

  const crumbs = useMemo<Crumb[]>(() => {
    const fromServer = currentTree?.crumbs ?? [];
    if (fromServer.some((crumb) => crumb.path === "")) return fromServer;
    return [{ name: "Root", path: "" }, ...fromServer];
  }, [currentTree]);

  const header = (
    <PageHeader
      title="Data Browser"
      description="Browse records as folders and download any as a zip."
      icon={<Database className="h-5 w-5" aria-hidden />}
    />
  );

  if (authLoading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!permitted) {
    return (
      <>
        {header}
        <section className="panel px-6 py-12 text-center">
          <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-purple-100 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="font-display text-base font-semibold text-ink-900">Dataset access is restricted</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">
            Ask an admin for dataset access.
          </p>
        </section>
      </>
    );
  }

  const entries = currentTree?.entries;
  const folderLoading = loadingPaths.has(selectedPath);
  const folders = (entries ?? []).filter((entry) => entry.kind === "folder");
  const files = (entries ?? []).filter((entry) => entry.kind === "file");

  const treeState: TreeState = {
    treeByPath,
    expandedPaths,
    loadingPaths,
    selectedPath,
    onToggle: toggleFolder,
    onSelect: selectFolder
  };

  return (
    <>
      {header}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div> : null}

      <DataSearchPanel onReveal={revealPath} />

      <TaxonomySwitcher taxonomies={taxonomies} active={activeTaxonomy} onSelect={selectFolder} />

      <div className="mb-5 grid gap-1">
        <span className="field-label">Review status</span>
        <SegmentedChoice
          label="Review status"
          value={reviewStatus}
          onChange={(value) => changeReviewStatus(value || DEFAULT_DATA_STATUS)}
          options={DATA_STATUS_OPTIONS}
        />
      </div>

      <BrowseByTypePanel />

      <TranscriptDataPanel />

      <div className="grid items-start gap-5 lg:grid-cols-[290px_minmax(0,1fr)]">
        <section className="panel p-3 lg:sticky lg:top-4">
          <div className="mb-2 flex items-center justify-between px-1">
            <span className="text-xs font-semibold uppercase tracking-wide text-ink-500">Folders</span>
            <button
              type="button"
              className="grid h-6 w-6 place-items-center rounded text-ink-500 hover:bg-purple-50 hover:text-purple-700"
              title="Reload current folder"
              onClick={() => loadFolder(selectedPath)}
            >
              <RefreshCw className={`h-3.5 w-3.5 ${folderLoading ? "animate-spin" : ""}`} aria-hidden />
            </button>
          </div>
          <button
            type="button"
            className={`mb-1 flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-left text-sm transition-colors pointer-coarse:min-h-11 ${
              selectedPath === "" ? "bg-purple-100 font-semibold text-purple-800" : "text-ink-700 hover:bg-purple-50"
            }`}
            onClick={() => selectFolder("")}
          >
            <HardDrive className="h-4 w-4 shrink-0 text-purple-700" aria-hidden />
            Dataset root
          </button>
          <div className="max-h-[65vh] overflow-y-auto pr-1">
            <TreeBranch parentPath="" depth={0} state={treeState} />
          </div>
        </section>

        <div className="grid gap-5">
          {currentTree?.info ? <RecordInfoCard info={currentTree.info} /> : null}

          <DataTablesPanel
            key={`${selectedPath || "__root"}:${reviewStatus}`}
            path={selectedPath}
            folderName={crumbs[crumbs.length - 1]?.name || "dataset"}
            status={statusParam}
          />

          <section className="panel p-4">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
              <span className="text-xs font-semibold uppercase tracking-wide text-ink-500">Include</span>
              {INCLUDE_OPTIONS.map((option) => (
                <Toggle
                  key={option.key}
                  className="w-auto gap-3"
                  label={option.label}
                  checked={includes[option.key]}
                  onChange={(next) => setIncludes((prev) => ({ ...prev, [option.key]: next }))}
                />
              ))}
              <div className="ml-auto">
                <button type="button" className="field-button" disabled={downloading} onClick={downloadFolder}>
                  <Download className="h-4 w-4" aria-hidden />
                  {downloading ? "Preparing zip…" : "Download this folder"}
                </button>
              </div>
            </div>
            {progress ? (
              <div className="mt-3 space-y-1.5 text-sm text-ink-500">
                <div>
                  {progress.phase === "fetching"
                    ? `Fetching file ${Math.min(progress.done + 1, progress.total)} of ${progress.total}`
                    : "Packaging"}
                  {" — "}
                  <span className="break-all font-medium text-ink-900">{progress.current}</span>
                </div>
                <ProgressBar
                  percent={progress.total ? Math.round((progress.done / progress.total) * 100) : 0}
                  className="h-2"
                  fillClassName="transition-all"
                />
              </div>
            ) : null}
            {downloadNote ? (
              <div className="mt-3 rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm text-success-600">{downloadNote}</div>
            ) : null}
            {failures.length ? (
              <details className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
                <summary className="cursor-pointer font-semibold">
                  {failures.length} file{failures.length === 1 ? "" : "s"} could not be fetched
                </summary>
                <ul className="mt-2 space-y-1 text-xs">
                  {failures.map((failure) => (
                    <li key={failure.path}>
                      <span className="break-all font-medium">{failure.path}</span> — {failure.reason}
                    </li>
                  ))}
                </ul>
              </details>
            ) : null}
          </section>

          <section className="panel overflow-hidden">
            <div className="flex flex-wrap items-center gap-1 border-b border-line-200 bg-surface-50 px-4 py-2.5 text-sm">
              {crumbs.map((crumb, index) => (
                <span key={crumb.path || "__root"} className="flex items-center gap-1">
                  {index > 0 ? <ChevronRight className="h-3.5 w-3.5 text-ink-500" aria-hidden /> : null}
                  {index === crumbs.length - 1 ? (
                    <span className="font-display font-semibold text-ink-900">{crumb.name}</span>
                  ) : (
                    <button type="button" className="font-medium text-purple-700 dark:text-purple-300 hover:underline" onClick={() => selectFolder(crumb.path)}>
                      {crumb.name}
                    </button>
                  )}
                </span>
              ))}
              <span className="ml-auto text-xs text-ink-500">
                {entries ? `${folders.length} folder${folders.length === 1 ? "" : "s"} · ${files.length} file${files.length === 1 ? "" : "s"}` : ""}
              </span>
            </div>

            {currentTree?.truncated ? (
              <div className="border-b border-amber-500/30 bg-amber-100 px-4 py-2 text-xs text-amber-800">
                Partial list. Open subfolders to see more.
              </div>
            ) : null}

            {!entries ? (
              <div className="p-4 text-sm text-ink-500">{folderLoading ? "Loading folder…" : "Select a folder to browse."}</div>
            ) : entries.length === 0 ? (
              <div className="p-4">
                <EmptyState
                  title="Empty folder"
                  body={reviewStatus === "ALL" ? "This folder has no files or subfolders." : `Nothing ${dataStatusLabel(reviewStatus).toLowerCase()} here.`}
                />
                {reviewStatus !== "ALL" ? (
                  <ButtonRow size="sm" className="mt-2">
                    <button type="button" className="field-button-secondary h-9 min-h-0 px-3 text-xs pointer-coarse:h-11" onClick={() => changeReviewStatus("ALL")}>
                      Show all statuses
                    </button>
                  </ButtonRow>
                ) : null}
              </div>
            ) : (
              <div>
                {folders.length ? (
                  <div className="grid gap-3 border-b border-line-200 p-4 sm:grid-cols-2 xl:grid-cols-3">
                    {folders.map((folder) => {
                      const Icon = recordIcon(folder.recordType);
                      return (
                        <button
                          key={folder.path}
                          type="button"
                          className="group flex items-center gap-3 rounded-lg border border-line-200 bg-card p-3 text-left shadow-sm transition hover:border-purple-300 hover:shadow-md"
                          onClick={() => selectFolder(folder.path)}
                        >
                          <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-purple-800 text-purple-50">
                            <Icon className="h-4 w-4" aria-hidden />
                          </span>
                          <span className="min-w-0">
                            <span className="block truncate font-display text-sm font-semibold text-ink-900">{folder.name}</span>
                            {folder.recordType ? (
                              <span className="block text-xs capitalize text-ink-500">{folder.recordType}</span>
                            ) : null}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                ) : null}
                {files.length ? (
                  <ul className="divide-y divide-line-200">
                    {files.map((file) => {
                      const transcriptOpen = openTranscripts.has(file.path);
                      const transcript = transcriptOpen ? transcriptFor(file) : null;
                      const inlineOpen = openInline.has(file.path);
                      const meta = [
                        file.mediaType ?? (file.content != null ? "TEXT" : "FILE"),
                        file.sizeBytes != null ? bytes(file.sizeBytes) : null
                      ]
                        .filter(Boolean)
                        .join(" · ");
                      return (
                        <li key={file.path} className="px-4 py-3">
                          <div className="flex flex-wrap items-center gap-3">
                            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700">
                              {fileIcon(file)}
                            </span>
                            <div className="min-w-0 flex-1">
                              <div className="break-all text-sm font-medium text-ink-900">{file.name}</div>
                              <div className="text-xs text-ink-500">{meta}</div>
                            </div>
                            {file.content != null ? (
                              <button
                                type="button"
                                className="text-sm font-semibold text-purple-700 hover:underline"
                                onClick={() => toggleInline(file.path)}
                              >
                                {inlineOpen ? "Hide" : "View"}
                              </button>
                            ) : null}
                            {file.transcriptAvailable ? (
                              <button
                                type="button"
                                className="text-sm font-semibold text-purple-700 hover:underline"
                                onClick={() => toggleTranscript(file)}
                              >
                                {transcriptOpen ? "Hide transcript" : "View transcript"}
                              </button>
                            ) : null}
                            {file.mediaType === "IMAGE" && file.url ? (
                              <a href={file.url} target="_blank" rel="noreferrer" className="shrink-0">
                                {/* eslint-disable-next-line @next/next/no-img-element */}
                                <img
                                  src={file.url}
                                  alt={file.name}
                                  loading="lazy"
                                  className="h-16 w-16 rounded-md border border-line-200 object-cover"
                                />
                              </a>
                            ) : null}
                          </div>
                          {file.transcriptFiles?.length ? (
                            <p data-row-ignore="" className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs" data-transcript-files>
                              {file.transcriptFiles.map((entry) => (
                                <a key={entry.url} href={entry.url} download={entry.name} className="font-semibold text-purple-700 hover:underline dark:text-purple-300">
                                  {entry.name}
                                </a>
                              ))}
                            </p>
                          ) : null}
                          {file.mediaType === "AUDIO" && file.url ? (
                            <audio controls preload="none" src={file.url} className="mt-2 h-9 w-full max-w-md" />
                          ) : null}
                          {inlineOpen && file.content != null ? (
                            <pre tabIndex={0} className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap rounded-md border border-line-200 bg-surface-50 p-4 font-sans text-sm leading-6 text-ink-700">
                              {file.content}
                            </pre>
                          ) : null}
                          {transcriptOpen ? (
                            transcriptLoading.has(selectedPath) ? (
                              <div className="mt-2 text-xs text-ink-500">Loading transcript…</div>
                            ) : transcript != null ? (
                              <div className="mt-2">
                                <TranscriptMarkdown>{transcript}</TranscriptMarkdown>
                              </div>
                            ) : (
                              <div className="mt-2 text-xs text-ink-500">No transcript content found for this file.</div>
                            )
                          ) : null}
                        </li>
                      );
                    })}
                  </ul>
                ) : null}
              </div>
            )}
          </section>
        </div>
      </div>
    </>
  );
}
