"use client";

import { Lock } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";

import { useAuth } from "@/components/AuthProvider";
import { DataSearchPanel } from "@/components/data/DataSearchPanel";
import { DataExplorer, ExplorerHeader, type ExplorerConfig } from "@/components/dataExplorer/DataExplorer";
import type { BrowseType } from "@/components/dataExplorer/BrowseByType";
import { TranscriptDataPanel } from "@/components/transcript/TranscriptDataPanel";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { useWorkshopScope, WorkshopScopeSelect } from "@/components/WorkshopScopeSelect";
import { listResource } from "@/lib/api";
import type { BrowsePage } from "@/lib/dataExplorer/contract";
import { canCreateRecords, canDownloadDataset } from "@/lib/permissions";
import { canUpdateRecord, recordUpdateHref, recordViewHref } from "@/lib/recordView";
import { DATA_STATUS_OPTIONS, DEFAULT_DATA_STATUS, dataStatusLabel, statusQuery } from "@/lib/review";
import { toolRowLabel } from "@/lib/toolArtisans";
import type { Artisan, Craft, MediaFile, PageResult, ProductDocumentation, QuestionnaireInterview, ToolDocumentation, User, Workshop } from "@/lib/types";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const PAGE_SIZE = 100;

type WithCreator = { createdBy?: { name?: string | null } | null };

type LegacyRow = { id: string; name: string; subtitle?: string | null; createdAt?: string | null };

function legacyPage<T>(result: PageResult<T>, map: (row: T) => LegacyRow): BrowsePage {
  const items = result.items.map(map).sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  return { items, total: result.total, page: result.page, pageSize: PAGE_SIZE, pages: result.pages, truncated: result.total > result.page * PAGE_SIZE };
}

function creator(row: WithCreator): string | null {
  return row.createdBy?.name ?? null;
}

type BrowseDef = {
  label: string;
  scoped?: boolean;
  load: (page: number, q: string, workshopIds: string | undefined) => Promise<BrowsePage>;
};

const BROWSE_TYPES: Record<string, BrowseDef> = {
  artisan: {
    label: "Artisans",
    load: async (page, q) =>
      legacyPage(await listResource<Artisan>("/artisans", { page, pageSize: PAGE_SIZE, search: q }), (x) => ({
        id: x.id,
        name: `${x.name}${x.place ? ` · ${x.place}` : ""}`,
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  product: {
    label: "Products",
    load: async (page, q) =>
      legacyPage(await listResource<ProductDocumentation>("/products", { page, pageSize: PAGE_SIZE, search: q }), (x) => ({
        id: x.id,
        name: `${x.productName} · ${x.artisanName}`,
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  tool: {
    label: "Tools",
    load: async (page, q) =>
      legacyPage(await listResource<ToolDocumentation>("/tools", { page, pageSize: PAGE_SIZE, search: q }), (x) => ({
        id: x.id,
        name: toolRowLabel(x),
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  workshop: {
    label: "Workshops",
    load: async (page, q) =>
      legacyPage(await listResource<Workshop>("/workshops", { page, pageSize: PAGE_SIZE, search: q }), (x) => ({
        id: x.id,
        name: workshopDisplayTitle(x),
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  craft: {
    label: "Crafts",
    load: async (page, q) =>
      legacyPage(await listResource<Craft & WithCreator>("/crafts", { page, pageSize: PAGE_SIZE, search: q }), (x) => ({
        id: x.id,
        name: x.place ? `${x.name} · ${x.place}` : x.name,
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  questionnaire: {
    label: "Questionnaire",
    scoped: true,
    load: async (page, q, workshopIds) =>
      legacyPage(await listResource<QuestionnaireInterview>("/questionnaire/interviews", { page, pageSize: PAGE_SIZE, search: q, workshopIds }), (x) => ({
        id: x.id,
        name: x.title?.trim() || "Untitled interview",
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  process: {
    label: "Processes",
    load: async (page, q) =>
      legacyPage(
        await listResource<{ id: string; name: string; createdAt?: string } & WithCreator>("/processes", { page, pageSize: PAGE_SIZE, search: q }),
        (x) => ({ id: x.id, name: x.name, subtitle: creator(x), createdAt: x.createdAt })
      )
  },
  media: {
    label: "Media",
    load: async (page, q) =>
      legacyPage(await listResource<MediaFile>("/media", { page, pageSize: PAGE_SIZE, search: q }), (x) => ({
        id: x.id,
        name: [x.originalFilename?.trim() || "Media", x.mediaType].filter(Boolean).join(" · "),
        subtitle: x.uploadedBy?.name ?? null,
        createdAt: x.createdAt
      }))
  }
};

function mediaHref(id: string): string {
  return `/media/${encodeURIComponent(id)}`;
}

function browseTypes(user: User): BrowseType[] {
  return Object.entries(BROWSE_TYPES).map(([value, def]) => ({
    value,
    label: def.label,
    scoped: def.scoped,
    viewHref: value === "media" ? mediaHref : (id: string) => recordViewHref(value, id),
    updateHref: canUpdateRecord(user, value) ? (id: string) => recordUpdateHref(value, id) : null,
    legacy: def.load
  }));
}

const TITLE = "Data";
const DESCRIPTION = "Browse, check and download everything you can see.";

const WIDE = "sm:max-lg:mx-1 lg:mx-[calc((100%-min(1360px,100vw-80px))/2)]";

const header = (
  <div className="mb-4">
    <ExplorerHeader title={TITLE} description={DESCRIPTION} />
  </div>
);

function Explorer({ user }: { user: User }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const reviewStatus = params.get("status") || DEFAULT_DATA_STATUS;
  const statusParam = statusQuery(reviewStatus);
  const scope = useWorkshopScope();

  function changeReviewStatus(next: string) {
    if (next === reviewStatus) return;
    const query = new URLSearchParams(params.toString());
    if (next === DEFAULT_DATA_STATUS) query.delete("status");
    else query.set("status", next);
    const text = query.toString();
    router.replace(`${pathname}${text ? `?${text}` : ""}`, { scroll: false });
  }

  const config: ExplorerConfig = {
    title: TITLE,
    description: DESCRIPTION,
    rootName: "Dataset root",
    extra: { status: statusParam },
    downloadExtra: {},
    statusKey: reviewStatus,
    viewer: user.id,
    fileHref: mediaHref,
    recordHref: (kind, id) => recordViewHref(kind, id),
    renderSearch: (reveal, openPreview) => <DataSearchPanel onReveal={reveal} onPreview={openPreview} />,
    headerExtra: (
      <div className="flex flex-wrap items-center gap-2">
        <span className="field-label">Review status</span>
        <SegmentedChoice label="Review status" value={reviewStatus} onChange={(value) => changeReviewStatus(value || DEFAULT_DATA_STATUS)} options={DATA_STATUS_OPTIONS} />
      </div>
    ),
    emptyBody: (folder) => (reviewStatus === "ALL" ? `Files appear as soon as someone records for ${folder}.` : `Nothing ${dataStatusLabel(reviewStatus).toLowerCase()} here.`),
    emptyAction: (
      <>
        {reviewStatus !== "ALL" ? (
          <button type="button" className="field-button-secondary" onClick={() => changeReviewStatus("ALL")}>
            Show all statuses
          </button>
        ) : null}
        {canCreateRecords(user) ? (
          <Link href="/media" className="inline-flex min-h-11 items-center rounded-md bg-purple-700 px-4 text-sm font-semibold text-white hover:bg-purple-800 dark:bg-purple-300 dark:text-purple-950">
            Record now
          </Link>
        ) : null}
      </>
    ),
    browseTypes: browseTypes(user),
    browseScope: { control: <WorkshopScopeSelect scope={scope} label="Workshops in this list" />, workshopIds: scope.queryValue },
    secondary: <TranscriptDataPanel />
  };
  return <DataExplorer config={config} />;
}

export default function DataBrowserPage() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!user || !canDownloadDataset(user)) {
    return (
      <>
        {header}
        <section className="panel px-6 py-12 text-center">
          <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-purple-100 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="font-display text-base font-semibold text-ink-900">Dataset access is restricted</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">Ask an admin for dataset access.</p>
        </section>
      </>
    );
  }

  return (
    <div className={WIDE}>
      <Suspense fallback={<section className="panel p-6 text-sm text-ink-500">Loading…</section>}>
        <Explorer user={user} />
      </Suspense>
    </div>
  );
}
