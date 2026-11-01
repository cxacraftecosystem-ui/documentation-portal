import type { Page, Route } from "@playwright/test";

import { QuestionnaireFake } from "./qv2Fake";

/**
 * AN IN-MEMORY API FOR `e2e/list-back-nav.spec.ts` (identical in both portals): every record list
 * answers 45 rows named "Record 01" … "Record 45", paged on the server as the real lists are
 * (`page`, `pageSize`, `search`), so "page 2" is a real second request; the questionnaire sessions
 * and recordings answer the same 45 in cursor pages of 20. Everything else is the questionnaire
 * fake's (`support/qv2Fake.ts`), which also answers `/me`, `/preferences/me` and the v2 config.
 */

type Json = Record<string, unknown>;

export const ROWS = 45;
const PAGE = 20;

export const rowName = (n: number) => `Record ${String(n).padStart(2, "0")}`;

/** The record lists, by API path, and the field each row prints as its name. */
const LIST_PATHS = [
  "/artisans",
  "/products",
  "/processes",
  "/tools",
  "/workshops",
  "/media",
  "/design-workshops",
  "/questionnaires",
  "/questionnaire/interviews",
  "/users"
];

function record(kind: string, n: number): Json {
  const name = rowName(n);
  const day = `2026-10-${String(1 + (n % 27)).padStart(2, "0")}`;
  return {
    id: `${kind.replace(/[^a-z]/g, "")}-${n}`,
    name,
    title: name,
    productName: name,
    toolkitName: name,
    originalFilename: `${name}.pdf`,
    caption: null,
    localName: null,
    place: "Bagru",
    clusterName: "Bagru",
    district: "Jaipur",
    state: "Rajasthan",
    craftName: "Hand Block Printing",
    workshopCode: null,
    status: "APPROVED",
    workshopKind: "BASELINE",
    kind: null,
    kindLabel: "Kind not stated",
    isActive: true,
    isShared: false,
    version: 1,
    email: null,
    role: "RESEARCHER",
    startDate: day,
    endDate: day,
    createdAt: `${day}T05:00:00Z`,
    updatedAt: `${day}T05:00:00Z`,
    recordedAt: `${day}T05:00:00Z`,
    mediaType: "PDF",
    mimeType: "application/pdf",
    sizeBytes: 1000,
    url: null,
    transcriptStatus: null,
    transcriptText: null,
    transcriptError: null,
    linkedRecordType: null,
    linkedRecordId: null,
    craft: null,
    crafts: [],
    artisans: [],
    steps: [],
    media: [],
    completeness: null,
    ownerId: "u-lead",
    ownerName: "Nandini Rao",
    designWorkshopId: null,
    designWorkshopTitle: null,
    uploadedBy: { id: "u-lead", name: "Nandini Rao" }
  };
}

export class ListBackNavFake {
  questionnaire = new QuestionnaireFake();
  sessions: Json[];
  recordings: Json[];

  constructor() {
    const [baseSession] = this.questionnaire.sessions;
    const [baseRecording] = this.questionnaire.recordings;
    this.sessions = Array.from({ length: ROWS }, (_, i) => {
      const copy = JSON.parse(JSON.stringify(baseSession)) as Json;
      return { ...copy, id: `s-${i + 1}`, clientKey: `ck-s-${i + 1}`, title: rowName(i + 1) };
    });
    this.recordings = Array.from({ length: ROWS }, (_, i) => {
      const copy = JSON.parse(JSON.stringify(baseRecording)) as Json;
      const chips = (copy.chips ?? {}) as Json;
      const session = (chips.session ?? {}) as Json;
      return {
        ...copy,
        id: `rec-${i + 1}`,
        sessionId: `s-${i + 1}`,
        downloadName: rowName(i + 1),
        removedAt: null,
        chips: { ...chips, session: { ...session, id: `s-${i + 1}`, title: rowName(i + 1) } }
      };
    });
  }

  private paged(path: string, query: URLSearchParams): Json {
    const search = (query.get("search") ?? query.get("q") ?? "").toLowerCase();
    const all = Array.from({ length: ROWS }, (_, i) => record(path, i + 1)).filter((row) => !search || String(row.name).toLowerCase().includes(search));
    const size = Math.max(1, Math.min(Number(query.get("pageSize") ?? PAGE) || PAGE, 500));
    const page = Math.max(1, Number(query.get("page") ?? 1) || 1);
    return { items: all.slice((page - 1) * size, page * size), total: all.length, page, pageSize: size, pages: Math.ceil(all.length / size) };
  }

  private cursorPage(rows: Json[], query: URLSearchParams, title: (row: Json) => string): Json {
    const search = (query.get("q") ?? "").toLowerCase();
    const kept = rows.filter((row) => !search || title(row).toLowerCase().includes(search));
    const start = Number(query.get("cursor") ?? 0) || 0;
    // `limit` as the server reads it (the search page's Recordings group asks for 5).
    const size = Math.max(1, Math.min(Number(query.get("limit")) || PAGE, PAGE));
    const items = kept.slice(start, start + size);
    return { items, nextCursor: start + size < kept.length ? String(start + size) : null, capped: false };
  }

  answer(method: string, path: string, query: URLSearchParams): { status: number; json?: unknown } | null {
    if (method === "GET" && path === "/questionnaire/sessions") return { status: 200, json: this.cursorPage(this.sessions, query, (row) => String(row.title)) };
    if (method === "GET" && path === "/questionnaire/recordings" && !query.get("sessionId"))
      return { status: 200, json: this.cursorPage(this.recordings, query, (row) => String(row.downloadName)) };
    if (method === "GET" && LIST_PATHS.includes(path)) return { status: 200, json: this.paged(path, query) };
    // The search page's browse route (the unified route answers 404, so the page falls back to it):
    // the artisans bucket holds the 45, every other bucket is empty.
    if (method === "GET" && path === "/search") {
      const artisans = this.paged("/artisans", query) as { items: Json[]; total: number; pages: number };
      return {
        status: 200,
        json: {
          artisans: artisans.items,
          workshops: [],
          products: [],
          tools: [],
          media: [],
          designWorkshops: [],
          totals: { artisans: artisans.total, workshops: 0, products: 0, tools: 0, media: 0, designWorkshops: 0 },
          pageCount: artisans.pages
        }
      };
    }
    // A row's own record (`artisans-7`), never a sub-resource such as `/media/jobs`.
    const one = /^\/(artisans|products|processes|tools|workshops|media)\/([a-z]+-\d+)$/.exec(path);
    if (method === "GET" && one) {
      const n = Number(one[2].split("-").pop()) || 1;
      return { status: 200, json: record(`/${one[1]}`, n) };
    }
    return null;
  }

  async install(page: Page): Promise<void> {
    await page.addInitScript(() => {
      window.localStorage.setItem("field_repo_token", "e2e-list-back-nav");
      window.localStorage.setItem("field_repo_preferences", JSON.stringify({ theme: "light", reducedMotion: true, largerText: false, highContrast: false }));
    });
    const delegate = this.questionnaire as unknown as {
      answer(method: string, path: string, query: URLSearchParams, body: Json | null): { status: number; json?: unknown; raw?: Buffer; contentType?: string; headers?: Record<string, string> } | null;
    };
    await page.route(
      (url) => url.pathname.startsWith("/api/") && !url.pathname.startsWith("/api/session/"),
      async (route: Route) => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname.replace(/^\/api/, "");
        let body: Json | null = null;
        try {
          body = request.postDataJSON() as Json | null;
        } catch {
          body = null;
        }
        const out = this.answer(request.method(), path, url.searchParams) ?? delegate.answer(request.method(), path, url.searchParams, body);
        if (!out) return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "Not Found" }) });
        if (out.status === 204) return route.fulfill({ status: 204, body: "" });
        const raw = (out as { raw?: Buffer }).raw;
        if (raw) return route.fulfill({ status: out.status, body: raw });
        return route.fulfill({ status: out.status, contentType: "application/json", headers: { date: new Date().toUTCString() }, body: JSON.stringify(out.json ?? null) });
      }
    );
  }
}
