"use client";

import { useEffect, useState, type RefObject } from "react";

import { CompletenessMeter } from "@/components/records/CompletenessMeter";
import { useCompletenessRules } from "@/lib/completeness";
import {
  evaluateCompleteness,
  type CompletenessBlock,
  type CompletenessKind,
  type CompletenessRules
} from "@/lib/completenessCore";
import { focusField } from "@/lib/forms";

/**
 * The form state as the evaluator reads it: the stored record, then every rule path the form holds a
 * control for, then the values a form keeps in React state (`values`).
 */
export function liveRecord(
  rules: CompletenessRules,
  kind: CompletenessKind,
  form: HTMLFormElement | null,
  stored: Record<string, unknown> | null | undefined,
  values: Record<string, unknown> | undefined,
  derive?: (data: FormData) => Record<string, unknown>
): Record<string, unknown> {
  const record: Record<string, unknown> = { ...(stored ?? {}) };
  if (form) {
    const data = new FormData(form);
    if (derive) Object.assign(record, derive(data));
    for (const rule of rules.kinds[kind] ?? []) {
      for (const path of rule.paths ?? (rule.path ? [rule.path] : [])) {
        if (form.querySelector(`[name="${path}"]`)) record[path] = data.get(path);
      }
    }
  }
  return { ...record, ...(values ?? {}) };
}

/**
 * Counts the form cannot see — photographs already on the record, a linked roster — are taken from
 * the server's block: a count rule the block does not list as missing is met. `add` is what the
 * form itself holds (attached photographs, picked artisans).
 */
export function liveCounts(
  rules: CompletenessRules,
  kind: CompletenessKind,
  stored: CompletenessBlock | null | undefined,
  add: Record<string, number> | undefined
): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const rule of rules.kinds[kind] ?? []) {
    if (rule.check !== "count" || !rule.of) continue;
    const met = stored ? !stored.missing.some((item) => item.key === rule.key) : false;
    counts[rule.of] = (met ? Number(rule.min ?? 1) : 0) + Number(add?.[rule.of] ?? 0);
  }
  return counts;
}

/**
 * One line under an edit form's header: the meter over what is on the form right now. Its pills move
 * the caret to the field. Works offline and on outbox drafts — nothing here asks the server.
 */
export function LiveCompleteness({
  kind,
  formRef,
  stored,
  values,
  derive,
  counts
}: {
  kind: CompletenessKind;
  formRef: RefObject<HTMLFormElement | null>;
  /** The record being edited, with its server block, or null for a new one. */
  stored?: (Record<string, unknown> & { completeness?: CompletenessBlock | null }) | null;
  values?: Record<string, unknown>;
  /** Paths the form answers under other names, read off its FormData (an artisan's location). */
  derive?: (data: FormData) => Record<string, unknown>;
  counts?: Record<string, number>;
}) {
  const rules = useCompletenessRules();
  const [block, setBlock] = useState<CompletenessBlock>(() =>
    evaluateCompleteness(kind, { ...(stored ?? {}), ...(values ?? {}) }, liveCounts(rules, kind, stored?.completeness, counts), rules)
  );

  // Themed dropdowns and date pickers fire no `input` event (§12.1), so clicks and focus changes
  // re-read the form too; the read is cheap and debounced.
  useEffect(() => {
    const form = formRef.current;
    let timer = 0;
    const read = () =>
      setBlock(
        evaluateCompleteness(
          kind,
          liveRecord(rules, kind, formRef.current, stored, values, derive),
          liveCounts(rules, kind, stored?.completeness, counts),
          rules
        )
      );
    const bump = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(read, 120);
    };
    const events = ["input", "change", "click", "focusout", "keyup"] as const;
    events.forEach((name) => form?.addEventListener(name, bump));
    bump();
    return () => {
      window.clearTimeout(timer);
      events.forEach((name) => form?.removeEventListener(name, bump));
    };
  }, [formRef, kind, rules, stored, values, derive, counts]);

  return <CompletenessMeter block={block} onPick={(key) => focusField(key, formRef.current ?? document)} compact className="mb-1" />;
}

/** Photographs among the files a form has picked but not yet uploaded — the `photos` count's share. */
export function photosPicked(files: readonly File[]): number {
  return files.filter((file) => file.type.startsWith("image/")).length;
}

/**
 * `locationId` as the form holds it: a fix on the location card (latitude AND longitude) is a location
 * the save will write, whatever the stored record had.
 */
export function locationOnForm(data: FormData): Record<string, unknown> {
  const latitude = data.get("latitude");
  const longitude = data.get("longitude");
  return typeof latitude === "string" && latitude.trim() && typeof longitude === "string" && longitude.trim()
    ? { locationId: "on-form" }
    : {};
}
