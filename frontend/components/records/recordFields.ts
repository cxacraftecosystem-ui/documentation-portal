import { formatDate } from "@/lib/format";
import type { RecordViewType } from "@/lib/recordView";
import { languageName, phoneOf } from "@/lib/standardValues";

/**
 * WHAT A RECORD'S READ-ONLY VIEW PRINTS, as plain label/value rows.
 *
 * Pure, so `e2e/record-view-unit.spec.ts` can hold the one rule that matters most here without a
 * browser: **a view never prints a regulated identity number.** `GET /artisans/{id}` returns the
 * Aadhaar number IN FULL (the edit form needs it to correct it), and the lists never print it at
 * all. A view is a browsing surface — it is opened by clicking a row, on a shared field laptop — so
 * it answers only "is a card on file?", exactly as the list's Pehchan chip does. The number itself
 * stays behind "Update", where it always was.
 *
 * The rows follow the handset's `ViewDataDetail` (`android/.../MainActivity.kt`) for the fields both
 * clients show, so a researcher reading one record on a phone and a laptop reads the same thing. A
 * value that is missing prints as "Not recorded" rather than disappearing: a view that silently
 * drops an empty field is indistinguishable from one that never had the field.
 */

export type RecordFieldRow = { label: string; value: string };

export const NOT_RECORDED = "Not recorded";

type Loose = Record<string, unknown>;

function text(value: unknown): string {
  if (value === null || value === undefined) return NOT_RECORDED;
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "number") return Number.isFinite(value) ? String(value) : NOT_RECORDED;
  const trimmed = String(value).trim();
  return trimmed ? trimmed : NOT_RECORDED;
}

/** A Prisma Decimal arrives as a string; print it as typed, or "Not recorded". */
function decimal(value: unknown, unit = ""): string {
  if (value === null || value === undefined || value === "") return NOT_RECORDED;
  const n = Number(value);
  if (!Number.isFinite(n)) return NOT_RECORDED;
  return `${n}${unit}`;
}

function rupees(value: unknown): string {
  const printed = decimal(value);
  return printed === NOT_RECORDED ? printed : `₹${printed}`;
}

function dimensions(record: Loose): string {
  const parts = [record.lengthInches, record.breadthInches, record.heightInches].map((v) => decimal(v));
  if (parts.every((p) => p === NOT_RECORDED)) return NOT_RECORDED;
  return `${parts.map((p) => (p === NOT_RECORDED ? "–" : p)).join(" × ")} in (L × B × H)`;
}

function name(value: unknown): string {
  return value && typeof value === "object" && "name" in value ? text((value as Loose).name) : NOT_RECORDED;
}

function workshopTitle(record: Loose): string {
  const workshop = record.workshop as Loose | null | undefined;
  return workshop ? text(workshop.title) : NOT_RECORDED;
}

function list(values: unknown[]): string {
  const names = values.map((v) => (typeof v === "string" ? v.trim() : "")).filter(Boolean);
  return names.length ? names.join(", ") : NOT_RECORDED;
}

function dateRange(record: Loose): string {
  const start = (record.startDate ?? record.date) as string | null | undefined;
  const end = record.endDate as string | null | undefined;
  if (!start && !end) return NOT_RECORDED;
  if (start && end && formatDate(start) !== formatDate(end)) return `${formatDate(start)} – ${formatDate(end)}`;
  return formatDate(start ?? end);
}

/** The rows for one record. Status is not here: the view draws it as a badge beside the title. */
export function recordFieldRows(type: RecordViewType, record: Loose): RecordFieldRow[] {
  switch (type) {
    case "artisan":
      return [
        { label: "Local name", value: text(record.localName) },
        { label: "Craft", value: name(record.craft) },
        { label: "Place", value: text(record.place) },
        { label: "Gender", value: text(record.gender) },
        { label: "Phone", value: text(phoneOf(record)) },
        { label: "Email", value: text(record.email) },
        { label: "Address", value: text(record.address) },
        { label: "Experience", value: experience(record) },
        // On file or not — never the digits. See the header.
        {
          label: "Aadhaar card",
          // C18: the explicit outcome reads as itself, so the follow-up is visible on the record.
          value: record.aadhaarNumber ? "On file" : record.aadhaarNotShared ? "Not shared by artisan — follow up" : NOT_RECORDED
        },
        { label: "Pehchan card", value: record.pehchanCardAvailable || record.pehchanCardNumber ? "On file" : "No" },
        { label: "Do's", value: text(record.dos) },
        { label: "Don'ts", value: text(record.donts) },
        { label: "Notes", value: text(record.notes) },
        { label: "Workshop", value: workshopTitle(record) }
      ];
    case "product":
      return [
        { label: "Local name", value: text(record.localName) },
        { label: "Craft", value: text(record.craftName) },
        { label: "Artisan", value: text(record.artisanName) },
        { label: "Place", value: text(record.place) },
        { label: "Type", value: text(record.productType) },
        { label: "Size", value: text(record.size) },
        { label: "Dimensions", value: dimensions(record) },
        { label: "Time taken to make", value: text(record.timeTakenToCompleteProduct) },
        { label: "Cost of making", value: rupees(record.costOfMaking) },
        { label: "Selling price", value: rupees(record.sellingPrice) },
        { label: "Market", value: text(record.marketDemand) },
        { label: "Materials", value: text(record.rawMaterialsUsed) },
        { label: "Tools", value: text(record.mainToolsUsed) },
        { label: "Function", value: text(record.productFunctionUse) },
        { label: "Remarks", value: text(record.remarks) },
        { label: "Workshop", value: workshopTitle(record) }
      ];
    case "tool":
      return [
        { label: "Local name", value: text(record.localName) },
        { label: "English name", value: text(record.englishName) },
        { label: "Craft", value: text(record.craftName) },
        { label: "Artisan", value: text(record.artisanName) },
        { label: "Place", value: text(record.place) },
        { label: "Material", value: text(record.material) },
        { label: "Maker", value: text(record.maker) },
        { label: "Tradition", value: text(record.traditionType) },
        { label: "Used in", value: text(record.processUsedIn) },
        { label: "Years in use", value: text(record.yearsInUse) },
        { label: "Dimensions", value: dimensions(record) },
        { label: "Weight", value: decimal(record.weight) },
        { label: "Replacement cost", value: rupees(record.replacementCost) },
        { label: "Suggestions for improvement", value: text(record.suggestionsForToolImprovement) },
        { label: "Remarks", value: text(record.remarks) },
        { label: "Workshop", value: workshopTitle(record) }
      ];
    case "craft":
      return [
        { label: "Local name", value: text(record.localName) },
        { label: "Category", value: text(record.category) },
        { label: "Place", value: text(record.place) },
        { label: "Description", value: text(record.description) },
        { label: "Workshop", value: workshopTitle(record) }
      ];
    case "workshop":
      return [
        { label: "Dates", value: dateRange(record) },
        { label: "Place", value: text(record.place) },
        { label: "Description", value: text(record.description) },
        { label: "Notes", value: text(record.notes) },
        {
          label: "Artisans",
          value: list(((record.artisans as Loose[] | undefined) ?? []).map((link) => (link.artisan as Loose | undefined)?.name))
        },
        {
          label: "Crafts",
          value: list(((record.crafts as Loose[] | undefined) ?? []).map((link) => (link.craft as Loose | undefined)?.name))
        },
        { label: "Questionnaire", value: record.questionnaire ? text((record.questionnaire as Loose).title ?? (record.questionnaire as Loose).name) : "Default" }
      ];
    case "process": {
      const product = record.product as Loose | null | undefined;
      return [
        { label: "Product", value: text(product?.productName) },
        { label: "Artisan", value: text(product?.artisanName) },
        { label: "Pre-processes", value: record.preProcessAvailable ? "Yes" : "No" },
        { label: "Notes", value: text(record.notes) },
        { label: "Workshop", value: workshopTitle(record) }
      ];
    }
    case "questionnaire":
      return [
        {
          label: "Artisans",
          value: list(((record.artisans as Loose[] | undefined) ?? []).map((link) => (link.artisan as Loose | undefined)?.name))
        },
        { label: "Interview date", value: record.interviewDate ? formatDate(record.interviewDate as string) : NOT_RECORDED },
        { label: "Place", value: text(record.place) },
        { label: "Language", value: text(languageName(record.language as string | null | undefined)) },
        { label: "Interviewer", value: name(record.createdBy) },
        { label: "Notes", value: text(record.notes) },
        { label: "Workshop", value: workshopTitle(record) }
      ];
  }
}

function experience(record: Loose): string {
  const years = Number(record.experienceYears);
  const months = Number(record.experienceMonths);
  const parts: string[] = [];
  if (Number.isFinite(years) && years > 0) parts.push(`${years} year${years === 1 ? "" : "s"}`);
  if (Number.isFinite(months) && months > 0) parts.push(`${months} month${months === 1 ? "" : "s"}`);
  return parts.length ? parts.join(" ") : NOT_RECORDED;
}

/** The record's display title, for the page heading. */
export function recordTitle(type: RecordViewType, record: Loose): string {
  const pick = (value: unknown, fallback: string) => (typeof value === "string" && value.trim() ? value.trim() : fallback);
  switch (type) {
    case "artisan":
      return pick(record.name, "Artisan");
    case "product":
      return pick(record.productName, "Product");
    case "tool":
      return pick(record.toolkitName, "Tool");
    case "craft":
      return pick(record.name, "Craft");
    case "workshop":
      return pick(record.title, "Untitled workshop");
    case "process":
      return pick(record.name, "Process");
    case "questionnaire":
      return pick(record.title, "Interview");
  }
}

/** The singular noun each view is about, for headings and sentences. */
export const RECORD_VIEW_NOUN: Record<RecordViewType, string> = {
  artisan: "artisan",
  product: "product",
  tool: "tool",
  craft: "craft",
  workshop: "workshop",
  process: "process",
  questionnaire: "interview"
};

/** Where the record is read from. The linked-media type is the same word as the view type for all seven. */
export const RECORD_VIEW_ENDPOINT: Record<RecordViewType, string> = {
  artisan: "/artisans",
  product: "/products",
  tool: "/tools",
  craft: "/crafts",
  workshop: "/workshops",
  process: "/processes",
  questionnaire: "/questionnaire/interviews"
};
