"use client";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import type { PromptButton, PromptSpec } from "@/lib/questionnairePrompts";

/** The class each prompt button wears, by tone. */
export function promptButtonClass(tone: PromptButton["tone"]): string {
  if (tone === "danger") return "field-button-secondary field-danger";
  if (tone === "secondary") return "field-button-secondary";
  return "field-button";
}

function buttons(spec: PromptSpec, onChoose: (id: string) => void) {
  const regular = spec.buttons.filter((b) => b.tone !== "danger");
  const danger = spec.buttons.filter((b) => b.tone === "danger");
  return {
    children: regular.map((b) => (
      <button key={b.id} type="button" className={promptButtonClass(b.tone)} data-prompt-button={b.id} onClick={() => onChoose(b.id)}>
        {b.label}
      </button>
    )),
    destructive: danger.map((b) => (
      <button key={b.id} type="button" className={promptButtonClass(b.tone)} data-prompt-button={b.id} data-danger onClick={() => onChoose(b.id)}>
        {b.label}
      </button>
    ))
  };
}

/**
 * A modal prompt (P1, P3, P5, P7, P7r, P8, P9, P10, P13, leave, finish). The first non-destructive
 * button is the one Escape and the backdrop resolve to, so nothing is lost by a reflex dismissal.
 */
export function PromptDialog({
  spec,
  open,
  onChoose,
  children
}: {
  spec: PromptSpec;
  open: boolean;
  onChoose: (id: string) => void;
  children?: React.ReactNode;
}) {
  const safe = spec.buttons.find((b) => b.tone !== "primary" && b.tone !== "danger")?.id ?? spec.buttons[0]?.id ?? "cancel";
  const row = buttons(spec, onChoose);
  return (
    <FieldDialog
      open={open}
      onClose={() => onChoose(safe)}
      title={spec.title ?? ""}
      description={spec.body}
      showClose={false}
      tone={spec.buttons.some((b) => b.tone === "danger") ? "danger" : "neutral"}
    >
      <div data-prompt={spec.id}>
        {children}
        <ButtonRow className="mt-5" destructive={row.destructive}>
          {row.children}
        </ButtonRow>
      </div>
    </FieldDialog>
  );
}

/** An inline prompt: P11, P12 hints and the P14 banner. */
export function InlinePrompt({ spec, onChoose }: { spec: PromptSpec; onChoose?: (id: string) => void }) {
  const row = buttons(spec, (id) => onChoose?.(id));
  return (
    <div data-prompt={spec.id} role="status" className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
      <p>{spec.body}</p>
      {spec.buttons.length > 0 ? (
        <ButtonRow className="mt-2" size="sm" destructive={row.destructive}>
          {row.children}
        </ButtonRow>
      ) : null}
    </div>
  );
}
