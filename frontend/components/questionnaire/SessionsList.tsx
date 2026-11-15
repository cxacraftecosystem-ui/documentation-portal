"use client";

import Link from "next/link";
import { ChevronRight, ClipboardList, MoreHorizontal, Plus } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { formatDate } from "@/lib/format";
import { questionnaireApi, type Session, type SessionTotals } from "@/lib/questionnaire/api";
import { coverageCell } from "@/lib/questionnaire/view";
import { FilterBar } from "@/components/questionnaire/FilterBar";
import { OutboxTray } from "@/components/questionnaire/OutboxTray";
import { useInboxCount } from "@/components/questionnaire/useInboxCount";
import { useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { useListPlace, useUrlFilters } from "@/components/questionnaire/useUrlFilters";
import { cleanFilters, type FilterKey, type Filters } from "@/lib/questionnaire/filters";
import { qs } from "@/lib/questionnaire/qs";
import {
  byStatus,
  continuePoint,
  isFinished,
  LIST_SHOWN,
  namesLine,
  openToday,
  percentOf,
  questionProgress,
  SESSION_STATUSES,
  sectionsDone,
  sessionHref,
  STATUS_KEY,
  STATUS_PARAM,
  statusCounts,
  totalsCounts,
  subLine,
  todayLocal,
  type ContinuePoint,
  type SessionStatus
} from "@/lib/questionnaire/sessionsList";
import { FORMS_HREF, listWorkshops } from "@/lib/questionnairePortal";

const PAGE_LIMIT = 100;

const PILL = "inline-flex min-h-11 items-center rounded-md border px-4 text-sm font-medium transition-colors";
const PILL_ON = "border-purple-700 bg-purple-50 text-purple-800 dark:border-purple-400 dark:bg-purple-950/40 dark:text-purple-200";
const PILL_OFF = "border-line-200 bg-card text-ink-700 hover:border-purple-300";
const MENU_ITEM = "flex min-h-11 w-full items-center rounded-md px-3 text-left text-sm text-ink-900 hover:bg-field-100";

export function SessionsList({ fixed, embedded = false }: { fixed?: Filters; embedded?: boolean } = {}) {
  const { canDeleteRecords } = useAdminView();
  const { user } = useAuth();
  const inbox = useInboxCount();
  const [deleted, setDeleted] = useState(false);
  const [items, setItems] = useState<Session[] | null>(null);
  const [done, setDone] = useState(false);
  const [cursor, setCursor] = useState<string | null>(null);
  const [totals, setTotals] = useState<SessionTotals | null>(null);
  const [loadingRest, setLoadingRest] = useState(false);
  const [todayPool, setTodayPool] = useState<Session[]>([]);
  const [status, setStatus] = useState<SessionStatus>("ALL");
  const [expanded, setExpanded] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const [workshopNames, setWorkshopNames] = useState<Record<string, string>>({});
  const moreRef = useRef<HTMLButtonElement | null>(null);
  const { filters, setFilters, ready } = useUrlFilters();
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const fixedKey = JSON.stringify(fixed ?? {});
  const hidden = Object.keys(fixed ?? {}) as FilterKey[];

  const query = useCallback((current: Filters) => cleanFilters({ ...current, ...(JSON.parse(fixedKey) as Filters) }), [fixedKey]);

  const load = useCallback(
    async (current: Filters, wanted: SessionStatus) => {
      const mine = (generation.current += 1);
      setError(null);
      setDone(false);
      setExpanded(false);
      setLoadingRest(false);
      try {
        const result = await questionnaireApi.sessions(query(current), { limit: LIST_SHOWN, deleted, state: STATUS_PARAM[wanted] });
        if (mine !== generation.current) return;
        setItems(result.items);
        setCursor(result.nextCursor);
        setTotals(result.totals ?? null);
        if (wanted !== "FINISHED") setTodayPool(result.items);
        setDone(true);
      } catch {
        if (mine === generation.current) setError(qs("listLoadError"));
      }
    },
    [deleted, query]
  );

  const loadRest = useCallback(
    async (current: Filters, wanted: SessionStatus, from: string | null, have: Session[]) => {
      if (!from) return;
      const mine = generation.current;
      setLoadingRest(true);
      let next: string | null = from;
      let all = have;
      try {
        while (next) {
          const result = await questionnaireApi.sessions(query(current), { cursor: next, limit: PAGE_LIMIT, deleted, state: STATUS_PARAM[wanted] });
          if (mine !== generation.current) return;
          all = [...all, ...result.items];
          next = result.nextCursor;
          setItems(all);
          setCursor(next);
        }
      } catch {
        if (mine === generation.current) setError(qs("listLoadError"));
      } finally {
        if (mine === generation.current) setLoadingRest(false);
      }
    },
    [deleted, query]
  );

  useEffect(() => {
    if (ready) void load(filters, status);
  }, [ready, filters, status, load]);

  useEffect(() => {
    let cancelled = false;
    void listWorkshops()
      .then((list) => {
        if (!cancelled) setWorkshopNames(Object.fromEntries(list.map((w) => [w.id, w.title])));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const all = useMemo(() => items ?? [], [items]);
  const counts = totals ? totalsCounts(totals) : statusCounts(all);
  const filtered = byStatus(all, status);
  const total = totals ? counts[status] : filtered.length;
  const hasHidden = total > LIST_SHOWN || Boolean(cursor);
  const shown = expanded ? filtered : filtered.slice(0, LIST_SHOWN);
  const showAll = useCallback(() => {
    setExpanded(true);
    void loadRest(filters, status, cursor, all);
  }, [loadRest, filters, status, cursor, all]);
  const { wantsMore } = useListPlace(expanded ? 2 : 1, items !== null && done && !loadingRest, !hasHidden || (expanded && !cursor));
  useEffect(() => {
    if (done && !expanded && hasHidden && wantsMore(1)) showAll();
  }, [done, expanded, hasHidden, wantsMore, showAll]);

  const today = useMemo(() => (deleted ? null : openToday(todayPool, todayLocal(), user?.id ?? null)), [todayPool, deleted, user?.id]);
  const filtering = Object.keys(cleanFilters({ ...filters })).length > 0 || status !== "ALL";

  return (
    <>
      {embedded ? null : (
        <PageHeader
          title={qs("sessionsTitle")}
          icon={<ClipboardList className="h-5 w-5" aria-hidden />}
          actions={
            <>
              <Link className="field-button-secondary" href="/questionnaire/inbox" data-inbox-link>
                {inbox ? `${qs("inboxTitle")} · ${inbox}` : qs("inboxTitle")}
              </Link>
              <button
                ref={moreRef}
                type="button"
                className="field-button-secondary"
                aria-haspopup="menu"
                aria-expanded={moreOpen}
                onClick={() => setMoreOpen((v) => !v)}
                data-sessions-more
              >
                <MoreHorizontal className="h-4 w-4" aria-hidden />
                {qs("more")}
              </button>
              <Link className="field-button" href="/questionnaire/sessions/new">
                <Plus className="h-4 w-4" aria-hidden />
                {qs("newSession")}
              </Link>
            </>
          }
        />
      )}
      {embedded ? null : (
        <AnchoredPopover open={moreOpen} onClose={() => setMoreOpen(false)} anchorRef={moreRef} label={qs("more")} className="w-60 p-1">
          <div role="menu" aria-label={qs("more")} className="grid">
            <Link role="menuitem" className={MENU_ITEM} href="/questionnaire/recordings" data-recordings-link onClick={() => setMoreOpen(false)}>
              {qs("recordingsTitle")}
            </Link>
            <Link role="menuitem" className={MENU_ITEM} href="/questionnaire/coverage" onClick={() => setMoreOpen(false)}>
              {qs("coverageTitle")}
            </Link>
            <Link role="menuitem" className={MENU_ITEM} href={FORMS_HREF} onClick={() => setMoreOpen(false)}>
              {qs("formsTitle")}
            </Link>
            {canDeleteRecords ? (
              <button
                type="button"
                role="menuitem"
                className={MENU_ITEM}
                onClick={() => {
                  setMoreOpen(false);
                  setStatus("ALL");
                  setDeleted(!deleted);
                }}
                data-deleted-toggle
              >
                {deleted ? qs("sessionsTitle") : qs("deletedSessions")}
              </button>
            ) : null}
          </div>
        </AnchoredPopover>
      )}
      {embedded ? null : <OutboxTray />}
      {today && !embedded ? <OpenTodayCard session={today} workshopNames={workshopNames} /> : null}
      {items !== null && !error ? (
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3" data-session-status-pills>
          <div className="flex flex-wrap gap-2" role="group" aria-label={qs("colStatus")}>
            {SESSION_STATUSES.map((key) => (
              <button
                key={key}
                type="button"
                aria-pressed={status === key}
                className={`${PILL} ${status === key ? PILL_ON : PILL_OFF}`}
                onClick={() => {
                  setStatus(key);
                  setExpanded(false);
                }}
                data-session-status={key}
              >
                {`${qs(STATUS_KEY[key])} ${counts[key]}`}
              </button>
            ))}
          </div>
          {filtered.length ? (
            <span className="text-[13px] text-ink-500" data-showing>
              {qs("showingOf", { shown: shown.length, total })}
            </span>
          ) : null}
        </div>
      ) : null}
      <FilterBar filters={filters} onChange={setFilters} placeholder={qs("filterSearchSessions")} hide={hidden} />
      {error ? (
        <div className="panel grid gap-3 p-4 text-center">
          <p className="text-sm text-ink-700">{error}</p>
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => void load(filters, status)}>
              {qs("tryAgain")}
            </button>
          </ButtonRow>
        </div>
      ) : items === null ? (
        <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>
      ) : shown.length === 0 ? (
        <EmptyState title={filtering ? qs("filterNoMatches") : qs("noSessionsYet")} />
      ) : (
        <ul className="overflow-hidden rounded-2xl border border-line-200 bg-card" data-sessions-list>
          {shown.map((s) => (
            <SessionRow
              key={s.id}
              session={s}
              workshopNames={workshopNames}
              deleted={deleted}
              onRestore={() => void questionnaireApi.restoreSession(s.id).then(() => load(filters, status))}
            />
          ))}
        </ul>
      )}
      {hasHidden && !expanded ? (
        <ButtonRow className="mt-4">
          <button type="button" className="field-button-secondary rounded-full" onClick={showAll} data-show-all-sessions>
            {qs("showAll", { count: total })}
          </button>
        </ButtonRow>
      ) : null}
    </>
  );
}

function SessionRow({
  session: s,
  workshopNames,
  deleted,
  onRestore
}: {
  session: Session;
  workshopNames: Record<string, string>;
  deleted: boolean;
  onRestore: () => void;
}) {
  const finished = isFinished(s);
  const progress = questionProgress(s);
  const names = namesLine(s) || s.title;
  const sub = subLine(s, workshopNames);
  const body = (
    <>
      <span className="flex min-w-0 flex-[2_1_240px] flex-col gap-0.5">
        <span className="break-words text-base font-semibold text-ink-900">{names}</span>
        {sub ? <span className="text-[13px] text-ink-500">{sub}</span> : null}
      </span>
      <span className="flex-[1_1_100px] text-sm text-ink-700">{formatDate(s.sessionDate)}</span>
      <span className="flex min-w-0 flex-[1_1_160px] items-center gap-2.5" data-session-progress>
        {progress ? (
          <>
            <ProgressBar percent={percentOf(progress)} tone={finished ? "done" : "brand"} className="flex-1" />
            <span className="text-[13px] text-ink-500">{qs("answeredOfTotal", progress)}</span>
          </>
        ) : (
          <span className="text-[13px] text-ink-500">{coverageCell(s)}</span>
        )}
      </span>
    </>
  );
  if (deleted) {
    return (
      <li className="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-line-200 px-6 py-4 last:border-b-0" data-session-row={s.id}>
        {body}
        <span className="text-xs text-ink-500">{qs("deletedOn", { date: formatDate(s.deletedAt ?? null) })}</span>
        <button type="button" className="field-button-secondary" data-restore-session={s.id} onClick={onRestore}>
          {qs("restore")}
        </button>
      </li>
    );
  }
  return (
    <li className="border-b border-line-200 last:border-b-0" data-session-row={s.id}>
      <Link href={sessionHref(s)} className="flex flex-wrap items-center gap-x-6 gap-y-2 px-6 py-4 text-ink-900 transition-colors hover:bg-field-50">
        {body}
        <span
          className={`h-[26px] rounded-full px-3 text-xs font-semibold leading-[26px] ${
            finished
              ? "bg-success-100 text-green-800 dark:bg-green-950/50 dark:text-green-300"
              : "bg-purple-100 text-purple-800 dark:bg-purple-950/50 dark:text-purple-200"
          }`}
          data-session-state={finished ? "FINISHED" : "OPEN"}
        >
          {finished ? qs("sessionsFinished") : qs("sessionsOpen")}
        </span>
        <span className="text-sm font-semibold text-purple-700 dark:text-purple-300">{finished ? qs("sessionsOpen") : qs("continueSession")}</span>
      </Link>
    </li>
  );
}

function OpenTodayCard({ session, workshopNames }: { session: Session; workshopNames: Record<string, string> }) {
  const queue = useSessionQueue(session);
  const [point, setPoint] = useState<ContinuePoint | null>(null);
  const [sections, setSections] = useState<{ done: number; total: number } | null>(null);
  const [progress, setProgress] = useState(() => questionProgress(session));
  useEffect(() => {
    let cancelled = false;
    void questionnaireApi
      .getSession(session.id)
      .then((detail) => {
        if (cancelled) return;
        setPoint(continuePoint(detail));
        setSections(sectionsDone(detail));
        setProgress(questionProgress(detail) ?? questionProgress(session));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [session]);
  const uploading = queue.pendingClips.length;
  return (
    <section className="panel mb-4 flex flex-wrap items-center gap-6 p-6" aria-label={qs("openToday")} data-open-today>
      <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-1.5">
        <span className="text-[13px] font-semibold uppercase tracking-[0.14em] text-purple-700 dark:text-purple-300">{qs("openToday")}</span>
        <span className="break-words font-display text-2xl font-bold text-ink-900">{namesLine(session) || session.title}</span>
        <span className="text-sm text-ink-500">{subLine(session, workshopNames, true)}</span>
      </div>
      <div className="flex min-w-0 flex-[1_1_280px] flex-col gap-2">
        {progress ? (
          <>
            <div className="flex flex-wrap justify-between gap-2 text-sm">
              <span className="font-semibold text-ink-900">{qs("questionsOfTotal", progress)}</span>
              {sections ? <span className="text-ink-500">{qs("sectionsOfTotal", sections)}</span> : null}
            </div>
            <ProgressBar percent={percentOf(progress)} className="h-2" />
          </>
        ) : null}
        {uploading ? <span className="text-[13px] text-ink-500">{qs("takesUploading", { count: uploading })}</span> : null}
      </div>
      <Link className="field-button rounded-full" href={sessionHref(session, point?.sectionId ?? null)} data-continue-at>
        {point ? qs("continueAt", { code: point.code }) : qs("continueSession")}
        <ChevronRight className="h-4 w-4" aria-hidden />
      </Link>
    </section>
  );
}
