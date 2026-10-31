"use client";

import Link from "next/link";
import { AudioLines, ClipboardList, Plus } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { StatusBadge } from "@/components/StatusBadge";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { formatDate } from "@/lib/format";
import { questionnaireApi, type Session } from "@/lib/questionnaire/api";
import { coverageCell, modeLabel } from "@/lib/questionnaire/view";
import { chipToggler, FilterBar } from "@/components/questionnaire/FilterBar";
import { OutboxTray } from "@/components/questionnaire/OutboxTray";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useUrlFilters } from "@/components/questionnaire/useUrlFilters";
import { cleanFilters, type FilterKey, type Filters } from "@/lib/questionnaire/filters";
import { qs } from "@/lib/questionnaire/qs";
import { FORMS_HREF } from "@/lib/questionnairePortal";

/**
 * The sessions list (DESIGN §4.8): generated titles with the note underneath, artisans and mode,
 * instrument, researchers, coverage ("6 sections · 9 takes", never "0 answers") and status. Search
 * and every filter run on the server (`FilterBar`, kept in the URL); each row carries the session's
 * chips, and tapping one filters by it. The page says when the list is capped.
 *
 * `fixed` pins filters a host screen owns (a form's own sessions pin its questionnaire); `embedded`
 * drops the page header so the list sits inside that screen.
 */
export function SessionsList({ fixed, embedded = false }: { fixed?: Filters; embedded?: boolean } = {}) {
  // The Deleted list and Restore are the content admins' (owner decision 3).
  const { canDeleteRecords } = useAdminView();
  const [deleted, setDeleted] = useState(false);
  const [items, setItems] = useState<Session[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [capped, setCapped] = useState(false);
  const { filters, setFilters, ready } = useUrlFilters();
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const fixedKey = JSON.stringify(fixed ?? {});
  const hidden = Object.keys(fixed ?? {}) as FilterKey[];

  const load = useCallback(async (current: Filters, after: string | null) => {
    const mine = (generation.current += 1);
    setError(null);
    try {
      const page = await questionnaireApi.sessions(cleanFilters({ ...current, ...(JSON.parse(fixedKey) as Filters) }), { cursor: after, deleted });
      if (mine !== generation.current) return;
      setItems((prev) => (after && prev ? [...prev, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setCapped(page.capped);
    } catch {
      if (mine === generation.current) setError(qs("listLoadError"));
    }
  }, [deleted, fixedKey]);

  useEffect(() => {
    if (ready) void load(filters, null);
  }, [ready, filters, load]);

  const shown = items ?? [];
  const onChip = chipToggler(filters, setFilters);

  return (
    <>
      {embedded ? null : <PageHeader
        title={qs("sessionsTitle")}
        description={qs("listDescription")}
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <Link className="field-button-secondary" href="/questionnaire/inbox">
              {qs("inboxTitle")}
            </Link>
            <Link className="field-button-secondary" href="/questionnaire/recordings" data-recordings-link>
              <AudioLines className="h-4 w-4" aria-hidden />
              {qs("recordingsTitle")}
            </Link>
            <Link className="field-button-secondary" href="/questionnaire/coverage">
              {qs("coverageTitle")}
            </Link>
            <Link className="field-button-secondary" href={FORMS_HREF}>
              {qs("formsTitle")}
            </Link>
            {canDeleteRecords ? (
              <button type="button" className="field-button-secondary" aria-pressed={deleted} onClick={() => setDeleted(!deleted)} data-deleted-toggle>
                {deleted ? qs("sessionsTitle") : qs("deletedSessions")}
              </button>
            ) : null}
            <Link className="field-button" href="/questionnaire/sessions/new">
              <Plus className="h-4 w-4" aria-hidden />
              {qs("newSession")}
            </Link>
          </>
        }
      />}
      {embedded ? null : <OutboxTray />}
      <FilterBar filters={filters} onChange={setFilters} placeholder={qs("filterSearchSessions")} hide={hidden} />
      {error ? (
        <div className="panel grid gap-3 p-4 text-center">
          <p className="text-sm text-ink-700">{error}</p>
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => void load(filters, null)}>
              {qs("tryAgain")}
            </button>
          </ButtonRow>
        </div>
      ) : items === null ? (
        <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>
      ) : shown.length === 0 ? (
        <EmptyState title={Object.keys(cleanFilters({ ...filters })).length ? qs("filterNoMatches") : qs("noSessionsYet")} />
      ) : (
        <div className="panel overflow-x-auto">
          <table className="w-full text-sm" data-sessions-list>
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-ink-500">
                <th className="p-3 font-medium">{qs("fieldDate")}</th>
                <th className="p-3 font-medium">{qs("fieldArtisans")}</th>
                <th className="p-3 font-medium">{qs("fieldInstrument")}</th>
                <th className="p-3 font-medium">{qs("colResearchers")}</th>
                <th className="p-3 font-medium">{qs("colCoverage")}</th>
                <th className="p-3 font-medium">{qs("colStatus")}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((s) => (
                <OpenableRow key={s.id} href={`/questionnaire/sessions/${encodeURIComponent(s.id)}/view`} className="border-t border-line-200">
                  <td className="p-3 align-top">
                    <RecordTitleLink href={`/questionnaire/sessions/${encodeURIComponent(s.id)}/view`}>
                      <span className="break-all">{s.title}</span>
                    </RecordTitleLink>
                    {s.note ? <p className="text-xs text-ink-500">{s.note}</p> : null}
                    <p className="text-xs text-ink-500">{formatDate(s.sessionDate)}</p>
                    <RecordingChips chips={s.chips} variant="session" people={false} onChip={onChip} filters={filters} className="mt-1.5" />
                  </td>
                  <td className="p-3 align-top">
                    <div className="flex flex-wrap gap-1">
                      {s.participants.map((p) => (
                        <span key={p.artisanId} className="rounded-full bg-purple-50 px-2 py-0.5 text-xs text-purple-700">
                          {p.name}
                        </span>
                      ))}
                      {(s.respondents ?? [])
                        .filter((r) => !r.artisanId)
                        .map((r) => (
                          <span key={r.id} className="rounded-full border border-line-200 px-2 py-0.5 text-xs text-ink-700" data-guest-chip>
                            {r.name} · {qs("guestBadge")}
                          </span>
                        ))}
                    </div>
                    <p className="mt-1 text-xs text-ink-500">{modeLabel(s)}</p>
                  </td>
                  <td className="p-3 align-top">{s.questionnaire.title}</td>
                  <td className="p-3 align-top">{s.researchers.map((r) => r.name).join(", ")}</td>
                  <td className="p-3 align-top">{coverageCell(s)}</td>
                  <td className="p-3 align-top">
                    {deleted ? (
                      <div className="grid gap-1">
                        <span className="text-xs text-ink-500">{qs("deletedOn", { date: formatDate(s.deletedAt ?? null) })}</span>
                        <button
                          type="button"
                          className="field-button-secondary"
                          data-restore-session={s.id}
                          onClick={() => void questionnaireApi.restoreSession(s.id).then(() => load(filters, null))}
                        >
                          {qs("restore")}
                        </button>
                      </div>
                    ) : (
                      <StatusBadge status={s.status} />
                    )}
                  </td>
                </OpenableRow>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {capped ? <p className="mt-2 text-sm text-ink-500">{qs("listCapped", { count: items?.length ?? 0 })}</p> : null}
      {cursor ? (
        <ButtonRow className="mt-4">
          <button type="button" className="field-button-secondary" onClick={() => void load(filters, cursor)}>
            {qs("showMore")}
          </button>
        </ButtonRow>
      ) : null}
    </>
  );
}
