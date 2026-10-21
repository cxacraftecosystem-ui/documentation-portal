"use client";

import Link from "next/link";
import { ClipboardList, Plus } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { formatDate } from "@/lib/format";
import { canManageQuestionnaire } from "@/lib/permissions";
import { questionnaireApi, type Session } from "@/lib/questionnaireApi";
import { coverageCell, modeLabel, sessionMatches } from "@/lib/questionnaireView";
import { qs } from "@/lib/strings/qs";

/**
 * The sessions list (DESIGN §4.8): generated titles with the note underneath, artisans and mode,
 * instrument, researchers, coverage ("6 sections · 9 takes", never "0 answers") and status. Search
 * runs on the server over title, note and legacyTitle; the page says when the list is capped.
 */
export function SessionsList() {
  const { user } = useAuth();
  const [items, setItems] = useState<Session[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [capped, setCapped] = useState(false);
  const [search, setSearch] = useState("");
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(async (query: string, after: string | null) => {
    const mine = (generation.current += 1);
    setError(null);
    try {
      const page = await questionnaireApi.listSessions({ search: query || undefined, cursor: after ?? undefined });
      if (mine !== generation.current) return;
      setItems((prev) => (after && prev ? [...prev, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setCapped(page.capped);
    } catch {
      if (mine === generation.current) setError(qs("listLoadError"));
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(search, null), 250);
    return () => window.clearTimeout(timer);
  }, [search, load]);

  const shown = (items ?? []).filter((s) => sessionMatches(s, search));

  return (
    <>
      <PageHeader
        title={qs("sessionsTitle")}
        description={qs("listDescription")}
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <Link className="field-button-secondary" href="/questionnaire/inbox">
              {qs("inboxTitle")}
            </Link>
            <Link className="field-button-secondary" href="/questionnaire/coverage">
              {qs("coverageTitle")}
            </Link>
            {/* The builder edits the instrument: managers only (audit H15). */}
            {canManageQuestionnaire(user) ? (
              <Link className="field-button-secondary" href="/questionnaire/builder">
                {qs("builder")}
              </Link>
            ) : null}
            <Link className="field-button" href="/questionnaire/sessions/new">
              <Plus className="h-4 w-4" aria-hidden />
              {qs("newSession")}
            </Link>
          </>
        }
      />
      <div className="mb-4">
        <SearchInput value={search} onChange={setSearch} placeholder={qs("searchSessions")} />
      </div>
      {error ? (
        <div className="panel grid gap-3 p-4 text-center">
          <p className="text-sm text-ink-700">{error}</p>
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => void load(search, null)}>
              {qs("tryAgain")}
            </button>
          </ButtonRow>
        </div>
      ) : items === null ? (
        <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>
      ) : shown.length === 0 ? (
        <EmptyState title={qs("noSessionsYet")} />
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
                  </td>
                  <td className="p-3 align-top">
                    <div className="flex flex-wrap gap-1">
                      {s.participants.map((p) => (
                        <span key={p.artisanId} className="rounded-full bg-purple-50 px-2 py-0.5 text-xs text-purple-700">
                          {p.name}
                        </span>
                      ))}
                    </div>
                    <p className="mt-1 text-xs text-ink-500">{modeLabel(s)}</p>
                  </td>
                  <td className="p-3 align-top">{s.questionnaire.title}</td>
                  <td className="p-3 align-top">{s.researchers.map((r) => r.name).join(", ")}</td>
                  <td className="p-3 align-top">{coverageCell(s)}</td>
                  <td className="p-3 align-top">
                    <StatusBadge status={s.status} />
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
          <button type="button" className="field-button-secondary" onClick={() => void load(search, cursor)}>
            {qs("showMore")}
          </button>
        </ButtonRow>
      ) : null}
    </>
  );
}
