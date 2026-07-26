import type { PageResult } from "@/lib/types";

/**
 * Next.js INLINES `process.env.NEXT_PUBLIC_API_URL` into the bundle at build time, so a build that
 * cannot see the variable does not fail — it substitutes `undefined` and ships a site that can
 * reach nothing while every signal stays green. That is not hypothetical: Vercel refuses to hand
 * env vars marked "sensitive" to `vercel pull`, a CI build inlined nothing, and a user discovered
 * the site could not log anyone in.
 *
 * A variable that exists but is BLANK is the worse half of the same bug: `??` does not treat `""`
 * as absent, so the base would stay `""`, every request would resolve against the Vercel origin
 * itself, and the app would parse Vercel's own 404 HTML as an API answer. Missing, empty and
 * whitespace all mean one thing — nobody told this bundle where the backend is — so they collapse
 * into one case here.
 */
const configuredBase = (process.env.NEXT_PUBLIC_API_URL ?? "").trim();

/** A checkout with no env file at all is the normal state of a developer's laptop; keep it working. */
const LOCAL_DEV_BASE = "http://localhost:8000";

export const API_BASE = configuredBase || LOCAL_DEV_BASE;

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "0.0.0.0", "[::1]"]);

/**
 * Does the base we ended up with only exist on the machine running the browser — whether by falling
 * back above or because someone deployed with a localhost URL? Resolved once at module load, since
 * it cannot change without a rebuild, which keeps the per-request check to two comparisons. A base
 * that does not parse is deliberately left alone: inventing new failure modes in the hot path is
 * not what this guard is for.
 */
const baseIsLoopback = ((): boolean => {
  try {
    const { hostname } = new URL(API_BASE);
    return LOOPBACK_HOSTS.has(hostname) || hostname.endsWith(".localhost");
  } catch {
    return false;
  }
})();

export class ApiError extends Error {
  status: number;
  payload: unknown;

  constructor(status: number, message: string, payload: unknown) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

/**
 * Raised instead of a request that could not possibly have worked: a deployed page whose only known
 * API address is the visitor's own machine.
 *
 * It extends {@link ApiError} for two concrete reasons. Every screen already renders an ApiError's
 * `message`, so the sentence below lands in the UI the researcher is looking at. And `lib/offline.ts`
 * treats a NON-ApiError as "the network is down" and quietly banks the save in the outbox — the same
 * defect all over again, a failure reported as success. The 503 is not a response anyone received;
 * no request was made. It is the code the rest of the app already reads as "the service is not
 * answering right now", which is the behaviour we want: queued work stays queued and drains by
 * itself once an administrator redeploys, while the person in front of the screen is still told.
 */
export class ApiUnconfiguredError extends ApiError {
  constructor() {
    super(
      503,
      "This site was published without the address of its data service, so it cannot sign you in or load any records. " +
        "Refreshing or signing in again will not help — an administrator needs to redeploy the site with its API address configured.",
      null
    );
    this.name = "ApiUnconfiguredError";
  }
}

let unconfiguredLogged = false;

/**
 * Refuse the request when this build has no usable API address. Exported so the handful of call
 * sites that build their own `fetch` from {@link API_BASE} (downloads, media object fetches) can
 * fail the same way rather than with a bare "Failed to fetch".
 *
 * Two conditions keep it honest. It never runs outside a browser: this module is imported by
 * `next build` and by SSR, and throwing there would break the build instead of the request — the
 * silence we are trying to end. And it only fires on an https:// page, which can only be a real
 * deployment; a developer on http://localhost is looking at the correct address, and a laptop
 * serving the dev site over the LAN genuinely can reach its own localhost API.
 */
export function assertApiConfigured(): void {
  if (typeof window === "undefined") return;
  if (!baseIsLoopback) return;
  if (window.location.protocol !== "https:") return;

  if (!unconfiguredLogged) {
    unconfiguredLogged = true;
    // The sentence above is for the researcher; this line is for whoever they forward the screenshot
    // to, and names the variable and the fix that the user-facing wording deliberately does not.
    console.error(
      `[api] NEXT_PUBLIC_API_URL was missing or blank when this bundle was built, so requests would go to ${API_BASE}, ` +
        "which no visitor can reach. Set it in the deployment's environment variables and redeploy — changing it in the " +
        "dashboard alone does nothing, because the value is baked into the shipped JavaScript."
    );
  }

  throw new ApiUnconfiguredError();
}

/**
 * The sentence a failed response is actually carrying, dug out of whatever shape `detail` took.
 *
 * A route's `detail` is not always a string. FastAPI's own 422 makes it a LIST of per-field error
 * objects, and a few routes raise a structured object instead (the artisan identity conflict carries
 * the clashing artisan along with the message, so the form can offer to open it). Both used to go
 * through `String(detail)`, which yields the literal "[object Object]" — and since every screen
 * renders `ApiError.message` and nothing else, that string was the entire answer a researcher got
 * from a rejected save on every form but the two that had privately re-parsed the response body for
 * themselves. Unpacking it once, here, is what gives the rest of them a real sentence.
 *
 * Exported because those private copies (`components/forms/ArtisanForm`, `components/review/
 * reviewErrors`) exist only to work around this and should eventually just call it.
 */
export function describeApiDetail(detail: unknown, fallback: string): string {
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const messages = detail
      .map((entry) => {
        if (!entry || typeof entry !== "object") return "";
        const record = entry as { msg?: unknown; loc?: unknown };
        const message = "msg" in record ? String(record.msg) : "";
        if (!message) return "";
        // `loc` is ["body", "<field>"] — naming the field is the whole point of showing a 422 raised
        // by a form with a dozen boxes in it. A bare ["body"] names nothing, so it is left off.
        const field = Array.isArray(record.loc)
          ? record.loc.filter((part): part is string => typeof part === "string").at(-1)
          : null;
        // Pydantic prefixes every custom validator message with "Value error, "; the researcher only
        // needs the sentence after it.
        const cleaned = message.replace(/^Value error,\s*/, "").trim();
        return field && field !== "body" ? `${field}: ${cleaned}` : cleaned;
      })
      .filter(Boolean);
    if (messages.length) return messages.join(" ");
  }
  if (detail && typeof detail === "object" && "message" in detail) {
    const message = String((detail as { message: unknown }).message);
    if (message.trim()) return message;
  }
  return fallback;
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem("field_repo_token");
}

export function setToken(token: string | null) {
  if (typeof window === "undefined") return;
  if (token) window.localStorage.setItem("field_repo_token", token);
  else window.localStorage.removeItem("field_repo_token");
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  assertApiConfigured();

  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE}/api${path}`, {
    ...init,
    headers,
    cache: "no-store"
  });

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json") ? await response.json() : await response.text();

  if (!response.ok) {
    const detail = typeof body === "object" && body && "detail" in body ? (body as { detail: unknown }).detail : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it cannot be the last
    // resort on its own, or a body-less failure reaches the screen as a blank error box.
    const message = describeApiDetail(detail, response.statusText || `The server refused the request (HTTP ${response.status}).`);
    if (response.status === 401 && token && typeof window !== "undefined") {
      // A previously-valid session expired: drop the stored token and send the user to login
      // (unless they are already there). Anonymous requests — e.g. the landing page's /me probe —
      // sent no token, so they fail without navigating the visitor away from public pages.
      setToken(null);
      if (window.location.pathname !== "/login") window.location.assign("/login");
    }
    throw new ApiError(response.status, message, body);
  }

  return body as T;
}

export function buildQuery(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

export async function listResource<T>(path: string, params: Record<string, string | number | undefined | null>) {
  return apiFetch<PageResult<T>>(`${path}${buildQuery(params)}`);
}
