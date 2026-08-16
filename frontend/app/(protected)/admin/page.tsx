"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  AudioLines,
  ClipboardCheck,
  ClipboardList,
  KeyRound,
  Lock,
  MessageSquare,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  UserCheck,
  UserCog,
  Wrench,
  type LucideIcon
} from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { bytes, formatDateTime } from "@/lib/format";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import type { MediaFile } from "@/lib/types";

type Tile = {
  label: string;
  description: string;
  href: string;
  icon: LucideIcon;
  visible?: boolean;
  /**
   * A count rendered on the tile — how many things behind it are waiting for this admin.
   *
   * `null` means "not known yet" and renders nothing; `0` means the server said there is nothing
   * waiting and also renders nothing, because a "0" badge is visual noise on nine tiles. Only a
   * positive number draws. The distinction between the two still matters upstream — see
   * `usePendingAccessCount`.
   */
  badge?: number | null;
};

/** /media/orphans returns the whole recovery list, so the table pages client-side. */
const PAGE_SIZE = 20;

/**
 * /admin — the hub, ADMIN and MASTER ADMIN.
 *
 * Two gates sit above this page and neither lives here. `require_admin` is mirrored by ROUTE_GUARDS
 * and by the `permitted` check below, and admin view is mirrored by ADMIN_CHROME_ROUTES: the whole
 * hub is admin chrome, so an admin browsing with admin view off gets AppShell's "hidden while admin
 * view is off" panel and this component never mounts — no /media/orphans call, no flash of tiles.
 * Both gates keep their role check, so the toggle can only ever subtract.
 */
export default function AdminHubPage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);
  /**
   * The access roster's pending count, for the tile below.
   *
   * Called unconditionally, above the early returns, because a hook cannot be called conditionally
   * — it gates itself on the same admin predicate internally and makes no request for anybody else.
   */
  const { pending: pendingAccess } = usePendingAccessCount();

  const [orphans, setOrphans] = useState<MediaFile[] | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading || !permitted) return;
    apiFetch<MediaFile[]>("/media/orphans")
      .then(setOrphans)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load recovered recordings"));
  }, [authLoading, permitted]);

  const header = (
    <PageHeader
      title="Settings"
      description="The admin hub — reviews, recovered recordings, feedback, assignments, and user management in one place."
      icon={<SlidersHorizontal className="h-5 w-5" aria-hidden />}
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
          <h2 className="font-display text-base font-semibold text-ink-900">Admin access required</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">
            The settings hub is available to admins and the master admin only.
          </p>
        </section>
      </>
    );
  }

  const tiles: Tile[] = [
    {
      label: "Reviews & approvals",
      description: "Approve, reject, or send submissions back for revision.",
      href: "/review",
      icon: ClipboardCheck
    },
    {
      label: "Recovered recordings",
      description: "Media whose parent record was deleted — relink it below.",
      href: "#recovered-recordings",
      icon: AudioLines
    },
    {
      label: "User feedback",
      description: "Read the feedback users sent from the apps.",
      href: "/feedback",
      icon: MessageSquare
    },
    {
      label: "Assign tools to artisans",
      description: "Link documented tools to the artisans who use them.",
      href: "/tools?assign=1",
      icon: Wrench
    },
    {
      // Replaces the old "Workshop assignments" tile, which pointed at /workshops and only told an
      // admin to go and open a record. Everything it hinted at — the request queue, each roster and
      // its access levels — is done properly on /workshop-access/manage, the tile below. Two doors
      // onto one job, one of which was a signpost rather than a page, is worse than one door.
      label: "Task assignment",
      description: "Hand documentation work to the people below you, then hold it to account.",
      href: "/settings/tasks",
      icon: ClipboardList
    },
    {
      label: "Workshop access",
      description: "The request queue across every workshop, plus each roster and its access levels.",
      // Straight to the console: this hub is admin-only, so the fork at /workshop-access would ask
      // an admin a question it already knows the answer to.
      href: "/workshop-access/manage",
      icon: ShieldCheck
    },
    {
      // The tile the sign-in gate hangs off. It sits NEXT TO "Manage users" on purpose: the two
      // answer the same question from opposite ends — /users is who has an account, the roster is
      // who is allowed to have one — and an admin chasing "why can this person not log in?" reaches
      // for the users screen first and finds nothing there, because the refusal is not on the
      // account, it is on the address.
      //
      // The badge is the whole notification. There is no email and no push in this codebase, so a
      // count on a surface admins already open is how "the admins should be notified to approve or
      // reject the user" is delivered.
      label: "Access roster",
      description: "Who may sign in at all, plus the queue of people waiting for a decision.",
      href: "/admin/access-roster",
      icon: UserCheck,
      badge: pendingAccess
    },
    {
      label: "Manage users",
      description: "Roles, promotions, capability grants, and account admin.",
      href: "/users",
      icon: UserCog
    },
    {
      label: "API keys",
      // Admin-visible because the transcription provider ranking lives behind this tile; the key
      // list itself is still master-admin-only once the page opens.
      description: "Rank the transcription providers, and rotate or test the keys they run on.",
      href: "/settings/api-keys",
      icon: KeyRound,
      visible: isAdmin(user)
    },
    {
      label: "App settings",
      description: "Global app configuration and OTA updates.",
      href: "/settings",
      icon: Settings,
      visible: isMasterAdmin(user)
    }
  ];

  const orphanPages = Math.max(1, Math.ceil((orphans?.length ?? 0) / PAGE_SIZE));
  const orphanPage = Math.min(page, orphanPages);
  const visibleOrphans = (orphans ?? []).slice((orphanPage - 1) * PAGE_SIZE, orphanPage * PAGE_SIZE);

  return (
    <>
      {header}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        {tiles
          .filter((tile) => tile.visible !== false)
          .map((tile) => (
            <Link
              key={tile.label}
              href={tile.href}
              className="group flex flex-col gap-2 rounded-lg border border-line-200 bg-card p-4 shadow-sm transition hover:border-purple-300 hover:shadow-md"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
                  <tile.icon className="h-5 w-5 text-white" aria-hidden />
                </div>
                {/* Only a POSITIVE count draws. Null is "not known yet" and zero is "nothing is
                    waiting" — both render nothing, because a row of "0" badges is noise and a "0"
                    shown while the request is still in flight would say the queue is empty at the
                    exact moment it might not be. The number is also worded for a screen reader,
                    since a bare "3" beside a tile title announces nothing. */}
                {typeof tile.badge === "number" && tile.badge > 0 ? (
                  <span className="inline-flex items-center rounded-full border border-amber-500/30 bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
                    <span aria-hidden>{tile.badge}</span>
                    <span className="sr-only">
                      {tile.badge} waiting for a decision
                    </span>
                  </span>
                ) : null}
              </div>
              <div className="font-display text-base font-bold leading-snug text-ink-900">{tile.label}</div>
              <p className="text-xs leading-5 text-ink-500">{tile.description}</p>
            </Link>
          ))}
      </div>

      <section id="recovered-recordings" className="panel mt-6 overflow-hidden">
        <div className="border-b border-line-200 px-4 py-3">
          <h2 className="font-display font-bold text-ink-900">Recovered recordings</h2>
          <p className="text-sm text-ink-500">
            Media still tagged to a deleted record. The files are intact in object storage — relink them to a live record from
            the Media page.
          </p>
        </div>
        {!orphans ? (
          <div className="p-4 text-sm text-ink-500">{error ? "Could not load the list." : "Loading…"}</div>
        ) : orphans.length === 0 ? (
          <div className="p-4">
            <EmptyState title="Nothing to recover" body="Every media file is linked to a live record." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>File</ResizableTh>
                  <ResizableTh>Type</ResizableTh>
                  <ResizableTh>Size</ResizableTh>
                  <ResizableTh>Was linked to</ResizableTh>
                  <ResizableTh>Uploaded</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {visibleOrphans.map((item) => (
                  <tr key={item.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{item.originalFilename}</div>
                      {item.caption ? <div className="max-w-xs truncate text-xs text-ink-500">{item.caption}</div> : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{item.mediaType}</td>
                    <td className="px-4 py-3 text-ink-700">{bytes(item.sizeBytes)}</td>
                    <td className="px-4 py-3 capitalize text-ink-700">{item.linkedRecordType ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        {item.url ? (
                          <a href={item.url} target="_blank" rel="noreferrer" className={rowAction("neutral")}>
                            Open
                          </a>
                        ) : null}
                        <Link href="/media" className={rowAction("edit")}>
                          Relink in Media
                        </Link>
                      </RowActions>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {orphans && orphans.length > 0 ? (
          <Pagination page={orphanPage} pages={orphanPages} total={orphans.length} onPage={setPage} />
        ) : null}
      </section>
    </>
  );
}
