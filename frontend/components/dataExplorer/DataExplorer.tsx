"use client";

import { FolderTree as FolderTreeIcon, Layers, RefreshCw, Table2 } from "lucide-react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import { BrowseByType, type BrowseType } from "@/components/dataExplorer/BrowseByType";
import { DataTables } from "@/components/dataExplorer/DataTables";
import { DownloadsPanel } from "@/components/dataExplorer/DownloadsPanel";
import { Problem } from "@/components/dataExplorer/explorerUi";
import { FolderContents, RecordInfoCard } from "@/components/dataExplorer/FolderContents";
import { FolderTree } from "@/components/dataExplorer/FolderTree";
import { PreviewDialog } from "@/components/dataExplorer/PreviewDialog";
import { useDownloadJob } from "@/components/dataExplorer/useDownloadJob";
import { useExplorerTree } from "@/components/dataExplorer/useExplorerTree";
import type { Crumb, ReportResponse, Taxonomy, TreeEntry } from "@/lib/dataExplorer/contract";
import { EMPTY_SELECTION, toggled, withAll, type Selection } from "@/lib/dataExplorer/selection";
import { ancestorsOf, readUrlState, recallScroll, rememberScroll, writeUrlState, type ExplorerUrlState, type ExplorerView } from "@/lib/dataExplorer/urlState";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export type ExplorerConfig = {
  rootName: string;
  extra: QueryParams;
  downloadExtra: QueryParams;
  statusKey: string;
  viewer: string;
  fileHref: (mediaId: string) => string | null;
  renderSearch: (reveal: (path: string) => void) => ReactNode;
  toolbarExtra?: ReactNode;
  emptyBody: string;
  emptyAction?: ReactNode;
  browseTypes: BrowseType[];
  browseScope?: { control: ReactNode; workshopIds?: string };
  datasetLine?: string | null;
  tablesNotice?: (report: ReportResponse) => string | null;
  secondary?: ReactNode;
};

function TaxonomyPicker({ taxonomies, active, onSelect }: { taxonomies: Taxonomy[]; active: string | null; onSelect: (path: string) => void }) {
  if (taxonomies.length === 0) return null;
  const current = taxonomies.find((taxonomy) => taxonomy.id === active);
  return (
    <div className="grid gap-1.5">
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
              className={`min-h-9 rounded-full border px-3 text-xs font-semibold transition pointer-coarse:min-h-11 ${
                selected ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
              }`}
            >
              {taxonomy.name}
            </button>
          );
        })}
      </div>
      {current?.description ? <p className="text-xs text-ink-500">{current.description}</p> : null}
    </div>
  );
}

function ViewTabs({ view, onView }: { view: ExplorerView; onView: (view: ExplorerView) => void }) {
  const id = useId();
  const tabs: { value: ExplorerView; label: string; icon: ReactNode }[] = [
    { value: "files", label: "Files", icon: <FolderTreeIcon className="h-4 w-4" aria-hidden /> },
    { value: "tables", label: "Tables", icon: <Table2 className="h-4 w-4" aria-hidden /> }
  ];
  const onKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key !== "ArrowRight" && event.key !== "ArrowLeft" && event.key !== "Home" && event.key !== "End") return;
    event.preventDefault();
    const next = view === "files" ? "tables" : "files";
    const target = event.key === "Home" ? "files" : event.key === "End" ? "tables" : next;
    onView(target);
    document.getElementById(`${id}-${target}`)?.focus();
  };
  return (
    <div role="tablist" aria-label="Show" className="inline-flex w-full gap-1 rounded-lg border border-line-200 bg-surface-50 p-1 sm:w-auto">
      {tabs.map((tab) => (
        <button
          key={tab.value}
          id={`${id}-${tab.value}`}
          type="button"
          role="tab"
          aria-selected={view === tab.value}
          aria-controls="explorer-view"
          tabIndex={view === tab.value ? 0 : -1}
          onClick={() => onView(tab.value)}
          onKeyDown={onKey}
          className={`inline-flex min-h-9 flex-1 items-center justify-center gap-1.5 rounded-md px-4 text-sm font-semibold transition pointer-coarse:min-h-11 sm:flex-none ${
            view === tab.value ? "bg-card text-ink-900 shadow-sm" : "text-ink-500 hover:text-ink-900"
          }`}
        >
          {tab.icon}
          {tab.label}
        </button>
      ))}
    </div>
  );
}

export function DataExplorer({ config }: { config: ExplorerConfig }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const url = useMemo(() => readUrlState(params), [params]);
  const search = params.toString();
  const tree = useExplorerTree(config.extra, true);
  const job = useDownloadJob();
  const [selection, setSelection] = useState<Selection>(EMPTY_SELECTION);
  const [preview, setPreview] = useState<TreeEntry | null>(null);
  const landed = useRef(false);
  const restored = useRef<string | null>(null);

  const navigate = useCallback(
    (next: Partial<ExplorerUrlState>, mode: "push" | "replace" = "replace") => {
      const query = writeUrlState({ ...url, ...next }, params);
      const href = `${pathname}${query}`;
      if (typeof window !== "undefined") rememberScroll(search, window.scrollY);
      if (mode === "push") router.push(href, { scroll: false });
      else router.replace(href, { scroll: false });
    },
    [url, params, pathname, router, search]
  );

  const open = useCallback(
    (path: string) => {
      if (path === url.path) return;
      navigate({ path, sheet: "", q: "" }, "push");
    },
    [navigate, url.path]
  );

  const reveal = useCallback(
    (path: string) => {
      tree.expand(ancestorsOf(path));
      navigate({ path, view: "files", sheet: "", q: "" }, "push");
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
    if (url.path) return;
    const fallback = root.taxonomies?.find((taxonomy) => taxonomy.default);
    if (fallback) navigate({ path: fallback.path }, "replace");
  }, [trees, url.path, navigate]);

  const current = trees[url.path];
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

  const crumbs = useMemo<Crumb[]>(() => {
    const fromServer = current?.crumbs ?? [];
    if (fromServer.some((crumb) => crumb.path === "")) return fromServer;
    return [{ name: config.rootName, path: "" }, ...fromServer];
  }, [current, config.rootName]);
  const folderName = crumbs[crumbs.length - 1]?.name || config.rootName;
  const taxonomies = current?.taxonomies ?? trees[""]?.taxonomies ?? [];
  const activeTaxonomy = current?.taxonomy ?? null;
  const wholeRepository = url.path.split("/").filter(Boolean).length <= 1;

  const onToggle = useCallback((entry: TreeEntry) => setSelection((previous) => toggled(previous, entry)), []);
  const onSelectAll = useCallback((entries: TreeEntry[], on: boolean) => setSelection((previous) => withAll(previous, entries, on)), []);
  const clearSelection = useCallback(() => setSelection(EMPTY_SELECTION), []);
  const onBrowse = useCallback((next: { type: string; page: number; q: string }) => navigate({ browse: next.type, browsePage: next.page, browseQ: next.q }), [navigate]);
  const onTables = useCallback((sheet: string, q: string) => navigate({ sheet, q }), [navigate]);
  const rootError = tree.errors[""];

  return (
    <>
      <div className="grid items-start gap-5 lg:grid-cols-[320px_minmax(0,1fr)]">
        <aside
          className="panel grid gap-3 p-3 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain"
          aria-label="Folders and search"
          data-testid="explorer-tree-panel"
        >
          <div className="grid gap-3" data-testid="tree-toolbar">
            {config.renderSearch(reveal)}
            <TaxonomyPicker taxonomies={taxonomies} active={activeTaxonomy} onSelect={open} />
            {config.toolbarExtra}
          </div>
          <div className="flex items-center justify-between border-t border-line-200 px-1 pt-2">
            <span className="field-label">Folders</span>
            <button
              type="button"
              className="grid h-9 w-9 place-items-center rounded text-ink-500 hover:bg-purple-50 hover:text-purple-700 pointer-coarse:h-11 pointer-coarse:w-11"
              aria-label="Reload this folder"
              onClick={() => void tree.load(url.path)}
            >
              <RefreshCw className={`h-3.5 w-3.5 ${tree.loading.has(url.path) ? "animate-spin" : ""}`} aria-hidden />
            </button>
          </div>
          {rootError ? <Problem onRetry={() => void tree.load("")}>{rootError}</Problem> : null}
          <div className="max-h-[50vh] overflow-y-auto pr-1 lg:max-h-none lg:overflow-visible">
            <FolderTree store={tree} selectedPath={url.path} rootName={config.rootName} onSelect={open} />
          </div>
        </aside>

        <div className="grid min-w-0 gap-5">
          <DownloadsPanel
            path={url.path}
            folderName={folderName}
            extra={config.extra}
            downloadExtra={config.downloadExtra}
            selection={selection}
            onClearSelection={clearSelection}
            job={job}
            datasetLine={config.datasetLine}
          />
          {current?.info ? <RecordInfoCard info={current.info} /> : null}
          <ViewTabs view={url.view} onView={(view) => navigate({ view })} />
          <div id="explorer-view" role="tabpanel" aria-label={url.view === "files" ? "Files" : "Tables"} className="min-w-0">
            {url.view === "files" ? (
              <FolderContents
                path={url.path}
                tree={current}
                crumbs={crumbs}
                loading={tree.loading.has(url.path)}
                error={url.path ? (tree.errors[url.path] ?? null) : null}
                extra={config.extra}
                selection={selection}
                fileHref={config.fileHref}
                emptyBody={config.emptyBody}
                emptyAction={config.emptyAction}
                onOpen={open}
                onToggle={onToggle}
                onSelectAll={onSelectAll}
                onPreview={setPreview}
                onMore={() => void tree.more(url.path)}
                onRetry={() => void tree.load(url.path)}
              />
            ) : (
              <DataTables
                key={`${url.path || "__root"}:${config.statusKey}`}
                path={url.path}
                folderName={folderName}
                extra={config.extra}
                memoryKey={{ status: config.statusKey, viewer: config.viewer }}
                autoLoad={!wholeRepository}
                initialSheet={url.sheet}
                initialQuery={url.q}
                onState={onTables}
                notice={config.tablesNotice}
              />
            )}
          </div>
        </div>
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

      <PreviewDialog entry={preview} onClose={() => setPreview(null)} recordHref={preview?.mediaId ? config.fileHref(preview.mediaId) : null} />
    </>
  );
}
