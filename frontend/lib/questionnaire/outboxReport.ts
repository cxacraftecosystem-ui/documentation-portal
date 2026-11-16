import { qs } from "@/lib/questionnaire/qs";
import type { ClipStep, SessionUnit } from "@/lib/questionnaire/queue";

export type OutboxSummary = { pending: number; failed: number; oldest: string | null };

export const INSTALL_ID_KEY = "field_repo_install_id";
export const OUTBOX_SUMMARY_KEY = "field_repo_outbox_summary";

const EMPTY: OutboxSummary = { pending: 0, failed: 0, oldest: null };

type Store = Pick<Storage, "getItem" | "setItem">;

function browserStore(): Store | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

function mintInstallId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

let memoryInstallId: string | null = null;

export function installId(store: Store | null = browserStore()): string {
  try {
    const held = store?.getItem(INSTALL_ID_KEY);
    if (held && /^[A-Za-z0-9-]{8,64}$/.test(held)) return held;
    const fresh = memoryInstallId ?? mintInstallId();
    store?.setItem(INSTALL_ID_KEY, fresh);
    memoryInstallId = fresh;
    return fresh;
  } catch {
    memoryInstallId ??= mintInstallId();
    return memoryInstallId;
  }
}

function earlier(a: string | null, b: string | null): string | null {
  if (!a) return b;
  if (!b) return a;
  return Date.parse(b) < Date.parse(a) ? b : a;
}

export function outboxSummary(units: SessionUnit[]): OutboxSummary {
  let pending = 0;
  let failed = 0;
  let oldest: string | null = null;
  for (const unit of units) {
    for (const step of unit.steps) {
      if (step.kind !== "clip" || step.status === "DONE") continue;
      const clip = step as ClipStep;
      if (clip.error === "MISSING_FILE") continue;
      pending += 1;
      if (clip.status === "FAILED") failed += 1;
      const at = clip.recording.deviceCapturedAt || unit.createdAt || null;
      if (at && !Number.isNaN(Date.parse(at))) oldest = earlier(oldest, new Date(Date.parse(at)).toISOString());
    }
  }
  return { pending, failed, oldest: pending ? oldest : null };
}

export function outboxHeaderValues(summary: OutboxSummary, install: string): Record<string, string> {
  const out: Record<string, string> = {
    "X-Install-Id": install,
    "X-Outbox-Pending": String(Math.max(0, Math.floor(summary.pending))),
    "X-Outbox-Failed": String(Math.max(0, Math.floor(summary.failed)))
  };
  if (summary.pending > 0 && summary.oldest) out["X-Outbox-Oldest"] = summary.oldest;
  return out;
}

let current: OutboxSummary | null = null;
let primed = false;

function persisted(store: Store | null): OutboxSummary | null {
  try {
    const raw = store?.getItem(OUTBOX_SUMMARY_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<OutboxSummary>;
    if (typeof value.pending !== "number" || typeof value.failed !== "number") return null;
    return { pending: value.pending, failed: value.failed, oldest: typeof value.oldest === "string" ? value.oldest : null };
  } catch {
    return null;
  }
}

export function noteOutbox(units: SessionUnit[]): OutboxSummary {
  current = outboxSummary(units);
  try {
    browserStore()?.setItem(OUTBOX_SUMMARY_KEY, JSON.stringify(current));
  } catch {
    return current;
  }
  return current;
}

async function prime(): Promise<void> {
  try {
    const { indexedDbQueueStore } = await import("@/lib/questionnaire/queue");
    if (typeof indexedDB === "undefined") return;
    noteOutbox(await indexedDbQueueStore().load());
  } catch {
    return;
  }
}

export function outboxHeaders(): Record<string, string> {
  if (typeof window === "undefined") return {};
  if (!primed) {
    primed = true;
    void prime();
  }
  return outboxHeaderValues(current ?? persisted(browserStore()) ?? EMPTY, installId());
}

export type HoldingInstall = {
  userId: string;
  name: string;
  email: string;
  installId: string;
  platform: string;
  appVersion: string | null;
  pending: number;
  failed: number;
  oldestAt: string | null;
  reportedAt: string | null;
};

export type HoldingList = { items: HoldingInstall[]; capped: boolean; limit: number };

export function waitedLabel(since: string | null, now: number = Date.now()): string {
  const at = since ? Date.parse(since) : NaN;
  if (Number.isNaN(at)) return "";
  const minutes = Math.max(0, Math.floor((now - at) / 60_000));
  if (minutes < 60) return qs("ageMinutes", { count: minutes });
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return qs("ageHours", { count: hours });
  return qs("ageDays", { count: Math.floor(hours / 24) });
}

export function deviceLabel(install: Pick<HoldingInstall, "platform" | "appVersion">): string {
  const platform = install.platform ? install.platform.charAt(0).toUpperCase() + install.platform.slice(1) : "";
  return [platform, install.appVersion ?? ""].filter(Boolean).join(" · ");
}
