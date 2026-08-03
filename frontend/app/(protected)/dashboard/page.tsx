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
  canReview,
  isAdmin,
  roleLabel
} from "@/lib/permissions";

/** The five record counters plus the pending backlog — the shape both halves of "At a glance" share. */
type StatTotals = {
  totalArtisans: number;
  totalWorkshops: number;
  totalProductRecords: number;
  totalToolRecords: number;
  totalMediaFiles: number;
  pendingSubmissions: number;
};

type DashboardStats = StatTotals & {
  recentSubmissions: Array<{
    id: string;
    type: string;
    title: string;
    place?: string;
    status: string;
    createdAt: string;
    /** Whose record this is. Absent on an API that predates the repository-wide recent list. */
    createdByName?: string | null;
  }>;
  /**
   * This account's own contribution. Optional because a deployed API may not send it yet, in which
   * case the second row of tiles is simply not drawn — never rendered as a row of zeroes, which
   * would read as "you have contributed nothing".
   */
  mine?: StatTotals;
};

type Tile = {
  label: string;
  icon: LucideIcon;
  newHref: string;
  updateHref?: string;
  /**
   * The primary button's wording. Defaults to "New"; the exceptions are copied verbatim from
   * Android's `EntryMode.createButtonLabel()` so the same tile says the same word in both apps.
   */
  newLabel?: string;
  /**
   * Whether this tile is offered at all. Every tile leads with a "New …" action, so the predicate
   * is the CREATE entitlement for that record type — the same one DynamicIslandNav's NAV_ITEMS use,
   * so the dashboard and the menu can never disagree about what a user may do.
   */
  visible?: boolean;
};

/**
 * Where a "recent submission" row goes when it is clicked.
 *
 * Two shapes, because the app has two ways of editing a record and neither is going away:
 *
 * * artisans, products and tools own a real `/[id]/edit` ROUTE, so the id is a path segment;
 * * workshops, crafts and processes are edited INLINE on their list page, so the id travels as
 *   `?edit=` and `useEditDeepLink` on that page loads it into the form.
 *
 * The three inline types used to return the bare list route and drop the id — clicking a named row
 * in Recent submissions opened the blank CREATE form for its type, which is the same screen the
 * "New" tile opens. (`/processes?edit=` was the exception: it carried the id but nothing on the
 * receiving page read it, so it behaved identically until that page grew the hook.) Interviews are
 * still list-level: an interview is identified by its artisan SET, not by a row id, so
 * `/questionnaire` has no single-record form to deep-link into.
 */
function recordHref(type: string, id: string): string | null {
  switch ((type || "").toLowerCase()) {
    case "artisan":
      return `/artisans/${id}/edit`;
    case "product":
      return `/products/${id}/edit`;
    case "tool":
      return `/tools/${id}/edit`;
    case "process":
      return `/processes?edit=${id}`;
    case "workshop":
      return `/workshops?edit=${id}`;
    case "craft":
      return `/crafts?edit=${id}`;
    case "questionnaire":
    case "interview":
      return "/questionnaire";
    default:
      return null;
  }
}

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

  // The four core record types share one entitlement (require_record_creator): Researcher and
  // above. A field contributor or volunteer answers existing interviews and adds media instead, so
  // offering them a "New artisan" button would only produce a 403 — the tile is not shown. The
  // Questionnaire and Media tiles below stay for everyone; those are how the lower tiers contribute.
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
    { label: "Questionnaire", icon: ClipboardList, newHref: "/questionnaire?new=1", updateHref: "/questionnaire", newLabel: "New interview" },
    { label: "Miscellaneous Media", icon: Images, newHref: "/media", newLabel: "Upload" },
    // Reading is never gated: without dataset access the tile leads to Browse records instead.
    { label: "View Data", icon: Eye, newHref: canDownloadDataset(user) ? "/data" : "/search", newLabel: "Open" },
    // The two web-only reading surfaces, which had no entry point anywhere and were reachable only
    // by typing the URL. They sit here, after View Data, because all three answer "show me what is
    // already in the repository" — and a feature a researcher cannot find is a feature that was not
    // built. Both are open to any signed-in user; the map filters its pins per viewer on the server.
    { label: "Map", icon: MapPinned, newHref: "/map", newLabel: "Open" },
    {
      label: "Consolidated questionnaire",
      icon: Layers,
      newHref: "/questionnaire/consolidated",
      newLabel: "Open"
    },
    // Tasks and Workshop access are dashboard tiles on Android and were menu-only here, which is
    // the difference between a new researcher finding "how do I get into this workshop" and not.
    { label: "Tasks", icon: ListTodo, newHref: "/tasks", newLabel: "Open" },
    { label: "Sharing", icon: Share2, newHref: "/sharing" },
    // Ungated, and now honestly so: the destination forks on the role, opening the admin console
    // only for an admin in admin view and the account's own request page for everyone else. It used
    // to point straight at the console, so this tile — shown to all — was a padlock for most of them.
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

  /**
   * Every total is a question ("which 74 tools?"), and a number you cannot click is a dead end.
   * Each card opens the search view already filtered to that record type — `?type=` is read by
   * app/(protected)/search — so the count and the list behind it can never disagree.
   *
   * Pending review is the exception: it opens the review queue, and only for someone who may
   * actually act on it. A researcher who cannot review still SEES the backlog (it tells them
   * their own submissions are waiting) but the card does not pretend to be a door they can open.
   *
   * THESE ARE THE REPOSITORY'S TOTALS, and saying so out loud is the point. They used to be the
   * signed-in researcher's own upload counts wearing these labels, because the API filtered every
   * total by ownership — so two people standing side by side read different numbers off the word
   * "Artisans", and somebody who had not uploaded yet read six zeroes and concluded the repository
   * was empty. Reading is open now; the caller's own contribution is the second row below, asked for
   * explicitly and labelled as theirs.
   */
  const statCards = stats
    ? [
        { label: "Artisans", value: stats.totalArtisans, icon: Users, href: "/search?type=artisans" },
        { label: "Workshops", value: stats.totalWorkshops, icon: MapPinned, href: "/search?type=workshops" },
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

  /**
   * The same six counters for THIS account's own work, and every one of them opens My Activity —
   * which now asks the API for `createdBy=<me>` rather than sifting page one of the repository, so
   * the number and the list behind it agree again.
   *
   * Drawn only when the API actually sent a `mine` block. A row of zeroes synthesised from a missing
   * field would be a lie in exactly the direction this whole change exists to fix.
   */
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

      {/* A short grid is otherwise unexplained: say WHY the record tiles are missing and where the
          tier comes from, rather than leaving a volunteer to assume the app is broken. */}
      {!creator ? (
        <p className="mt-4 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-500">
          You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>. That covers
          answering existing interviews, uploading media and commenting — find an entry through{" "}
          <Link href="/search" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            Browse records
          </Link>{" "}
          and add to it. Opening a new artisan, product, process or tool needs Researcher access — ask an admin to
          raise your tier.{" "}
          <Link href="/guide" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            Open the walkthrough
          </Link>
          .
        </p>
      ) : null}

      <section className="mt-8">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h2 className="font-display text-lg font-bold text-ink-900">At a glance</h2>
          <p className="text-xs text-ink-500">Everything in the repository, not only your own entries.</p>
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
                  className="panel block p-4 transition-shadow hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700"
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

      {/* The caller's own contribution, in the SAME six counters and the same order, so the two rows
          read as one comparison rather than as two unrelated grids. Denser than the row above on
          purpose: it is the secondary question, and every tile leads to the same place. */}
      {mineCards ? (
        <section className="mt-6">
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
            <h2 className="font-display text-lg font-bold text-ink-900">Your contribution</h2>
            <Link
              href="/activity"
              className="text-xs font-semibold text-purple-700 underline-offset-2 hover:underline"
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
                className="panel block p-3 transition-shadow hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700"
              >
                <div className="flex items-center gap-2">
                  <card.icon className="h-4 w-4 shrink-0 text-purple-700" aria-hidden />
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
            The newest entries across the repository, whoever filed them.
          </p>
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
                  {/* The list is the whole repository now, so an unattributed row is a row nobody
                      can follow up on. */}
                  <th className="px-4 py-3">Recorded by</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {stats.recentSubmissions.map((item) => (
                  <tr key={`${item.type}-${item.id}`} className="hover:bg-surface-50">
                    <td className="px-4 py-3 font-medium text-ink-900">
                      {/* The row a researcher recognises is the one they want to correct, so the
                          title is the link — straight into the edit view for that record type. */}
                      {recordHref(item.type, item.id) ? (
                        <Link
                          href={recordHref(item.type, item.id)!}
                          className="text-purple-700 underline-offset-2 hover:underline"
                        >
                          {item.title}
                        </Link>
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
