import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const APP = join(__dirname, "..", "app");

function read(...parts: string[]): string {
  return readFileSync(join(APP, ...parts), "utf8");
}

test("every boundary exists, so no failure reaches Next's unthemed page", () => {
  for (const parts of [["error.tsx"], ["(protected)", "error.tsx"], ["not-found.tsx"], ["global-error.tsx"]]) {
    expect(existsSync(join(APP, ...parts)), parts.join("/")).toBe(true);
  }
});

test("the error boundaries are client components that offer a retry", () => {
  for (const parts of [["error.tsx"], ["(protected)", "error.tsx"], ["global-error.tsx"]]) {
    const source = read(...parts);
    expect(source.startsWith('"use client";'), parts.join("/")).toBe(true);
    expect(source, parts.join("/")).toContain("onClick={reset}");
  }
});

test("the global boundary draws its own document and theme, and leans on no root provider", () => {
  const source = read("global-error.tsx");
  expect(source).toContain("<html");
  expect(source).toContain("<body");
  expect(source).toContain("PREFERENCES_BOOT_SCRIPT");
  expect(source).toContain('import "./globals.css";');
  for (const provider of ["AuthProvider", "useAuth", "ThemeProvider", "ToastProvider", "AdminViewProvider", "DeadEnd"]) expect(source).not.toContain(provider);
});
