import { apiFetch } from "@/lib/api";
import type { SavedView } from "@/lib/dataExplorer/contract";

export const DEFAULT_TIMEZONE = "Asia/Kolkata";
export const DEFAULT_WEEKDAY = 0;
export const DEFAULT_HOUR = 8;

export const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"] as const;

export type ScheduleFile = { fileName: string; sizeBytes: number; attached: boolean };

export type ReportSchedule = {
  id: string;
  savedViewId: string;
  viewName: string | null;
  path: string | null;
  weekday: number;
  hourLocal: number;
  timezone: string;
  label: string;
  options: Record<string, unknown>;
  paused: boolean;
  nextRunAt: string | null;
  lastSentAt: string | null;
  lastError: string | null;
  lastFile: ScheduleFile | null;
  createdAt: string | null;
};

export type ReportScheduleList = {
  items: ReportSchedule[];
  enabled: boolean;
  mailAvailable: boolean;
  maxPerOwner: number;
  attachMaxBytes: number;
  defaultTimezone: string;
};

export type ScheduleDraft = {
  savedViewId: string;
  weekday: number;
  hourLocal: number;
  timezone?: string;
  options?: Record<string, unknown>;
};

export type ScheduleChange = Partial<Pick<ReportSchedule, "weekday" | "hourLocal" | "timezone" | "paused">> & {
  options?: Record<string, unknown>;
};

export type UnsubscribeInfo = { viewName: string | null; label: string; paused: boolean };

export type ScheduleFileLink = { url: string; fileName: string; sizeBytes: number; sentAt: string | null; viewName: string | null };

const BASE = "/data/report/schedules";

export function fetchSchedules(): Promise<ReportScheduleList> {
  return apiFetch<ReportScheduleList>(BASE);
}

export function createSchedule(draft: ScheduleDraft): Promise<ReportSchedule> {
  return apiFetch<ReportSchedule>(BASE, { method: "POST", body: JSON.stringify(draft) });
}

export function updateSchedule(id: string, change: ScheduleChange): Promise<ReportSchedule> {
  return apiFetch<ReportSchedule>(`${BASE}/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify(change) });
}

export function deleteSchedule(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export function fetchScheduleFile(id: string): Promise<ScheduleFileLink> {
  return apiFetch<ScheduleFileLink>(`${BASE}/${encodeURIComponent(id)}/file`);
}

export function lookupUnsubscribe(token: string): Promise<UnsubscribeInfo> {
  return apiFetch<UnsubscribeInfo>("/report-schedule/unsubscribe/lookup", { method: "POST", body: JSON.stringify({ token }) });
}

export function confirmUnsubscribe(token: string): Promise<UnsubscribeInfo> {
  return apiFetch<UnsubscribeInfo>("/report-schedule/unsubscribe", { method: "POST", body: JSON.stringify({ token }) });
}

export function hourLabel(hour: number): string {
  const clean = Math.min(Math.max(Math.trunc(hour), 0), 23);
  return `${String(clean).padStart(2, "0")}:00`;
}

export function zoneLabel(timezone: string | null | undefined): string {
  const zone = (timezone || DEFAULT_TIMEZONE).trim();
  return zone === DEFAULT_TIMEZONE ? "IST" : zone;
}

export function scheduleLabel(weekday: number, hour: number, timezone?: string | null): string {
  const day = WEEKDAYS[((Math.trunc(weekday) % 7) + 7) % 7];
  return `Every ${day} at ${hourLabel(hour)} ${zoneLabel(timezone)}`;
}

export const WEEKDAY_OPTIONS = WEEKDAYS.map((day, index) => ({ value: String(index), label: day }));

export const HOUR_OPTIONS = Array.from({ length: 24 }, (_, hour) => ({ value: String(hour), label: hourLabel(hour) }));

export function viewOptions(views: readonly SavedView[]): { value: string; label: string; hint?: string }[] {
  const seen = new Set<string>();
  const options: { value: string; label: string; hint?: string }[] = [];
  for (const view of views) {
    if (seen.has(view.id)) continue;
    seen.add(view.id);
    const hint = view.mine ? undefined : view.ownerName ? `Shared by ${view.ownerName}` : "Shared";
    options.push(hint ? { value: view.id, label: view.name, hint } : { value: view.id, label: view.name });
  }
  return options;
}

export function browserTimezone(): string {
  try {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (zone === "Asia/Calcutta") return DEFAULT_TIMEZONE;
    return zone && zone.includes("/") ? zone : DEFAULT_TIMEZONE;
  } catch {
    return DEFAULT_TIMEZONE;
  }
}

const DAY_MS = 24 * 60 * 60 * 1000;

export function nextRunWords(nextRunAt: string | null, now: number = Date.now()): string | null {
  if (!nextRunAt) return null;
  const at = Date.parse(nextRunAt);
  if (!Number.isFinite(at)) return null;
  const ahead = at - now;
  if (ahead <= 60 * 1000) return "Sending soon";
  if (ahead < DAY_MS) {
    const hours = Math.max(1, Math.round(ahead / (60 * 60 * 1000)));
    return `Next in ${hours} hour${hours === 1 ? "" : "s"}`;
  }
  const days = Math.round(ahead / DAY_MS);
  return `Next in ${days} day${days === 1 ? "" : "s"}`;
}

export function sizeWords(bytes: number): string {
  const size = Math.max(0, Math.trunc(bytes || 0));
  if (size >= 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  if (size >= 1024) return `${Math.round(size / 1024)} KB`;
  return `${size} bytes`;
}

export function sentWords(lastSentAt: string | null, now: number = Date.now()): string | null {
  if (!lastSentAt) return null;
  const at = Date.parse(lastSentAt);
  if (!Number.isFinite(at)) return null;
  const days = Math.floor((now - at) / DAY_MS);
  if (days <= 0) return "Sent today";
  if (days === 1) return "Sent yesterday";
  return `Sent ${days} days ago`;
}

export type ScheduleStatus = { text: string; tone: "info" | "warn" | "done" };

export function scheduleStatus(schedule: ReportSchedule, now: number = Date.now()): ScheduleStatus {
  if (schedule.paused) return { text: "Paused", tone: "info" };
  if (schedule.lastError) return { text: schedule.lastError, tone: "warn" };
  const sent = sentWords(schedule.lastSentAt, now);
  const next = nextRunWords(schedule.nextRunAt, now);
  if (sent && schedule.lastFile) {
    const how = schedule.lastFile.attached ? "attached" : "as a link";
    return { text: [`${sent}, ${how} (${sizeWords(schedule.lastFile.sizeBytes)})`, next].filter(Boolean).join(" · "), tone: "done" };
  }
  return { text: next ?? "Scheduled", tone: "info" };
}

export function canAddSchedule(list: Pick<ReportScheduleList, "items" | "maxPerOwner"> | null): boolean {
  return list !== null && list.items.length < list.maxPerOwner;
}

export function scheduleFilePath(id: string): string {
  return `/data/report-schedules/${encodeURIComponent(id)}`;
}
