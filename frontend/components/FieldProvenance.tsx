"use client";

import { useAdminView } from "@/components/AdminViewProvider";
import { formatDateTime } from "@/lib/format";
import type { ExtraMetadata } from "@/lib/types";

function humanizeField(field: string): string {
  return field
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (char) => char.toUpperCase())
    .replace(/\bId\b/g, "")
    .trim();
}

/**
 * Shows which user populated/changed each field of a record. Only rendered in admin view, so
 * researchers never see it and admins can opt in/out. Reads extraMetadata.fieldProvenance written
 * by the backend on every create/update.
 */
export function FieldProvenance({
  extraMetadata,
  title = "Field contributions"
}: {
  extraMetadata?: ExtraMetadata | null;
  title?: string;
}) {
  const { adminMode } = useAdminView();
  const provenance = extraMetadata?.fieldProvenance;
  if (!adminMode || !provenance || Object.keys(provenance).length === 0) return null;

  const entries = Object.entries(provenance).sort(([a], [b]) => a.localeCompare(b));

  return (
    <section className="panel p-4">
      <h3 className="font-serif text-lg text-ink">{title}</h3>
      <p className="mt-1 text-sm text-ink-muted">Who populated each field. Visible to administrators in admin view.</p>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-xs uppercase text-ink-soft">
            <tr>
              <th className="py-2 pr-4">Field</th>
              <th className="py-2 pr-4">Contributor</th>
              <th className="py-2">When</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#ebe6df]">
            {entries.map(([field, entry]) => (
              <tr key={field}>
                <td className="py-2 pr-4 font-medium text-ink">{humanizeField(field)}</td>
                <td className="py-2 pr-4 text-ink-muted">{entry?.byName ?? entry?.by ?? "Unknown"}</td>
                <td className="py-2 text-ink-muted">{entry?.at ? formatDateTime(entry.at) : "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
