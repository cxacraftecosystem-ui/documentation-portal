import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { lookUpCard } from "@/components/artisans/CardCodeLookup";
import { CARD_ALPHABET, CARD_CHECK_MESSAGE, cardShortCode, looksLikeArtisanCard, parseArtisanCard } from "@/lib/artisanCard";
import { maskedLast4, qrImageSrc, sheetRequestBody, type CardResolution } from "@/lib/artisanCardApi";

/**
 * The artisan card on the web: the parser against the vectors both portals share (the backend and
 * Android read the same file), the typed-code lookup, and where the card is mounted. No browser.
 */
type Vectors = {
  alphabet: string;
  shortCodes: { issuer: string; code: string; check: string; shortCode: string }[];
  parse: { input: string; expect?: Record<string, unknown>; error?: string }[];
  tags: { cases: { payload: string; issuer: string; code: string; keyVersion: number; tag: string }[] };
};

const root = join(__dirname, "..", "..");
const vectors = JSON.parse(readFileSync(join(root, "shared", "artisan-card-vectors.json"), "utf8")) as Vectors;
const source = (rel: string) => readFileSync(join(__dirname, "..", rel), "utf8");

test("the alphabet and short codes match the shared vectors", () => {
  expect(CARD_ALPHABET).toBe(vectors.alphabet);
  for (const item of vectors.shortCodes) expect(cardShortCode(item.issuer, item.code)).toBe(item.shortCode);
});

test("every parse vector reads the same here as on the server", () => {
  for (const item of vectors.parse) {
    const parsed = parseArtisanCard(item.input);
    if (item.error) expect(parsed, item.input).toEqual({ ok: false, reason: item.error });
    else expect(parsed, item.input).toEqual({ ok: true, card: item.expect });
  }
});

test("a full QR payload parses to its code and tag, and carries nothing else", () => {
  for (const item of vectors.tags.cases) {
    const parsed = parseArtisanCard(item.payload);
    expect(parsed).toEqual({
      ok: true,
      card: { issuer: item.issuer, code: item.code, keyVersion: item.keyVersion, tag: item.tag, typed: false }
    });
    expect(item.payload).not.toMatch(/\d{12}/);
  }
});

test("an old DPW1 code is not a card, so it is left to the old decoder", () => {
  expect(parseArtisanCard("DPW1:A:clx0abc123:7")).toEqual({ ok: false, reason: "NOT_A_CARD" });
  expect(looksLikeArtisanCard("DPW1:A:clx0abc123:7")).toBe(false);
  expect(looksLikeArtisanCard("F-01234-56789-S4")).toBe(true);
});

test("the typed lookup checks the code before asking, and reports each answer", async () => {
  let asked = 0;
  const found: CardResolution = {
    status: "FOUND",
    via: "LOCAL",
    artisan: { id: "a1", name: "Meena", craft: null, cluster: null, place: "Bhuj", aadhaarLast4: "9012" }
  };
  const resolve = async () => {
    asked += 1;
    return found;
  };
  expect(await lookUpCard("F-01234-56789-S4", resolve)).toEqual({ kind: "error", message: CARD_CHECK_MESSAGE });
  expect(await lookUpCard("hello", resolve)).toEqual({ kind: "error", message: "That isn't an artisan card code." });
  expect(await lookUpCard("  ", resolve)).toEqual({ kind: "error", message: "Type the code under the QR." });
  expect(asked).toBe(0);
  expect(await lookUpCard("f 01234 56789 s5", resolve)).toEqual({ kind: "found", artisan: found.artisan });
  expect(await lookUpCard("F-01234-56789-S5", async () => ({ status: "REPLACED", artisanId: "a9" }))).toEqual({
    kind: "replaced",
    artisanId: "a9"
  });
  const refused = await lookUpCard("D-7K3QM-9XA2F-HQ", async () => {
    throw new Error("This card is from the Design Prototype Workshop and this artisan isn't linked here.");
  });
  expect(refused).toEqual({
    kind: "error",
    message: "This card is from the Design Prototype Workshop and this artisan isn't linked here."
  });
});

test("the face masks Aadhaar to the last four, and the QR is shown as an image", () => {
  expect(maskedLast4("9012")).toBe("XXXX XXXX 9012");
  expect(maskedLast4(null)).toBeNull();
  expect(maskedLast4("123456789012")).toBeNull();
  expect(qrImageSrc("<svg/>")).toBe("data:image/svg+xml;charset=utf-8,%3Csvg%2F%3E");
});

test("a sheet names exactly one source, the workshop on this portal", () => {
  expect(sheetRequestBody({ workshopId: "w1" }, "id1", true)).toEqual({
    artisanIds: null,
    workshopId: "w1",
    size: "id1",
    photo: true,
    source: "WEB"
  });
  expect(sheetRequestBody({ artisanIds: ["a1", "a2"] }, "a6", false)).toMatchObject({ artisanIds: ["a1", "a2"], workshopId: null });
});

test("the card is mounted where the design puts it, and only behind the server's switch", () => {
  const view = source("components/records/RecordView.tsx");
  expect(view).toContain('<ArtisanCardPanel artisanId={record.id} canReissue={canUpdateRecord(user, "artisan")} />');
  expect(view).toContain('<ArtisanCardSheetActions source={{ workshopId }} primaryLabel="Print artisan cards" />');
  expect(source("app/(protected)/artisans/page.tsx")).toContain("<ArtisanCardSheetActions source={{ artisanIds: [...picked] }}");
  expect(source("app/(protected)/search/page.tsx")).toContain("<CardCodeLookup");
  expect(source("components/forms/ProductForm.tsx")).toContain("<CardCodeLookup");
  expect(source("components/forms/ToolForm.tsx")).toContain("<CardCodeLookup");
  for (const rel of ["components/artisans/ArtisanCardPanel.tsx", "components/artisans/ArtisanCardSheetActions.tsx"]) {
    expect(source(rel)).toContain("artisanCardStatus()");
  }
  expect(source("components/artisans/CardCodeLookup.tsx")).toContain("useArtisanCardsEnabled()");
});

test("the web never scans or holds a key: typing is the way in on this portal", () => {
  const lib = source("lib/artisanCardApi.ts") + source("components/artisans/CardCodeLookup.tsx");
  expect(lib).not.toMatch(/getUserMedia|BarcodeDetector|ARTISAN_CARD_KEY/);
});
