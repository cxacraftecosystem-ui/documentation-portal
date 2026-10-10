import { apiFetch } from "@/lib/api";

/**
 * E-mail: whether this deployment sends any, and the signed-in person's opt-out.
 *
 * `available` is read before ANY e-mail control is drawn — the Settings switch, "E-mail them" on the
 * access roster. When it is false the controls are simply absent and nothing on screen mentions e-mail.
 *
 * Kept apart from `lib/preferences.ts`: appearance preferences are sent whole on every save, and a
 * client that knew nothing about e-mail must not switch somebody's e-mails back on by saving a theme.
 */
export type NotificationPreferences = {
  available: boolean;
  /** E-mail me when a task of mine is handed in for my approval, approved, or sent back. */
  emailTaskUpdates: boolean;
};

export const NO_EMAIL: NotificationPreferences = { available: false, emailTaskUpdates: true };

export async function fetchNotificationPreferences(): Promise<NotificationPreferences> {
  try {
    const answer = await apiFetch<Partial<NotificationPreferences>>("/preferences/notifications");
    return { available: answer?.available === true, emailTaskUpdates: answer?.emailTaskUpdates !== false };
  } catch {
    // An older API or a failed read: draw no e-mail control rather than one that cannot work.
    return { ...NO_EMAIL };
  }
}

export async function saveNotificationPreferences(
  value: Pick<NotificationPreferences, "emailTaskUpdates">
): Promise<NotificationPreferences> {
  const answer = await apiFetch<NotificationPreferences>("/preferences/notifications", {
    method: "PUT",
    body: JSON.stringify(value)
  });
  return { available: answer.available === true, emailTaskUpdates: answer.emailTaskUpdates !== false };
}
