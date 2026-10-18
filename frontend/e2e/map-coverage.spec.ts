import { expect, test, type Page, type Route } from "@playwright/test";

/**
 * The coverage view in a browser, against a dev server, with every API call answered by this file.
 *
 * Nothing here needs a backend or a real account: `/me` answers a master admin (so admin view is on
 * by default), `/map/coverage*` answers a small corpus, and the district shapes are one square for
 * Barmer. What is under test is the page — URL sync, the filters, the unit panel and its links, the
 * gaps panel and the unmatched flow — at a phone and a laptop width, in light and dark.
 */

const API = process.env.E2E_API_BASE ?? "http://localhost:8000";
const SHOTS = process.env.SHOT_DIR ?? "shots/coverage";

const UNIT = {
  stateLgd: 8,
  districtLgd: 90,
  state: "Rajasthan",
  district: "Barmer",
  polygon: "OWN",
  parentLgd: null,
  counts: { workshops: 2, artisans: 5, crafts: 1, products: 3, tools: 0, media: 4 },
  peer: null,
  craftCodes: ["ajrakh"],
  clusters: []
};

function coverage(url: URL, unmatched: number) {
  const craft = url.searchParams.get("craftCode");
  const units = craft && craft !== "ajrakh" ? [] : [UNIT];
  return {
    asOf: "2026-10-18T00:00:00Z",
    register: { lgdAsOf: "2026-10-18", listVersion: 3, districtCount: 795, stateCount: 36 },
    boundaries: { shapesVersion: "test", depiction: "Survey of India" },
    filters: {
      level: url.searchParams.get("level") ?? "DISTRICT",
      craftCode: craft ? [craft] : [],
      scheme: [],
      fy: [],
      stateLgd: null
    },
    catalogue: {
      crafts: [
        { code: "ajrakh", name: "Ajrakh" },
        { code: "bandhani", name: "Bandhani" }
      ],
      schemes: [
        { value: "DPD", label: "Design & Prototype Development Workshop" },
        { value: "OTHER", label: "Other workshop" }
      ],
      years: [{ fy: 2025, label: "2025-26" }]
    },
    units,
    markers: [],
    markersTruncated: false,
    gaps: {
      craftsNeverDocumented: units.length ? [{ code: "bandhani", name: "Bandhani" }] : [
        { code: "ajrakh", name: "Ajrakh" },
        { code: "bandhani", name: "Bandhani" }
      ],
      districtsWithoutWorkshop: {
        count: 794,
        byState: [
          { stateLgd: 8, state: "Rajasthan", total: 41, without: [{ lgd: 110, name: "Jaipur" }] }
        ]
      },
      statesWithoutWorkshop: [{ lgd: 24, name: "Gujarat" }]
    },
    unmatched: { count: unmatched },
    truncated: false
  };
}

const SHAPES = {
  version: "test",
  source: "test",
  asOf: "2026-10-18",
  scale: 1000,
  districts: [
    // A square around Barmer, encoded in the outline's polyline scheme by the unit spec's encoder.
    { lgd: 90, stateLgd: 8, rings: [encode([[70.5, 25.0], [72.0, 25.0], [72.0, 26.4], [70.5, 26.4]])], centroid: [71.2, 25.7] }
  ]
};

function encode(points: Array<[number, number]>): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  let out = "";
  let x = 0;
  let y = 0;
  const varint = (value: number) => {
    let zig = value < 0 ? ~(value << 1) : value << 1;
    for (;;) {
      const chunk = zig & 0x1f;
      zig >>= 5;
      out += alphabet[chunk | (zig ? 0x20 : 0)];
      if (!zig) return;
    }
  };
  varint(points.length);
  for (const [lon, lat] of points) {
    varint(Math.round(lon * 1000) - x);
    varint(Math.round(lat * 1000) - y);
    x = Math.round(lon * 1000);
    y = Math.round(lat * 1000);
  }
  return out;
}

async function mockApi(page: Page) {
  let unmatched = [
    {
      model: "Location",
      rawState: "Rajasthan",
      rawDistrict: "Kachchh",
      rows: 2,
      suggestion: { stateLgd: 24, districtLgd: 469, reason: "Kachchh is a district of Gujarat, not Rajasthan." }
    }
  ];
  const aliases: unknown[] = [];
  // E2E_REAL_SHAPES=1 draws the committed district file instead of the one-square fixture.
  if (!process.env.E2E_REAL_SHAPES) {
    await page.route("**/boundaries/district-shapes.json", (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(SHAPES) })
    );
  }
  await page.route(`${API}/**`, async (route: Route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace(/^\/api/, "");
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (path === "/me") {
      return json({ id: "u1", email: "admin@example.org", name: "Admin", role: "MASTER_ADMIN" });
    }
    if (path === "/map/coverage/unmatched") return json({ items: unmatched, total: unmatched.length });
    if (path === "/map/coverage/aliases" && route.request().method() === "POST") {
      aliases.push(route.request().postDataJSON());
      unmatched = [];
      return json({ id: "alias1" }, 201);
    }
    if (path.startsWith("/map/coverage/units/")) {
      return json({
        unit: { stateLgd: 8, districtLgd: 90, state: "Rajasthan", district: "Barmer", polygon: "OWN", parentLgd: null },
        buckets: {
          workshops: {
            total: 1,
            items: [{ id: "w1", title: "Ajrakh at Barmer", href: "/workshops/w1", craftCode: "ajrakh", fy: 2025 }],
            nextCursor: null
          },
          artisans: { total: 0, items: [], nextCursor: null }
        },
        peerHref: null
      });
    }
    if (path === "/map/coverage") return json(coverage(url, unmatched.length));
    if (path === "/reference/district-lineage") return json({ parents: {}, stateFallback: [] });
    return json({});
  });
  await page.addInitScript(() => window.localStorage.setItem("field_repo_token", "test-token"));
  return { aliases };
}

async function openCoverage(page: Page, query = "") {
  await page.goto(`/map?view=coverage${query}`);
  await expect(page.getByRole("heading", { name: "Gaps" })).toBeVisible({ timeout: 60_000 });
}

test.describe.configure({ timeout: 180_000 });

test("switching views keeps the URL in sync", async ({ page }) => {
  await mockApi(page);
  await openCoverage(page);
  await expect(page.getByRole("link", { name: "Coverage" })).toHaveAttribute("aria-current", "page");
  await expect(page.getByTestId("coverage-caption")).toHaveText(
    "External boundaries of India as depicted by Survey of India. District boundaries are indicative."
  );
  await page.getByRole("group", { name: "Colour by" }).getByRole("button", { name: "Artisans" }).click();
  await expect(page).toHaveURL(/metric=artisans/);
  await page.getByRole("group", { name: "Detail" }).getByRole("button", { name: "State" }).click();
  await expect(page).toHaveURL(/level=STATE/);
  await expect(page).toHaveURL(/metric=artisans/);
  await page.reload();
  await expect(page.getByRole("group", { name: "Colour by" }).getByRole("button", { name: "Artisans" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("link", { name: "Places" })).toHaveAttribute("href", "/map");
});

test("a craft filter changes the map and the gaps", async ({ page }) => {
  await mockApi(page);
  await openCoverage(page);
  await expect(page.getByTestId("coverage-table")).toContainText("Barmer, Rajasthan");
  await openCoverage(page, "&craftCode=bandhani");
  await expect(page.getByText("No records under these filters.")).toBeVisible();
  await expect(page.getByText("Crafts never documented")).toContainText("2");
});

test("clicking a district opens its panel and the record links work", async ({ page }) => {
  await mockApi(page);
  await openCoverage(page);
  await page.locator('[data-testid="coverage-map"] path[data-lgd="90"]').click({ force: true });
  const panel = page.getByTestId("coverage-unit-panel");
  await expect(panel.getByRole("heading", { name: "Barmer, Rajasthan" })).toBeVisible();
  await expect(panel.getByRole("link", { name: "Ajrakh at Barmer" })).toHaveAttribute("href", "/workshops/w1");
});

test("the gaps panel shows its counts", async ({ page }) => {
  await mockApi(page);
  await openCoverage(page);
  const gaps = page.getByTestId("coverage-gaps");
  await expect(gaps).toContainText("Crafts never documented · 1");
  await expect(gaps).toContainText("Districts without a workshop · 794");
  await gaps.getByRole("button", { name: /Rajasthan/ }).click();
  await expect(gaps).toContainText("Jaipur");
});

test("an administrator maps an unmatched place and it leaves the list", async ({ page }) => {
  const { aliases } = await mockApi(page);
  await openCoverage(page);
  const panel = page.getByTestId("unmatched-panel");
  await expect(panel).toContainText("Rajasthan / Kachchh");
  await panel.getByRole("button", { name: "Map" }).click();
  await expect(page.getByTestId("unmatched-panel")).toHaveCount(0);
  expect(aliases).toEqual([{ rawState: "Rajasthan", rawDistrict: "Kachchh", stateLgd: 24, districtLgd: 469 }]);
});

for (const width of [375, 1280]) {
  for (const theme of ["light", "dark"] as const) {
    test(`renders at ${width}px in ${theme} with no sideways scroll`, async ({ page }) => {
      await mockApi(page);
      await page.addInitScript((value) => {
        window.localStorage.setItem("field_repo_preferences", JSON.stringify({ theme: value }));
      }, theme);
      await page.setViewportSize({ width, height: 900 });
      await openCoverage(page);
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth
      );
      expect(overflow).toBeLessThanOrEqual(1);
      await page.screenshot({ path: `${SHOTS}/coverage-${width}-${theme}.png`, fullPage: true });
    });
  }
}
