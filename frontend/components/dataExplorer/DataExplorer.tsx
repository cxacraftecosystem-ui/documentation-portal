"use client";

import { Bookmark, Database, Download, LayoutGrid, List } from "lucide-react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";

import { BrowseByType, type BrowseType } from "@/components/dataExplorer/BrowseByType";
import { CommandPalette, ShortcutsSheet, useExplorerShortcuts } from "@/components/dataExplorer/CommandPalette";
import { DataTables } from "@/components/dataExplorer/DataTables";
import { DownloadsTray } from "@/components/dataExplorer/DownloadsTray";
import { Note, OfflineNote, Problem, useOnline } from "@/components/dataExplorer/explorerUi";
import { FolderContents } from "@/components/dataExplorer/FolderContents";
import { FolderHeader, RecordDetails, sizedFor, useFolderSize, useSelectionSize } from "@/components/dataExplorer/FolderHeader";
import { FolderTree } from "@/components/dataExplorer/FolderTree";
import { MiniPlayer } from "@/components/dataExplorer/MiniPlayer";
import { fetchLocateHits, locateHit, type PaletteHit } from "@/components/dataExplorer/paletteSearch";
import { canPreview, PreviewDialog } from "@/components/dataExplorer/PreviewDialog";
import { ReportOptionsDialog } from "@/components/dataExplorer/ReportOptionsDialog";
import { SavedViewsDialog } from "@/components/dataExplorer/SavedViewsDialog";
import { SelectionBar } from "@/components/dataExplorer/SelectionBar";
import { SortMenu } from "@/components/dataExplorer/SortMenu";
import { TranscriptsTab } from "@/components/dataExplorer/TranscriptsTab";
import { useDownloads } from "@/components/dataExplorer/useDownloads";
import { useExplorerTree } from "@/components/dataExplorer/useExplorerTree";
import { WorkshopOverview } from "@/components/dataExplorer/WorkshopOverview";
import { bundleManifestEndpoint, fetchBundle, fetchReportSheets, fetchSavedView, includeParam, recordVisit, reportFileUrl, reportOptionParams, reportPackUrl, transcriptBundleUrl } from "@/lib/dataExplorer/api";
import { INCLUDE_KEYS, type Crumb, type FolderVisit, type IncludeKey, type ReportBundle, type ReportOptions, type ReportResponse, type ReportStatusFilter, type SavedView, type Taxonomy, type TreeEntry } from "@/lib/dataExplorer/contract";
import { formatBytes, limitSentence, overLimit } from "@/lib/dataExplorer/plan";
import { ActionGroup } from "@/components/ui/ActionGroup";
import type { JobSource } from "@/lib/dataExplorer/protocol";
import { EMPTY_SELECTION, selectionLabel, selectionSource, toggled, withAll, type Selection } from "@/lib/dataExplorer/selection";
import { availableKeys, LIST_DEFAULT, readSort, sortParam, treeLevel, writeSort, type SortChoice } from "@/lib/dataExplorer/sorting";
import { ancestorsOf, readUrlState, recallScroll, rememberScroll, writeUrlState, type ExplorerUrlState, type ExplorerView } from "@/lib/dataExplorer/urlState";
import { clampTreeWidth, mergeNavigation, readTreeWidth, TREE_WIDTH, TREE_WIDTH_KEY, treeWidthBounds, treeWidthForKey, type WidthBounds } from "@/lib/dataExplorer/explorerView";
import type { QueryParams } from "@/lib/dataExplorer/wire";
import { exportFilename } from "@/lib/naming";

export type PreviewTarget = { mediaId: string; name: string; mediaType: string | null; startMs?: number | null; find?: string };

export type ExplorerConfig = {
  rootName: string;
  extra: QueryParams;
  downloadExtra: QueryParams;
  statusKey: string;
  statusFilter?: ReportStatusFilter;
  viewer: string;
  fileHref: (mediaId: string) => string | null;
  recordHref?: (kind: string, id: string) => string | null;
  renderSearch: (reveal: (path: string) => void, openPreview: (target: PreviewTarget) => void, ask: SearchAsk | null) => ReactNode;
  title?: string;
  description?: string;
  headerExtra?: ReactNode;
  toolbarExtra?: ReactNode;
  emptyBody: string | ((folder: string) => string);
  emptyAction?: ReactNode;
  browseTypes: BrowseType[];
  browseScope?: { control: ReactNode; workshopIds?: string };
  datasetLine?: string | null;
  tablesNotice?: (report: ReportResponse) => string | null;
  secondary?: ReactNode;
};

export function zipName(subject: string): string {
  return exportFilename("Dataset", "Export", "zip", { subject, withTime: true });
}

export function taxonomyChip(name: string): string {
  const bare = name.replace(/^by\s+/i, "").trim();
  return bare ? bare.charAt(0).toUpperCase() + bare.slice(1) : name;
}

export function taxonomyRoot(name: string): string {
  const bare = name.replace(/^by\s+/i, "").trim().toLowerCase();
  if (!bare) return name;
  return `All ${bare.endsWith("s") ? bare : `${bare}s`}`;
}

export function ExplorerHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4" data-testid="explorer-header">
      <div className="min-w-0">
        <h1 className="font-display text-[30px] leading-[1.25] font-extrabold tracking-[-0.01em] text-ink-900 max-lg:text-[26px]">{title}</h1>
        {description ? <p className="mt-1 text-sm text-ink-500 max-lg:hidden">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}

const PILL = "min-h-[34px] rounded-full px-3 text-[13px] transition pointer-coarse:min-h-11";
const PILL_ON = "bg-purple-700 font-semibold text-white dark:bg-purple-300 dark:text-purple-950";
const PILL_OFF = "border border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40";

function TaxonomyPicker({ taxonomies, active, onSelect }: { taxonomies: Taxonomy[]; active: string | null; onSelect: (path: string) => void }) {
  if (taxonomies.length === 0) return null;
  return (
    <>
      <div className="flex items-center justify-between px-1.5 py-1">
        <span className="text-xs font-semibold tracking-[0.06em] text-ink-500 uppercase">Organise by</span>
      </div>
      <div className="flex flex-wrap gap-1.5 px-1 pb-1.5" role="group" aria-label="Organise by">
        {taxonomies.map((taxonomy) => {
          const selected = taxonomy.id === active;
          return (
            <button
              key={taxonomy.id}
              type="button"
              aria-pressed={selected}
              title={taxonomy.description ?? undefined}
              onClick={() => onSelect(taxonomy.path)}
              className={`${PILL} ${selected ? PILL_ON : PILL_OFF}`}
            >
              {taxonomyChip(taxonomy.name)}
            </button>
          );
        })}
      </div>
    </>
  );
}

const VIEW_TABS: { value: ExplorerView; label: string }[] = [
  { value: "files", label: "Files" },
  { value: "tables", label: "Tables" },
  { value: "transcripts", label: "Transcripts" }
];

function ViewTabs({ view, counts, onView, trailing }: { view: ExplorerView; counts: Partial<Record<ExplorerView, string | null>>; onView: (view: ExplorerView) => void; trailing?: ReactNode }) {
  const id = useId();
  const onKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    const at = VIEW_TABS.findIndex((tab) => tab.value === view);
    const last = VIEW_TABS.length - 1;
    const target = event.key === "ArrowRight" ? (at === last ? 0 : at + 1) : event.key === "ArrowLeft" ? (at === 0 ? last : at - 1) : event.key === "Home" ? 0 : event.key === "End" ? last : null;
    if (target === null) return;
    event.preventDefault();
    onView(VIEW_TABS[target].value);
    document.getElementById(`${id}-${VIEW_TABS[target].value}`)?.focus();
  };
  return (
    <div className="flex items-end gap-1 border-line-200 px-3 pt-2 max-sm:flex-wrap max-sm:gap-2 max-sm:p-0 sm:border-b">
      <div role="tablist" aria-label="Contents" className="flex gap-1 max-sm:w-full max-sm:rounded-md max-sm:border max-sm:border-line-200 max-sm:bg-card max-sm:p-[3px]">
        {VIEW_TABS.map((tab) => {
          const selected = view === tab.value;
          const count = counts[tab.value];
          return (
            <button
              key={tab.value}
              id={`${id}-${tab.value}`}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls="explorer-view"
              tabIndex={selected ? 0 : -1}
              onClick={() => onView(tab.value)}
              onKeyDown={onKey}
              className={`inline-flex min-h-11 items-center justify-center px-3.5 text-sm transition max-sm:min-h-10 max-sm:flex-1 max-sm:rounded-[9px] max-sm:text-[13px] ${
                selected
                  ? "font-semibold text-purple-800 shadow-[inset_0_-2px_0_var(--purple-700)] max-sm:bg-purple-700 max-sm:text-white max-sm:shadow-none dark:text-purple-200 dark:shadow-[inset_0_-2px_0_#c090f5] max-sm:dark:bg-purple-300 max-sm:dark:text-purple-950"
                  : "text-ink-700 hover:text-ink-900"
              }`}
            >
              {tab.label}
              {count ? <span className="max-sm:hidden">&nbsp;{count}</span> : null}
            </button>
          );
        })}
      </div>
      <span className="flex-1 max-sm:hidden" />
      {trailing ? <div className="flex items-center gap-1.5 pb-1.5 max-sm:w-full max-sm:justify-end max-sm:pb-0">{trailing}</div> : null}
    </div>
  );
}

export type SearchAsk = { q: string; nonce: number };

function readStoredWidth(): number | null {
  try {
    return readTreeWidth(window.localStorage.getItem(TREE_WIDTH_KEY));
  } catch {
    return null;
  }
}

function storeWidth(width: number): void {
  try {
    window.localStorage.setItem(TREE_WIDTH_KEY, String(width));
  } catch {
    return;
  }
}

function PanelDivider({ width, bounds, controls, onWidth, onCommit }: { width: number; bounds: WidthBounds; controls: string; onWidth: (width: number) => void; onCommit: (width: number) => void }) {
  const drag = useRef<{ pointer: number; startX: number; startWidth: number; width: number } | null>(null);
  const [dragging, setDragging] = useState(false);
  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { pointer: event.pointerId, startX: event.clientX, startWidth: width, width };
    setDragging(true);
  };
  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const active = drag.current;
    if (!active || active.pointer !== event.pointerId) return;
    const next = clampTreeWidth(active.startWidth + event.clientX - active.startX, bounds);
    if (next === active.width) return;
    active.width = next;
    onWidth(next);
  };
  const finish = (event: ReactPointerEvent<HTMLDivElement>) => {
    const active = drag.current;
    if (!active || active.pointer !== event.pointerId) return;
    drag.current = null;
    setDragging(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    onCommit(active.width);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const next = treeWidthForKey(event.key, width, bounds, event.shiftKey);
    if (next === null) return;
    event.preventDefault();
    onWidth(next);
    onCommit(next);
  };
  return (
    <div
      role="separator"
      tabIndex={0}
      aria-orientation="vertical"
      aria-label="Resize folders panel"
      aria-controls={controls}
      aria-valuemin={bounds.min}
      aria-valuemax={bounds.max}
      aria-valuenow={width}
      aria-valuetext={`${width} pixels`}
      data-testid="explorer-tree-divider"
      data-dragging={dragging || undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={finish}
      onPointerCancel={finish}
      onKeyDown={onKeyDown}
      onDoubleClick={() => {
        const reset = clampTreeWidth(TREE_WIDTH.initial, bounds);
        onWidth(reset);
        onCommit(reset);
      }}
      style={{ left: `calc(${width}px + 0.5rem - 8px)` }}
      className="group absolute inset-y-0 z-10 hidden w-4 cursor-col-resize touch-none justify-center rounded-full lg:flex"
    >
      <span aria-hidden className={`my-2 w-0.5 rounded-full transition-colors ${dragging ? "bg-purple-700 dark:bg-purple-300" : "bg-transparent group-hover:bg-purple-300 group-focus-visible:bg-purple-700 dark:group-focus-visible:bg-purple-300"}`} />
    </div>
  );
}

function SearchSlot({ render, reveal, openPreview, ask }: { render: ExplorerConfig["renderSearch"]; reveal: (path: string) => void; openPreview: (target: PreviewTarget) => void; ask: SearchAsk | null }) {
  return <>{render(reveal, openPreview, ask)}</>;
}

export function DataExplorer({ config }: { config: ExplorerConfig }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const url = useMemo(() => readUrlState(params), [params]);
  const search = params.toString();
  const sortOfPath = useCallback((path: string) => sortParam(readSort(config.viewer, treeLevel(path)), "tree"), [config.viewer]);
  const tree = useExplorerTree(config.extra, true, sortOfPath);
  const downloads = useDownloads();
  const [selection, setSelection] = useState<Selection>(EMPTY_SELECTION);
  const [include, setInclude] = useState<IncludeKey[]>([...INCLUDE_KEYS]);
  const [previewEntry, setPreviewEntry] = useState<TreeEntry | null>(null);
  const [searchAsk, setSearchAsk] = useState<SearchAsk | null>(null);
  const [reportOpen, setReportOpen] = useState(false);
  const [viewsOpen, setViewsOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [showTree, setShowTree] = useState(false);
  const [layout, setLayout] = useState<"list" | "grid">("list");
  const [visit, setVisit] = useState<FolderVisit | null>(null);
  const [transcriptCount, setTranscriptCount] = useState<{ path: string; count: number } | null>(null);
  const [tableCount, setTableCount] = useState<{ path: string; count: number } | null>(null);
  const [miniTrack, setMiniTrack] = useState<{ mediaId: string; name: string; src: string; startMs?: number } | null>(null);
  const [miniAt, setMiniAt] = useState<{ ms: number; durationMs: number | null } | null>(null);
  const [viewError, setViewError] = useState<string | null>(null);
  const [loadedAt, setLoadedAt] = useState<number | null>(null);
  const online = useOnline();
  const [pendingDownload, setPendingDownload] = useState<string | null>(null);
  const landed = useRef(false);
  const restored = useRef<string | null>(null);
  const latest = useRef(url);
  const visited = useRef(new Set<string>());
  const dismissedPreview = useRef<string | null>(null);
  const pushedPreview = useRef(false);
  const fetchedTables = useRef(new Set<string>());
  const panelId = useId();
  const columnsRef = useRef<HTMLDivElement | null>(null);
  const [treeWidth, setTreeWidth] = useState<number>(TREE_WIDTH.initial);
  const [widthBounds, setWidthBounds] = useState<WidthBounds>(() => treeWidthBounds(null));

  useEffect(() => {
    latest.current = url;
  }, [url]);

  const navigate = useCallback(
    (next: Partial<ExplorerUrlState>, mode: "push" | "replace" = "replace") => {
      const merged = mergeNavigation(latest.current, next);
      latest.current = merged;
      const base = new URLSearchParams(typeof window !== "undefined" ? window.location.search : params.toString());
      base.delete("viewId");
      const href = `${pathname}${writeUrlState(merged, base)}`;
      if (typeof window !== "undefined") rememberScroll(search, window.scrollY);
      if (mode === "push") router.push(href, { scroll: false });
      else router.replace(href, { scroll: false });
    },
    [params, pathname, router, search]
  );

  const open = useCallback(
    (path: string) => {
      if (path === latest.current.path) return;
      setShowTree((value) => (typeof window !== "undefined" && window.matchMedia("(min-width: 1024px)").matches ? value : false));
      navigate({ path, sheet: "", q: "", preview: "", t: 0, find: "" }, "push");
    },
    [navigate]
  );

  const reveal = useCallback(
    (path: string) => {
      tree.expand(ancestorsOf(path));
      navigate({ path, view: "files", sheet: "", q: "", preview: "", t: 0, find: "" }, "push");
      if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
    },
    [navigate, tree]
  );

  const { expand, trees } = tree;
  useEffect(() => {
    expand(ancestorsOf(url.path));
  }, [url.path, expand]);

  useEffect(() => {
    const stored = readStoredWidth();
    if (stored !== null) setTreeWidth(stored);
  }, []);

  useEffect(() => {
    const node = columnsRef.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const measure = () => {
      const bounds = treeWidthBounds(node.clientWidth);
      setWidthBounds((previous) => (previous.min === bounds.min && previous.max === bounds.max ? previous : bounds));
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (landed.current) return;
    const root = trees[""];
    if (!root) return;
    landed.current = true;
    if (latest.current.path || params.get("viewId")) return;
    const fallback = root.taxonomies?.find((taxonomy) => taxonomy.default);
    if (fallback) navigate({ path: fallback.path }, "replace");
  }, [trees, navigate, params]);

  const bundleId = params.get("bundle");
  const [bundle, setBundle] = useState<{ id: string; data: ReportBundle | null; error: string | null } | null>(null);
  useEffect(() => {
    if (!bundleId) return;
    let cancelled = false;
    fetchBundle(bundleId)
      .then((data) => {
        if (!cancelled) setBundle({ id: bundleId, data, error: null });
      })
      .catch(() => {
        if (!cancelled) setBundle({ id: bundleId, data: null, error: "That download isn't available to you." });
      });
    return () => {
      cancelled = true;
    };
  }, [bundleId]);

  const dropBundle = useCallback(() => {
    setBundle(null);
    const base = new URLSearchParams(window.location.search);
    base.delete("bundle");
    const text = base.toString();
    router.replace(`${pathname}${text ? `?${text}` : ""}`, { scroll: false });
  }, [router, pathname]);

  const startBundle = useCallback(
    (data: ReportBundle) => {
      void downloads.enqueueZip({
        label: data.label,
        fileName: zipName(data.label),
        source: { kind: "manifests", requests: [{ method: "GET", endpoint: bundleManifestEndpoint(data.id), params: {} }], extra: [] },
        include: null,
        mode: "zip",
        bytes: data.bytes,
        files: data.fileCount
      });
      dropBundle();
    },
    [downloads, dropBundle]
  );

  const viewId = params.get("viewId");
  useEffect(() => {
    if (!viewId) return;
    let cancelled = false;
    fetchSavedView(viewId)
      .then((view) => {
        if (cancelled) return;
        expand(ancestorsOf(view.path));
        navigate({ path: view.path, view: view.state.view, sheet: view.state.sheet, q: view.state.q }, "replace");
      })
      .catch(() => {
        if (!cancelled) setViewError("That saved view isn't available to you.");
      });
    return () => {
      cancelled = true;
    };
  }, [viewId, navigate, expand]);

  const current = trees[url.path];
  useEffect(() => {
    if (current) setLoadedAt(Date.now());
  }, [current]);

  useEffect(() => {
    if (!current || restored.current === search) return;
    restored.current = search;
    const y = recallScroll(search);
    if (y !== null) window.scrollTo({ top: y });
  }, [current, search]);

  useEffect(() => {
    const save = () => rememberScroll(search, window.scrollY);
    window.addEventListener("pagehide", save);
    return () => {
      save();
      window.removeEventListener("pagehide", save);
    };
  }, [search]);

  useEffect(() => {
    if (!url.path || visited.current.has(url.path)) return;
    const path = url.path;
    const timer = window.setTimeout(() => {
      visited.current.add(path);
      recordVisit(path)
        .then((answer) => setVisit(answer))
        .catch(() => setVisit(null));
    }, 1200);
    return () => window.clearTimeout(timer);
  }, [url.path]);

  const extraText = JSON.stringify(config.extra);
  const tablesOpen = url.view === "tables";
  useEffect(() => {
    if (!url.path || !tablesOpen) return;
    const path = url.path;
    const key = JSON.stringify([path, extraText]);
    if (fetchedTables.current.has(key)) return;
    const abort = new AbortController();
    fetchReportSheets(path, JSON.parse(extraText) as QueryParams, abort.signal)
      .then((list) => {
        if (abort.signal.aborted || !list) return;
        fetchedTables.current.add(key);
        setTableCount({ path, count: list.sheets.filter((sheet) => (sheet.count ?? 0) > 0).length });
      })
      .catch(() => undefined);
    return () => abort.abort();
  }, [url.path, extraText, tablesOpen]);

  const crumbs = useMemo<Crumb[]>(() => {
    const fromServer = current?.crumbs ?? [];
    if (fromServer.some((crumb) => crumb.path === "")) return fromServer;
    return [{ name: config.rootName, path: "" }, ...fromServer];
  }, [current, config.rootName]);
  const parentPath = url.path.split("/").slice(0, -1).join("/");
  const ownEntry = trees[parentPath]?.entries.find((entry) => entry.path === url.path) ?? null;
  const folderPending = Boolean(url.path) && !current;
  const folderTitle = folderPending ? (ownEntry?.name ?? "") : crumbs[crumbs.length - 1]?.name || config.rootName;
  const folderName = folderTitle || "Folder";
  const recordType = ownEntry?.recordType ?? null;
  const taxonomies = current?.taxonomies ?? trees[""]?.taxonomies ?? [];
  const activeTaxonomy = current?.taxonomy ?? null;
  const depth = url.path.split("/").filter(Boolean).length;
  const wholeRepository = depth <= 1;
  const isWorkshop = recordType === "workshop";
  const wide = url.view !== "files";

  const folderSize = useFolderSize(url.path, config.extra, config.downloadExtra, downloads.measure);
  const selectionSize = useSelectionSize(selection, config.extra, config.downloadExtra, downloads.measure);
  const wanted = include.length === INCLUDE_KEYS.length ? null : include;
  const folderBytes = folderSize.sized ? sizedFor(folderSize.sized, include).bytes : null;
  const caps = downloads.capabilities;
  const tooBig = folderBytes !== null && overLimit(folderBytes, caps) && !caps.stream && !caps.saveFile;

  const files = useMemo(() => (current?.entries ?? []).filter((entry) => entry.kind === "file"), [current]);
  const previewable = useMemo(() => files.filter((entry) => canPreview(entry)), [files]);

  const folderSource = useCallback(
    (): JobSource => ({
      kind: "manifests",
      requests: [{ method: "GET", endpoint: "/data/manifest", params: { path: latest.current.path, include: includeParam(wanted), ...config.downloadExtra } }],
      extra: []
    }),
    [wanted, config.downloadExtra]
  );

  const downloadFolder = useCallback(
    (mode: "zip" | "folder") => {
      void downloads.enqueueZip({ label: folderName, fileName: zipName(folderName), source: folderSource(), include: wanted, mode, bytes: folderBytes, files: folderSize.sized ? sizedFor(folderSize.sized, include).files : null });
    },
    [downloads, folderName, folderSource, wanted, folderBytes, folderSize.sized, include]
  );

  const downloadSelection = useCallback(
    (mode: "zip" | "folder") => {
      void downloads.enqueueZip({
        label: `Selected · ${selectionLabel(selection)}`,
        fileName: zipName("Selection"),
        source: selectionSource(selection, wanted, config.downloadExtra),
        include: wanted,
        mode,
        bytes: selectionSize.sized?.bytes ?? null,
        files: selectionSize.sized?.files ?? null
      });
    },
    [downloads, selection, wanted, config.downloadExtra, selectionSize.sized]
  );

  const downloadDataset = useCallback(
    (mode: "zip" | "folder") => {
      void downloads.enqueueZip({ label: "Whole dataset", fileName: zipName("All Records"), source: { kind: "manifests", requests: [{ method: "GET", endpoint: "/export/dataset", params: {} }], extra: [] }, include: wanted, mode, bytes: null, files: null });
    },
    [downloads, wanted]
  );

  const makeReport = useCallback(
    (options: ReportOptions, name: string, extra: QueryParams = config.extra) => {
      downloads.enqueue({ kind: "report", label: `Report (.xlsx) · ${folderName}`, url: reportFileUrl(url.path, "xlsx", { ...extra, ...reportOptionParams(options, url.q, name) }), fileName: name, path: url.path });
    },
    [downloads, folderName, url.path, url.q, config.extra]
  );

  const makePack = useCallback(
    (options: ReportOptions, name: string, extra: QueryParams = config.extra) => {
      const packName = name.replace(/\.xlsx$/i, ".zip");
      downloads.enqueue({ kind: "pack", label: `Report pack · ${folderName}`, url: reportPackUrl(url.path, { ...extra, ...reportOptionParams(options, url.q, name) }), fileName: packName, path: url.path });
    },
    [downloads, folderName, url.path, url.q, config.extra]
  );

  const openPreview = useCallback(
    (target: { mediaId: string; name: string; mediaType: string | null; startMs?: number | null; find?: string }) => {
      const known = files.find((entry) => entry.mediaId === target.mediaId);
      dismissedPreview.current = null;
      const already = Boolean(latest.current.preview);
      if (!already) pushedPreview.current = true;
      setPreviewEntry(known ?? { name: target.name, path: `media:${target.mediaId}`, kind: "file", mediaId: target.mediaId, mediaType: target.mediaType ?? undefined });
      navigate({ preview: target.mediaId, t: target.startMs ? Math.round(target.startMs / 1000) : 0, find: target.find ?? "", kind: target.mediaType ?? "" }, already ? "replace" : "push");
    },
    [files, navigate]
  );

  useEffect(() => {
    if (!url.preview) {
      dismissedPreview.current = null;
      if (!latest.current.preview) {
        pushedPreview.current = false;
        setPreviewEntry(null);
      }
      return;
    }
    if (url.preview === dismissedPreview.current || (previewEntry && previewEntry.mediaId === url.preview)) return;
    const known = files.find((entry) => entry.mediaId === url.preview);
    if (known) setPreviewEntry(known);
    else if (!current) return;
    else setPreviewEntry({ name: "File", path: `media:${url.preview}`, kind: "file", mediaId: url.preview, mediaType: url.kind || undefined });
  }, [url.preview, url.kind, files, previewEntry, current]);

  const closePreview = useCallback(() => {
    dismissedPreview.current = latest.current.preview || dismissedPreview.current;
    setPreviewEntry(null);
    if (pushedPreview.current && latest.current.preview && typeof window !== "undefined") {
      pushedPreview.current = false;
      latest.current = mergeNavigation(latest.current, { preview: "", t: 0, find: "", kind: "" });
      router.back();
      return;
    }
    pushedPreview.current = false;
    navigate({ preview: "", t: 0, find: "", kind: "" });
  }, [navigate, router]);

  const showView = useCallback(
    (view: ExplorerView) => {
      if (view !== latest.current.view) navigate({ view }, "push");
    },
    [navigate]
  );

  const commitWidth = useCallback((width: number) => storeWidth(width), []);

  const onToggle = useCallback((entry: TreeEntry) => setSelection((previous) => toggled(previous, entry)), []);
  const onSelectAll = useCallback((entries: TreeEntry[], on: boolean) => setSelection((previous) => withAll(previous, entries, on)), []);
  const clearSelection = useCallback(() => setSelection(EMPTY_SELECTION), []);
  const onBrowse = useCallback((next: { type: string; page: number; q: string }) => navigate({ browse: next.type, browsePage: next.page, browseQ: next.q }), [navigate]);
  const onTables = useCallback((sheet: string, q: string) => navigate({ sheet, q }), [navigate]);
  const rootError = tree.errors[""];
  const filesCount = current ? `${files.length.toLocaleString("en-IN")}${current.nextCursor ? "+" : ""}` : null;
  const transcriptsKnown = transcriptCount && transcriptCount.path === url.path ? transcriptCount.count : (folderSize.sized?.byType.transcripts?.files ?? null);
  const reportItem = downloads.reportFor(url.path);
  const newHere = visit && visit.path === url.path && visit.previousVisitAt && visit.newFiles > 0 ? visit.newFiles : 0;

  const withPath = useCallback(async (hit: PaletteHit, run: (path: string) => void, openRecord = false) => {
    try {
      const found = await locateHit(hit);
      if (found.path) run(found.path);
      else if (openRecord && found.recordHref) router.push(found.recordHref);
      else setViewError(`“${hit.title}” isn't filed in a folder yet.`);
    } catch {
      setViewError("That record couldn't be found in the folders.");
    }
  }, [router]);

  useEffect(() => {
    if (!pendingDownload || pendingDownload !== url.path || !folderSize.sized) return;
    setPendingDownload(null);
    downloadFolder("zip");
  }, [pendingDownload, url.path, folderSize.sized, downloadFolder]);

  useExplorerShortcuts({
    search: () => (document.querySelector<HTMLInputElement>("[data-explorer-search] input, input[data-explorer-search]") ?? null)?.focus(),
    files: () => showView("files"),
    tables: () => showView("tables"),
    transcripts: () => showView("transcripts"),
    up: () => {
      if (url.path) open(parentPath);
    },
    download: () => downloadFolder("zip"),
    selectAll: () => onSelectAll(current?.entries ?? [], true),
    palette: () => setPaletteOpen(true),
    help: () => setHelpOpen(true),
    goData: () => document.querySelector<HTMLElement>("[data-testid='downloads-tray'] button")?.focus()
  });

  const taxonomyRow = taxonomies.find((taxonomy) => taxonomy.id === activeTaxonomy) ?? null;
  const shownCrumbs = crumbs
    .filter((crumb) => crumb.path !== "" || crumbs.length === 1)
    .map((crumb) => (taxonomyRow && crumb.path === taxonomyRow.path ? { ...crumb, name: taxonomyRoot(taxonomyRow.name) } : crumb));
  const treeRoot = taxonomyRow ? { path: taxonomyRow.path, name: taxonomyRoot(taxonomyRow.name) } : { path: "", name: config.rootName };

  const sidePanel = (
    <aside
      id={panelId}
      className={`panel flex flex-col gap-2 p-3 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain ${showTree ? "" : wide ? "hidden" : "max-lg:hidden"}`}
      aria-label="Folders"
      data-testid="explorer-tree-panel"
    >
      <TaxonomyPicker taxonomies={taxonomies} active={activeTaxonomy} onSelect={open} />
      {config.toolbarExtra}
      {rootError ? <Problem onRetry={() => void tree.load("")}>{rootError}</Problem> : null}
      <div className="max-h-[60vh] overflow-y-auto lg:max-h-none lg:overflow-visible">
        <FolderTree store={tree} selectedPath={url.path} rootName={treeRoot.name} rootPath={treeRoot.path} onSelect={open} />
      </div>
    </aside>
  );

  const iconToggle = (on: boolean) =>
    `grid h-9 w-9 place-items-center rounded-sm border transition ${on ? "border-purple-300 bg-purple-100 text-purple-800 dark:border-purple-700 dark:bg-purple-950/60 dark:text-purple-200" : "border-line-200 bg-card text-ink-700 hover:border-purple-300"}`;

  const treeSort = readSort(config.viewer, treeLevel(url.path)) ?? LIST_DEFAULT.tree;
  const changeTreeSort = (choice: SortChoice) => {
    const level = treeLevel(url.path);
    writeSort(config.viewer, level, choice, "tree");
    tree.resort(Object.keys(tree.trees).filter((path) => treeLevel(path) === level));
  };

  const tabTrailing = (
    <>
      {url.view === "files" && current ? <SortMenu value={treeSort} keys={availableKeys(current.sorts)} onChange={changeTreeSort} label="Sort folder" /> : null}
      {url.view === "files" ? (
        <div className="flex gap-1.5" role="group" aria-label="Layout">
          <button type="button" aria-pressed={layout === "list"} aria-label="List view" onClick={() => setLayout("list")} className={iconToggle(layout === "list")}>
            <List className="h-4 w-4" aria-hidden />
          </button>
          <button type="button" aria-pressed={layout === "grid"} aria-label="Grid view" onClick={() => setLayout("grid")} className={iconToggle(layout === "grid")}>
            <LayoutGrid className="h-4 w-4" aria-hidden />
          </button>
        </div>
      ) : null}
      <button type="button" aria-label="Saved views" title="Saved views" className={iconToggle(false)} onClick={() => setViewsOpen(true)}>
        <Bookmark className="h-4 w-4" aria-hidden />
      </button>
    </>
  );

  const headerBlock = (
    <>
            <FolderHeader
              crumbs={shownCrumbs}
              folderName={folderPending ? folderTitle : (shownCrumbs[shownCrumbs.length - 1]?.name ?? folderName)}
              recordType={recordType}
              size={folderSize}
              include={include}
              onInclude={setInclude}
              transcripts={transcriptsKnown}
              onOpen={open}
              onReport={() => setReportOpen(true)}
              onDownload={() => downloadFolder("zip")}
              onSaveFolder={caps.saveFolder ? () => downloadFolder("folder") : null}
              busy={false}
              loading={folderPending ? !tree.errors[url.path] : !current && tree.loading.has(url.path)}
              recordHref={current?.info?.record && config.recordHref ? config.recordHref(current.info.record.kind, current.info.record.id) : null}
              onWholeDataset={wholeRepository ? () => downloadDataset("zip") : null}
              info={current?.info ?? null}
              fresh={newHere ? `+${newHere} new since you looked` : null}
              parent={shownCrumbs.length > 1 ? shownCrumbs[shownCrumbs.length - 2] : null}
              foldersButton={
                <button type="button" className="inline-flex min-h-11 items-center rounded-full border border-line-200 bg-card px-3 text-[13px] text-ink-700 hover:border-purple-300" aria-expanded={showTree} onClick={() => setShowTree((value) => !value)}>
                  {showTree ? "Hide folders" : "Folders"}
                </button>
              }
            />
            {tooBig ? <Note tone="warn">Too big for one zip in this browser. Use Save to a folder or leave some types out.</Note> : null}
            {!caps.stream && !caps.saveFile && limitSentence(caps) ? <p className="text-xs text-ink-500">{limitSentence(caps)}</p> : null}
            {config.datasetLine && wholeRepository ? <p className="text-xs text-ink-500">{config.datasetLine}</p> : null}
            {current?.info ? <RecordDetails info={current.info} recordHref={current.info.record && config.recordHref ? config.recordHref(current.info.record.kind, current.info.record.id) : null} /> : null}
            {isWorkshop ? <WorkshopOverview path={url.path} extra={config.extra} visit={visit && visit.path === url.path ? visit : null} onOpen={open} onShowNew={() => showView("files")} /> : null}
    </>
  );

  return (
    <>
      <div className="grid gap-4 max-lg:gap-3.5 max-sm:gap-3">
        {config.title ? (
          <div className="max-sm:sr-only">
            <ExplorerHeader
              title={config.title}
              description={config.description}
              actions={
                <>
                  <span className="contents max-sm:hidden">{config.headerExtra}</span>
                  <span className={wide ? "hidden sm:contents" : "hidden sm:max-lg:contents"}>
                    <button type="button" className="inline-flex min-h-11 items-center rounded-md border border-line-200 bg-card px-3.5 text-sm text-ink-900 hover:border-purple-300" aria-expanded={showTree} onClick={() => setShowTree((value) => !value)}>
              {showTree ? "Hide folders" : "Folders"}
            </button>
                  </span>
                </>
              }
            />
          </div>
        ) : null}
        {config.headerExtra ? <div className={`flex flex-wrap items-center justify-end gap-2 ${config.title ? "sm:hidden" : ""}`}>{config.headerExtra}</div> : null}
        <div data-explorer-search="">
          <SearchSlot render={config.renderSearch} reveal={reveal} openPreview={openPreview} ask={searchAsk} />
        </div>
        {!online.online ? <OfflineNote loadedAt={loadedAt} /> : null}
        {viewError ? <Problem onRetry={() => setViewError(null)} retryLabel="Dismiss">{viewError}</Problem> : null}
        {bundle && bundle.id === bundleId ? (
          bundle.data ? (
            <section aria-label="Download from the report" className="panel flex flex-wrap items-center gap-3 p-4" data-testid="bundle-offer">
              <div className="min-w-0 flex-1">
                <p className="font-display text-base font-bold text-ink-900">{bundle.data.label}</p>
                <p className="text-sm text-ink-500">
                  {bundle.data.fileCount.toLocaleString("en-IN")} {bundle.data.fileCount === 1 ? "file" : "files"} · {formatBytes(bundle.data.bytes)}
                  {bundle.data.hiddenCount > 0 ? ` · ${bundle.data.hiddenCount.toLocaleString("en-IN")} you can't see are left out` : ""}
                </p>
              </div>
              <ActionGroup align="end">
                <button type="button" className="field-button-secondary" onClick={dropBundle}>
                  Not now
                </button>
                <button type="button" className="field-button" onClick={() => bundle.data && startBundle(bundle.data)}>
                  <Download className="h-4 w-4" aria-hidden />
                  Download · {formatBytes(bundle.data.bytes)}
                </button>
              </ActionGroup>
            </section>
          ) : (
            <Problem onRetry={dropBundle} retryLabel="Dismiss">
              {bundle.error}
            </Problem>
          )
        ) : null}
        <div
          ref={columnsRef}
          style={{ ["--explorer-tree-width" as string]: `${clampTreeWidth(treeWidth, widthBounds)}px` }}
          className={`relative grid items-start gap-4 max-lg:gap-3.5 max-sm:gap-3 ${wide && !showTree ? "" : "lg:grid-cols-[var(--explorer-tree-width)_minmax(0,1fr)]"}`}
        >
          {sidePanel}
          {wide && !showTree ? null : <PanelDivider width={clampTreeWidth(treeWidth, widthBounds)} bounds={widthBounds} controls={panelId} onWidth={setTreeWidth} onCommit={commitWidth} />}
          <div className="grid min-w-0 gap-4 max-lg:gap-3.5 max-sm:gap-3">
            {headerBlock}
            <section className="panel overflow-hidden max-sm:grid max-sm:gap-3 max-sm:overflow-visible max-sm:rounded-none max-sm:border-0 max-sm:bg-transparent max-sm:shadow-none" aria-label="Contents">
              <ViewTabs view={url.view} counts={{ files: folderSize.sized ? `${folderSize.sized.files.toLocaleString("en-IN")}${folderSize.sized.truncated ? "+" : ""}` : filesCount, tables: tableCount && tableCount.path === url.path && tableCount.count > 0 ? tableCount.count.toLocaleString("en-IN") : null, transcripts: transcriptsKnown !== null ? transcriptsKnown.toLocaleString("en-IN") : null }} onView={showView} trailing={tabTrailing} />
              <div id="explorer-view" role="tabpanel" aria-label={VIEW_TABS.find((tab) => tab.value === url.view)?.label} className="min-w-0">
                <div hidden={url.view !== "files"}>
                  <FolderContents
                    path={url.path}
                    tree={current}
                    loading={tree.loading.has(url.path)}
                    error={url.path ? (tree.errors[url.path] ?? null) : null}
                    extra={config.extra}
                    selection={selection}
                    fileHref={config.fileHref}
                    emptyBody={config.emptyBody}
                    emptyAction={config.emptyAction}
                    mediaStatus={downloads.mediaStatus}
                    onOpen={open}
                    onToggle={onToggle}
                    onSelectAll={onSelectAll}
                    onPreview={(entry) => openPreview({ mediaId: entry.mediaId ?? "", name: entry.name, mediaType: entry.mediaType ?? null })}
                    onDownload={(request) => downloads.enqueue(request)}
                    onMore={() => void tree.more(url.path)}
                    onRetry={() => void tree.load(url.path)}
                    layout={layout}
                    playing={miniTrack && !previewEntry ? { mediaId: miniTrack.mediaId, ms: miniAt?.ms ?? miniTrack.startMs ?? 0, durationMs: miniAt?.durationMs ?? null } : null}
                  />
                </div>
                {url.view === "tables" || reportItem ? (
                  <div hidden={url.view !== "tables"}>
                    <DataTables
                      path={url.path}
                      folderName={folderName}
                      extra={config.extra}
                      memoryKey={{ status: config.statusKey, viewer: config.viewer }}
                      autoLoad={!wholeRepository}
                      initialSheet={url.sheet}
                      initialQuery={url.q}
                      onState={onTables}
                      notice={config.tablesNotice}
                      reportItem={reportItem}
                      onReport={() => setReportOpen(true)}
                      onTryReport={downloads.startNow}
                      now={downloads.now}
                      onDownload={(request) => downloads.enqueue(request)}
                      onBack={() => showView("files")}
                    />
                  </div>
                ) : null}
                {url.view === "transcripts" ? (
                  <TranscriptsTab
                    path={url.path}
                    folderName={folderName}
                    extra={config.extra}
                    onRead={openPreview}
                    onDownload={(request) => downloads.enqueue(request)}
                    zipUrl={transcriptBundleUrl(url.path, config.extra)}
                    onCount={(count) => setTranscriptCount({ path: url.path, count })}
                    viewer={config.viewer}
                    trail={shownCrumbs.filter((crumb) => !taxonomyRow || crumb.path !== taxonomyRow.path).map((crumb) => crumb.name).join(" › ")}
                  />
                ) : null}
              </div>
            </section>
          </div>
        </div>
        <SelectionBar
          selection={selection}
          sized={selectionSize.sized}
          onClear={clearSelection}
          onDownload={() => downloadSelection("zip")}
          onSaveFolder={caps.saveFolder ? () => downloadSelection("folder") : null}
          onSelectAll={current && current.entries.length > selection.size ? () => onSelectAll(current.entries, true) : null}
          total={files.length}
          shareUrl={typeof window !== "undefined" ? window.location.href : null}
        />
      </div>

      <div className="mt-5 grid gap-5">
        <BrowseByType
          types={config.browseTypes}
          type={url.browse}
          page={url.browsePage}
          q={url.browseQ}
          workshopIds={config.browseScope?.workshopIds}
          scopeControl={config.browseScope?.control}
          viewer={config.viewer}
          onChange={onBrowse}
        />
        {config.secondary}
      </div>

      <DownloadsTray downloads={downloads} />
      <MiniPlayer
        key={miniTrack?.mediaId ?? "none"}
        track={previewEntry ? null : miniTrack}
        onExpand={() => {
          if (miniTrack) openPreview({ mediaId: miniTrack.mediaId, name: miniTrack.name, mediaType: "AUDIO", startMs: miniTrack.startMs ?? null });
        }}
        onClose={() => {
          setMiniTrack(null);
          setMiniAt(null);
        }}
        onPosition={(ms, durationMs) => setMiniAt((previous) => (previous && Math.floor(previous.ms / 1000) === Math.floor(ms / 1000) && previous.durationMs === durationMs ? previous : { ms, durationMs }))}
      />
      <PreviewDialog
        entry={previewEntry}
        siblings={previewable}
        onNavigate={(entry) => openPreview({ mediaId: entry.mediaId ?? "", name: entry.name, mediaType: entry.mediaType ?? null })}
        onClose={closePreview}
        recordHref={previewEntry?.mediaId ? config.fileHref(previewEntry.mediaId) : null}
        recordName={current?.info?.title ?? folderName}
        startMs={url.t ? url.t * 1000 : null}
        find={url.find || undefined}
        onDownload={(request) => downloads.enqueue(request)}
        onMinimise={(track) => {
          setMiniTrack(track);
          closePreview();
        }}
      />
      <ReportOptionsDialog
        open={reportOpen}
        onClose={() => setReportOpen(false)}
        path={url.path}
        folderName={folderName}
        subtitle={crumbs.slice(1).map((crumb) => crumb.name).join(" · ") || folderName}
        extra={config.extra}
        statusFilter={config.statusFilter}
        query={url.view === "tables" ? url.q : ""}
        onMake={makeReport}
        onPack={makePack}
      />
      <SavedViewsDialog
        open={viewsOpen}
        onClose={() => setViewsOpen(false)}
        current={{ path: url.path, state: { view: url.view, sheet: url.sheet, q: url.q, filters: Object.fromEntries(Object.entries(config.extra).filter(([, value]) => value !== undefined && value !== null && value !== "").map(([key, value]) => [key, String(value)])) } }}
        folderName={folderName}
        onOpenView={(view: SavedView) => {
          setViewsOpen(false);
          tree.expand(ancestorsOf(view.path));
          navigate({ path: view.path, view: view.state.view, sheet: view.state.sheet, q: view.state.q }, "push");
        }}
      />
      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        onOpen={(hit: PaletteHit) => {
          setPaletteOpen(false);
          void withPath(hit, reveal, true);
        }}
        onSearchEverything={(q) => {
          setPaletteOpen(false);
          setSearchAsk((previous) => ({ q, nonce: (previous?.nonce ?? 0) + 1 }));
        }}
        actions={[
          { key: "D", label: "Download folder", icon: "↓", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            reveal(path);
            setPendingDownload(path);
          }) },
          { key: "R", label: "Report (.xlsx)", icon: "▦", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            reveal(path);
            setReportOpen(true);
          }) },
          { key: "T", label: "Transcripts (.zip)", icon: "¶", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            tree.expand(ancestorsOf(path));
            navigate({ path, view: "transcripts", sheet: "", q: "" }, "push");
          }) },
          { key: "L", label: "Copy link to this folder", icon: "⧉", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            void navigator.clipboard?.writeText(`${window.location.origin}${pathname}${writeUrlState({ ...latest.current, path, preview: "", t: 0, find: "" })}`);
          }) }
        ]}
        search={fetchLocateHits}
      />
      <ShortcutsSheet open={helpOpen} onClose={() => setHelpOpen(false)} />
    </>
  );
}
