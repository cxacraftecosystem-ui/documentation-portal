"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { MessageSquareWarning, Pencil } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { RecordTitleLink } from "@/components/records/OpenableRow";
import { StatusBadge } from "@/components/StatusBadge";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { formatDateTime } from "@/lib/format";
import { recordUpdateHref, recordViewHref } from "@/lib/recordView";
import { fetchReturnedRecords, returnedHeading, returnedNextStep, sentBackTitle, type ReturnedList } from "@/lib/review";

const TYPE_LABEL: Record<string, string> = {
  artisan: "Artisan",
  workshop: "Workshop",
  product: "Product",
  tool: "Tool",
  process: "Process",
  questionnaire: "Interview"
};

/**
 * "Sent back to you" (A2): every record of this account's that a reviewer returned, across record
 * types, each with the reviewer's note — the list the uploader used to rebuild by filtering four to
 * six record lists by status. Rejected records are here too, because the creator's own edit reopens
 * them.
 */
export default function SentBackPage() {
  const [list, setList] = useState<ReturnedList | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchReturnedRecords()
      .then((answer) => {
        if (!cancelled) setList(answer);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Couldn't load what was sent back.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeader
        title={list ? sentBackTitle(list.total) : "Sent back to you"}
        description="Records a reviewer returned, with their notes."
        icon={<MessageSquareWarning className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {!list ? (
        error ? null : <div className="panel p-4 text-sm text-ink-muted">Loading…</div>
      ) : list.items.length === 0 ? (
        <div className="panel p-4">
          <EmptyState title="Nothing sent back" body="Every record you sent is with a reviewer or approved." />
        </div>
      ) : (
        <ul className="grid gap-3">
          {list.items.map((item) => {
            const view = recordViewHref(item.recordType, item.id);
            const update = recordUpdateHref(item.recordType, item.id);
            return (
              <li key={`${item.recordType}-${item.id}`} className="panel grid gap-2 p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs font-medium uppercase tracking-wide text-ink-500">
                    {TYPE_LABEL[item.recordType] ?? item.recordType}
                  </span>
                  <StatusBadge status={item.status} />
                  {item.reasonLabel ? (
                    <span className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs text-ink-700">
                      {item.reasonLabel}
                    </span>
                  ) : null}
                </div>
                <div className="font-display text-base font-bold text-ink-900">
                  {view ? <RecordTitleLink href={view}>{item.label}</RecordTitleLink> : item.label}
                  {item.place ? <span className="ml-2 text-sm font-normal text-ink-500">{item.place}</span> : null}
                </div>
                <p className="text-xs text-ink-500">
                  {returnedHeading(item.status, item.reviewerName)}
                  {item.reviewedAt ? ` · ${formatDateTime(item.reviewedAt)}` : ""}
                </p>
                {item.reviewNotes && item.reviewNotes !== item.reasonLabel ? (
                  <p className="whitespace-pre-line break-words rounded-md bg-surface-50 px-3 py-2 text-sm text-ink-900">
                    {item.reviewNotes}
                  </p>
                ) : null}
                <p className="text-xs text-ink-500">{returnedNextStep(item.status)}</p>
                {update ? (
                  <ButtonRow size="sm" align="start">
                    <Link className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11" href={update}>
                      <Pencil className="h-3.5 w-3.5" aria-hidden />
                      Update
                    </Link>
                  </ButtonRow>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}
