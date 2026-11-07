import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { errorCopy, errorKindOf, retryDelaySeconds, safeNext, sessionEndedHref, signInHref, type ErrorScreen } from "../lib/errorKind";

const APP = join(__dirname, "..", "app");

function read(...parts: string[]): string {
  return readFileSync(join(APP, ...parts), "utf8");
}

function apiError(status: number): Error & { status: number } {
  return Object.assign(new Error(`status ${status}`), { status });
}

test("every boundary exists, so no failure reaches Next's unthemed page", () => {
  for (const parts of [["error.tsx"], ["(protected)", "error.tsx"], ["not-found.tsx"], ["global-error.tsx"], ["session-ended", "page.tsx"]]) {
    expect(existsSync(join(APP, ...parts)), parts.join("/")).toBe(true);
  }
});

test("the segment boundaries are client components that retry through reset", () => {
  for (const parts of [["error.tsx"], ["(protected)", "error.tsx"]]) {
    const source = read(...parts);
    expect(source.startsWith('"use client";'), parts.join("/")).toBe(true);
    expect(source, parts.join("/")).toContain("onRetry={reset}");
    expect(source, parts.join("/")).toContain("errorKindOf(error)");
    expect(source, parts.join("/")).toContain("reference={error.digest}");
  }
});

test("the protected boundary draws inside the shell, so the nav and header stay", () => {
  const layout = read("(protected)", "layout.tsx");
  expect(layout).toContain("<AppShell>");
  const boundary = read("(protected)", "error.tsx");
  expect(boundary).not.toContain("DeadEndFrame");
  expect(boundary).not.toContain("<html");
  const nested: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (name === "error.tsx" && dir !== join(APP, "(protected)")) nested.push(full);
    }
  };
  walk(join(APP, "(protected)"));
  for (const file of nested) expect(readFileSync(file, "utf8"), file).not.toContain("DeadEndFrame");
});

test("the global boundary draws its own document and theme, offers Reload, and leans on no root provider", () => {
  const source = read("global-error.tsx");
  expect(source).toContain("<html");
  expect(source).toContain("<body");
  expect(source).toContain("PREFERENCES_BOOT_SCRIPT");
  expect(source).toContain('import "./globals.css";');
  for (const provider of ["AuthProvider", "useAuth", "ThemeProvider", "ToastProvider", "AdminViewProvider", "DeadEnd"]) expect(source).not.toContain(provider);
  const view = readFileSync(join(__dirname, "..", "components", "GlobalErrorView.tsx"), "utf8");
  expect(view).toContain("window.location.reload()");
  for (const provider of ["useAuth", "DeadEnd", "useRouter"]) expect(view).not.toContain(provider);
});

test("API failures sort into the right screen", () => {
  expect(errorKindOf(apiError(401), false)).toBe("session");
  expect(errorKindOf(apiError(403), false)).toBe("forbidden");
  expect(errorKindOf(apiError(404), false)).toBe("not-found");
  expect(errorKindOf(apiError(410), false)).toBe("not-found");
  for (const status of [408, 502, 503, 504]) expect(errorKindOf(apiError(status), false), String(status)).toBe("unavailable");
  for (const status of [500, 501, 409, 422]) expect(errorKindOf(apiError(status), false), String(status)).toBe("server");
  expect(errorKindOf(new TypeError("Failed to fetch"), false)).toBe("offline");
  expect(errorKindOf(new TypeError("Load failed"), false)).toBe("offline");
  expect(errorKindOf(new TypeError("Cannot read properties of undefined (reading 'name')"), false)).toBe("server");
  expect(errorKindOf(Object.assign(new Error("slow"), { name: "TimeoutError" }), false)).toBe("unavailable");
  expect(errorKindOf(new Error("anything"), true)).toBe("offline");
  expect(errorKindOf(apiError(503), true)).toBe("offline");
  expect(errorKindOf(apiError(404), true)).toBe("not-found");
  expect(errorKindOf("not an error", false)).toBe("server");
});

test("the retry countdown backs off and stops growing", () => {
  expect([0, 1, 2, 3, 9].map(retryDelaySeconds)).toEqual([10, 20, 30, 30, 30]);
});

test("sign-in returns only to a path inside the app", () => {
  expect(safeNext("/artisans/a1?tab=media")).toBe("/artisans/a1?tab=media");
  for (const bad of ["https://evil.example/", "//evil.example", "/\\evil", "javascript:alert(1)", "", null, "/login", "/session-ended?next=/x"]) {
    expect(safeNext(bad), String(bad)).toBeNull();
  }
  expect(signInHref("/workshops/w1")).toBe("/login?next=%2Fworkshops%2Fw1");
  expect(signInHref("https://evil.example")).toBe("/login");
  expect(sessionEndedHref("/products")).toBe("/session-ended?next=%2Fproducts");
});

test("every screen has a short title, one line, and no exclamation", () => {
  const screens: ErrorScreen[] = ["not-found", "forbidden", "session", "server", "offline", "unavailable", "global"];
  for (const screen of screens) {
    for (const options of [{}, { noun: "workshop" }, { noun: "artisan", canRequest: true }]) {
      const copy = errorCopy(screen, options);
      expect(copy.title.split(" ").length, `${screen} title`).toBeLessThanOrEqual(4);
      expect(copy.line.length, `${screen} line`).toBeLessThanOrEqual(64);
      expect(`${copy.title} ${copy.line}`, screen).not.toContain("!");
      expect(copy.line.split(/[.?]\s/).length, `${screen} is one or two short sentences`).toBeLessThanOrEqual(2);
    }
  }
});

test("a record's API failure draws the shared error state, not a red sentence", () => {
  const view = readFileSync(join(__dirname, "..", "components", "records", "RecordView.tsx"), "utf8");
  expect(view).toContain("<ApiErrorState");
  expect(view).not.toContain("bg-red-50");
});

test("a 401 sends the person to the session-ended page with the way back", () => {
  const api = readFileSync(join(__dirname, "..", "lib", "api.ts"), "utf8");
  expect(api).toContain("sessionEndedHref(");
  const login = readFileSync(join(APP, "login", "page.tsx"), "utf8");
  expect(login).toContain("rememberSignInReturn(window.location.search)");
  expect(login).not.toContain('router.replace("/dashboard")');
});
