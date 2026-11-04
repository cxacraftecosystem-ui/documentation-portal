import bundledRules from "@/lib/generated/record-completeness.json";

export type CompletenessKind = "artisan" | "product" | "tool" | "interview" | "workshop";
export type CompletenessLevel = "required" | "recommended";

export type CompletenessRule = {
  key: string;
  label: string;
  words: string;
  level: CompletenessLevel;
  check: "present" | "notIn" | "anyPresent" | "count";
  path?: string;
  paths?: string[];
  empty?: string[];
  of?: string;
  min?: number;
  source?: string;
};

export type CompletenessRules = {
  contract: string;
  version: number;
  checks: string[];
  kinds: Record<CompletenessKind, CompletenessRule[]>;
  counts: Record<string, string>;
  vectors: Array<{
    kind: CompletenessKind;
    record: Record<string, unknown>;
    counts: Record<string, number>;
    expect: { filled: number; total: number; missing: string[]; requiredMissing: number; sentence: string };
  }>;
};

export type CompletenessMissing = { key: string; label: string; words: string; level: CompletenessLevel };

export type CompletenessBlock = {
  rulesVersion: number;
  filled: number;
  total: number;
  percent: number;
  requiredMissing: number;
  missing: CompletenessMissing[];
};

export const BUNDLED_RULES = bundledRules as unknown as CompletenessRules;

export const COMPLETENESS_KINDS: readonly CompletenessKind[] = ["artisan", "product", "tool", "interview", "workshop"];

export function isCompletenessKind(kind: string): kind is CompletenessKind {
  return (COMPLETENESS_KINDS as readonly string[]).includes(kind);
}

function present(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return value.trim() !== "";
  return true;
}

function satisfied(rule: CompletenessRule, record: Record<string, unknown>, counts: Record<string, number>): boolean {
  switch (rule.check) {
    case "present":
      return present(record[rule.path ?? ""]);
    case "notIn":
      return !(rule.empty ?? []).includes(record[rule.path ?? ""] as string);
    case "anyPresent":
      return (rule.paths ?? []).some((path) => present(record[path]));
    case "count":
      return Number(counts[rule.of ?? ""] ?? 0) >= Number(rule.min ?? 1);
    default:
      throw new Error(`unknown check ${String((rule as { check: unknown }).check)}`);
  }
}

export function evaluateCompleteness(
  kind: CompletenessKind,
  record: Record<string, unknown>,
  counts: Record<string, number> = {},
  rules: CompletenessRules = BUNDLED_RULES
): CompletenessBlock {
  const items = rules.kinds[kind] ?? [];
  const missing = items
    .filter((rule) => !satisfied(rule, record, counts))
    .sort((a, b) => (a.level === "required" ? 0 : 1) - (b.level === "required" ? 0 : 1));
  const total = items.length;
  const filled = total - missing.length;
  return {
    rulesVersion: rules.version,
    filled,
    total,
    percent: total ? Math.floor((filled * 100) / total) : 100,
    requiredMissing: missing.filter((rule) => rule.level === "required").length,
    missing: missing.map(({ key, label, words, level }) => ({ key, label, words, level }))
  };
}

export function completenessSentence(block: Pick<CompletenessBlock, "missing">): string {
  const missing = block.missing ?? [];
  const count = missing.length;
  if (count === 0) return "Complete";
  const head = missing
    .slice(0, 3)
    .map((item) => item.words)
    .join(", ");
  const more = count > 3 ? `, +${count - 3} more` : "";
  return `${count} ${count === 1 ? "field" : "fields"} left: ${head}${more}`;
}

export function completenessAria(block: Pick<CompletenessBlock, "filled" | "total">): string {
  return `${block.filled} of ${block.total} fields filled`;
}

export function completenessOf(
  kind: CompletenessKind,
  record: Record<string, unknown> & { completeness?: CompletenessBlock | null },
  counts?: Record<string, number>,
  rules?: CompletenessRules
): CompletenessBlock {
  return record.completeness ?? evaluateCompleteness(kind, record, counts, rules);
}

export function withFocus(updateHref: string, key: string): string {
  return `${updateHref}${updateHref.includes("?") ? "&" : "?"}focus=${encodeURIComponent(key)}`;
}

export type CompletenessFilter = "" | "incomplete" | "required";
export const COMPLETENESS_FILTERS: ReadonlyArray<{ value: CompletenessFilter; label: string }> = [
  { value: "", label: "All" },
  { value: "incomplete", label: "Incomplete" },
  { value: "required", label: "Missing required" }
];

export function parseCompletenessFilter(value: unknown): CompletenessFilter {
  return value === "incomplete" || value === "required" ? value : "";
}

export function pickRules(bundled: CompletenessRules, fetched: unknown): CompletenessRules {
  if (!fetched || typeof fetched !== "object") return bundled;
  const candidate = fetched as Partial<CompletenessRules>;
  if (candidate.contract !== "record-completeness" || typeof candidate.version !== "number" || !candidate.kinds) return bundled;
  return candidate.version > bundled.version ? (candidate as CompletenessRules) : bundled;
}

