"use client";

import { BadgeCheck, MapPin } from "lucide-react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { formatDateTime } from "@/lib/format";
import { roleLabel } from "@/lib/permissions";
import type { ExtraMetadata, FieldProvenanceEntry } from "@/lib/types";

/** Backend stamps may also carry the contributor's role (designation) — optional for older records. */
type ProvenanceEntry = FieldProvenanceEntry & { byRole?: string | null };

/** Location summary shown above the table (matches the API's Location payload subset). */
export type ProvenanceLocation = {
  latitude?: number | string | null;
  longitude?: number | string | null;
  placeName?: string | null;
  address?: string | null;
};

function humanizeField(field: string): string {
  return field
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (char) => char.toUpperCase())
    .replace(/\bId\b/g, "")
    .trim();
}

function coordText(value: number | string | null | undefined): string | null {
  if (value === null || value === undefined || value === "") return null;
  const num = Number(value);
  return Number.isFinite(num) ? num.toFixed(5) : null;
}

function locationLine(location: ProvenanceLocation): string | null {
  const lat = coordText(location.latitude);
  const lng = coordText(location.longitude);
  const parts = [location.placeName?.trim() || null, location.address?.trim() || null, lat && lng ? `${lat}, ${lng}` : null].filter(Boolean);
  return parts.length ? parts.join(" · ") : null;
}

function RoleChip({ role }: { role?: string | null }) {
  if (!role) return null;
  return (
    <span className="inline-flex items-center rounded-full bg-purple-50 px-2 py-0.5 text-[11px] font-medium text-purple-700">
      {roleLabel(role)}
    </span>
  );
}

function whenText(at?: string | null): string {
  return at ? `${formatDateTime(at)} IST` : "-";
}

/**
 * Provenance record view: which user populated/changed each field of a record, with the date and
 * time of each contribution. Above the table, an optional header block shows the record's location
 * and its review line (reviewer + designation + when). md+ screens get a scrollable table with
 * user-resizable columns; small screens get Android-style stacked cards. Rendered in admin view and
 * for users granted the canViewProvenance capability. Reads extraMetadata.fieldProvenance written
 * by the backend on every create/update.
 */
export function FieldProvenance({
  extraMetadata,
  title = "Field contributions",
  location,
  reviewedBy,
  reviewedAt
}: {
  extraMetadata?: ExtraMetadata | null;
  title?: string;
  location?: ProvenanceLocation | null;
  reviewedBy?: { name?: string; role?: string } | null;
  reviewedAt?: string | null;
}) {
  const { adminMode } = useAdminView();
  const { user } = useAuth();
  const provenance = extraMetadata?.fieldProvenance;
  const canView = adminMode || Boolean(user?.canViewProvenance);
  const entries = Object.entries(provenance ?? {}).sort(([a], [b]) => a.localeCompare(b)) as Array<[string, ProvenanceEntry | undefined]>;
  const recordLocation = location ? locationLine(location) : null;
  const hasReview = Boolean(reviewedBy?.name || reviewedAt);
  if (!canView || (entries.length === 0 && !recordLocation && !hasReview)) return null;

  const headerCell = "py-2 pr-4 [resize:horizontal] overflow-hidden";

  return (
    <section className="panel p-4">
      <h3 className="font-display font-bold text-lg text-ink-900">{title}</h3>
      <p className="mt-1 text-sm text-ink-500">Who populated each field, and when. Visible in admin view and to users granted provenance access.</p>

      {recordLocation || hasReview ? (
        <div className="mt-3 grid gap-1.5 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm">
          {recordLocation ? (
            <div className="flex items-start gap-2 text-ink-700">
              <MapPin className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
              <span className="min-w-0">
                <span className="font-medium text-ink-900">Record location:</span> {recordLocation}
              </span>
            </div>
          ) : null}
          {hasReview ? (
            <div className="flex flex-wrap items-center gap-2 text-ink-700">
              <BadgeCheck className="h-4 w-4 shrink-0 text-purple-700" aria-hidden />
              <span>
                <span className="font-medium text-ink-900">Reviewed by</span> {reviewedBy?.name ?? "Unknown"}
              </span>
              <RoleChip role={reviewedBy?.role} />
              {reviewedAt ? <span className="text-ink-500">{whenText(reviewedAt)}</span> : null}
            </div>
          ) : null}
        </div>
      ) : null}

      {entries.length ? (
        <>
          {/* md+ — scrollable table; drag a header's right edge to resize its column. */}
          <div className="mt-3 hidden overflow-auto md:block">
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-ink-500">
                <tr>
                  <th className={headerCell}>Field</th>
                  <th className={headerCell}>Recorded by</th>
                  <th className="py-2 [resize:horizontal] overflow-hidden">Date &amp; time</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {entries.map(([field, entry]) => (
                  <tr key={field}>
                    <td className="py-2 pr-4 font-medium text-ink-900">{humanizeField(field)}</td>
                    <td className="py-2 pr-4 text-ink-700">
                      <span className="inline-flex flex-wrap items-center gap-2">
                        {entry?.byName ?? entry?.by ?? "Unknown"}
                        <RoleChip role={entry?.byRole} />
                      </span>
                    </td>
                    <td className="py-2 text-ink-500">{whenText(entry?.at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {/* Small screens — Android-style stacked cards. */}
          <div className="mt-3 grid gap-2 md:hidden">
            {entries.map(([field, entry]) => (
              <div key={field} className="rounded-md border border-line-200 bg-surface-50 p-3">
                <div className="text-sm font-medium text-ink-900">{humanizeField(field)}</div>
                <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-ink-700">
                  {entry?.byName ?? entry?.by ?? "Unknown"}
                  <RoleChip role={entry?.byRole} />
                </div>
                <div className="mt-0.5 text-xs text-ink-500">{whenText(entry?.at)}</div>
              </div>
            ))}
          </div>
        </>
      ) : (
        <p className="mt-3 text-sm text-ink-500">No per-field attribution recorded yet.</p>
      )}
    </section>
  );
}
