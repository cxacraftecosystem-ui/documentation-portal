import { apiFetch } from "@/lib/api";
import { safeLink } from "@/lib/inAppNotifications";

export type DigestCoverage = {
  label: string;
  value: number;
  of: number | null;
  percent: number | null;
  scope?: string | null;
};

export type DigestSummary = {
  id: string;
  weekStart: string;
  waiting: number;
  overdue: number;
  newThisWeek: number;
  coverage: DigestCoverage | null;
};

export type DigestPreference = {
  eligible: boolean;
  enabled: boolean;
  emailAvailable: boolean;
  schedule: string;
  latest: DigestSummary | null;
};

export type DigestLine = { key: string; label: string; count: number; link: string | null };
export type DigestItem = { title: string; link: string | null; since?: string | null };
export type DigestSection = {
  key: "waiting" | "overdue" | "new" | string;
  label: string;
  total: number;
  lines: DigestLine[];
  items: DigestItem[];
};

export type WeeklyDigest = {
  id: string;
  weekStart: string;
  generatedAt: string | null;
  sections: DigestSection[];
  coverage: DigestCoverage | null;
};

export const DIGEST_PATH = "/notifications/digest";
export const digestPath = (id: string) => `/notifications/digests/${encodeURIComponent(id)}`;
export const digestHref = (id: string) => `/digest/${encodeURIComponent(id)}`;

export const NOT_ELIGIBLE: DigestPreference = {
  eligible: false,
  enabled: false,
  emailAvailable: false,
  schedule: "Mondays, 08:00 IST",
  latest: null
};

function normalise(answer: Partial<DigestPreference> | null | undefined): DigestPreference {
  if (!answer || answer.eligible !== true) {
    return { ...NOT_ELIGIBLE, emailAvailable: answer?.emailAvailable === true };
  }
  return {
    eligible: true,
    enabled: answer.enabled !== false,
    emailAvailable: answer.emailAvailable === true,
    schedule: answer.schedule || NOT_ELIGIBLE.schedule,
    latest: answer.latest ?? null
  };
}

export async function fetchDigestPreference(): Promise<DigestPreference> {
  try {
    return normalise(await apiFetch<Partial<DigestPreference>>(DIGEST_PATH));
  } catch {
    return { ...NOT_ELIGIBLE };
  }
}

export async function saveDigestPreference(enabled: boolean): Promise<DigestPreference> {
  return normalise(
    await apiFetch<DigestPreference>(DIGEST_PATH, { method: "PUT", body: JSON.stringify({ enabled }) })
  );
}

export async function fetchDigest(id: string): Promise<WeeklyDigest> {
  return apiFetch<WeeklyDigest>(digestPath(id));
}

export const DIGEST_LABEL = "Weekly summary";
export const DIGEST_SWITCH = "Send it to me";
export const DIGEST_DESCRIPTION = "Mondays at 08:00: what waits for you, what is overdue, what came in.";

export function digestCardView(state: DigestPreference | null): {
  description: string;
  channel: string;
  latestHref: string | null;
} | null {
  if (!state?.eligible) return null;
  return {
    description: DIGEST_DESCRIPTION,
    channel: state.emailAvailable ? "By e-mail and here." : "Here, in your notifications.",
    latestHref: state.latest ? digestHref(state.latest.id) : null
  };
}

export async function toggleDigest(
  before: DigestPreference,
  next: boolean,
  save: (enabled: boolean) => Promise<DigestPreference>,
  show: (state: DigestPreference) => void
): Promise<{ state: DigestPreference; error: string | null }> {
  show({ ...before, enabled: next });
  try {
    const saved = await save(next);
    show(saved);
    return { state: saved, error: null };
  } catch (err) {
    show(before);
    return { state: before, error: err instanceof Error && err.message ? err.message : "Couldn't save. Try again." };
  }
}

export function weekWords(weekStart: string): string {
  const [year, month, day] = weekStart.split("-").map(Number);
  if (!year || !month || !day) return weekStart;
  return new Date(year, month - 1, day).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

export function coverageFigure(coverage: DigestCoverage | null | undefined): string {
  if (!coverage) return "0";
  if (coverage.percent !== null && coverage.percent !== undefined) return `${coverage.percent}%`;
  return String(coverage.value ?? 0);
}

export type DigestTile = { key: string; label: string; figure: string };

export function digestTiles(digest: Pick<WeeklyDigest, "sections" | "coverage">): DigestTile[] {
  const total = (key: string) => digest.sections.find((section) => section.key === key)?.total ?? 0;
  return [
    { key: "waiting", label: "Waiting for you", figure: String(total("waiting")) },
    { key: "overdue", label: "Overdue", figure: String(total("overdue")) },
    { key: "new", label: "New this week", figure: String(total("new")) },
    { key: "coverage", label: digest.coverage?.label || "Coverage", figure: coverageFigure(digest.coverage) }
  ];
}

export function visibleLines(section: DigestSection): DigestLine[] {
  return (section.lines ?? [])
    .filter((line) => line.count > 0)
    .map((line) => ({ ...line, link: safeLink(line.link) }));
}

export function visibleItems(section: DigestSection): DigestItem[] {
  return (section.items ?? []).slice(0, 5).map((item) => ({ ...item, link: safeLink(item.link) }));
}
