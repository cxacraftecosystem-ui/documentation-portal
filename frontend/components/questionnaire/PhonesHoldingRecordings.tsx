"use client";

import { Smartphone } from "lucide-react";
import { useEffect, useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { formatDateTime } from "@/lib/format";
import { questionnaireApi } from "@/lib/questionnaire/api";
import { deviceLabel, waitedLabel, type HoldingList } from "@/lib/questionnaire/outboxReport";
import { qs } from "@/lib/questionnaire/qs";

export function PhonesHoldingRecordings() {
  const [list, setList] = useState<HoldingList | null>(null);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [now, setNow] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    questionnaireApi
      .outboxReports()
      .then((answer) => {
        if (cancelled) return;
        setList(answer);
        setError(false);
        setNow(Date.now());
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const items = list?.items ?? [];
  return (
    <section id="phones-holding-recordings" className="panel mt-6 overflow-hidden" data-phones-holding>
      <div className="flex items-center gap-3 border-b border-line-200 px-4 py-3">
        <Smartphone className="h-5 w-5 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
        <h2 className="font-display font-bold text-ink-900">{qs("phonesHoldingTitle")}</h2>
      </div>
      {error ? (
        <div className="grid gap-3 px-4 py-6 text-center">
          <p className="text-sm text-ink-700">{qs("phonesHoldingError")}</p>
          <ButtonRow>
            <button type="button" className="field-button-secondary" onClick={() => setAttempt((n) => n + 1)}>
              {qs("tryAgain")}
            </button>
          </ButtonRow>
        </div>
      ) : list === null ? (
        <p className="px-4 py-6 text-sm text-ink-500">{qs("loading")}</p>
      ) : items.length === 0 ? (
        <p className="px-4 py-6 text-sm text-ink-500" data-phones-holding-empty>
          {qs("phonesHoldingEmpty")}
        </p>
      ) : (
        <>
          <ul className="divide-y divide-line-200" data-phones-holding-list>
            {items.map((install) => (
              <li key={`${install.userId}:${install.installId}`} className="grid gap-1.5 px-4 py-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center sm:gap-x-6" data-phone-row={install.installId}>
                <div className="grid min-w-0 gap-0.5">
                  <span className="truncate text-sm font-semibold text-ink-900">{install.name || install.email}</span>
                  <span className="truncate text-[0.8125rem] text-ink-500">{[deviceLabel(install), install.email].filter(Boolean).join(" · ")}</span>
                </div>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[0.8125rem] sm:justify-end">
                  <span className="font-semibold text-ink-900">{qs("holdingWaiting", { count: install.pending })}</span>
                  {install.failed > 0 ? (
                    <span className="font-semibold text-error-600 dark:text-red-300">{qs("holdingFailed", { count: install.failed })}</span>
                  ) : null}
                  {install.oldestAt ? <span className="text-ink-700">{qs("holdingOldest", { age: waitedLabel(install.oldestAt, now ?? undefined) })}</span> : null}
                  {install.reportedAt ? <span className="text-ink-500">{qs("holdingReported", { when: formatDateTime(install.reportedAt) })}</span> : null}
                </div>
              </li>
            ))}
          </ul>
          {list.capped ? <p className="border-t border-line-200 px-4 py-2 text-[0.8125rem] text-ink-500">{qs("phonesHoldingCapped", { count: list.limit })}</p> : null}
        </>
      )}
    </section>
  );
}
