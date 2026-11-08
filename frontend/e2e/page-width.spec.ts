import { existsSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Page } from "@playwright/test";

import { ListBackNavFake } from "./support/listBackNavFake";

type Json = Record<string, unknown>;

const APP = join(__dirname, "..", "app", "(protected)");
const has = (route: string) => existsSync(join(APP, ...route.split("/").filter(Boolean), "page.tsx"));

const LONG_TITLE = "Group-Interview_2026-09-23_Krishnanagar_Krishnanagar-Terracotta_4-Artisans_Shristi_O_Anusandhan_Third_Toolkit";
const LONG_EMAIL = "artisan.documentation.coordinator.krishnanagar.terracotta.cluster@field-repository.example.org";
const LONG_NAME = "Dhirendra Chandra Pal Bandyopadhyay Chattopadhyay";
const LONG_CODE = "DESIGN_PROTOTYPE_DEVELOPMENT_AND_PRODUCT_DIVERSIFICATION_WORKSHOP";

const ROUTES = [
  { path: "/dashboard", file: "dashboard", ready: "#main-content h1" },
  { path: "/questionnaire/recordings", file: "questionnaire/recordings", ready: "[data-recording-row]" },
  { path: "/questionnaire/inbox", file: "questionnaire/inbox", ready: "[data-inbox-section]" },
  { path: "/questionnaire/sessions/s-1", file: "questionnaire/sessions/[id]", ready: "[data-session-title]" },
  { path: "/questionnaire/sessions/s-1/view", file: "questionnaire/sessions/[id]/view", ready: "[data-session-fields]" },
  { path: "/questionnaires", file: "questionnaires", ready: "#main-content h1" },
  { path: "/users", file: "users", ready: "text=/Select users/" },
  { path: "/settings/usage", file: "settings/usage", ready: "text=Traffic over time" },
  { path: "/admin/workshop-types", file: "admin/workshop-types", ready: "text=Design and prototype development One" },
  { path: "/workshop-access/manage", file: "workshop-access/manage", ready: "text=Workshop roster" }
] as const;

const WIDTHS = [
  { width: 390, height: 844 },
  { width: 834, height: 1194 },
  { width: 1440, height: 900 }
] as const;

const THEMES = ["light", "dark"] as const;

const WINDOW = { from: "2026-10-01T00:00:00Z", to: "2026-10-08T00:00:00Z", days: 7, maxDays: 31, interval: "[from, to)", naiveDatesReadAs: "UTC" };
const LIMITS = { maxWindowDays: 31, maxRoutesPerRequest: 50, minimumIdentifiedUsers: 3, maxBuckets: 400 };
const SCOPE = { templates: [], source: "mounted", count: 40, mountedTotal: 40, notIncluded: 0, maxPerRequest: 50 };

function person(n: number): Json {
  return { id: `u-${n}`, name: `${LONG_NAME} ${n}`, email: `${n}.${LONG_EMAIL}`, role: "RESEARCHER", isActive: true };
}

class PageWidthFake extends ListBackNavFake {
  constructor() {
    super();
    this.questionnaire.role = "MASTER_ADMIN";
    const first = this.questionnaire.sessions[0] as Json;
    first.title = LONG_TITLE;
    this.recordings = this.recordings.map((row) => {
      const chips = (row.chips ?? {}) as Json;
      const session = (chips.session ?? {}) as Json;
      return { ...row, downloadName: `${LONG_TITLE}_${String(row.id)}`, chips: { ...chips, session: { ...session, title: `${LONG_TITLE}_${String(row.id)}` } } };
    });
    this.sessions = this.sessions.map((row) => ({ ...row, title: `${LONG_TITLE}_${String(row.id)}` }));
  }

  answer(method: string, path: string, query: URLSearchParams): { status: number; json?: unknown } | null {
    if (method !== "GET") return super.answer(method, path, query);
    if (path === "/users") {
      const people = Array.from({ length: 12 }, (_, i) => person(i + 1));
      return { status: 200, json: { items: people, total: people.length, page: 1, pageSize: Number(query.get("pageSize") ?? 20), pages: 1 } };
    }
    if (path === "/users/directory") return { status: 200, json: Array.from({ length: 12 }, (_, i) => person(i + 1)) };
    if (path === "/workshop-types") {
      return {
        status: 200,
        json: ["One", "Two", "Three"].map((label, index) => ({
          id: `wt-${index}`,
          key: `${LONG_CODE}_${label.toUpperCase()}`,
          label: `Design and prototype development ${label}`,
          sortOrder: index,
          isActive: index !== 2,
          routesToDesignWorkshop: index === 0,
          createdAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-10-01T00:00:00Z"
        }))
      };
    }
    if (/^\/workshops\/[^/]+\/assignments$/.test(path)) {
      const workshopId = path.split("/")[2];
      return {
        status: 200,
        json: Array.from({ length: 4 }, (_, i) => ({
          id: `as-${i}`,
          workshopId,
          userId: `u-${i + 1}`,
          accessLevel: "CONTRIBUTE",
          status: i === 3 ? "REVOKED" : "GRANTED",
          requestNote: `${LONG_TITLE} ${LONG_TITLE}`,
          decisionNote: null,
          decidedAt: "2026-10-02T10:00:00Z",
          createdAt: "2026-10-01T10:00:00Z",
          user: person(i + 1),
          requestedBy: person(i + 1)
        }))
      };
    }
    if (path === "/workshops/access-requests") {
      return {
        status: 200,
        json: [
          {
            id: "req-1",
            workshopId: "workshops-1",
            userId: "u-1",
            accessLevel: "VIEW",
            status: "PENDING",
            requestNote: LONG_TITLE,
            createdAt: "2026-10-01T10:00:00Z",
            user: person(1),
            workshop: { id: "workshops-1", title: LONG_TITLE, place: "Krishnanagar" }
          }
        ]
      };
    }
    if (path === "/usage/routes") {
      const items = Array.from({ length: 6 }, (_, i) => ({
        routeTemplate: `/api/design-workshops/{workshop_id}/stages/{stage_key}/fields/{field_key}/media/${i}`,
        requests: 120 + i,
        identifiedUsers: 5,
        withheld: false,
        ok: 110,
        clientErrors: 8,
        serverErrors: 2,
        avgDurationMs: 140,
        maxDurationMs: 900
      }));
      return {
        status: 200,
        json: {
          items,
          total: items.length,
          page: 1,
          pageSize: 25,
          pages: 1,
          window: WINDOW,
          routeSource: "mounted",
          limits: LIMITS,
          totalsForThisPage: { routes: items.length, routesWithheld: 1, requests: 750, ok: 700, clientErrors: 40, serverErrors: 10 },
          notMeasured: [],
          notes: []
        }
      };
    }
    if (path === "/usage/timeline") {
      const series = Array.from({ length: 8 }, (_, i) => ({
        bucket: `2026-10-0${i + 1}`,
        requests: 80 + i * 7,
        ok: 70,
        clientErrors: 6,
        serverErrors: 1,
        errorRate: 0.08,
        identifiedUsers: 5,
        withheld: false
      }));
      return { status: 200, json: { window: WINDOW, bucket: "day", scope: SCOPE, series, limits: LIMITS, notes: [] } };
    }
    return super.answer(method, path, query);
  }
}

async function widths(page: Page) {
  return page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, inner: window.innerWidth }));
}

for (const theme of THEMES) {
  for (const size of WIDTHS) {
    test.describe(`${size.width}px ${theme}`, () => {
      test.use({ viewport: size, actionTimeout: 20_000 });

      for (const route of ROUTES) {
        test(`${route.path} never scrolls sideways`, async ({ page }) => {
          test.skip(!has(route.file), `${route.path} is not in this portal`);
          await new PageWidthFake().install(page);
          await page.addInitScript((chosen) => {
            window.localStorage.setItem("field_repo_preferences", JSON.stringify({ theme: chosen, reducedMotion: true, largerText: false, highContrast: false }));
          }, theme);
          await page.goto(route.path);
          await expect(page.locator("#main-content")).toBeVisible({ timeout: 30_000 });
          await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
          await page.waitForLoadState("networkidle");
          await expect(page.locator(route.ready).filter({ visible: true }).first()).toBeVisible({ timeout: 30_000 });
          await page.waitForTimeout(500);
          const measured = await widths(page);
          expect(measured.scroll, `${route.path} at ${size.width}px ${theme} is ${measured.scroll}px wide`).toBeLessThanOrEqual(measured.inner);
        });
      }
    });
  }
}
