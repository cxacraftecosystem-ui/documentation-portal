"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  AudioLines,
  ClipboardCheck,
  KeyRound,
  Lock,
  MessageSquare,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  UserCog,
  UsersRound,
  Wrench,
  type LucideIcon
} from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
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
};

/** /media/orphans returns the whole recovery list, so the table pages client-side. */
const PAGE_SIZE = 20;

export default function AdminHubPage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);

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
      label: "Workshop assignments",
      description: "Open a workshop record to assign researchers and approve out-of-window work.",
      href: "/workshops",
      icon: UsersRound
    },
    {
      label: "Workshop access",
      description: "The request queue across every workshop, plus each roster and its access levels.",
      href: "/settings/workshop-access",
      icon: ShieldCheck
    },
    {
      label: "Manage users",
      description: "Roles, promotions, capability grants, and account admin.",
      href: "/users",
      icon: UserCog
    },
    {
      label: "API keys",
      description: "Rotate, test and reveal the provider keys — live immediately, no restart.",
      href: "/settings/api-keys",
      icon: KeyRound,
      visible: isMasterAdmin(user)
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
              <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
                <tile.icon className="h-5 w-5 text-white" aria-hidden />
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
