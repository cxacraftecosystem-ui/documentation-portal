"use client";

import { useEffect, useState } from "react";
import { ClipboardCheck } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch, listResource } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";

type ReviewItem = {
  id: string;
  type: "artisan" | "workshop" | "product" | "tool" | "media";
  title: string;
  place?: string;
  status: string;
  createdAt: string;
};

export default function ReviewPage() {
  const [items, setItems] = useState<ReviewItem[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      const [artisans, workshops, products, tools, media] = await Promise.all([
        listResource<Artisan>("/artisans", { statusFilter: "PENDING", pageSize: 50 }),
        listResource<Workshop>("/workshops", { statusFilter: "PENDING", pageSize: 50 }),
        listResource<ProductDocumentation>("/products", { statusFilter: "PENDING", pageSize: 50 }),
        listResource<ToolDocumentation>("/tools", { statusFilter: "PENDING", pageSize: 50 }),
        listResource<MediaFile>("/media", { statusFilter: "PENDING", pageSize: 50 })
      ]);
      setItems(
        [
          ...artisans.items.map((item) => ({ id: item.id, type: "artisan" as const, title: item.name, place: item.place, status: item.status, createdAt: item.createdAt })),
          ...workshops.items.map((item) => ({ id: item.id, type: "workshop" as const, title: item.title, place: item.place, status: item.status, createdAt: item.createdAt })),
          ...products.items.map((item) => ({ id: item.id, type: "product" as const, title: item.productName, place: item.place, status: item.status, createdAt: item.createdAt })),
          ...tools.items.map((item) => ({ id: item.id, type: "tool" as const, title: item.toolkitName, place: item.place, status: item.status, createdAt: item.createdAt })),
          ...media.items.map((item) => ({ id: item.id, type: "media" as const, title: item.originalFilename, place: item.linkedRecordType ?? undefined, status: item.status, createdAt: item.createdAt }))
        ].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load review queue");
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function decide(item: ReviewItem, action: "approve" | "reject") {
    const notes = window.prompt(action === "approve" ? "Approval notes" : "Rejection notes") ?? "";
    await apiFetch(`/review/${item.type}/${item.id}/${action}`, {
      method: "POST",
      body: JSON.stringify({ notes })
    });
    load();
  }

  return (
    <>
      <PageHeader
        title="Admin Review"
        description="Approve or reject pending field submissions and store review notes."
        icon={<ClipboardCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <section className="panel overflow-hidden">
        {items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No pending submissions" body="Pending records from all modules will appear here for admin review." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Record</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Place or link</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Submitted</th>
                  <th className="px-4 py-3 text-right">Decision</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {items.map((item) => (
                  <tr key={`${item.type}-${item.id}`}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-900">{item.title}</div>
                      <div className="text-xs text-neutral-500">{item.id}</div>
                    </td>
                    <td className="px-4 py-3 capitalize text-neutral-600">{item.type}</td>
                    <td className="px-4 py-3 text-neutral-600">{item.place ?? "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDateTime(item.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <button className="mr-2 text-sm font-semibold text-field-700" onClick={() => decide(item, "approve")}>
                        Approve
                      </button>
                      <button className="text-sm font-semibold text-red-700" onClick={() => decide(item, "reject")}>
                        Reject
                      </button>
                    </td>
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
