"use client";

import { Bookmark, Database, FileText, FolderTree as FolderTreeIcon, Layers, RefreshCw, Table2 } from "lucide-react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

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
import { TranscriptsTab } from "@/components/dataExplorer/TranscriptsTab";
import { useDownloads } from "@/components/dataExplorer/useDownloads";
import { useExplorerTree } from "@/components/dataExplorer/useExplorerTree";
import { WorkshopOverview } from "@/components/dataExplorer/WorkshopOverview";
import { fetchSavedView, includeParam, recordVisit, reportFileUrl, reportOptionParams, transcriptBundleUrl } from "@/lib/dataExplorer/api";
import { INCLUDE_KEYS, type Crumb, type FolderVisit, type IncludeKey, type ReportOptions, type ReportResponse, type SavedView, type Taxonomy, type TreeEntry } from "@/lib/dataExplorer/contract";
import { limitSentence, overLimit } from "@/lib/dataExplorer/plan";
import type { JobSource } from "@/lib/dataExplorer/protocol";
import { EMPTY_SELECTION, selectionLabel, selectionSource, toggled, withAll, type Selection } from "@/lib/dataExplorer/selection";
import { ancestorsOf, readUrlState, recallScroll, rememberScroll, writeUrlState, type ExplorerUrlState, type ExplorerView } from "@/lib/dataExplorer/urlState";
import { mergeNavigation } from "@/lib/dataExplorer/explorerView";
import type { QueryParams } from "@/lib/dataExplorer/wire";
import { exportFilename } from "@/lib/naming";

export type PreviewTarget = { mediaId: string; name: string; mediaType: string | null; startMs?: number | null; find?: string };

export type ExplorerConfig = {
  rootName: string;
  extra: QueryParams;
  downloadExtra: QueryParams;
  statusKey: string;
  viewer: string;
  fileHref: (mediaId: string) => string | null;
  renderSearch: (reveal: (path: string) => void, openPreview: (target: PreviewTarget) => void) => ReactNode;
  headerExtra?: ReactNode;
  toolbarExtra?: ReactNode;
  emptyBody: string;
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

function TaxonomyPicker({ taxonomies, active, onSelect }: { taxonomies: Taxonomy[]; active: string | null; onSelect: (path: string) => void }) {
  if (taxonomies.length === 0) return null;
  const current = taxonomies.find((taxonomy) => taxonomy.id === active);
  return (
    <div className="grid gap-1.5 px-1">
      <span className="field-label flex items-center gap-1.5">
        <Layers className="h-3.5 w-3.5" aria-hidden />
        Organise by
      </span>
      <div className="flex flex-wrap gap-1.5" role="group" aria-label="Organise by">
        {taxonomies.map((taxonomy) => {
          const selected = taxonomy.id === active;
          return (
            <button
              key={taxonomy.id}
              type="button"
              aria-pressed={selected}
              onClick={() => onSelect(taxonomy.path)}
              className={`min-h-9 rounded-full px-3 text-xs font-semibold transition pointer-coarse:min-h-11 ${
                selected ? "bg-purple-700 text-white" : "border border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
              }`}
            >
              {taxonomy.name}
            </button>
          );
        })}
      </div>
      {current?.description ? <p className="text-xs leading-5 text-ink-500">{current.description}</p> : null}
    </div>
  );
}

const VIEW_TABS: { value: ExplorerView; label: string; icon: ReactNode }[] = [
  { value: "files", label: "Files", icon: <FolderTreeIcon className="h-4 w-4" aria-hidden /> },
  { value: "tables", label: "Tables", icon: <Table2 className="h-4 w-4" aria-hidden /> },
  { value: "transcripts", label: "Transcripts", icon: <FileText className="h-4 w-4" aria-hidden /> }
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
    <div className="flex flex-wrap items-end gap-2 border-b border-line-200 px-2 pt-2 sm:px-3">
      <div role="tablist" aria-label="Contents" className="flex w-full gap-1 max-sm:rounded-md max-sm:border max-sm:border-line-200 max-sm:p-1 sm:w-auto">
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
              className={`inline-flex min-h-11 flex-1 items-center justify-center gap-1.5 px-3.5 text-sm transition sm:flex-none ${
                selected
                  ? "font-semibold text-purple-800 max-sm:rounded-sm max-sm:bg-purple-700 max-sm:text-white sm:shadow-[inset_0_-2px_0_var(--purple-700)] dark:text-purple-200"
                  : "text-ink-700 hover:text-ink-900"
              }`}
            >
              <span className="max-sm:hidden">{tab.icon}</span>
              {tab.label}
              {count ? <span className={`text-xs tabular-nums ${selected ? "" : "text-ink-500"} max-sm:hidden`}>{count}</span> : null}
            </button>
          );
        })}
      </div>
      <span className="flex-1" />
      {trailing ? <div className="flex items-center gap-2 pb-2">{trailing}</div> : null}
    </div>
  );
}

function SearchSlot({ render, reveal, openPreview }: { render: ExplorerConfig["renderSearch"]; reveal: (path: string) => void; openPreview: (target: PreviewTarget) => void }) {
  return <>{render(reveal, openPreview)}</>;
}

export function DataExplorer({ config }: { config: ExplorerConfig }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const url = useMemo(() => readUrlState(params), [params]);
  const search = params.toString();
  const tree = useExplorerTree(config.extra, true);
  const downloads = useDownloads();
  const [selection, setSelection] = useState<Selection>(EMPTY_SELECTION);
  const [include, setInclude] = useState<IncludeKey[]>([...INCLUDE_KEYS]);
  const [previewEntry, setPreviewEntry] = useState<TreeEntry | null>(null);
  const [reportOpen, setReportOpen] = useState(false);
  const [viewsOpen, setViewsOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [showTree, setShowTree] = useState(false);
  const [visit, setVisit] = useState<FolderVisit | null>(null);
  const [transcriptCount, setTranscriptCount] = useState<{ path: string; count: number } | null>(null);
  const [miniTrack, setMiniTrack] = useState<{ mediaId: string; name: string; src: string; startMs?: number } | null>(null);
  const [viewError, setViewError] = useState<string | null>(null);
  const [loadedAt, setLoadedAt] = useState<number | null>(null);
  const online = useOnline();
  const [pendingDownload, setPendingDownload] = useState<string | null>(null);
  const landed = useRef(false);
  const restored = useRef<string | null>(null);
  const latest = useRef(url);
  const visited = useRef(new Set<string>());

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
      setShowTree(false);
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
    if (landed.current) return;
    const root = trees[""];
    if (!root) return;
    landed.current = true;
    if (latest.current.path || params.get("viewId")) return;
    const fallback = root.taxonomies?.find((taxonomy) => taxonomy.default);
    if (fallback) navigate({ path: fallback.path }, "replace");
  }, [trees, navigate, params]);

  const viewId = params.get("viewId");
  useEffect(() => {
    if (!viewId) return;
    let cancelled = false;
    fetchSavedView(viewId)
      .then((view) => {
        if (cancelled) return;
        tree.expand(ancestorsOf(view.path));
        navigate({ path: view.path, view: view.state.view, sheet: view.state.sheet, q: view.state.q }, "replace");
      })
      .catch(() => {
        if (!cancelled) setViewError("That saved view isn't available to you.");
      });
    return () => {
      cancelled = true;
    };
  }, [viewId, navigate, tree]);

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

  const crumbs = useMemo<Crumb[]>(() => {
    const fromServer = current?.crumbs ?? [];
    if (fromServer.some((crumb) => crumb.path === "")) return fromServer;
    return [{ name: config.rootName, path: "" }, ...fromServer];
  }, [current, config.rootName]);
  const folderName = crumbs[crumbs.length - 1]?.name || config.rootName;
  const parentPath = url.path.split("/").slice(0, -1).join("/");
  const ownEntry = trees[parentPath]?.entries.find((entry) => entry.path === url.path) ?? null;
  const recordType = ownEntry?.recordType ?? null;
  const taxonomies = current?.taxonomies ?? trees[""]?.taxonomies ?? [];
  const activeTaxonomy = current?.taxonomy ?? null;
  const depth = url.path.split("/").filter(Boolean).length;
  const wholeRepository = depth <= 1;
  const isWorkshop = recordType === "workshop";

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
    (options: ReportOptions, name: string) => {
      downloads.enqueue({ kind: "report", label: `Report (.xlsx) · ${folderName}`, url: reportFileUrl(url.path, "xlsx", { ...config.extra, ...reportOptionParams(options, url.q, name) }), fileName: name, path: url.path });
    },
    [downloads, folderName, url.path, url.q, config.extra]
  );

  const openPreview = useCallback(
    (target: { mediaId: string; name: string; mediaType: string | null; startMs?: number | null; find?: string }) => {
      const known = files.find((entry) => entry.mediaId === target.mediaId);
      setPreviewEntry(known ?? { name: target.name, path: `media:${target.mediaId}`, kind: "file", mediaId: target.mediaId, mediaType: target.mediaType ?? undefined });
      navigate({ preview: target.mediaId, t: target.startMs ? Math.round(target.startMs / 1000) : 0, find: target.find ?? "", kind: target.mediaType ?? "" });
    },
    [files, navigate]
  );

  useEffect(() => {
    if (!url.preview || (previewEntry && previewEntry.mediaId === url.preview)) return;
    const known = files.find((entry) => entry.mediaId === url.preview);
    if (known) setPreviewEntry(known);
    else if (!current) return;
    else setPreviewEntry({ name: "File", path: `media:${url.preview}`, kind: "file", mediaId: url.preview, mediaType: url.kind || undefined });
  }, [url.preview, url.kind, files, previewEntry, current]);

  const closePreview = useCallback(() => {
    setPreviewEntry(null);
    navigate({ preview: "", t: 0, find: "", kind: "" });
  }, [navigate]);

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

  const withPath = useCallback(async (hit: PaletteHit, run: (path: string) => void) => {
    try {
      const path = await locateHit(hit);
      if (path) run(path);
      else setViewError(`“${hit.title}” isn't filed in a folder yet.`);
    } catch {
      setViewError("That record couldn't be found in the folders.");
    }
  }, []);

  useEffect(() => {
    if (!pendingDownload || pendingDownload !== url.path || !folderSize.sized) return;
    setPendingDownload(null);
    downloadFolder("zip");
  }, [pendingDownload, url.path, folderSize.sized, downloadFolder]);

  useExplorerShortcuts({
    search: () => (document.querySelector<HTMLInputElement>("[data-explorer-search] input, input[data-explorer-search]") ?? null)?.focus(),
    files: () => navigate({ view: "files" }),
    tables: () => navigate({ view: "tables" }),
    transcripts: () => navigate({ view: "transcripts" }),
    up: () => {
      if (url.path) open(parentPath);
    },
    download: () => downloadFolder("zip"),
    selectAll: () => onSelectAll(current?.entries ?? [], true),
    palette: () => setPaletteOpen(true),
    help: () => setHelpOpen(true),
    goData: () => document.querySelector<HTMLElement>("[data-testid='downloads-tray'] button")?.focus()
  });

  const sidePanel = (
    <aside
      className={`panel grid gap-3 p-3 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain ${showTree ? "" : "max-lg:hidden"}`}
      aria-label="Folders"
      data-testid="explorer-tree-panel"
    >
      <TaxonomyPicker taxonomies={taxonomies} active={activeTaxonomy} onSelect={open} />
      {config.toolbarExtra}
      <div className="flex items-center justify-between border-t border-line-200 px-1 pt-2">
        <span className="field-label">Folders</span>
        <button
          type="button"
          className="grid h-9 w-9 place-items-center rounded-sm text-ink-500 hover:bg-purple-50 hover:text-purple-700 pointer-coarse:h-11 pointer-coarse:w-11"
          aria-label="Reload this folder"
          onClick={() => void tree.load(url.path)}
        >
          <RefreshCw className={`h-3.5 w-3.5 ${tree.loading.has(url.path) ? "animate-spin" : ""}`} aria-hidden />
        </button>
      </div>
      {rootError ? <Problem onRetry={() => void tree.load("")}>{rootError}</Problem> : null}
      <div className="max-h-[60vh] overflow-y-auto pr-1 lg:max-h-none lg:overflow-visible">
        <FolderTree store={tree} selectedPath={url.path} rootName={config.rootName} onSelect={open} />
      </div>
    </aside>
  );

  const tabTrailing =
    url.view === "files" ? (
      <>
        {newHere ? <span className="rounded-full bg-purple-100 px-2.5 py-0.5 text-xs font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">+{newHere} new since you looked</span> : null}
        <button type="button" className="inline-flex min-h-9 items-center gap-1.5 rounded-sm px-2.5 text-xs font-semibold text-ink-700 hover:bg-purple-50 pointer-coarse:min-h-11" onClick={() => setViewsOpen(true)}>
          <Bookmark className="h-3.5 w-3.5" aria-hidden />
          Saved views
        </button>
      </>
    ) : (
      <button type="button" className="inline-flex min-h-9 items-center gap-1.5 rounded-sm px-2.5 text-xs font-semibold text-ink-700 hover:bg-purple-50 pointer-coarse:min-h-11" onClick={() => setViewsOpen(true)}>
        <Bookmark className="h-3.5 w-3.5" aria-hidden />
        Saved views
      </button>
    );

  return (
    <>
      <div className="grid gap-4">
        {config.headerExtra ? <div className="flex flex-wrap items-center justify-end gap-2">{config.headerExtra}</div> : null}
        <div data-explorer-search="">
          <SearchSlot render={config.renderSearch} reveal={reveal} openPreview={openPreview} />
        </div>
        {!online.online ? <OfflineNote loadedAt={loadedAt} /> : null}
        {viewError ? <Problem onRetry={() => setViewError(null)} retryLabel="Dismiss">{viewError}</Problem> : null}
        <div className="flex items-center gap-2 lg:hidden">
          <button type="button" className="field-button-secondary min-h-11" aria-expanded={showTree} onClick={() => setShowTree((value) => !value)}>
            <FolderTreeIcon className="h-4 w-4" aria-hidden />
            {showTree ? "Hide folders" : "Folders"}
          </button>
        </div>
        <div className="grid items-start gap-4 lg:grid-cols-[320px_minmax(0,1fr)]">
          {sidePanel}
          <div className="grid min-w-0 gap-4">
            <FolderHeader
              crumbs={crumbs}
              folderName={folderName}
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
              loading={!current && tree.loading.has(url.path)}
              extraActions={
                wholeRepository ? (
                  <button type="button" className="field-button-secondary min-h-11" onClick={() => downloadDataset("zip")}>
                    <Database className="h-4 w-4" aria-hidden />
                    Whole dataset
                  </button>
                ) : null
              }
            />
            {tooBig ? <Note tone="warn">Too big for one zip in this browser. Use Save to a folder or leave some types out.</Note> : null}
            {!caps.stream && !caps.saveFile && limitSentence(caps) ? <p className="text-xs text-ink-500">{limitSentence(caps)}</p> : null}
            {config.datasetLine && wholeRepository ? <p className="text-xs text-ink-500">{config.datasetLine}</p> : null}
            {current?.info ? <RecordDetails info={current.info} /> : null}
            {isWorkshop ? <WorkshopOverview path={url.path} extra={config.extra} visit={visit && visit.path === url.path ? visit : null} onOpen={open} onShowNew={() => navigate({ view: "files" })} /> : null}
            <section className="panel overflow-hidden" aria-label="Contents">
              <ViewTabs view={url.view} counts={{ files: filesCount, transcripts: transcriptsKnown !== null ? transcriptsKnown.toLocaleString("en-IN") : null }} onView={(view) => navigate({ view })} trailing={tabTrailing} />
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
          besideTray={downloads.items.length > 0}
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
        onClose={() => setMiniTrack(null)}
      />
      <PreviewDialog
        entry={previewEntry}
        siblings={previewable}
        onNavigate={(entry) => openPreview({ mediaId: entry.mediaId ?? "", name: entry.name, mediaType: entry.mediaType ?? null })}
        onClose={closePreview}
        recordHref={previewEntry?.mediaId ? config.fileHref(previewEntry.mediaId) : null}
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
        query={url.view === "tables" ? url.q : ""}
        onMake={makeReport}
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
          void withPath(hit, reveal);
        }}
        onSearchEverything={(q) => {
          setPaletteOpen(false);
          const input = document.querySelector<HTMLInputElement>("[data-explorer-search] input");
          if (input) {
            input.focus();
            input.value = q;
            input.dispatchEvent(new Event("input", { bubbles: true }));
          }
        }}
        actions={[
          { key: "D", label: "Download", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            reveal(path);
            setPendingDownload(path);
          }) },
          { key: "R", label: "Report", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            reveal(path);
            setReportOpen(true);
          }) },
          { key: "T", label: "Transcripts", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            tree.expand(ancestorsOf(path));
            navigate({ path, view: "transcripts", sheet: "", q: "" }, "push");
          }) },
          { key: "L", label: "Copy link", run: (hit: PaletteHit) => void withPath(hit, (path) => {
            void navigator.clipboard?.writeText(`${window.location.origin}${pathname}${writeUrlState({ ...latest.current, path, preview: "", t: 0, find: "" })}`);
          }) }
        ]}
        search={fetchLocateHits}
      />
      <ShortcutsSheet open={helpOpen} onClose={() => setHelpOpen(false)} />
    </>
  );
}
