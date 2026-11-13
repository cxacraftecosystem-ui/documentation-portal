import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { NO_ACCEPTED_MEASUREMENTS } from "@/components/forms/measurementMethods";
import { ARTISAN_FORM_CONTRACT } from "@/lib/artisanFormContract";
import { endpointTakesClientKey } from "@/lib/offline";
import {
  artisanBody,
  craftBody,
  interviewBody,
  processBody,
  productBody,
  toolBody,
  workshopBody
} from "@/lib/recordPayloads";
import { DERIVED_NAME_SOURCES, writeBody } from "@/lib/writeBody";

const FIXTURE_PATH = join(__dirname, "..", "..", "shared", "web-request-fixtures.json");
const CLIENT_KEY = "client-key-1";
const NOW = new Date("2026-11-10T05:30:00.000Z");

type Variant = "filled" | "blank-optionals";
type Method = "POST" | "PATCH";
type Fixture = { method: Method; path: string; variant: Variant; body: unknown };

function formOf(values: Record<string, string | string[]>): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(values)) {
    for (const item of Array.isArray(value) ? value : [value]) form.append(key, item);
  }
  return form;
}

const FILLED_LOCATION = {
  latitude: "27.1767",
  longitude: "78.0081",
  altitude: "171",
  accuracy: "12",
  locationAddress: "Taj Ganj, Agra, Uttar Pradesh 282001",
  placeName: "Taj Ganj",
  capturedAt: "2026-11-10T05:30:00.000Z",
  state: "Uttar Pradesh",
  district: "Agra",
  village: "Taj Ganj",
  pincode: "282001",
  subjectLatitude: "27.1751",
  subjectLongitude: "78.0421",
  recordedAt: "2026-11-10T05:30:00.000Z",
  recordedTimezone: "Asia/Kolkata"
};

const BLANK_LOCATION = {
  latitude: "27.1767",
  longitude: "78.0081",
  altitude: "",
  accuracy: "",
  locationAddress: " ",
  placeName: "",
  capturedAt: "",
  state: "",
  district: " ",
  village: "",
  pincode: "",
  subjectLatitude: "",
  subjectLongitude: "",
  recordedAt: "2026-11-10T05:30:00.000Z",
  recordedTimezone: ""
};

const EXIF = { exifItems: [], exifRemark: "" };

type Builder = (variant: Variant, isUpdate: boolean) => unknown;

const RECORDS: { create: string; update: string; build: Builder }[] = [
  {
    create: "/api/tools",
    update: "/api/tools/{tool_id}",
    build: (variant, isUpdate) => {
      const filled = variant === "filled";
      const form = formOf({
        ...(filled ? FILLED_LOCATION : BLANK_LOCATION),
        place: filled ? "Agra" : "",
        artisanName: filled ? "Ramesh Kumar" : " ",
        toolkitName: "Thappa block",
        localName: filled ? "Thappa" : "",
        englishName: filled ? "Printing block" : " ",
        processUsedIn: filled ? "Block printing" : "",
        material: filled ? "Sheesham wood" : "",
        materialFamily: filled ? "WOOD" : "",
        yearsInUse: filled ? "12" : "",
        thickness: filled ? "1.5" : "",
        weight: filled ? "0.4" : "",
        radius: filled ? "0" : "",
        maker: filled ? "SELF" : "",
        traditionType: filled ? "TRADITIONAL" : "",
        replacementCost: filled ? "850" : "",
        suggestionsForToolImprovement: filled ? "A lighter handle would help." : " ",
        remarks: filled ? "Carved by the artisan's father." : "",
        status: filled ? "APPROVED" : ""
      });
      return toolBody(form, {
        ...EXIF,
        height: filled ? "8" : "",
        width: filled ? "6" : "",
        length: filled ? "4" : "",
        breadth: filled ? "3" : "",
        heightInches: filled ? "2" : "",
        accepted: NO_ACCEPTED_MEASUREMENTS,
        artisanId: "artisan-1",
        craftId: "craft-1",
        craftIds: filled ? ["craft-1", "craft-2"] : ["craft-1"],
        artisanIds: filled ? ["artisan-1", "artisan-2"] : ["artisan-1"],
        craftLinksChanged: !isUpdate || filled,
        artisanLinksChanged: !isUpdate || filled,
        workshopId: filled ? "workshop-1" : "",
        initialStatus: isUpdate ? "PENDING" : null
      });
    }
  },
  {
    create: "/api/products",
    update: "/api/products/{product_id}",
    build: (variant, isUpdate) => {
      const filled = variant === "filled";
      const form = formOf({
        ...(filled ? FILLED_LOCATION : BLANK_LOCATION),
        productName: "Indigo dupatta",
        localName: filled ? "Neel dupatta" : "",
        productType: filled ? "TEXTILE" : "",
        timeTakenToCompleteProduct: filled ? "3 days" : " ",
        size: filled ? "2.5 m x 1 m" : "",
        costOfMaking: filled ? "450" : "",
        sellingPrice: filled ? "1200" : "",
        marketDemand: filled ? "HIGH" : "",
        rawMaterialsUsed: filled ? "Cotton, indigo" : "",
        mainToolsUsed: filled ? "Thappa block" : "",
        productFunctionUse: filled ? "Worn as a stole" : " ",
        remarks: filled ? "Sold at the Agra haat." : "",
        status: filled ? "APPROVED" : ""
      });
      return productBody(form, {
        ...EXIF,
        isUpdate,
        place: filled ? "Agra" : "",
        artisanName: filled ? "Ramesh Kumar" : " ",
        length: filled ? "98" : "",
        breadth: filled ? "39" : "",
        height: "",
        accepted: NO_ACCEPTED_MEASUREMENTS,
        offRecord: false,
        artisanId: "artisan-1",
        craftId: filled ? "craft-1" : "",
        workshopId: filled ? "workshop-1" : "",
        initialStatus: isUpdate ? "PENDING" : null
      });
    }
  },
  {
    create: "/api/processes",
    update: "/api/processes/{process_id}",
    build: (variant, isUpdate) => {
      const filled = variant === "filled";
      return processBody({
        isEdit: isUpdate,
        name: "Indigo dyeing",
        productId: "product-1",
        workshopId: filled ? "workshop-1" : "",
        preProcessAvailable: filled,
        notes: filled ? "Done in the courtyard vats." : " ",
        status: filled ? "APPROVED" : "PENDING",
        steps: [
          {
            serverId: isUpdate ? "step-1" : undefined,
            name: "Soak the cloth",
            stepType: "PROCESS",
            recordAdditional: filled,
            notes: filled ? "Overnight in plain water." : ""
          },
          { name: "Dip in the vat", stepType: "PROCESS", recordAdditional: true, notes: filled ? "Three dips." : " " }
        ],
        now: NOW
      });
    }
  },
  {
    create: "/api/artisans",
    update: "/api/artisans/{artisan_id}",
    build: (variant, isUpdate) => {
      const filled = variant === "filled";
      const form = formOf({
        ...(filled ? FILLED_LOCATION : BLANK_LOCATION),
        name: "Ramesh Kumar",
        localName: filled ? "रमेश कुमार" : "",
        gender: filled ? "MALE" : "",
        dateOfBirth: "1978-04-12",
        craftStartDate: "1995-06-01",
        experienceYears: filled ? "31" : "",
        experienceMonths: filled ? "5" : "",
        phone: filled ? "9876543210" : "",
        phoneCountryCode: filled ? "+91" : "",
        email: filled ? "ramesh@example.com" : " ",
        place: "Agra",
        address: filled ? "12 Taj Ganj, Agra 282001" : "",
        notes: filled ? "Third-generation block printer." : "",
        aadhaarNumber: filled ? "234123412346" : "",
        pehchanCardAvailable: filled ? "true" : "false",
        pehchanCardNumber: filled ? "UP123456" : "",
        dos: "Wash in cold water",
        donts: "Do not bleach",
        status: filled ? "APPROVED" : ""
      });
      return artisanBody(form, {
        ...EXIF,
        isUpdate,
        craftId: "craft-1",
        aadhaarNotShared: false,
        storedAadhaarNotShared: false,
        workshopId: filled ? "workshop-1" : "",
        initialStatus: isUpdate ? "PENDING" : null,
        dismissed: filled ? ["artisan-9"] : [],
        formContract: ARTISAN_FORM_CONTRACT
      });
    }
  },
  {
    create: "/api/crafts",
    update: "/api/crafts/{craft_id}",
    build: (variant) => {
      const filled = variant === "filled";
      const form = formOf({
        name: "Dabu printing",
        localName: filled ? "Dabu" : " ",
        description: filled ? "Mud-resist block printing." : ""
      });
      return craftBody(form, {
        ...EXIF,
        subCategoryId: "subcategory-1",
        stateOfOrigin: filled ? ["Uttar Pradesh"] : [],
        stateOfPractice: filled ? ["Uttar Pradesh", "Rajasthan"] : [],
        giStatus: filled ? "GI" : "NON_GI",
        workshopId: filled ? "workshop-1" : ""
      });
    }
  },
  {
    create: "/api/workshops",
    update: "/api/workshops/{workshop_id}",
    build: (variant, isUpdate) => {
      const filled = variant === "filled";
      const form = formOf({
        ...(filled ? FILLED_LOCATION : BLANK_LOCATION),
        title: "Agra block printing workshop",
        workshopType: filled ? "OTHER" : "",
        date: "2026-11-10",
        startDate: "2026-11-10",
        endDate: filled ? "2026-11-14" : "2026-11-10",
        place: "Agra",
        description: filled ? "Five days with the Taj Ganj printers." : " ",
        notes: filled ? "Bring blocks." : "",
        status: filled ? "APPROVED" : ""
      });
      return workshopBody(form, {
        canSetStatus: filled,
        artisanIds: !isUpdate || filled ? ["artisan-1"] : undefined,
        craftIds: !isUpdate || filled ? ["craft-1"] : undefined
      });
    }
  },
  {
    create: "/api/questionnaire/interviews",
    update: "/api/questionnaire/interviews/{interview_id}",
    build: (variant, isUpdate) => {
      const filled = variant === "filled";
      const form = formOf({
        ...(filled ? FILLED_LOCATION : BLANK_LOCATION),
        title: filled ? "Ramesh Kumar · 10 Nov 2026" : " ",
        place: filled ? "Agra" : "",
        language: filled ? "hi" : "",
        notes: filled ? "Recorded in the workshop." : " ",
        status: filled ? "APPROVED" : ""
      });
      return interviewBody(form, {
        editingId: isUpdate ? "interview-1" : null,
        automaticTitle: "Interview · Ramesh Kumar",
        canPickStatus: filled,
        workshopId: filled ? "workshop-1" : "",
        artisanIds: ["artisan-1"],
        responses: filled
          ? [
              { questionId: "question-1", answerText: "Natural indigo from Agra." },
              { questionId: "question-2", answerText: "Since 1995.", answerSource: "TRANSCRIPT" }
            ]
          : [],
        questionnaireId: filled ? "questionnaire-1" : null
      });
    }
  }
];

function wireBody(endpoint: string, method: Method, body: unknown): unknown {
  const sent = writeBody(body, method) as Record<string, unknown>;
  const keyed = method === "POST" && endpointTakesClientKey(endpoint) && !("clientKey" in sent) ? { ...sent, clientKey: CLIENT_KEY } : sent;
  return JSON.parse(JSON.stringify(keyed));
}

function fixtures(): Fixture[] {
  const entries: Fixture[] = [];
  for (const record of RECORDS) {
    const endpoint = record.create.replace(/^\/api/, "");
    for (const variant of ["filled", "blank-optionals"] as const) {
      entries.push({ method: "POST", path: record.create, variant, body: wireBody(endpoint, "POST", record.build(variant, false)) });
      entries.push({ method: "PATCH", path: record.update, variant, body: wireBody(`${endpoint}/id-1`, "PATCH", record.build(variant, true)) });
    }
  }
  return entries;
}

function blankStrings(value: unknown, path = "body"): string[] {
  if (typeof value === "string") return value.trim() ? [] : [path];
  if (Array.isArray(value)) return value.flatMap((item, index) => blankStrings(item, `${path}[${index}]`));
  if (value && typeof value === "object") return Object.entries(value).flatMap(([key, item]) => blankStrings(item, `${path}.${key}`));
  return [];
}

test("shared/web-request-fixtures.json matches what the web sends", () => {
  const expected = `${JSON.stringify(fixtures(), null, 2)}\n`;
  if (process.env.UPDATE_WEB_REQUEST_FIXTURES === "1") writeFileSync(FIXTURE_PATH, expected);
  let committed = "";
  try {
    committed = readFileSync(FIXTURE_PATH, "utf8").replace(/\r\n/g, "\n");
  } catch {
    committed = "";
  }
  expect(
    committed,
    "shared/web-request-fixtures.json is stale. Regenerate: UPDATE_WEB_REQUEST_FIXTURES=1 npx playwright test e2e/web-request-fixtures-unit.spec.ts"
  ).toBe(expected);
});

for (const record of RECORDS) {
  test(`${record.create}: a create with blank optionals sends no blank text`, () => {
    const body = wireBody(record.create.replace(/^\/api/, ""), "POST", record.build("blank-optionals", false));
    expect(blankStrings(body)).toEqual([]);
  });

  test(`${record.create}: no blank derivable name is sent while its id is set`, () => {
    for (const isUpdate of [false, true]) {
      const body = wireBody(record.create.replace(/^\/api/, ""), isUpdate ? "PATCH" : "POST", record.build("blank-optionals", isUpdate)) as Record<
        string,
        unknown
      >;
      for (const [name, sources] of Object.entries(DERIVED_NAME_SOURCES)) {
        const linked = sources.some((key) => {
          const value = body[key];
          return (typeof value === "string" && value.trim() !== "") || (Array.isArray(value) && value.length > 0);
        });
        if (linked) expect(body[name] === undefined || (typeof body[name] === "string" && (body[name] as string).trim() !== "")).toBe(true);
      }
    }
  });
}
