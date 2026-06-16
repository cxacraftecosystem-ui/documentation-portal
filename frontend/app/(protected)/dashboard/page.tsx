"use client";

import { useEffect, useState } from "react";
import { Boxes, Camera, ClipboardCheck, Gauge, Hammer, MapPinned, Users } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";

type DashboardStats = {
  totalArtisans: number;
  totalWorkshops: number;
  totalProductRecords: number;
  totalToolRecords: number;
  totalMediaFiles: number;
  pendingSubmissions: number;
  recentSubmissions: Array<{ id: string; type: string; title: string; place?: string; status: string; createdAt: string }>;
};

export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<DashboardStats>("/dashboard/stats")
      .then(setStats)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load dashboard"));
  }, []);

  const cards = stats
    ? [
        { label: "Artisans", value: stats.totalArtisans, icon: Users, tone: "bg-field-200 text-field-600" },
        { label: "Workshops", value: stats.totalWorkshops, icon: MapPinned, tone: "bg-field-200 text-field-600" },
        { label: "Products", value: stats.totalProductRecords, icon: Boxes, tone: "bg-field-200 text-field-600" },
        { label: "Tools", value: stats.totalToolRecords, icon: Hammer, tone: "bg-field-200 text-field-600" },
        { label: "Media files", value: stats.totalMediaFiles, icon: Camera, tone: "bg-field-200 text-field-600" },
        { label: "Pending review", value: stats.pendingSubmissions, icon: ClipboardCheck, tone: "bg-field-900 text-field-50" }
      ]
    : [];

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="A quick view of the people, workshops, objects, tools and interviews documented by the field team."
        icon={<Gauge className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <section className="surface-dark mb-5 grid gap-6 p-5 md:grid-cols-[1.1fr_0.9fr] md:p-6">
        <div>
          <div className="mb-3 inline-flex rounded-full bg-field-900 px-3 py-1 text-xs font-medium text-field-300 ring-1 ring-field-50/10">
            Field archive
          </div>
          <h2 className="font-serif text-3xl font-normal text-field-50 md:text-4xl">Craft stories, field notes, objects and interviews in one shared place.</h2>
          <p className="mt-3 max-w-2xl text-sm leading-6 text-[#a09d96]">
            Capture what researchers see, hear and learn during fieldwork, then review each entry so the archive stays trustworthy and easy to return to.
          </p>
        </div>
        <div className="rounded-xl bg-[#252320] p-4 text-sm text-field-50">
          <div className="mb-3 flex items-center gap-2 text-xs text-[#a09d96]">
            <span className="h-2 w-2 rounded-full bg-emerald-400" />
            ready for fieldwork
          </div>
          <div className="grid gap-3 leading-6 text-[#d8d2c8]">
            <p>Start from an artisan profile, add product or tool observations, and keep interview audio with the questionnaire.</p>
            <p>Every entry can carry time, place, photographs, recordings and researcher notes.</p>
          </div>
        </div>
      </section>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {cards.map((card) => (
          <div className="panel p-4" key={card.label}>
            <div className="flex items-center justify-between gap-3">
              <div className="text-sm font-medium text-ink-muted">{card.label}</div>
              <div className={`grid h-10 w-10 place-items-center rounded-lg ${card.tone}`}>
                <card.icon className="h-5 w-5" aria-hidden />
              </div>
            </div>
            <div className="mt-3 font-serif text-4xl font-normal text-ink">{card.value}</div>
          </div>
        ))}
      </div>
      <section className="mt-6 panel overflow-hidden">
        <div className="border-b border-[#e6dfd8] px-4 py-3">
          <h2 className="font-medium text-ink">Recent submissions</h2>
        </div>
        {!stats ? (
          <div className="p-4 text-sm text-ink-muted">Loading...</div>
        ) : stats.recentSubmissions.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No submissions yet" body="New field documentation will appear here after records are created." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-left text-sm">
              <thead className="bg-field-100 text-xs uppercase text-ink-muted">
                <tr>
                  <th className="px-4 py-3">Title</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {stats.recentSubmissions.map((item) => (
                  <tr key={`${item.type}-${item.id}`}>
                    <td className="px-4 py-3 font-medium text-ink">{item.title}</td>
                    <td className="px-4 py-3 capitalize text-ink-muted">{item.type}</td>
                    <td className="px-4 py-3 text-ink-muted">{item.place ?? "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-muted">{formatDateTime(item.createdAt)}</td>
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
