import { isMaskedIdentityNumber } from "@/components/forms/AadhaarField";
import { measurementMethodsFor, type AcceptedMeasurements } from "@/components/forms/measurementMethods";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { acknowledgedIds } from "@/lib/duplicates";
import {
  locationFromForm,
  numericValue,
  recordedAtFromForm,
  recordedTimezoneFromForm,
  requiredText,
  textValue
} from "@/lib/forms";
import type { collectExifMetadata } from "@/lib/media";

export type ExifItems = Awaited<ReturnType<typeof collectExifMetadata>>;

export type ExifContext = { exifItems: ExifItems; exifRemark: string };

export const PEHCHAN_NO = "false";

function toNum(value: string) {
  const n = Number(value);
  return value.trim() && Number.isFinite(n) ? n : null;
}

function exifExtra(exifItems: ExifItems) {
  return exifItems.length ? { mediaExif: exifItems } : {};
}

export type ToolBodyContext = ExifContext & {
  height: string;
  width: string;
  length: string;
  breadth: string;
  heightInches: string;
  accepted: AcceptedMeasurements;
  artisanId: string;
  craftId: string;
  craftIds: string[];
  artisanIds: string[];
  craftLinksChanged: boolean;
  artisanLinksChanged: boolean;
  workshopId: string;
  initialStatus?: string | null;
};

export function toolBody(form: FormData, ctx: ToolBodyContext) {
  return {
    place: requiredText(form, "place"),
    artisanName: requiredText(form, "artisanName"),
    toolkitName: requiredText(form, "toolkitName"),
    localName: textValue(form, "localName"),
    englishName: textValue(form, "englishName"),
    processUsedIn: textValue(form, "processUsedIn"),
    material: textValue(form, "material"),
    materialFamily: textValue(form, "materialFamily"),
    yearsInUse: numericValue(form, "yearsInUse"),
    height: toNum(ctx.height),
    width: toNum(ctx.width),
    lengthInches: toNum(ctx.length),
    breadthInches: toNum(ctx.breadth),
    heightInches: toNum(ctx.heightInches),
    measurementMethods: measurementMethodsFor(ctx.accepted, {
      lengthInches: ctx.length,
      breadthInches: ctx.breadth,
      heightInches: ctx.heightInches
    }),
    thickness: numericValue(form, "thickness"),
    weight: numericValue(form, "weight"),
    radius: numericValue(form, "radius"),
    maker: requiredText(form, "maker") || "UNKNOWN",
    traditionType: requiredText(form, "traditionType") || "UNKNOWN",
    replacementCost: numericValue(form, "replacementCost"),
    suggestionsForToolImprovement: textValue(form, "suggestionsForToolImprovement"),
    remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, ctx.exifRemark),
    artisanId: ctx.artisanId || null,
    craftId: ctx.craftId || null,
    craftIds: ctx.craftLinksChanged ? ctx.craftIds : undefined,
    artisanIds: ctx.artisanLinksChanged ? ctx.artisanIds : undefined,
    workshopId: ctx.workshopId || null,
    status: requiredText(form, "status") || ctx.initialStatus || "PENDING",
    recordedAt: recordedAtFromForm(form),
    recordedTimezone: recordedTimezoneFromForm(form),
    location: locationFromForm(form),
    extraMetadata: exifExtra(ctx.exifItems)
  };
}

export type ProductBodyContext = ExifContext & {
  isUpdate: boolean;
  place: string;
  artisanName: string;
  length: string;
  breadth: string;
  height: string;
  accepted: AcceptedMeasurements;
  offRecord: boolean;
  artisanId: string;
  craftId: string;
  workshopId: string;
  initialStatus?: string | null;
};

export function productBody(form: FormData, ctx: ProductBodyContext) {
  const names = { place: ctx.place.trim(), artisanName: ctx.artisanName.trim() };
  const sentNames = ctx.isUpdate ? Object.fromEntries(Object.entries(names).filter(([, value]) => value)) : names;
  return {
    ...sentNames,
    productName: requiredText(form, "productName"),
    localName: textValue(form, "localName"),
    productType: requiredText(form, "productType") || "OTHER",
    timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
    size: textValue(form, "size"),
    lengthInches: toNum(ctx.length),
    breadthInches: toNum(ctx.breadth),
    heightInches: toNum(ctx.height),
    measurementMethods: measurementMethodsFor(ctx.accepted, {
      lengthInches: ctx.length,
      breadthInches: ctx.breadth,
      heightInches: ctx.height
    }),
    costOfMaking: numericValue(form, "costOfMaking"),
    sellingPrice: numericValue(form, "sellingPrice"),
    marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
    rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
    mainToolsUsed: textValue(form, "mainToolsUsed"),
    productFunctionUse: textValue(form, "productFunctionUse"),
    remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, ctx.exifRemark),
    artisanId: ctx.offRecord ? null : ctx.artisanId || null,
    craftId: ctx.craftId || null,
    workshopId: ctx.workshopId || null,
    status: requiredText(form, "status") || ctx.initialStatus || "PENDING",
    recordedAt: recordedAtFromForm(form),
    recordedTimezone: recordedTimezoneFromForm(form),
    location: locationFromForm(form),
    extraMetadata: exifExtra(ctx.exifItems)
  };
}

export type ProcessStepState = {
  serverId?: string;
  name: string;
  stepType: string;
  recordAdditional: boolean;
  notes: string;
};

export type ProcessBodyContext = {
  isEdit: boolean;
  name: string;
  productId: string;
  workshopId: string;
  preProcessAvailable: boolean;
  notes: string;
  status: string;
  steps: readonly ProcessStepState[];
  now: Date;
};

export function processBody(ctx: ProcessBodyContext) {
  return {
    name: ctx.name.trim(),
    productId: ctx.productId,
    workshopId: ctx.workshopId || null,
    preProcessAvailable: ctx.preProcessAvailable,
    notes: ctx.notes.trim() || null,
    status: ctx.status,
    steps: ctx.steps.map((step, index) => ({
      id: step.serverId,
      name: step.name.trim(),
      stepType: step.stepType,
      sortOrder: index + 1,
      notes: step.recordAdditional ? step.notes.trim() || null : null
    })),
    ...(ctx.isEdit ? {} : { recordedAt: ctx.now.toISOString() })
  };
}

export type ArtisanBodyContext = ExifContext & {
  isUpdate: boolean;
  craftId: string;
  aadhaarNotShared: boolean;
  storedAadhaarNotShared: boolean;
  workshopId: string;
  initialStatus?: string | null;
  dismissed: Iterable<string>;
  formContract: number;
};

export function artisanBody(form: FormData, ctx: ArtisanBodyContext) {
  const pehchanAvailable = textValue(form, "pehchanCardAvailable") !== PEHCHAN_NO;
  const pehchanNumber = textValue(form, "pehchanCardNumber");
  const pehchanUnchanged = pehchanAvailable && isMaskedIdentityNumber(pehchanNumber);
  const experienceMonthsText = textValue(form, "experienceMonths");
  const aadhaarNotShared = ctx.aadhaarNotShared;
  return {
    name: requiredText(form, "name"),
    localName: textValue(form, "localName"),
    gender: textValue(form, "gender"),
    dateOfBirth: textValue(form, "dateOfBirth") || null,
    craftStartDate: textValue(form, "craftStartDate") || null,
    experienceYears: textValue(form, "experienceYears") ? Number(textValue(form, "experienceYears")) : null,
    experienceMonths: experienceMonthsText === null ? null : Number(experienceMonthsText),
    phone: textValue(form, "phone"),
    phoneCountryCode: textValue(form, "phoneCountryCode"),
    email: textValue(form, "email"),
    place: requiredText(form, "place"),
    address: textValue(form, "address"),
    notes: appendStoredParagraph(textValue(form, "notes") as string | null, ctx.exifRemark, "paragraph"),
    aadhaarNumber: aadhaarNotShared ? null : textValue(form, "aadhaarNumber"),
    ...(aadhaarNotShared || ctx.storedAadhaarNotShared ? { aadhaarNotShared } : {}),
    pehchanCardAvailable: pehchanAvailable,
    ...(pehchanUnchanged ? {} : { pehchanCardNumber: pehchanAvailable ? pehchanNumber : null }),
    dos: requiredText(form, "dos"),
    donts: requiredText(form, "donts"),
    craftId: ctx.craftId,
    workshopId: ctx.workshopId || null,
    status: requiredText(form, "status") || ctx.initialStatus || "PENDING",
    recordedAt: recordedAtFromForm(form),
    recordedTimezone: recordedTimezoneFromForm(form),
    location: locationFromForm(form),
    extraMetadata: exifExtra(ctx.exifItems),
    formContract: ctx.formContract,
    ...(ctx.isUpdate ? {} : { duplicateAcknowledged: acknowledgedIds(ctx.dismissed) })
  };
}

export type CraftBodyContext = ExifContext & {
  subCategoryId: string;
  stateOfOrigin: string[];
  stateOfPractice: string[];
  giStatus: string;
  workshopId: string;
};

export function craftBody(form: FormData, ctx: CraftBodyContext): Record<string, unknown> {
  const description = [textValue(form, "description"), ctx.exifRemark].filter(Boolean).join("\n\n") || null;
  const body: Record<string, unknown> = {
    name: requiredText(form, "name"),
    localName: textValue(form, "localName"),
    subCategoryId: ctx.subCategoryId || null,
    stateOfOrigin: ctx.stateOfOrigin,
    stateOfPractice: ctx.stateOfPractice,
    giStatus: ctx.giStatus,
    workshopId: ctx.workshopId || null,
    description
  };
  if (ctx.exifItems.length) body.extraMetadata = { mediaExif: ctx.exifItems };
  return body;
}

export type WorkshopBodyContext = {
  canSetStatus: boolean;
  artisanIds?: string[];
  craftIds?: string[];
};

export function workshopBody(form: FormData, ctx: WorkshopBodyContext): Record<string, unknown> {
  const body: Record<string, unknown> = {
    title: requiredText(form, "title"),
    workshopType: requiredText(form, "workshopType") || "OTHER",
    date: requiredText(form, "date"),
    startDate: requiredText(form, "startDate"),
    endDate: requiredText(form, "endDate"),
    place: requiredText(form, "place"),
    description: textValue(form, "description"),
    notes: textValue(form, "notes"),
    status: ctx.canSetStatus ? requiredText(form, "status") : "PENDING",
    location: locationFromForm(form)
  };
  if (ctx.artisanIds) body.artisanIds = ctx.artisanIds;
  if (ctx.craftIds) body.craftIds = ctx.craftIds;
  return body;
}

export type InterviewResponse = { questionId: string; answerText: string; answerSource?: string };

export type InterviewBodyContext = {
  editingId: string | null;
  automaticTitle: string;
  canPickStatus: boolean;
  workshopId: string;
  artisanIds: string[];
  responses: InterviewResponse[];
  questionnaireId: string | null;
};

export function interviewTitle(form: FormData, automaticTitle: string): string {
  return textValue(form, "title") || automaticTitle;
}

export function interviewBody(form: FormData, ctx: InterviewBodyContext) {
  const location = locationFromForm(form);
  const commonPayload = {
    title: interviewTitle(form, ctx.automaticTitle),
    place: textValue(form, "place"),
    language: textValue(form, "language"),
    notes: textValue(form, "notes"),
    status: ctx.canPickStatus ? textValue(form, "status") || "PENDING" : "PENDING",
    workshopId: ctx.workshopId || null,
    artisanIds: ctx.artisanIds,
    responses: ctx.responses
  };
  return ctx.editingId
    ? { ...commonPayload, ...(location ? { location } : {}) }
    : {
        ...commonPayload,
        questionnaireId: ctx.questionnaireId || null,
        recordedAt: recordedAtFromForm(form),
        recordedTimezone: recordedTimezoneFromForm(form),
        location
      };
}
