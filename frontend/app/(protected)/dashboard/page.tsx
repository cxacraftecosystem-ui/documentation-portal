"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  Boxes,
  Brush,
  Camera,
  ClipboardCheck,
  ClipboardList,
  Eye,
  GitBranch,
  Hammer,
  Images,
  Layers,
  ListTodo,
  LockOpen,
  MapPinned,
  Package,
  Settings,
  Share2,
  User as UserIcon,
  UserCog,
  Users,
  UsersRound,
  Wrench,
  type LucideIcon
} from "lucide-react";

import { DashboardCard } from "@/components/DashboardCard";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { useCraftCatalogue } from "@/lib/craftCatalogue";
import { formatDateTime } from "@/lib/format";
import { recordViewHref } from "@/lib/recordView";
import {
  canCreateRecords,
  canDownloadDataset,
  canManageCrafts,
  canManageUsers,
  canManageWorkshops,
  canReview,
  isAdmin,
  roleLabel
} from "@/lib/permissions";
import { SentBackCard } from "@/components/review/SentBackCard";

type StatTotals = {
  totalArtisans: number;
  totalWorkshops: number;
  totalProductRecords: number;
  totalToolRecords: number;
  totalMediaFiles: number;
  pendingSubmissions: number;
  sentBack?: number;
  rejected?: number;
};

type DashboardStats = StatTotals & {
  recentSubmissions: Array<{
    id: string;
    type: string;
    title: string;
    place?: string;
    status: string;
    createdAt: string;
    createdByName?: string | null;
  }>;
  mine?: StatTotals;
};

type Tile = {
  label: string;
  icon: LucideIcon;
  newHref: string;
  updateHref?: string;
  newLabel?: string;
  visible?: boolean;
};

function recordHref(type: string, id: string): string | null {
  return recordViewHref(type, id);
}

export default function DashboardPage() {
  return <DashboardView />;
}

function DashboardView() {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const craftCatalogue = useCraftCatalogue();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<DashboardStats>("/dashboard/stats")
      .then(setStats)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load dashboard"));
  }, []);

  const creator = canCreateRecords(user);
  const adminSurface = (allowed: boolean) => allowed && (!isAdmin(user) || adminMode);

  const tiles: Tile[] = [
    { label: "Artisan", icon: UserIcon, newHref: "/artisans/new", updateHref: "/artisans", visible: creator },
    { label: "Product", icon: Package, newHref: "/products/new", updateHref: "/products", visible: creator },
    { label: "Process", icon: GitBranch, newHref: "/processes?new=1", updateHref: "/processes", visible: creator },
    { label: "Tool", icon: Wrench, newHref: "/tools/new", updateHref: "/tools", visible: creator },
    { label: "Questionnaire", icon: ClipboardList, newHref: "/questionnaire?new=1", updateHref: "/questionnaire", newLabel: "New" },
    { label: "Miscellaneous Media", icon: Images, newHref: "/media", newLabel: "Upload" },
    { label: "Data", icon: Eye, newHref: canDownloadDataset(user) ? "/data" : "/search", newLabel: "Open" },
    { label: "Map", icon: MapPinned, newHref: "/map", newLabel: "Open" },
    {
      label: "Consolidated questionnaire",
      icon: Layers,
      newHref: "/questionnaire/consolidated",
      newLabel: "Open"
    },
    { label: "Tasks", icon: ListTodo, newHref: "/tasks", newLabel: "Open" },
    { label: "Sharing", icon: Share2, newHref: "/sharing" },
    { label: "Workshop access", icon: LockOpen, newHref: "/workshop-access", newLabel: "Open" },
    { label: "Users", icon: UserCog, newHref: "/users", visible: adminSurface(canManageUsers(user)), newLabel: "Manage" },
    { label: "Settings", icon: Settings, newHref: "/admin", visible: adminSurface(isAdmin(user)), newLabel: "Open" },
    { label: "Craft", icon: Brush, newHref: "/crafts?new=1", updateHref: "/crafts", visible: canManageCrafts(user) },
    {
      label: "Workshop",
      icon: UsersRound,
      newHref: "/workshops?new=1",
      updateHref: "/workshops",
      visible: canManageWorkshops(user)
    }
  ];

  const statCards = stats
    ? [
        { label: "Artisans", value: stats.totalArtisans, icon: Users, href: "/search?type=artisans" },
        { label: "Workshops", value: stats.totalWorkshops, icon: MapPinned, href: "/search?type=workshops" },
        ...(craftCatalogue.catalogue
          ? [{ label: "Crafts", value: craftCatalogue.catalogue.crafts.length, icon: Brush, href: "/crafts" }]
          : []),
        { label: "Products", value: stats.totalProductRecords, icon: Boxes, href: "/search?type=products" },
        { label: "Tools", value: stats.totalToolRecords, icon: Hammer, href: "/search?type=tools" },
        { label: "Media files", value: stats.totalMediaFiles, icon: Camera, href: "/search?type=media" },
        {
          label: "Pending review",
          value: stats.pendingSubmissions,
          icon: ClipboardCheck,
          href: canReview(user) ? "/review" : null
        }
      ]
    : [];

  const mineCards =
    stats?.mine
      ? [
          { label: "Artisans", value: stats.mine.totalArtisans, icon: Users },
          { label: "Workshops", value: stats.mine.totalWorkshops, icon: MapPinned },
          { label: "Products", value: stats.mine.totalProductRecords, icon: Boxes },
          { label: "Tools", value: stats.mine.totalToolRecords, icon: Hammer },
          { label: "Media files", value: stats.mine.totalMediaFiles, icon: Camera },
          { label: "Awaiting review", value: stats.mine.pendingSubmissions, icon: ClipboardCheck }
        ]
      : null;

  return (
    <>
      <PageHeader title="What would you like to do?" back={false} />
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
      ) : null}
      {stats?.mine && (stats.mine.sentBack ?? 0) + (stats.mine.rejected ?? 0) > 0 ? (
        <SentBackCard sentBack={stats.mine.sentBack ?? 0} rejected={stats.mine.rejected ?? 0} />
      ) : null}
      <div className="relative">
        <div aria-hidden className="pointer-events-none absolute -inset-x-6 -inset-y-8 overflow-hidden">
          <div className="absolute -left-12 -top-4 h-72 w-72 rounded-full bg-purple-300/25 blur-3xl" />
          <div className="absolute -right-8 bottom-0 h-80 w-80 rounded-full bg-purple-400/20 blur-3xl" />
        </div>
        <div className="relative grid grid-cols-2 gap-3 md:grid-cols-3">
          {tiles
            .filter((tile) => tile.visible !== false)
            .map((tile) => (
              <DashboardCard
                key={tile.label}
                label={tile.label}
                icon={tile.icon}
                newHref={tile.newHref}
                updateHref={tile.updateHref}
                newLabel={tile.newLabel}
              />
            ))}
        </div>
      </div>

      {!creator ? (
        <p className="mt-4 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-500">
          As <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span> you can add to existing records
          from{" "}
          <Link href="/search" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            Search
          </Link>
          . New records need Researcher access.{" "}
          <Link href="/guide" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            Open the walkthrough
          </Link>
          .
        </p>
      ) : null}

      <section className="mt-8">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h2 className="font-display text-lg font-bold text-ink-900">At a glance</h2>
          <p className="text-xs text-ink-500">Every record, not only your own.</p>
        </div>
        {!stats && !error ? (
          <div className="panel p-4 text-sm text-ink-500">Loading...</div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {statCards.map((card) => {
              const body = (
                <>
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-medium text-ink-500">{card.label}</div>
                    <div className="grid h-9 w-9 place-items-center rounded-md bg-purple-50 text-purple-700">
                      <card.icon className="h-[18px] w-[18px]" aria-hidden />
                    </div>
                  </div>
                  <div className="mt-3 font-display text-3xl font-bold text-ink-900">{card.value}</div>
                </>
              );
              return card.href ? (
                <Link
                  key={card.label}
                  href={card.href}
                  aria-label={`${card.label}: ${card.value}. Open the full list.`}
                  className="panel block p-4 transition-shadow hover:shadow-md focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700"
                >
                  {body}
                </Link>
              ) : (
                <div className="panel p-4" key={card.label}>
                  {body}
                </div>
              );
            })}
          </div>
        )}
      </section>

      {mineCards ? (
        <section className="mt-6">
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
            <h2 className="font-display text-lg font-bold text-ink-900">Your contribution</h2>
            <Link
              href="/activity"
              className="text-xs font-semibold text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline"
            >
              Open My Activity
            </Link>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            {mineCards.map((card) => (
              <Link
                key={card.label}
                href="/activity"
                aria-label={`Your ${card.label.toLowerCase()}: ${card.value}. Open My Activity.`}
                className="panel block p-3 transition-shadow hover:shadow-md focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700"
              >
                <div className="flex items-center gap-2">
                  <card.icon className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
                  <span className="min-w-0 truncate text-xs font-medium text-ink-500">{card.label}</span>
                </div>
                <div className="mt-2 font-display text-2xl font-bold text-ink-900">{card.value}</div>
              </Link>
            ))}
          </div>
        </section>
      ) : null}

      <section className="mt-6 panel overflow-hidden">
        <div className="border-b border-line-200 px-4 py-3">
          <h2 className="font-display font-bold text-ink-900">Recent submissions</h2>
          <p className="mt-0.5 text-xs text-ink-500">
            The newest records, whoever added them.
          </p>
        </div>
        {!stats ? (
          <div className="p-4 text-sm text-ink-500">Loading...</div>
        ) : stats.recentSubmissions.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No submissions yet" body="New records will appear here." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th className="px-4 py-3">Title</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Recorded by</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {stats.recentSubmissions.map((item) => (
                  <RecentRow key={`${item.type}-${item.id}`} href={recordHref(item.type, item.id)}>
                    <td className="px-4 py-3 font-medium text-ink-900">
                      {recordHref(item.type, item.id) ? (
                        <RecordTitleLink href={recordHref(item.type, item.id)!}>{item.title}</RecordTitleLink>
                      ) : (
                        item.title
                      )}
                    </td>
                    <td className="px-4 py-3 capitalize text-ink-700">{item.type}</td>
                    <td className="px-4 py-3 text-ink-700">{item.place ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{item.createdByName || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                  </RecentRow>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function RecentRow({ href, children }: { href: string | null; children: React.ReactNode }) {
  if (!href) return <tr>{children}</tr>;
  return <OpenableRow href={href}>{children}</OpenableRow>;
}
