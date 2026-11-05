import { apiFetch } from "@/lib/api";
import { formatBytes } from "@/lib/dataExplorer/plan";

export type SharePortal = "designer" | "fieldRepository";

export const SHARE_PRODUCT_NAMES: Record<SharePortal, string> = {
  designer: "Design Workshop",
  fieldRepository: "Field Repository"
};

export const SHARE_DAYS = [1, 7, 14, 30] as const;
export const SHARE_DAYS_DEFAULT = 7;

export type ShareKpi = {
  key: string;
  label: string;
  value: number | null;
  unit: "count" | "bytes" | "percent";
  sub: string | null;
};

export type ShareSeries = { key?: string; name: string; values: number[] };

export type ShareChart = {
  key: string;
  title: string;
  kind: "bar" | "column" | "stackedBar" | "line";
  categories: string[];
  series: ShareSeries[];
  unit?: "count" | "percent";
  earlierOmitted?: number;
  sectionsOmitted?: number;
  period?: "day" | "week";
};

export type ShareLocale = "en" | "hi";

export const SHARE_LABELS_EN = {
  eyebrow: "{product} · Live report",
  sharedBy: "Shared by",
  teamMember: "a team member",
  figuresFrom: "Figures from",
  linkExpires: "Link expires",
  truncated: "Large folder: figures cover the first 5,000 of each kind.",
  keyFigures: "Key figures",
  showAsTable: "Show as table",
  date: "Date",
  category: "Category",
  legend: "Legend",
  nothingYet: "Nothing to show yet.",
  earlierOmitted: "Showing the latest 52 weeks; {n} earlier not shown.",
  sectionsOmitted: "{n} more sections not shown.",
  weekOf: "week of {date}",
  periods: "{title}, {n} periods",
  withheldOne: "{n} recording is left out for consent.",
  withheldMany: "{n} recordings are left out for consent.",
  readOnly: "Read-only. Figures were fixed when the link was made.",
  "filter.status": "Review status",
  opening: "Opening the report…",
  expired: "This link has expired or was turned off.",
  failed: "Couldn't open this report. Check your connection and try again.",
  incomplete: "This link is incomplete. Open the whole link.",
  tryAgain: "Try again"
} as const;

export type ShareLabelKey = keyof typeof SHARE_LABELS_EN;
export type ShareLabels = Record<ShareLabelKey, string>;

export const SHARE_LABELS_HI: ShareLabels = {
  eyebrow: "{product} · लाइव रिपोर्ट",
  sharedBy: "द्वारा साझा",
  teamMember: "एक दल सदस्य",
  figuresFrom: "आँकड़े दिनांक",
  linkExpires: "लिंक की समाप्ति",
  truncated: "बड़ा फ़ोल्डर: आँकड़े प्रत्येक प्रकार के प्रथम 5,000 अभिलेखों के हैं।",
  keyFigures: "मुख्य आँकड़े",
  showAsTable: "तालिका के रूप में देखें",
  date: "तिथि",
  category: "श्रेणी",
  legend: "संकेत-सूची",
  nothingYet: "अभी दर्शाने हेतु कुछ नहीं है।",
  earlierOmitted: "नवीनतम 52 सप्ताह दर्शाए गए हैं; इससे पहले के {n} नहीं दर्शाए गए।",
  sectionsOmitted: "{n} और खंड नहीं दर्शाए गए।",
  weekOf: "{date} का सप्ताह",
  periods: "{title}, {n} अवधियाँ",
  withheldOne: "सहमति के कारण {n} रिकॉर्डिंग छोड़ी गई है।",
  withheldMany: "सहमति के कारण {n} रिकॉर्डिंग छोड़ी गई हैं।",
  readOnly: "केवल पठन हेतु। आँकड़े लिंक बनाते समय निर्धारित किए गए थे।",
  "filter.status": "समीक्षा की स्थिति",
  opening: "रिपोर्ट खोली जा रही है…",
  expired: "इस लिंक की अवधि समाप्त हो गई है या इसे बंद कर दिया गया है।",
  failed: "यह रिपोर्ट नहीं खुल सकी। अपना कनेक्शन जाँचें और पुनः प्रयास करें।",
  incomplete: "यह लिंक अधूरा है। पूरा लिंक खोलें।",
  tryAgain: "पुनः प्रयास करें"
};

export function langOf(value: string | null | undefined): ShareLocale {
  return value === "hi" ? "hi" : "en";
}

export type ShareSnapshot = {
  version: number;
  portal: SharePortal;
  title: string;
  crumbs: string[];
  generatedBy: string | null;
  generatedAt: string;
  dataVersion: string;
  kpis: ShareKpi[];
  charts: ShareChart[];
  filters?: Record<string, string>;
  withheldTranscripts: number;
  truncated: boolean;
  expiresAt: string;
  sharedAt: string;
  locale?: ShareLocale;
  labels?: Partial<Record<string, string>>;
};

export type ShareState = "active" | "expired" | "revoked";

export type ReportShare = {
  id: string;
  url: string;
  path: string;
  title: string;
  options: Record<string, unknown>;
  createdAt: string;
  expiresAt: string;
  revokedAt: string | null;
  state: ShareState;
  views: number;
  lastOpenedAt: string | null;
};

export type ShareView = {
  id: string;
  openedAt: string;
  viewer: { id: string; name: string | null } | null;
  visitor: string | null;
  userAgent: string | null;
};

export type ShareViews = { items: ShareView[]; total: number; truncated: boolean };

export function productName(portal: string | null | undefined): string {
  return portal === "fieldRepository" ? SHARE_PRODUCT_NAMES.fieldRepository : SHARE_PRODUCT_NAMES.designer;
}

export function localeOf(snapshot: Pick<ShareSnapshot, "locale"> | null | undefined): ShareLocale {
  return snapshot?.locale === "hi" ? "hi" : "en";
}

export function shareLabels(snapshot: Pick<ShareSnapshot, "labels" | "locale"> | null | undefined, lang?: ShareLocale): ShareLabels {
  const locale = snapshot?.locale ?? lang ?? "en";
  const out = { ...(locale === "hi" ? SHARE_LABELS_HI : SHARE_LABELS_EN) } as ShareLabels;
  for (const key of Object.keys(SHARE_LABELS_EN) as ShareLabelKey[]) {
    const value = snapshot?.labels?.[key];
    if (typeof value === "string" && value.trim()) out[key] = value;
  }
  return out;
}

export function fill(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in values ? String(values[name]) : whole));
}

export function filterLabel(labels: ShareLabels, key: string): string {
  const known = (labels as Record<string, string>)[`filter.${key}`];
  return known ?? key;
}

export function isWeekly(chart: ShareChart): boolean {
  return chart.period === "week" || /week/i.test(chart.title);
}

export function kpiValue(kpi: ShareKpi): string {
  if (kpi.value === null || !Number.isFinite(kpi.value)) return "—";
  if (kpi.unit === "bytes") return formatBytes(kpi.value);
  if (kpi.unit === "percent") return `${Math.round(kpi.value)}%`;
  return kpi.value.toLocaleString("en-IN");
}

export function chartValue(value: number, unit: ShareChart["unit"]): string {
  const safe = Number.isFinite(value) ? value : 0;
  if (unit === "percent") return `${Math.round(safe)}%`;
  return safe.toLocaleString("en-IN", { maximumFractionDigits: 1 });
}

export function niceMax(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 1;
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((m) => m * power >= value) ?? 10;
  return step * power;
}

export function ticks(max: number): number[] {
  const top = niceMax(max);
  const count = [4, 5, 2].find((c) => Number.isInteger(top / c)) ?? 1;
  return Array.from({ length: count + 1 }, (_, i) => (top / count) * i);
}

export function seriesTotal(chart: ShareChart): number {
  return chart.series.reduce((sum, s) => sum + s.values.reduce((a, b) => a + (Number.isFinite(b) ? b : 0), 0), 0);
}

export function stackTotals(chart: ShareChart): number[] {
  return chart.categories.map((_, i) => chart.series.reduce((sum, s) => sum + (s.values[i] ?? 0), 0));
}

export function hasData(chart: ShareChart): boolean {
  return chart.categories.length > 0 && seriesTotal(chart) > 0;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const MONTHS_HI = ["जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"];

export function dayLabel(iso: string, locale: ShareLocale = "en"): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!match) return iso;
  const months = locale === "hi" ? MONTHS_HI : MONTHS;
  return `${Number(match[3])} ${months[Number(match[2]) - 1] ?? ""}`.trim();
}

export function labelEvery(count: number, room: number): number {
  if (count <= 0 || room <= 0) return 1;
  return Math.max(1, Math.ceil(count / room));
}

export function formatWhen(iso: string | null | undefined, locale: ShareLocale = "en"): string {
  if (!iso) return "";
  const moment = new Date(iso);
  if (Number.isNaN(moment.getTime())) return "";
  return moment.toLocaleString(locale === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" });
}

export function expiryLine(state: ShareState, expiresAt: string, now: number = Date.now()): string {
  if (state === "revoked") return "Turned off";
  const end = new Date(expiresAt).getTime();
  if (state === "expired" || !Number.isFinite(end) || end <= now) return "Expired";
  const hours = Math.round((end - now) / 3_600_000);
  if (hours < 24) return hours <= 1 ? "Expires within an hour" : `Expires in ${hours} hours`;
  const days = Math.round(hours / 24);
  return `Expires in ${days} ${days === 1 ? "day" : "days"}`;
}

export function daysLabel(days: number): string {
  return days === 1 ? "1 day" : `${days} days`;
}

export function viewsLabel(count: number): string {
  if (count === 0) return "Not opened yet";
  return count === 1 ? "Opened once" : `Opened ${count.toLocaleString("en-IN")} times`;
}

export function deviceOf(userAgent: string | null): string {
  const ua = userAgent ?? "";
  const browser = /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Firefox\//.test(ua) ? "Firefox" : /Safari\//.test(ua) ? "Safari" : "";
  const system = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Windows/.test(ua) ? "Windows" : /Mac OS X/.test(ua) ? "Mac" : /Linux/.test(ua) ? "Linux" : "";
  if (browser && system) return `${browser} on ${system}`;
  return browser || system || "Unknown device";
}

export function viewerLabel(view: ShareView): string {
  if (view.viewer) return view.viewer.name || "Signed-in user";
  return view.visitor ? `Visitor ${view.visitor.slice(0, 4)}` : "Visitor";
}

export function createShare(body: { path: string; options: Record<string, unknown>; filters: Record<string, string>; expiresInDays: number }): Promise<ReportShare> {
  return apiFetch<ReportShare>("/data/report/shares", { method: "POST", body: JSON.stringify(body) });
}

export async function listShares(path: string): Promise<ReportShare[]> {
  const answer = await apiFetch<{ items: ReportShare[] }>(`/data/report/shares?path=${encodeURIComponent(path)}`);
  return answer.items ?? [];
}

export function revokeShare(id: string): Promise<void> {
  return apiFetch<void>(`/data/report/shares/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export function fetchShareViews(id: string, offset = 0): Promise<ShareViews> {
  return apiFetch<ShareViews>(`/data/report/shares/${encodeURIComponent(id)}/views${offset > 0 ? `?offset=${offset}` : ""}`);
}

export function mergeShareViews(current: ShareViews, next: ShareViews): ShareViews {
  const seen = new Set(current.items.map((view) => view.id));
  const items = [...current.items, ...next.items.filter((view) => !seen.has(view.id))];
  const total = Math.max(next.total, items.length);
  return { items, total, truncated: next.truncated && items.length > current.items.length && items.length < total };
}

export function openShare(token: string): Promise<ShareSnapshot> {
  return apiFetch<ShareSnapshot>(`/report-share/${encodeURIComponent(token)}`, {}, { redirectOn401: false });
}

export function filtersOf(extra: Record<string, string | number | undefined | null> | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(extra ?? {})) {
    if (value === undefined || value === null || value === "") continue;
    out[key] = String(value);
  }
  return out;
}
