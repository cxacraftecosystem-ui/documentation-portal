import { test as base, expect, type Page } from "@playwright/test";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";

export { expect };

export const API = (process.env.GATE_API ?? "").replace(/\/$/, "");
export const WEB = (process.env.GATE_WEB ?? "").replace(/\/$/, "");
const EMAIL = process.env.GATE_EMAIL ?? "";
const PASSWORD = process.env.GATE_PASSWORD ?? "";
const OUT = process.env.GATE_OUT ?? resolve(process.cwd(), "..", "release-gate-out");
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export const READ_ONLY_POSTS = new Set(
  readFileSync(resolve(process.cwd(), "..", "scripts", "release_gate", "read_only_posts.txt"), "utf8")
    .split(/\r?\n/)
    .map((line) => line.trim().split(/\s+/)[0])
    .filter(Boolean)
);

export function allowed(method: string, url: string): boolean {
  const verb = method.toUpperCase();
  if (SAFE_METHODS.has(verb)) return true;
  const target = new URL(url);
  const apiOrigin = API ? new URL(API).origin : "";
  const touchesApi = target.origin === apiOrigin || target.pathname.startsWith("/api/");
  if (!touchesApi) return true;
  return verb === "POST" && READ_ONLY_POSTS.has(target.pathname);
}

export type Row = { item: string; expected: unknown; actual: unknown; ok: boolean };

export type Gate = {
  token: string;
  dir: string;
  shot: (name: string, page?: Page) => Promise<string>;
  count: (values: Record<string, unknown>) => void;
  expectRow: (item: string, expected: unknown, actual: unknown, ok?: boolean) => boolean;
  get: (path: string) => Promise<unknown>;
};

function slug(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

async function signIn(page: Page): Promise<string> {
  expect(EMAIL && PASSWORD, "GATE_EMAIL and GATE_PASSWORD are set").toBeTruthy();
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.status(), "sign-in answered").toBe(200);
  const body = await res.json();
  const token = (body.accessToken ?? body.access_token ?? "") as string;
  expect(token, "sign-in returned a token").not.toBe("");
  const web = new URL(WEB || "http://localhost:3000");
  const secure = web.protocol === "https:";
  const common = { url: web.origin, secure, sameSite: "Lax" as const };
  await page.context().addCookies([
    { name: secure ? "__Host-session" : "session", value: token, httpOnly: true, ...common },
    { name: secure ? "__Host-session-marker" : "session-marker", value: `gate-${Date.now().toString(36)}`, httpOnly: false, ...common }
  ]);
  return token;
}

export const test = base.extend<{ gate: Gate }>({
  gate: async ({ page }, use, testInfo) => {
    const check = `web-${slug(basename(testInfo.file, ".spec.ts"))}-${slug(testInfo.title)}`;
    const dir = join(OUT, check);
    mkdirSync(dir, { recursive: true });
    const blocked: string[] = [];
    const counts: Record<string, unknown> = {};
    const rows: Row[] = [];
    await page.context().route("**/*", async (route) => {
      const request = route.request();
      if (allowed(request.method(), request.url())) return route.fallback();
      blocked.push(`${request.method()} ${new URL(request.url()).pathname}`);
      return route.abort("blockedbyclient");
    });
    const token = await signIn(page);
    const gate: Gate = {
      token,
      dir,
      shot: async (name, target = page) => {
        const path = join(dir, `${slug(name)}.png`);
        await target.screenshot({ path, fullPage: true });
        return path;
      },
      count: (values) => Object.assign(counts, values),
      expectRow: (item, expected, actual, ok) => {
        const passed = ok ?? JSON.stringify(expected) === JSON.stringify(actual);
        rows.push({ item, expected, actual, ok: passed });
        return passed;
      },
      get: async (path) => {
        const res = await page.request.get(`${API}${path}`, { headers: { Authorization: `Bearer ${token}` } });
        expect(res.status(), `GET ${path}`).toBe(200);
        return res.json();
      }
    };
    let error = "";
    try {
      await use(gate);
    } catch (thrown) {
      error = thrown instanceof Error ? thrown.message : String(thrown);
      throw thrown;
    } finally {
      if (testInfo.status !== testInfo.expectedStatus && !error) error = testInfo.error?.message ?? "the test failed";
      const ok = !error && blocked.length === 0 && rows.every((row) => row.ok);
      writeFileSync(
        join(dir, "result.json"),
        `${JSON.stringify({ check, kind: "web", ok, error, blocked, counts, rows }, null, 2)}\n`
      );
    }
    expect(blocked, "writes the read-only gate blocked").toEqual([]);
    expect(rows.filter((row) => !row.ok), "rows whose actual differs from expected").toEqual([]);
  }
});
