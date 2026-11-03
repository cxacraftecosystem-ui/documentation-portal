import { isContentAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

export const DANGER_ZONE = {
  title: "Danger zone",
  body: (noun: string) => `Permanently deletes this ${noun} and its links.`,
  button: (noun: string) => `Delete this ${noun}`,
  confirmTitle: (noun: string) => `Delete ${noun}?`,
  confirmBody: "This permanently deletes the record.",
  deleting: "Deleting…",
  failed: "Unable to delete (admin only)"
} as const;

export function dangerZoneVisible(user: User | null | undefined): boolean {
  return isContentAdmin(user);
}

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
