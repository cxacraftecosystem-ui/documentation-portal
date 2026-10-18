"use client";

import Link from "next/link";

import { EmptyState } from "@/components/EmptyState";
import { OpenableBlock } from "@/components/records/OpenableRow";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { formatDateTime } from "@/lib/format";
import {
  PORTAL_LABELS,
  highlightSegments,
  remoteStatusLine,
  type UnifiedGroup,
  type UnifiedItem,
  type UnifiedResponse,
  type UnifiedType
} from "@/lib/unifiedSearch";

/**
 * The unified answer on `/search` (docs/SEARCH.md): one panel per group in the server's order, five
 * rows each, "Show all" paging one group. A designer-portal row carries a portal badge and opens
 * the read-only remote card; a local row opens the record's own read-only page.
 */
export function UnifiedResults({
  data,
  focus,
  pageSize,
  loading,
  onFocus
}: {
  data: UnifiedResponse;
  focus: { type: UnifiedType; page: number } | null;
  pageSize: number;
  loading: boolean;
  onFocus: (next: { type: UnifiedType; page: number } | null) => void;
}) {
  const statusLine = remoteStatusLine(data.remote?.status, data.remote?.label ?? PORTAL_LABELS.designer);
  const groups = focus ? data.groups.filter((group) => group.type === focus.type) : data.groups;
  return (
    <div className="grid gap-5">
      {statusLine ? <p className="text-sm text-ink-500">{statusLine}</p> : null}
      {groups.length === 0 ? <EmptyState title={focus && focus.page > 1 ? "No more results" : "No matching records"} /> : null}
      {groups.map((group) => (
        <UnifiedSection
          key={group.type}
          group={group}
          focused={Boolean(focus)}
          onShowAll={() => onFocus({ type: group.type, page: 1 })}
        />
      ))}
      {focus ? (
        <div className="panel flex flex-col gap-2 px-4 py-3 text-sm text-ink-700 sm:flex-row sm:items-center sm:justify-between">
          <span>Page {focus.page}</span>
          <ButtonRow align="end">
            <button type="button" className="field-button-secondary" onClick={() => onFocus(null)}>
              All results
            </button>
            <button
              type="button"
              className="field-button-secondary"
              disabled={loading || focus.page <= 1}
              onClick={() => onFocus({ type: focus.type, page: focus.page - 1 })}
            >
              Previous
            </button>
            <button
              type="button"
              className="field-button-secondary"
              disabled={loading || !hasNextPage(groups[0], focus.page, pageSize)}
              onClick={() => onFocus({ type: focus.type, page: focus.page + 1 })}
            >
              Next
            </button>
          </ButtonRow>
        </div>
      ) : null}
    </div>
  );
}

function UnifiedSection({ group, focused, onShowAll }: { group: UnifiedGroup; focused: boolean; onShowAll: () => void }) {
  const more = !focused && group.total > group.items.length;
  return (
    <section className="panel overflow-hidden" aria-label={group.label}>
      <div className="flex items-center justify-between gap-3 border-b border-line-200 px-4 py-3">
        <h2 className="font-semibold text-ink-900">
          {group.label} <span className="text-sm font-normal text-ink-500">{group.total}</span>
        </h2>
        {more ? (
          <button type="button" className="text-sm font-medium text-purple-700 hover:underline" onClick={onShowAll}>
            Show all {group.total}
          </button>
        ) : null}
      </div>
      <div className="divide-y divide-line-200">
        {group.items.map((item) => (
          <UnifiedRow key={`${item.portal}:${item.id}`} item={item} />
        ))}
      </div>
    </section>
  );
}

function UnifiedRow({ item }: { item: UnifiedItem }) {
  return (
    <OpenableBlock href={item.href} className="flex flex-col gap-1 px-4 py-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Link className="font-medium text-ink-900 hover:text-purple-700 hover:underline" href={item.href}>
          {item.title}
        </Link>
        {!item.local ? (
          <span className="rounded-full border border-purple-200 bg-purple-50 px-2 py-0.5 text-[11px] font-medium text-purple-800 dark:border-purple-700 dark:bg-purple-950 dark:text-purple-200">
            {PORTAL_LABELS[item.portal] ?? item.portal}
          </span>
        ) : null}
      </div>
      {item.subtitle ? <div className="text-sm text-ink-700">{item.subtitle}</div> : null}
      {item.snippet ? (
        <p className="text-sm text-ink-500">
          {highlightSegments(item.snippet, item.highlights).map((segment, index) =>
            segment.hit ? (
              <mark key={index} className="rounded-sm bg-purple-100 px-0.5 text-ink-900 dark:bg-purple-900/60">
                {segment.text}
              </mark>
            ) : (
              <span key={index}>{segment.text}</span>
            )
          )}
        </p>
      ) : null}
      {item.recordDate ? <span className="text-xs text-ink-500">{formatDateTime(item.recordDate)}</span> : null}
    </OpenableBlock>
  );
}

/** A paged group holds this page of each portal, so there is more while either side has more. */
function hasNextPage(group: UnifiedGroup | undefined, page: number, pageSize: number): boolean {
  if (!group) return false;
  return page * pageSize < Math.max(group.localTotal ?? 0, group.remoteTotal ?? 0);
}
