export type ExplorerView = "files" | "tables";

export type ExplorerUrlState = {
  path: string;
  view: ExplorerView;
  sheet: string;
  q: string;
  browse: string;
  browsePage: number;
  browseQ: string;
};

export const DEFAULT_URL_STATE: ExplorerUrlState = {
  path: "",
  view: "files",
  sheet: "",
  q: "",
  browse: "",
  browsePage: 1,
  browseQ: ""
};

const KEYS: Record<keyof ExplorerUrlState, string> = {
  path: "path",
  view: "view",
  sheet: "sheet",
  q: "q",
  browse: "browse",
  browsePage: "page",
  browseQ: "find"
};

type ParamsLike = { get(name: string): string | null };

export function readUrlState(params: ParamsLike | null | undefined): ExplorerUrlState {
  if (!params) return { ...DEFAULT_URL_STATE };
  const page = Number(params.get(KEYS.browsePage) ?? "1");
  const view = params.get(KEYS.view);
  return {
    path: (params.get(KEYS.path) ?? "").replace(/^\/+|\/+$/g, ""),
    view: view === "tables" ? "tables" : "files",
    sheet: params.get(KEYS.sheet) ?? "",
    q: params.get(KEYS.q) ?? "",
    browse: params.get(KEYS.browse) ?? "",
    browsePage: Number.isInteger(page) && page > 0 ? page : 1,
    browseQ: params.get(KEYS.browseQ) ?? ""
  };
}

export function writeUrlState(state: ExplorerUrlState, base: ParamsLike & { toString(): string } = new URLSearchParams()): string {
  const params = new URLSearchParams(base.toString());
  for (const key of Object.keys(KEYS) as (keyof ExplorerUrlState)[]) {
    const name = KEYS[key];
    const value = state[key];
    const fallback = DEFAULT_URL_STATE[key];
    if (value === fallback || value === "") params.delete(name);
    else params.set(name, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

const SCROLL_PREFIX = "data-explorer-scroll:";

export function scrollKey(search: string): string {
  return SCROLL_PREFIX + (search || "?");
}

export function rememberScroll(search: string, y: number): void {
  try {
    window.sessionStorage.setItem(scrollKey(search), String(Math.max(0, Math.round(y))));
  } catch {
    return;
  }
}

export function recallScroll(search: string): number | null {
  try {
    const raw = window.sessionStorage.getItem(scrollKey(search));
    if (raw === null) return null;
    const y = Number(raw);
    return Number.isFinite(y) ? y : null;
  } catch {
    return null;
  }
}

export function ancestorsOf(path: string): string[] {
  const parts = path.split("/").filter(Boolean);
  const out = [""];
  for (let i = 1; i <= parts.length; i += 1) out.push(parts.slice(0, i).join("/"));
  return out;
}
