/**
 * The in-app activity list — the pure half (types, paths, words), apart from React so
 * `e2e/notifications-unit.spec.ts` can call it.
 *
 * The server writes a notice with the SAME call that queues its e-mail
 * (`backend/app/services/notifications.notify`), so the list holds every notice even where mail is
 * off. The API and these names are identical in the designer portal and on both handsets:
 *
 *   GET  /notifications?limit=&before=&unreadOnly=   -> NotificationPage
 *   GET  /notifications/unread-count                 -> { unreadCount }
 *   POST /notifications/{id}/read                    -> { item, unreadCount }
 *   POST /notifications/read  { ids } | { all: true } -> { updated, unreadCount }
 */

import { buildQuery } from "@/lib/api";

export type AppNotification = {
  id: string;
  kind: string;
  title: string;
  body: string | null;
  /** An app path ("/tasks", "/review"…), or null when the notice has nowhere to open. */
  link: string | null;
  read: boolean;
  readAt: string | null;
  createdAt: string;
};

export type NotificationPage = {
  items: AppNotification[];
  unreadCount: number;
  hasMore: boolean;
};

export const NOTIFICATIONS_HREF = "/notifications";
export const NOTIFICATIONS_PAGE_SIZE = 30;
export const UNREAD_COUNT_PATH = "/notifications/unread-count";

export function notificationsPath(options: { before?: string | null; unreadOnly?: boolean; limit?: number } = {}): string {
  return `/notifications${buildQuery({
    limit: options.limit ?? NOTIFICATIONS_PAGE_SIZE,
    before: options.before ?? undefined,
    unreadOnly: options.unreadOnly ? "true" : undefined
  })}`;
}

export function markOnePath(id: string): string {
  return `/notifications/${encodeURIComponent(id)}/read`;
}

export const MARK_READ_PATH = "/notifications/read";

/** The bell's whole name, for a pointer and a screen reader alike — never a bare digit. */
export function bellLabel(unread: number): string {
  if (unread <= 0) return "Notifications";
  return unread === 1 ? "Notifications, 1 unread" : `Notifications, ${unread} unread`;
}

/** What the badge prints: the count, capped so it fits the dot. */
export function badgeText(unread: number): string | null {
  if (unread <= 0) return null;
  return unread > 99 ? "99+" : String(unread);
}

/** Only an app path is followed from a notice; anything else opens nothing. */
export function safeLink(link: string | null | undefined): string | null {
  if (!link || !link.startsWith("/") || link.startsWith("//")) return null;
  return link;
}

/** "Just now", "5 min ago", "3 h ago", "Yesterday", then the date. */
export function whenWords(createdAt: string, now: Date = new Date()): string {
  const moment = new Date(createdAt);
  const seconds = Math.round((now.getTime() - moment.getTime()) / 1000);
  if (!Number.isFinite(seconds)) return "";
  if (seconds < 60) return "Just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  if (hours < 48) return "Yesterday";
  return new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", year: "numeric" }).format(moment);
}

/** One notice marked read, everywhere it appears in a list already on screen. */
export function markedRead(items: AppNotification[], ids: Set<string> | "all", at: string): AppNotification[] {
  return items.map((item) =>
    !item.read && (ids === "all" || ids.has(item.id)) ? { ...item, read: true, readAt: at } : item
  );
}

/** Appends a further page, dropping any notice already shown (a new one can shift the pages). */
export function appendPage(items: AppNotification[], next: AppNotification[]): AppNotification[] {
  const seen = new Set(items.map((item) => item.id));
  return [...items, ...next.filter((item) => !seen.has(item.id))];
}
