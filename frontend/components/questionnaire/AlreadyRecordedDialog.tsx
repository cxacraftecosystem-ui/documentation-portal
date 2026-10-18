"use client";

import { useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { promptButtonClass } from "@/components/questionnaire/Prompts";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { REPEAT_REASONS, type RepeatReason } from "@/lib/questionnaireApi";
import { alreadyRecorded, REASON_LABEL, type P2Input } from "@/lib/questionnairePrompts";
import { qs } from "@/lib/strings/qs";

export type P2Choice =
  | { kind: "cancel" }
  | { kind: "confirm"; reason: RepeatReason }
  | { kind: "part2" }
  | { kind: "replace" }
  | { kind: "different"; targetValue: string };

/**
 * P2 "Already recorded" (DESIGN §4.4): asked BEFORE recording, on Record press or file pick. The
 * reason is preselected by the rule in `preselectReason`; More options holds part 2 (only when its
 * rule holds), replace (only for your own take in this session) and "Use a different question".
 */
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
  /** Uncovered targets first. */
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
          <SegmentedChoice
            label={qs("whyAgain")}
            value={reason}
            options={REPEAT_REASONS.map((r) => ({ value: r, label: REASON_LABEL[r] }))}
            onChange={(v) => setReason(v as RepeatReason)}
          />
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
            {(spec.more ?? [])
              .filter((b) => b.id !== "different")
              .map((b) => (
                <button key={b.id} type="button" className="field-button-secondary w-full" onClick={() => onChoose({ kind: b.id as "part2" | "replace" })}>
                  {b.label}
                </button>
              ))}
            <div className="grid gap-1">
              <span className="field-label">{qs("useDifferentQuestion")}</span>
              <SearchableSelect
                value={target}
                onChange={(v) => {
                  setTarget(v);
                  if (v) onChoose({ kind: "different", targetValue: v });
                }}
                options={otherTargets}
                searchable
                ariaLabel={qs("useDifferentQuestion")}
              />
            </div>
          </div>
        ) : null}
      </div>
    </FieldDialog>
  );
}
