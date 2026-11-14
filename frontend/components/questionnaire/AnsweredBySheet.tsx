"use client";

import { Check } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { shortName } from "@/lib/questionnaire/coverageCache";
import { qs } from "@/lib/questionnaire/qs";

export type AnsweredByPerson = { id: string; name: string; away: boolean };

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1][0] ?? "") : "")).toUpperCase();
}

export function AnsweredBySheet({
  people,
  chosen,
  caption,
  onToggle,
  onSave,
  onLater
}: {
  people: AnsweredByPerson[];
  chosen: string[];
  caption: string;
  onToggle: (id: string) => void;
  onSave: () => void;
  onLater: () => void;
}) {
  return (
    <FieldDialog open onClose={onLater} title={qs("whoAnsweredTitle")} description={caption} showClose={false}>
      <div className="grid gap-3" data-answered-by>
        <p className="text-sm text-ink-500">{qs("presentArePicked")}</p>
        <ul className="grid gap-2" role="group" aria-label={qs("answeredBy")}>
          {people.map((p) => {
            const on = chosen.includes(p.id);
            return (
              <li key={p.id}>
                <button
                  type="button"
                  role="checkbox"
                  aria-checked={on}
                  data-answered={p.id}
                  className={`flex min-h-14 w-full items-center gap-3 rounded-md border px-3 text-left transition ${
                    on ? "border-purple-700 bg-purple-100 dark:border-purple-400 dark:bg-purple-700/25" : "border-line-200 bg-card"
                  }`}
                  onClick={() => onToggle(p.id)}
                >
                  <span aria-hidden className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-field-100 text-xs font-bold text-ink-700">
                    {initials(p.name)}
                  </span>
                  <span className="min-w-0 flex-1 text-sm font-medium text-ink-900">{p.name}</span>
                  {p.away ? <span className="rounded-sm bg-field-100 px-2 py-0.5 text-xs font-medium text-ink-500">{qs("away")}</span> : null}
                  <span
                    aria-hidden
                    className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-sm border ${on ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card"}`}
                  >
                    {on ? <Check className="h-4 w-4" /> : null}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
        <ButtonRow className="mt-2">
          <button type="button" className="field-button-secondary" onClick={onLater}>
            {qs("notNow")}
          </button>
          <button type="button" className="field-button" disabled={chosen.length === 0} onClick={onSave}>
            {qs("saveTake")}
          </button>
        </ButtonRow>
      </div>
    </FieldDialog>
  );
}

export function answeredByCaption(target: string, durationMs: number): string {
  const s = Math.max(0, Math.floor(durationMs / 1000));
  return durationMs > 0 ? `${target} · ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}` : target;
}

export { shortName as answeredByName };
