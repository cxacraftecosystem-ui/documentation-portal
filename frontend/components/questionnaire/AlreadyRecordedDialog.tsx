"use client";

import { Check } from "lucide-react";
import { useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { promptButtonClass } from "@/components/questionnaire/Prompts";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import { REPEAT_REASONS, type RepeatReason } from "@/lib/questionnaire/api";
import { alreadyRecorded, REASON_LABEL, type P2Input } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";

export type P2Choice =
  | { kind: "cancel" }
  | { kind: "confirm"; reason: RepeatReason }
  | { kind: "part2" }
  | { kind: "replace" }
  | { kind: "different"; targetValue: string };

export function AlreadyRecordedDialog({
  open,
  input,
  preselected,
  otherTargets,
  onChoose
}: {
  open: boolean;
  input: P2Input;
  preselected: RepeatReason;
  otherTargets: SelectOption[];
  onChoose: (choice: P2Choice) => void;
}) {
  const [reason, setReason] = useState<RepeatReason>(preselected);
  const [more, setMore] = useState(false);
  const [target, setTarget] = useState("");
  const { spec, covered, first } = alreadyRecorded(input);

  return (
    <FieldDialog open={open} onClose={() => onChoose({ kind: "cancel" })} title={spec.title ?? ""} description={spec.body} showClose={false}>
      <div data-prompt="P2" className="grid gap-3">
        {covered.length > 1 || first.length ? (
          <ul className="grid gap-1 text-sm text-ink-700">
            {covered.map((row) => (
              <li key={row.artisanId}>{row.line}</li>
            ))}
            {first.map((line) => (
              <li key={line} className="text-ink-500">
                {line}
              </li>
            ))}
          </ul>
        ) : null}
        <div className="grid gap-1">
          <span className="field-label">{qs("whyAgain")}</span>
          <div role="group" aria-label={qs("whyAgain")} className="flex flex-wrap gap-2" data-why-again>
            {REPEAT_REASONS.map((r) => {
              const on = r === reason;
              return (
                <button
                  key={r}
                  type="button"
                  aria-pressed={on}
                  onClick={() => setReason(r)}
                  className={`inline-flex min-h-11 items-center gap-1.5 rounded-md border px-3.5 text-sm font-medium transition-colors ${
                    on
                      ? "border-purple-700 bg-purple-700 text-white dark:border-purple-400 dark:bg-purple-700/40 dark:text-purple-100"
                      : "border-line-200 bg-card text-ink-900 hover:border-purple-300"
                  }`}
                >
                  {on ? <Check className="h-4 w-4" aria-hidden /> : null}
                  {REASON_LABEL[r]}
                </button>
              );
            })}
          </div>
        </div>
        <ButtonRow>
          {spec.buttons.map((b) => (
            <button
              key={b.id}
              type="button"
              className={promptButtonClass(b.tone)}
              data-prompt-button={b.id}
              onClick={() => onChoose(b.id === "confirm" ? { kind: "confirm", reason } : { kind: "cancel" })}
            >
              {b.label}
            </button>
          ))}
        </ButtonRow>
        <div className="flex justify-center">
          <button type="button" className="text-sm font-medium text-ink-500 underline" aria-expanded={more} onClick={() => setMore((m) => !m)}>
            {qs("moreOptions")}
          </button>
        </div>
        {more ? (
          <div className="grid gap-2" data-p2-more>
            {(spec.more ?? []).map((b) =>
              b.id === "different" ? (
                <div key={b.id} className="grid gap-1">
                  <span className="field-label">{b.label}</span>
                  <SearchableSelect
                    value={target}
                    onChange={(v) => {
                      setTarget(v);
                      if (v) onChoose({ kind: "different", targetValue: v });
                    }}
                    options={otherTargets}
                    searchable
                    ariaLabel={b.label}
                  />
                </div>
              ) : (
                <button
                  key={b.id}
                  type="button"
                  className={`field-button-secondary w-full${b.tone === "danger" ? " text-error-600" : ""}`}
                  onClick={() => onChoose({ kind: b.id as "part2" | "replace" })}
                >
                  {b.label}
                </button>
              )
            )}
          </div>
        ) : null}
      </div>
    </FieldDialog>
  );
}
