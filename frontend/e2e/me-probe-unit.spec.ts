import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { ME_CACHE_KEY, MeTimeoutError, forgetCachedMe, meFailure, meRetryDelay, readCachedMe, withinTimeout, writeCachedMe } from "@/lib/meProbe";
import type { User } from "@/lib/types";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function memoryStorage(): Storage {
  const items = new Map<string, string>();
  return {
    get length() {
      return items.size;
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, String(value))
  };
}

test("a dead session signs out; anything else is the connection, and is retried", () => {
  expect(meFailure(new ApiError(401, "no", null), false)).toBe("signedOut");
  expect(meFailure(new ApiError(403, "no", null), false)).toBe("signedOut");
  expect(meFailure(new ApiError(401, "no", null), true)).toBe("replaced");
  expect(meFailure(new ApiError(504, "gateway", null), false)).toBe("unreachable");
  expect(meFailure(new TypeError("Failed to fetch"), false)).toBe("unreachable");
  expect(meFailure(new MeTimeoutError(), false)).toBe("unreachable");
});

test("retries back off and settle at half a minute", () => {
  expect([0, 1, 2, 3, 4, 9].map(meRetryDelay)).toEqual([2000, 4000, 8000, 15000, 30000, 30000]);
});

test("a /me that never answers gives up at the timeout and cancels the request", async () => {
  let aborted = false;
  const started = Date.now();
  await expect(
    withinTimeout(
      (signal) =>
        new Promise<never>(() => {
          signal.addEventListener("abort", () => {
            aborted = true;
          });
        }),
      50
    )
  ).rejects.toBeInstanceOf(MeTimeoutError);
  expect(aborted).toBe(true);
  expect(Date.now() - started).toBeLessThan(2000);
  await expect(withinTimeout(async () => "ok", 50)).resolves.toBe("ok");
});

test("the cached account belongs to one session and is dropped with it", () => {
  const globals = globalThis as unknown as { window?: { localStorage: Storage } };
  const before = globals.window;
  globals.window = { localStorage: memoryStorage() };
  try {
    const user = { id: "u1", email: "a@b.c", name: "Asha", role: "RESEARCHER" } as unknown as User;
    writeCachedMe("marker-1", user);
    expect(readCachedMe("marker-1")).toEqual(user);
    expect(readCachedMe("marker-2")).toBeNull();
    expect(readCachedMe(null)).toBeNull();
    expect(globals.window.localStorage.getItem(ME_CACHE_KEY)).not.toContain("marker-1");
    forgetCachedMe();
    expect(readCachedMe("marker-1")).toBeNull();
    globals.window.localStorage.setItem(ME_CACHE_KEY, "{not json");
    expect(readCachedMe("marker-1")).toBeNull();
  } finally {
    globals.window = before;
  }
});

test("the shell renders from the cached account and never waits on /me forever", () => {
  const auth = read("components", "AuthProvider.tsx");
  const shell = read("components", "AppShell.tsx");
  expect(auth).toContain("withinTimeout((signal) => apiFetch<User>(\"/me\", { signal }");
  expect(auth).toContain("readCachedMe(getSessionMarker())");
  expect(auth).toContain("forgetCachedMe();");
  expect(shell).toContain('if (!loading && !user && reach === "ok") router.replace(signInHref(`${window.location.pathname}${window.location.search}`));');
  expect(shell).toContain('if (!user && reach === "retrying") return <Unreachable onRetry={retryMe} />;');
  expect(shell).toContain('data-testid="reconnecting"');
});
