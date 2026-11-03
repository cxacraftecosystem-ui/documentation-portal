import { recordEditHref } from "@/components/forms/RecordSwitcher";
import { canManageCrafts, canManageWorkshops } from "@/lib/permissions";
import type { User } from "@/lib/types";

export const RECORD_VIEW_TYPES = ["artisan", "product", "tool", "craft", "workshop", "process", "questionnaire"] as const;

export type RecordViewType = (typeof RECORD_VIEW_TYPES)[number];

export function recordViewHref(type: string, id: string): string | null {
  const encoded = encodeURIComponent(id);
  switch (asRecordViewType(type)) {
    case "artisan":
      return `/artisans/${encoded}`;
    case "product":
      return `/products/${encoded}`;
    case "tool":
      return `/tools/${encoded}`;
    case "craft":
      return `/crafts/${encoded}`;
    case "workshop":
      return `/workshops/${encoded}`;
    case "process":
      return `/processes/${encoded}`;
    case "questionnaire":
      return `/questionnaire/interviews/${encoded}`;
    default:
      return null;
  }
}

export function recordUpdateHref(type: string, id: string): string | null {
  const kind = asRecordViewType(type);
  switch (kind) {
    case "artisan":
    case "product":
    case "tool":
    case "craft":
    case "process":
      return recordEditHref(kind, id);
    case "workshop":
      return `/workshops?edit=${encodeURIComponent(id)}`;
    case "questionnaire":
      return `/questionnaire?edit=${encodeURIComponent(id)}`;
    default:
      return null;
  }
}

export function canUpdateRecord(user: User | null | undefined, type: string): boolean {
  if (!user) return false;
  switch (asRecordViewType(type)) {
    case "craft":
      return canManageCrafts(user);
    case "workshop":
      return canManageWorkshops(user);
    case null:
      return false;
    default:
      return true;
  }
}

export function asRecordViewType(type: string): RecordViewType | null {
  const lower = type.trim().toLowerCase();
  if (lower === "interview") return "questionnaire";
  return (RECORD_VIEW_TYPES as readonly string[]).includes(lower) ? (lower as RecordViewType) : null;
}

export const ROW_INNER_CONTROLS = "a, button, input, select, textarea, label, summary, [role='button'], [role='link'], [data-row-ignore]";

export function rowClickOpensRecord(target: { closest?: (selector: string) => unknown } | null, row: unknown): boolean {
  if (!target || typeof target.closest !== "function") return true;
  const inner = target.closest(ROW_INNER_CONTROLS);
  return !inner || inner === row;
}
