"use client";

import Link from "next/link";
import { AudioLines } from "lucide-react";
import { useEffect, useState } from "react";

import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { chipHref, useChipNavigator } from "@/components/questionnaire/useUrlFilters";
import { questionnaireApi, type Recording } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

/**
 * A workshop's latest questionnaire takes with their chips (owner, 31 Oct 2026), and the way to all
 * of them on the recordings list, filtered to the workshop. `type` says which kind of workshop the
 * id names: a field workshop, or (designer-portal) a design workshop. Draws nothing while the
 * questionnaire is off or the workshop has no takes.
 */
export function WorkshopRecordings({ workshopId, type = "FIELD", limit = 5 }: { workshopId: string; type?: "FIELD" | "DESIGN"; limit?: number }) {
  const on = Boolean(useQuestionnaireConfig()?.v2Enabled);
  const goChip = useChipNavigator();
  const [items, setItems] = useState<Recording[] | null>(null);
  const key = type === "DESIGN" ? "designWorkshopId" : "workshopId";

  useEffect(() => {
    if (!on) return;
    let cancelled = false;
    void questionnaireApi
      .listRecordings({ [key]: [workshopId] }, null, null, limit)
      .then((page) => !cancelled && setItems(page.items))
      .catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [on, key, workshopId, limit]);

  if (!on || !items || items.length === 0) return null;
  return (
    <section className="panel mt-5 grid gap-2 p-4" data-workshop-recordings>
      <div className="flex items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 font-display text-base font-bold text-ink-900">
          <AudioLines className="h-4 w-4 text-purple-700" aria-hidden />
          {qs("recordingsTitle")}
        </h2>
        <Link className="text-sm text-purple-700 underline-offset-2 hover:underline" href={chipHref({ key, value: workshopId })}>
          {qs("showMore")}
        </Link>
      </div>
      <ul className="grid gap-2">
        {items.map((r) => (
          <li key={r.id} className="rounded-md border border-line-200 p-2">
            <RecordingChips chips={r.chips} onChip={goChip} />
          </li>
        ))}
      </ul>
    </section>
  );
}
