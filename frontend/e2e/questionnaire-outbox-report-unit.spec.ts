import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { deviceLabel, INSTALL_ID_KEY, installId, outboxHeaderValues, outboxSummary, waitedLabel } from "@/lib/questionnaire/outboxReport";
import type { ClipStep, SessionUnit } from "@/lib/questionnaire/queue";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function clip(key: string, status: ClipStep["status"], capturedAt: string, error?: string): ClipStep {
  return {
    kind: "clip",
    status,
    clientClipKey: key,
    blobKey: `blob-${key}`,
    sizeBytes: 10,
    contentType: "audio/mp4",
    extension: "m4a",
    presign: { contentSha256: null, targets: [], subjectArtisanIds: [], deviceCapturedAt: capturedAt, clockOffsetMs: 0 },
    recording: {
      targets: [],
      subjects: [],
      deviceCapturedAt: capturedAt,
      clockOffsetMs: 0,
      capturedAtSource: "DEVICE",
      durationMs: 1000,
      rosterCount: 1,
      contentSha256: null,
      continuationOfId: null,
      replacesRecordingId: null,
      ackedRecordingIds: []
    },
    ...(error ? { error } : {})
  } as ClipStep;
}

test("the outbox is reported as what this device still holds: waiting and failed takes, and the oldest", () => {
  const units = [
    {
      id: "u1",
      sessionId: "s1",
      createdAt: "2026-11-16T06:00:00.000Z",
      steps: [
        clip("a", "PENDING", "2026-11-15T08:00:00.000Z"),
        clip("b", "FAILED", "2026-11-14T08:00:00.000Z"),
        clip("c", "DONE", "2026-11-01T08:00:00.000Z"),
        clip("d", "PENDING", "2026-11-01T08:00:00.000Z", "MISSING_FILE")
      ]
    },
    { id: "u2", sessionId: null, createdAt: "2026-11-16T07:00:00.000Z", steps: [clip("e", "PENDING", "2026-11-16T07:30:00.000Z")] }
  ] as unknown as SessionUnit[];
  const summary = outboxSummary(units);
  expect(summary).toEqual({ pending: 3, failed: 1, oldest: "2026-11-14T08:00:00.000Z" });
  expect(outboxHeaderValues(summary, "0b9f6c1e-1d2b-4c5e-9f00-1234567890ab")).toEqual({
    "X-Install-Id": "0b9f6c1e-1d2b-4c5e-9f00-1234567890ab",
    "X-Outbox-Pending": "3",
    "X-Outbox-Failed": "1",
    "X-Outbox-Oldest": "2026-11-14T08:00:00.000Z"
  });
  expect(outboxSummary([])).toEqual({ pending: 0, failed: 0, oldest: null });
  expect(outboxHeaderValues({ pending: 0, failed: 0, oldest: null }, "0b9f6c1e-1d2b"), "nothing waiting sends no oldest").toEqual({
    "X-Install-Id": "0b9f6c1e-1d2b",
    "X-Outbox-Pending": "0",
    "X-Outbox-Failed": "0"
  });
});

test("an install keeps one random id, and every API request carries the four headers", () => {
  const held = new Map<string, string>();
  const store = { getItem: (k: string) => held.get(k) ?? null, setItem: (k: string, v: string) => void held.set(k, v) };
  const first = installId(store);
  expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  expect(installId(store), "the same install answers the same id").toBe(first);
  expect(held.get(INSTALL_ID_KEY)).toBe(first);
  held.set(INSTALL_ID_KEY, "bad id;");
  expect(installId(store)).not.toBe("bad id;");
  const api = read("lib", "api.ts");
  expect(api).toContain('import { outboxHeaders } from "@/lib/questionnaire/outboxReport";');
  expect(api).toContain("for (const [name, value] of Object.entries(outboxHeaders())) if (!headers.has(name)) headers.set(name, value);");
  const queue = read("components", "questionnaire", "useSessionQueue.ts");
  expect(queue).toContain("noteOutbox(all);");
  const report = read("lib", "questionnaire", "outboxReport.ts");
  expect(report).toContain('await import("@/lib/questionnaire/queue")');
  expect(report).not.toMatch(/^import \{[^}]*\} from "@\/lib\/questionnaire\/queue"/m);
});

test("the admin list says how long the oldest take has waited and which build holds it", () => {
  const now = Date.parse("2026-11-16T12:00:00Z");
  expect(waitedLabel("2026-11-16T11:30:00Z", now)).toBe("30 min");
  expect(waitedLabel("2026-11-16T07:00:00Z", now)).toBe("5 h");
  expect(waitedLabel("2026-11-13T12:00:00Z", now)).toBe("3 d");
  expect(waitedLabel(null, now)).toBe("");
  expect(deviceLabel({ platform: "android", appVersion: "0.0.7" })).toBe("Android · 0.0.7");
  expect(deviceLabel({ platform: "web", appVersion: null })).toBe("Web");
  const list = read("components", "questionnaire", "PhonesHoldingRecordings.tsx");
  expect(list).toContain('qs("phonesHoldingEmpty")');
  expect(list).toContain("questionnaireApi\n      .outboxReports()");
  for (const portalAdmin of [read("app", "(protected)", "admin", "page.tsx")]) expect(portalAdmin).toContain("<PhonesHoldingRecordings />");
});
