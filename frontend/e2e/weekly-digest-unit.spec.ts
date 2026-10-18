import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DIGEST_PATH,
  digestCardView,
  digestHref,
  digestPath,
  digestTiles,
  toggleDigest,
  visibleLines,
  weekWords,
  type DigestPreference,
  type WeeklyDigest
} from "@/lib/weeklyDigest";

/**
 * THE MONDAY SUMMARY — the Settings card's judgements and the summary page's shape, held without a
 * renderer. The three paths are the shared contract with the designer portal and both handsets.
 */

const ROOT = join(__dirname, "..");

function preference(overrides: Partial<DigestPreference> = {}): DigestPreference {
  return {
    eligible: true,
    enabled: true,
    emailAvailable: true,
    schedule: "Mondays, 08:00 IST",
    latest: null,
    ...overrides
  };
}

const DIGEST: WeeklyDigest = {
  id: "dg1",
  weekStart: "2026-10-19",
  generatedAt: "2026-10-19T02:30:00+00:00",
  sections: [
    {
      key: "waiting",
      label: "Waiting for you",
      total: 4,
      lines: [
        { key: "records", label: "Records to review", count: 4, link: "/review" },
        { key: "tasks", label: "Tasks to check", count: 0, link: "/settings/tasks" }
      ],
      items: []
    },
    { key: "overdue", label: "Overdue", total: 0, lines: [], items: [] },
    {
      key: "new",
      label: "New this week",
      total: 11,
      lines: [{ key: "artisans", label: "Artisans", count: 11, link: "https://elsewhere.example" }],
      items: []
    }
  ],
  coverage: { label: "Districts documented", value: 41, of: null, percent: null }
};

test("the three routes are the shared contract", () => {
  expect(DIGEST_PATH).toBe("/notifications/digest");
  expect(digestPath("c 1")).toBe("/notifications/digests/c%201");
  expect(digestHref("dg1")).toBe("/digest/dg1");
});

test("the card is drawn only for an account that gets a summary", () => {
  expect(digestCardView(null)).toBeNull();
  expect(digestCardView(preference({ eligible: false }))).toBeNull();
  expect(digestCardView(preference())).not.toBeNull();
});

test("the card says where the summary arrives", () => {
  expect(digestCardView(preference({ emailAvailable: true }))?.channel).toBe("By e-mail and here.");
  expect(digestCardView(preference({ emailAvailable: false }))?.channel).toBe("Here, in your notifications.");
  expect(digestCardView(preference())?.latestHref).toBeNull();
  const latest = { id: "dg1", weekStart: "2026-10-19", waiting: 4, overdue: 0, newThisWeek: 11, coverage: null };
  expect(digestCardView(preference({ latest }))?.latestHref).toBe("/digest/dg1");
});

test("the switch shows the change at once and puts it back when the save fails", async () => {
  const shown: boolean[] = [];
  const before = preference({ enabled: true });
  const failed = await toggleDigest(
    before,
    false,
    async () => {
      throw new Error("Network down");
    },
    (state) => shown.push(state.enabled)
  );
  expect(shown).toEqual([false, true]);
  expect(failed.state.enabled).toBe(true);
  expect(failed.error).toBe("Network down");

  const saved: boolean[] = [];
  const ok = await toggleDigest(before, false, async (enabled) => preference({ enabled }), (s) => saved.push(s.enabled));
  expect(saved).toEqual([false, false]);
  expect(ok.error).toBeNull();
});

test("the page draws four tiles and leaves out rows that count nothing", () => {
  expect(digestTiles(DIGEST)).toEqual([
    { key: "waiting", label: "Waiting for you", figure: "4" },
    { key: "overdue", label: "Overdue", figure: "0" },
    { key: "new", label: "New this week", figure: "11" },
    { key: "coverage", label: "Districts documented", figure: "41" }
  ]);
  expect(digestTiles({ ...DIGEST, coverage: { label: "Reports in", value: 41, of: 58, percent: 71 } })[3].figure).toBe("71%");
  expect(visibleLines(DIGEST.sections[0]).map((line) => line.key)).toEqual(["records"]);
  // Only app paths become links.
  expect(visibleLines(DIGEST.sections[2])[0].link).toBeNull();
});

test("the week is read as a calendar date", () => {
  expect(weekWords("2026-10-19")).toBe("19 Oct");
});

test("Settings draws the card right under the e-mail card", () => {
  const page = readFileSync(join(ROOT, "app/(protected)/settings/page.tsx"), "utf8");
  const email = page.indexOf("<EmailNotificationsCard />");
  const digest = page.indexOf("<WeeklyDigestCard />");
  expect(email).toBeGreaterThan(0);
  expect(digest).toBeGreaterThan(email);
  expect(page.slice(email, digest).split("\n").length).toBeLessThanOrEqual(2);
});

test("the card's switch does not repeat the heading", () => {
  const card = readFileSync(join(ROOT, "components/settings/WeeklyDigestCard.tsx"), "utf8");
  expect(card).toContain("busy={saving}");
  expect(card).toContain("label={DIGEST_SWITCH}");
});
