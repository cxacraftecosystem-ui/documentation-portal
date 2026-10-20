import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";
import { AppRouterContext } from "next/dist/shared/lib/app-router-context.shared-runtime";

import { ScanPanel } from "@/components/scan/ScanSheet";
import { SCAN_COPY, SCAN_HREF, dispatchScan } from "@/lib/scanDispatch";
import { runScanAction, scanServices } from "@/lib/scanServices";

/**
 * The Field Repository's scanner, without a browser or a sign-in (REQUIREMENTS_2026-10-20 §6.3), so
 * CI runs it on every push. `e2e/scan-session-join.spec.ts` drives the same two paths on the real
 * page when E2E credentials are set.
 *
 * 1. A typed six-character session code is answered with Join, and pressing it calls the real
 *    `POST /questionnaire/sessions/join` (fetch stubbed) and leads to the session.
 * 2. With no camera at all (Node has none), the panel still draws "Choose a photo" and the typed
 *    box, and the camera button answers a missing camera with a sentence naming both.
 */

type Sent = { url: string; method: string; body: string };

function stubApi(reply: (sent: Sent) => Response): { sent: Sent[]; restore: () => void } {
  const original = globalThis.fetch;
  const sent: Sent[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const call = { url: String(input), method: (init.method ?? "GET").toUpperCase(), body: init.body ? String(init.body) : "" };
    sent.push(call);
    return reply(call);
  }) as typeof globalThis.fetch;
  return { sent, restore: () => (globalThis.fetch = original) };
}

test("a typed session code joins through POST /sessions/join and opens the session", async () => {
  const outcome = await dispatchScan("ab3-k9z", scanServices());
  expect(outcome.kind).toBe("JOIN_SESSION");
  expect(outcome.title).toBe(SCAN_COPY.session);
  expect(outcome.action?.label).toBe(SCAN_COPY.joinSession);

  const api = stubApi(
    () => new Response(JSON.stringify({ id: "cmsessionscan0001" }), { status: 200, headers: { "content-type": "application/json" } })
  );
  try {
    const next = await runScanAction(outcome.action!);
    expect(api.sent).toHaveLength(1);
    expect(api.sent[0].method).toBe("POST");
    expect(api.sent[0].url).toMatch(/\/sessions\/join$/);
    expect(JSON.parse(api.sent[0].body)).toEqual({ joinCode: "AB3K9Z" });
    expect(next).toEqual({ navigate: SCAN_HREF.session("cmsessionscan0001") });
  } finally {
    api.restore();
  }
});

test("a refused join is a sentence, not a navigation", async () => {
  const outcome = await dispatchScan("AB3K9Z", scanServices());
  const api = stubApi(
    () => new Response(JSON.stringify({ detail: "No open session has that code." }), { status: 404, headers: { "content-type": "application/json" } })
  );
  try {
    const next = await runScanAction(outcome.action!);
    expect(next).toEqual({ outcome: { kind: "REFUSED", title: "No open session has that code." } });
  } finally {
    api.restore();
  }
});

test("a scanned session QR joins by its clientKey and never creates the session", async () => {
  const qr = { v: 1 as const, clientKey: "ck-lead-phone-1", questionnaireId: "q1", workshopId: "w1", sessionDate: "2026-10-20", participants: ["a1"] };
  const api = stubApi(
    () => new Response(JSON.stringify({ id: "cmsessionscan0002" }), { status: 200, headers: { "content-type": "application/json" } })
  );
  try {
    const next = await runScanAction({ type: "joinSession", label: SCAN_COPY.joinSession, joinCode: null, qr });
    expect(api.sent).toHaveLength(1);
    expect(api.sent[0].method).toBe("POST");
    expect(api.sent[0].url).toMatch(/\/sessions\/join-by-key$/);
    expect(JSON.parse(api.sent[0].body)).toEqual({ clientKey: "ck-lead-phone-1" });
    expect(next).toEqual({ navigate: SCAN_HREF.session("cmsessionscan0002") });
  } finally {
    api.restore();
  }
  // Not synced yet: the server's sentence, and nothing was created.
  const notYet = "This session hasn't synced yet. Try again once the lead's phone is online.";
  const refused = stubApi(
    () =>
      new Response(JSON.stringify({ detail: { code: "SESSION_UNKNOWN", message: notYet } }), {
        status: 404,
        headers: { "content-type": "application/json" }
      })
  );
  try {
    const next = await runScanAction({ type: "joinSession", label: SCAN_COPY.joinSession, joinCode: null, qr });
    expect(refused.sent.every((call) => !/\/sessions$/.test(call.url))).toBe(true);
    expect(next).toEqual({ outcome: { kind: "REFUSED", title: notYet } });
  } finally {
    refused.restore();
  }
});

test("with no camera, the photo and the typed code are still offered", () => {
  const router = { push: () => {}, replace: () => {}, refresh: () => {}, back: () => {}, forward: () => {}, prefetch: () => {} };
  const html = renderToStaticMarkup(
    createElement(AppRouterContext.Provider, { value: router as never }, createElement(ScanPanel))
  );
  expect(html).toContain(SCAN_COPY.useCamera);
  expect(html).toContain(SCAN_COPY.choosePhoto);
  expect(html).toContain('type="file"');
  expect(html).toContain(SCAN_COPY.typeCode);
  expect(html).toContain('id="scan-typed"');

  // The camera button, pressed where `navigator.mediaDevices` is missing, names both other routes.
  const source = readFileSync(join(__dirname, "..", "components", "scan", "ScanSheet.tsx"), "utf8");
  expect(source).toContain("if (!navigator.mediaDevices?.getUserMedia) {");
  expect(source).toContain("setProblem(`No camera here. ${SCAN_COPY.choosePhoto} or type the code.`);");
});

test("nothing scanned is written into the typed box", () => {
  const source = readFileSync(join(__dirname, "..", "components", "scan", "ScanSheet.tsx"), "utf8");
  const fromScanner = source.slice(source.indexOf("const fromScanner"), source.indexOf("const startCamera"));
  expect(fromScanner).toContain('setTyped("");');
  expect(fromScanner).not.toMatch(/setTyped\((?!"")/);
});
