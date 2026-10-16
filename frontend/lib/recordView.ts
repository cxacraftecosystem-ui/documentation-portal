import { recordEditHref } from "@/components/forms/RecordSwitcher";
import { canManageCrafts, canManageWorkshops } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * OPENING A RECORD READS IT; ONLY "UPDATE" EDITS IT.
 *
 * Owner ruling, 2026-10-16: a click anywhere on a record's card or row opens that record in a
 * read-only view, and the edit form is reached only through an explicit "Update" button. Before this,
 * half the lists opened the edit form straight from a row and the other half answered only a small
 * link in the last column — so a researcher who just wanted to read a record landed in a form, and
 * one stray keystroke there armed the unsaved-changes prompt or, worse, a save.
 *
 * These are the pure halves of that rule — where a record's view lives, where its Update goes, and
 * who sees the Update button — so `e2e/record-view-unit.spec.ts` can pin them without a browser.
 * Every list, the dashboard, search, the map and the data browser build their links from here, so
 * "where does clicking this record go" has one answer.
 */

/** Every record type with a read-only view. Media is not one of them: a file opens in its preview. */
export const RECORD_VIEW_TYPES = ["artisan", "product", "tool", "craft", "workshop", "process", "questionnaire"] as const;

export type RecordViewType = (typeof RECORD_VIEW_TYPES)[number];

/**
 * The view route for one record, or null for a type that has none.
 *
 * Interviews live under `/questionnaire/interviews/` rather than at `/questionnaire/[id]` because
 * `/questionnaire/consolidated` and `/questionnaire/workbooks` already sit one level down, and an
 * interview id beside them would read as a third kind of page.
 */
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

/**
 * Where "Update" goes — the edit form that already exists for this type. Artisans, products and
 * tools have an `/[id]/edit` route; crafts, workshops, processes and interviews are edited inline on
 * their list page and take the id as `?edit=` (read there by `useEditDeepLink`).
 */
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

/**
 * Whether this account sees the Update button for a record of this type. It mirrors the server's
 * own gate on the PATCH and adds nothing:
 *
 *   - crafts and workshops: `require_craft_manager` / `require_workshop_manager`, Professor and up;
 *   - every other type: any signed-in account. `PATCH` on artisans, products, tools, processes and
 *     interviews takes `get_current_user` and decides field by field in `guard_record_edit` — an
 *     ordinary contributor may fill a blank field but not overwrite one — which is why the lists
 *     have always offered their edit control to everybody. Hiding it here would take away the
 *     "fill in what is missing" path those tiers exist for.
 */
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

/**
 * The type a wire string names, or null. The API spells an interview two ways depending on the route
 * that produced the row, and a record type nobody recognises must open nothing rather than the wrong
 * page.
 */
export function asRecordViewType(type: string): RecordViewType | null {
  const lower = type.trim().toLowerCase();
  if (lower === "interview") return "questionnaire";
  return (RECORD_VIEW_TYPES as readonly string[]).includes(lower) ? (lower as RecordViewType) : null;
}

/**
 * Whether a click that landed on `target` belongs to the row it is inside, or to a control of its
 * own. Rows carry Update, Discuss, Delete and media previews; a click on any of them must do that
 * thing and never also open the record. Written over a minimal element shape so the rule can be
 * tested without a DOM.
 */
export const ROW_INNER_CONTROLS = "a, button, input, select, textarea, label, summary, [role='button'], [role='link'], [data-row-ignore]";

export function rowClickOpensRecord(target: { closest?: (selector: string) => unknown } | null, row: unknown): boolean {
  if (!target || typeof target.closest !== "function") return true;
  const inner = target.closest(ROW_INNER_CONTROLS);
  return !inner || inner === row;
}
