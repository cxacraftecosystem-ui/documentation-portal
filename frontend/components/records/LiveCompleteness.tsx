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
  stored?: (Record<string, unknown> & { completeness?: CompletenessBlock | null }) | null;
  values?: Record<string, unknown>;
  derive?: (data: FormData) => Record<string, unknown>;
  counts?: Record<string, number>;
}) {
  const rules = useCompletenessRules();
  const [block, setBlock] = useState<CompletenessBlock>(() =>
    evaluateCompleteness(kind, { ...(stored ?? {}), ...(values ?? {}) }, liveCounts(rules, kind, stored?.completeness, counts), rules)
  );

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

export function photosPicked(files: readonly File[]): number {
  return files.filter((file) => file.type.startsWith("image/")).length;
}

export function locationOnForm(data: FormData): Record<string, unknown> {
  const latitude = data.get("latitude");
  const longitude = data.get("longitude");
  return typeof latitude === "string" && latitude.trim() && typeof longitude === "string" && longitude.trim()
    ? { locationId: "on-form" }
    : {};
}
