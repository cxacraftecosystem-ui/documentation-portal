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
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  canCreateRecords,
  canDownloadDataset,
  canManageCrafts,
  canManageUsers,
  canManageWorkshops,
  isAdmin,
  roleLabel
} from "@/lib/permissions";

type DashboardStats = {
  totalArtisans: number;
  totalWorkshops: number;
  totalProductRecords: number;
  totalToolRecords: number;
  totalMediaFiles: number;
  pendingSubmissions: number;
  recentSubmissions: Array<{ id: string; type: string; title: string; place?: string; status: string; createdAt: string }>;
};

type Tile = {
  label: string;
  icon: LucideIcon;
  newHref: string;
  updateHref?: string;
  /**
   * Whether this tile is offered at all. Every tile leads with a "New …" action, so the predicate
   * is the CREATE entitlement for that record type — the same one DynamicIslandNav's NAV_ITEMS use,
   * so the dashboard and the menu can never disagree about what a user may do.
   */
  visible?: boolean;
};

export default function DashboardPage() {
  // ToastProvider lives in app/layout.tsx — see the note in `ui/Toast`.
  return <DashboardView />;
}

function DashboardView() {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<DashboardStats>("/dashboard/stats")
      .then(setStats)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load dashboard"));
  }, []);

  // The four core record types share one entitlement (require_record_creator): Field Contributor and
  // above. A Crowdsource Volunteer contributes interviews, media and comments instead, so offering
  // them a "New artisan" button would only produce a 403 — the tile is not shown.
  const creator = canCreateRecords(user);
  /**
   * Admin-tier chrome, matching DynamicIslandNav's `adminSurface`: capability holders below admin
   * (professors, grantees) keep the tile permanently, while an admin — who owns the toggle — sees it
   * only while admin view is ON. The entitlement is checked first, so the toggle can never widen it.
   */
  const adminSurface = (allowed: boolean) => allowed && (!isAdmin(user) || adminMode);

  // Android EntryMode parity: same tiles, same order, same labels.
  const tiles: Tile[] = [
    { label: "Artisan", icon: UserIcon, newHref: "/artisans/new", updateHref: "/artisans", visible: creator },
    { label: "Product", icon: Package, newHref: "/products/new", updateHref: "/products", visible: creator },
    { label: "Process", icon: GitBranch, newHref: "/processes?new=1", updateHref: "/processes", visible: creator },
    { label: "Tool", icon: Wrench, newHref: "/tools/new", updateHref: "/tools", visible: creator },
    // Answering an interview and uploading media are open to every signed-in user — they are how a
    // volunteer contributes.
    { label: "Questionnaire", icon: ClipboardList, newHref: "/questionnaire?new=1", updateHref: "/questionnaire" },
    { label: "Miscellaneous Media", icon: Images, newHref: "/media" },
    // Reading is never gated: without dataset access the tile leads to Browse records instead.
    { label: "View Data", icon: Eye, newHref: canDownloadDataset(user) ? "/data" : "/search" },
    { label: "Sharing", icon: Share2, newHref: "/sharing" },
    { label: "Users", icon: UserCog, newHref: "/users", visible: adminSurface(canManageUsers(user)) },
    { label: "Settings", icon: Settings, newHref: "/admin", visible: adminSurface(isAdmin(user)) },
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
        { label: "Artisans", value: stats.totalArtisans, icon: Users },
        { label: "Workshops", value: stats.totalWorkshops, icon: MapPinned },
        { label: "Products", value: stats.totalProductRecords, icon: Boxes },
        { label: "Tools", value: stats.totalToolRecords, icon: Hammer },
        { label: "Media files", value: stats.totalMediaFiles, icon: Camera },
        { label: "Pending review", value: stats.pendingSubmissions, icon: ClipboardCheck }
      ]
    : [];

  return (
    <>
      {/* The dashboard is the navigation root — it never shows a back button. */}
      <PageHeader title="What would you like to do?" back={false} />
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}
      {/* The tiles are glass, and glass on a flat canvas refracts nothing you can see — these
          two soft orbs are what their rims bend. Purple only: `grad-mesh` carries a faint amber
          orb, and gold belongs to the marketing surfaces, never to a data screen. */}
      <div className="relative">
        <div aria-hidden className="pointer-events-none absolute -inset-x-6 -inset-y-8 overflow-hidden">
          <div className="absolute -left-12 -top-4 h-72 w-72 rounded-full bg-purple-300/25 blur-3xl" />
          <div className="absolute -right-8 bottom-0 h-80 w-80 rounded-full bg-purple-400/20 blur-3xl" />
        </div>
        <div className="relative grid grid-cols-2 gap-3 md:grid-cols-3">
          {tiles
            .filter((tile) => tile.visible !== false)
            .map((tile) => (
              <DashboardCard key={tile.label} label={tile.label} icon={tile.icon} newHref={tile.newHref} updateHref={tile.updateHref} />
            ))}
        </div>
      </div>

      {/* A short grid is otherwise unexplained: say WHY the record tiles are missing and where the
          tier comes from, rather than leaving a volunteer to assume the app is broken. */}
      {!creator ? (
        <p className="mt-4 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-500">
          You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>. That covers
          interviews, media uploads and comments. Creating artisans, products, processes and tools needs Field
          Contributor access — ask an admin to raise your tier.{" "}
          <Link href="/guide" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            Open the walkthrough
          </Link>
          .
        </p>
      ) : null}

      <section className="mt-8">
        <h2 className="mb-3 font-display text-lg font-bold text-ink-900">At a glance</h2>
        {!stats && !error ? (
          <div className="panel p-4 text-sm text-ink-500">Loading...</div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {statCards.map((card) => (
              <div className="panel p-4" key={card.label}>
                <div className="flex items-center justify-between gap-3">
                  <div className="text-sm font-medium text-ink-500">{card.label}</div>
                  <div className="grid h-9 w-9 place-items-center rounded-md bg-purple-50 text-purple-700">
                    <card.icon className="h-[18px] w-[18px]" aria-hidden />
                  </div>
                </div>
                <div className="mt-3 font-display text-3xl font-bold text-ink-900">{card.value}</div>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="mt-6 panel overflow-hidden">
        <div className="border-b border-line-200 px-4 py-3">
          <h2 className="font-display font-bold text-ink-900">Recent submissions</h2>
        </div>
        {!stats ? (
          <div className="p-4 text-sm text-ink-500">Loading...</div>
        ) : stats.recentSubmissions.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No submissions yet" body="New field documentation will appear here after records are created." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th className="px-4 py-3">Title</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {stats.recentSubmissions.map((item) => (
                  <tr key={`${item.type}-${item.id}`}>
                    <td className="px-4 py-3 font-medium text-ink-900">{item.title}</td>
                    <td className="px-4 py-3 capitalize text-ink-700">{item.type}</td>
                    <td className="px-4 py-3 text-ink-700">{item.place ?? "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
