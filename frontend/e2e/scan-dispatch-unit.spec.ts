import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  SCAN_COPY,
  SCAN_PORTAL,
  classifyScan,
  dispatchScan,
  type CardAnswer,
  type RecordAnswer,
  type ScanPortal,
  type ScanServices
} from "@/lib/scanDispatch";
import { SCAN_CODECS } from "@/lib/scanCodecs";

/**
 * The top-bar dispatcher against the shared fixture (REQUIREMENTS_2026-10-20 §6.3). This file and
 * `fixtures/scan-dispatch.json` are byte-identical in both portals, and Android's `ScanDispatchTest`
 * reads the same fixture. Each portal runs its own column; the designer portal, which carries the
 * DPW decoders, runs the field column too (the field column needs no decoder at all).
 */

type Expectation = {
  outcome: string;
  title?: string;
  action?: string;
  href?: string;
  joinCode?: string;
  clientKey?: string;
};

type Row = {
  name: string;
  input: string;
  secret?: string;
  aadhaar?: boolean;
  services?: Partial<Record<ScanPortal, { card?: string; record?: string }>>;
  expect: Record<ScanPortal, Expectation>;
};

const FIXTURE = JSON.parse(readFileSync(join(__dirname, "fixtures", "scan-dispatch.json"), "utf8")) as { rows: Row[] };

const ARTISAN_ID = "cmartisan00000001";
const NAME = "Meena Devi";

function servicesFor(portal: ScanPortal, row: Row): ScanServices {
  const mock = row.services?.[portal] ?? {};
  const card = async (): Promise<CardAnswer> => {
    switch (mock.card) {
      case "found":
        return { status: "found", artisanId: ARTISAN_ID, name: NAME };
      case "replaced":
        return { status: "replaced", artisanId: ARTISAN_ID };
      case "peer":
        return { status: "peer" };
      case "refused":
        return { status: "refused", message: "Server refusal." };
      case "offline":
        return { status: "offline" };
      default:
        throw new Error(`${row.name}: no card answer mocked for ${portal}`);
    }
  };
  const record = async (): Promise<RecordAnswer> => {
    switch (mock.record) {
      case "found":
        return { status: "found", label: NAME, href: "/somewhere" };
      case "missing":
        return { status: "missing" };
      case "offline":
        return { status: "offline" };
      default:
        throw new Error(`${row.name}: no record answer mocked for ${portal}`);
    }
  };
  return { portal, codecs: portal === "designer" ? SCAN_CODECS : undefined, resolveCard: card, lookUpRecord: record };
}

const PORTALS: ScanPortal[] = SCAN_PORTAL === "designer" ? ["designer", "field"] : ["field"];

for (const portal of PORTALS) {
  test.describe(`the ${portal} column`, () => {
    for (const row of FIXTURE.rows) {
      test(row.name, async () => {
        const want = row.expect[portal];
        const got = await dispatchScan(row.input, servicesFor(portal, row));
        expect(got.kind).toBe(want.outcome);
        if (want.title) expect(got.title).toBe(want.title);
        if (want.action) expect(got.action?.type).toBe(want.action);
        if (want.href) expect(got.action && "href" in got.action ? got.action.href : undefined).toBe(want.href);
        if (want.joinCode) {
          expect(got.action?.type === "joinSession" ? got.action.joinCode : undefined).toBe(want.joinCode);
        }
        if (want.clientKey) {
          expect(got.action?.type === "joinSession" ? got.action.qr?.clientKey : undefined).toBe(want.clientKey);
        }
        // A join card's secret never reaches the words on screen.
        if (row.secret) expect(`${got.title} ${got.detail ?? ""}`).not.toContain(row.secret);
        // An Aadhaar payload is refused and no part of it comes back.
        if (row.aadhaar) {
          expect(got.action).toBeUndefined();
          const said = JSON.stringify(got);
          for (let i = 0; i + 8 <= row.input.length; i += 8) expect(said).not.toContain(row.input.slice(i, i + 8));
        }
      });
    }
  });
}

test("the order is fixed: a join card is read before anything else", () => {
  const step = classifyScan("DPW2:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2WY:7AWF", "field");
  expect(step).toEqual({ step: "outcome", outcome: { kind: "REFUSED", title: SCAN_COPY.joinCardElsewhere } });
});

test("nothing typed is answered without a lookup", () => {
  expect(classifyScan("   ", SCAN_PORTAL, SCAN_CODECS)).toEqual({ step: "outcome", outcome: { kind: "REFUSED", title: SCAN_COPY.empty } });
});

test("a lookup that throws reads as offline, never as a crash", async () => {
  const services: ScanServices = {
    portal: "field",
    resolveCard: async () => {
      throw new TypeError("Failed to fetch");
    },
    lookUpRecord: async () => ({ status: "missing" })
  };
  expect((await dispatchScan("D-7K3QM-9XA2F-HQ", services)).kind).toBe("OFFLINE");
});
