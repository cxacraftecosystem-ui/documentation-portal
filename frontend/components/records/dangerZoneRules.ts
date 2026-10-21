import { isContentAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * THE "DANGER ZONE" CARD THAT ENDS A RECORD'S PAGE — its words and its gate, pure so
 * `e2e/record-danger-zone-unit.spec.ts` holds them without a browser.
 *
 * Byte-identical in designer-portal and documentation-portal, and the same words as both Android
 * apps' `ui/RecordDangerZone.kt`. Drawn for content admins (Admin, Master Admin, Ministry Admin —
 * `isContentAdmin`) with admin view on (`adminMode`), the gate every other Delete in the app reads,
 * and for nobody else; the server refuses everyone else regardless.
 */
export const DANGER_ZONE = {
  title: "Danger zone",
  body: (noun: string) => `Permanently deletes this ${noun} and its links.`,
  button: (noun: string) => `Delete this ${noun}`,
  confirmTitle: (noun: string) => `Delete ${noun}?`,
  confirmBody: "This permanently deletes the record.",
  deleting: "Deleting…",
  failed: "Unable to delete (admin only)"
} as const;

/** Whether the card is drawn for this reader. */
export function dangerZoneVisible(user: User | null | undefined, adminMode: boolean): boolean {
  return adminMode && isContentAdmin(user);
}

/** Where a deleted record's page goes next: the list it came from. Keyed by both portals' kinds. */
export const RECORD_LIST_HREF: Record<string, string> = {
  artisan: "/artisans",
  product: "/products",
  tool: "/tools",
  process: "/processes",
  craft: "/crafts",
  workshop: "/workshops",
  interview: "/questionnaire",
  questionnaire: "/questionnaire",
  media: "/media"
};
