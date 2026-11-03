import { buildQuery } from "@/lib/api";

export type AppNotification = {
  id: string;
  kind: string;
  title: string;
  body: string | null;
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

export function bellLabel(unread: number): string {
  if (unread <= 0) return "Notifications";
  return unread === 1 ? "Notifications, 1 unread" : `Notifications, ${unread} unread`;
}

export function badgeText(unread: number): string | null {
  if (unread <= 0) return null;
  return unread > 99 ? "99+" : String(unread);
}

export function safeLink(link: string | null | undefined): string | null {
  if (!link || !link.startsWith("/") || link.startsWith("//")) return null;
  return link;
}

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

export function markedRead(items: AppNotification[], ids: Set<string> | "all", at: string): AppNotification[] {
  return items.map((item) =>
    !item.read && (ids === "all" || ids.has(item.id)) ? { ...item, read: true, readAt: at } : item
  );
}

export function appendPage(items: AppNotification[], next: AppNotification[]): AppNotification[] {
  const seen = new Set(items.map((item) => item.id));
  return [...items, ...next.filter((item) => !seen.has(item.id))];
}
