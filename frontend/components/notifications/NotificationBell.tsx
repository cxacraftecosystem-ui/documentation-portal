"use client";

import Link from "next/link";
import { Bell } from "lucide-react";

import { useUnreadNotificationCount } from "@/components/hooks/useUnreadNotifications";
import { badgeText, bellLabel, NOTIFICATIONS_HREF } from "@/lib/inAppNotifications";

export function NotificationBell({ active }: { active: boolean }) {
  const unread = useUnreadNotificationCount(true);
  const text = badgeText(unread);
  return (
    <Link
      href={NOTIFICATIONS_HREF}
      aria-label={bellLabel(unread)}
      title={bellLabel(unread)}
      aria-current={active ? "page" : undefined}
      className={
        "relative inline-flex h-9 w-9 items-center justify-center rounded-full border border-line-200 bg-card transition hover:bg-surface-50 pointer-coarse:h-11 pointer-coarse:w-11 " +
        (active ? "text-purple-700" : "text-ink-900")
      }
    >
      <Bell className="h-4 w-4" aria-hidden />
      {text ? (
        <span
          aria-hidden
          className="absolute -right-1 -top-1 inline-flex min-w-[1.125rem] items-center justify-center rounded-full bg-purple-700 px-1 text-[10px] font-semibold leading-[1.125rem] text-white ring-2 ring-card"
        >
          {text}
        </span>
      ) : null}
    </Link>
  );
}
