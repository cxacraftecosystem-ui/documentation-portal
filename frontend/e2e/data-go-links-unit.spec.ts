import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { GO_NO_ACCESS, GO_UNAVAILABLE, goFailure, safeInternalHref } from "@/lib/dataExplorer/goLinks";
import { canAccessRoute, routeGuardFor } from "@/lib/permissions";
import type { User } from "@/lib/types";

const plain = { id: "u1", email: "a@b.c", name: "Asha", role: "FIELD_CONTRIBUTOR", canDownloadDataset: false } as unknown as User;
const data = { id: "u2", email: "d@b.c", name: "Dev", role: "FIELD_CONTRIBUTOR", canDownloadDataset: true } as unknown as User;

test("report links open for anyone signed in, the Data page stays gated", () => {
  for (const path of ["/data/go/media/m1/download", "/data/go/open/artisan/a1", "/data/go/transcript/m1/docx"]) {
    expect(canAccessRoute(plain, path), path).toBe(true);
    expect(canAccessRoute(data, path), path).toBe(true);
    expect(canAccessRoute(null, path), path).toBe(false);
    expect(routeGuardFor(path)?.path).toBe("/data/go");
  }
  expect(canAccessRoute(plain, "/data")).toBe(false);
  expect(canAccessRoute(data, "/data")).toBe(true);
  expect(canAccessRoute(plain, "/data/report-schedules/s1")).toBe(false);
});

test("the panel says access required on 403, not available on 404, and offers a retry otherwise", () => {
  const refused = goFailure(new ApiError(403, "no", null));
  expect(refused).toEqual({ title: "Access required", message: GO_NO_ACCESS, retry: false });
  const gone = goFailure(new ApiError(404, "gone", null));
  expect(gone).toEqual({ title: "Not available", message: GO_UNAVAILABLE, retry: false });
  for (const failure of [new ApiError(500, "boom", null), new ApiError(422, "bad", null), new Error("offline")]) {
    const problem = goFailure(failure);
    expect(problem.retry).toBe(true);
    expect(problem.title).not.toBe("Access required");
  }
});

test("a go page only follows links inside the portal", () => {
  expect(safeInternalHref("/artisans/a1")).toBe("/artisans/a1");
  expect(safeInternalHref("//evil.example")).toBeNull();
  expect(safeInternalHref("https://evil.example")).toBeNull();
  expect(safeInternalHref(null)).toBeNull();
});
