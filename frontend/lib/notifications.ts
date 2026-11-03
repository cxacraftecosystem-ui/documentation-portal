import { apiFetch } from "@/lib/api";

export type NotificationPreferences = {
  available: boolean;
  emailTaskUpdates: boolean;
};

export const NO_EMAIL: NotificationPreferences = { available: false, emailTaskUpdates: true };

export async function fetchNotificationPreferences(): Promise<NotificationPreferences> {
  try {
    const answer = await apiFetch<Partial<NotificationPreferences>>("/preferences/notifications");
    return { available: answer?.available === true, emailTaskUpdates: answer?.emailTaskUpdates !== false };
  } catch {
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
