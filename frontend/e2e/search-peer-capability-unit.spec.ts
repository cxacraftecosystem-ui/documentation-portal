import { expect, test } from "@playwright/test";

import { peerSearchOffered } from "@/lib/unifiedSearch";

test("both portals is offered from the server's availability flag, whatever the scope", () => {
  expect(peerSearchOffered({ status: "off", available: true })).toBe(true);
  expect(peerSearchOffered({ status: "filteredOut", available: true })).toBe(true);
  expect(peerSearchOffered({ status: "ok", available: false })).toBe(false);
  expect(peerSearchOffered({ status: "notPermitted", available: false })).toBe(false);
});

test("an older server without the flag falls back to the status", () => {
  expect(peerSearchOffered({ status: "ok" })).toBe(true);
  expect(peerSearchOffered({ status: "unavailable" })).toBe(true);
  expect(peerSearchOffered({ status: "off" })).toBe(false);
  expect(peerSearchOffered({ status: "notPermitted" })).toBe(false);
  expect(peerSearchOffered(null)).toBe(false);
});
