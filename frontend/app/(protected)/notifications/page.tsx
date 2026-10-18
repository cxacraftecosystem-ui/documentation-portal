"use client";

/**
 * /notifications — the in-app activity list (C25). Every notice the server sends lands here, by the
 * same call that queues its e-mail, so nothing is missed where mail is off. The bell in the island
 * opens this page; Android's "Notifications" screen is the same list, kept offline.
 *
 * Opening a notice marks it read and follows its link; "Mark all as read" clears the badge.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Bell } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { setUnreadNotificationCount } from "@/components/hooks/useUnreadNotifications";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { Toggle } from "@/components/ui/Toggle";
import { readableError } from "@/components/review/reviewErrors";
import { apiFetch } from "@/lib/api";
import {
  appendPage,
  markedRead,
  markOnePath,
  MARK_READ_PATH,
  notificationsPath,
  safeLink,
  whenWords,
  type AppNotification,
  type NotificationPage
} from "@/lib/inAppNotifications";

export default function NotificationsPage() {
  const router = useRouter();
  const [items, setItems] = useState<AppNotification[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [unread, setUnread] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [marking, setMarking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const take = useCallback((page: NotificationPage) => {
    setUnread(page.unreadCount);
    setUnreadNotificationCount(page.unreadCount);
    setHasMore(page.hasMore);
  }, []);

  useEffect(() => {
    const mine = ++generation.current;
    setItems(null);
    setError(null);
    apiFetch<NotificationPage>(notificationsPath({ unreadOnly }))
      .then((page) => {
        if (mine !== generation.current) return;
        setItems(page.items);
        take(page);
      })
      .catch((failure) => {
        if (mine !== generation.current) return;
        setItems([]);
        setError(readableError(failure, "Couldn't load your notifications."));
      });
  }, [unreadOnly, take]);

  async function loadMore() {
    if (!items?.length) return;
    const mine = generation.current;
    setLoadingMore(true);
    try {
      const page = await apiFetch<NotificationPage>(
        notificationsPath({ unreadOnly, before: items[items.length - 1].id })
      );
      if (mine !== generation.current) return;
      setItems((current) => appendPage(current ?? [], page.items));
      take(page);
    } catch (failure) {
      setError(readableError(failure, "Couldn't load more."));
    } finally {
      setLoadingMore(false);
    }
  }

  async function markAll() {
    setMarking(true);
    try {
      const answer = await apiFetch<{ updated: number; unreadCount: number }>(MARK_READ_PATH, {
        method: "POST",
        body: JSON.stringify({ all: true })
      });
      setItems((current) => (unreadOnly ? [] : markedRead(current ?? [], "all", new Date().toISOString())));
      setUnread(answer.unreadCount);
      setUnreadNotificationCount(answer.unreadCount);
    } catch (failure) {
      setError(readableError(failure, "Couldn't mark them read."));
    } finally {
      setMarking(false);
    }
  }

  async function open(item: AppNotification) {
    const link = safeLink(item.link);
    if (!item.read) {
      setItems((current) => markedRead(current ?? [], new Set([item.id]), new Date().toISOString()));
      try {
        const answer = await apiFetch<{ unreadCount: number }>(markOnePath(item.id), { method: "POST" });
        setUnread(answer.unreadCount);
        setUnreadNotificationCount(answer.unreadCount);
      } catch {
        /* the next read of the list puts it right; following the link matters more */
      }
    }
    if (link) router.push(link);
  }

  return (
    <>
      <PageHeader
        title="Notifications"
        description={unread > 0 ? `${unread} unread` : "You're all caught up."}
        icon={<Bell className="h-5 w-5" aria-hidden />}
        actions={
          unread > 0 ? (
            <ButtonRow align="end">
              <button type="button" className="field-button-secondary" onClick={markAll} disabled={marking}>
                {marking ? "Marking…" : "Mark all as read"}
              </button>
            </ButtonRow>
          ) : null
        }
      />

      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      <div className="panel mb-4 p-1">
        <Toggle label="Unread only" checked={unreadOnly} onChange={setUnreadOnly} />
      </div>

      {items === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : items.length === 0 ? (
        <EmptyState title={unreadOnly ? "No unread notifications" : "No notifications yet"} />
      ) : (
        <ul className="panel divide-y divide-line-200 overflow-hidden" aria-label="Notifications">
          {items.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => void open(item)}
                className="flex w-full items-start gap-3 px-4 py-3 text-left transition hover:bg-surface-50"
              >
                <span
                  aria-hidden
                  className={
                    "mt-1.5 h-2 w-2 shrink-0 rounded-full " + (item.read ? "bg-transparent" : "bg-purple-700")
                  }
                />
                <span className="min-w-0 flex-1">
                  <span className={"block text-sm " + (item.read ? "text-ink-700" : "font-semibold text-ink-900")}>
                    {item.read ? null : <span className="sr-only">Unread: </span>}
                    {item.title}
                  </span>
                  {item.body ? (
                    <span className="mt-0.5 line-clamp-2 block text-sm text-ink-500">{item.body}</span>
                  ) : null}
                  <span className="mt-1 block text-xs text-ink-500">{whenWords(item.createdAt)}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {items && items.length > 0 ? (
        hasMore ? (
          <ButtonRow className="mt-4">
            <button type="button" className="field-button-secondary" onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? "Loading…" : "Show older"}
            </button>
          </ButtonRow>
        ) : (
          <p className="mt-4 text-center text-xs text-ink-500">{"That's everything."}</p>
        )
      ) : null}
    </>
  );
}
