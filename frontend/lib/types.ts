import type { TranscriptPreview } from "@/lib/transcript/contract";
import type { RecordingChips } from "@/lib/questionnaire/chips";
import type { CompletenessBlock } from "@/lib/completenessCore";

export type UserRole =
  | "MASTER_ADMIN"
  | "ADMIN"
  | "PROFESSOR"
  | "RESEARCHER"
  | "FIELD_CONTRIBUTOR"
  | "CROWDSOURCE_VOLUNTEER";
export type RecordStatus = "DRAFT" | "PENDING" | "APPROVED" | "REJECTED" | "NEEDS_REVISION";
export type MediaType = "IMAGE" | "VIDEO" | "AUDIO" | "PDF" | "DOCUMENT" | "OTHER";

export type PageResult<T> = {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
};

export type User = {
  id: string;
  email: string;
  name: string;
  role: UserRole;
  avatarUrl?: string | null;
  authProvider?: string;
  canManageQuestionnaire?: boolean;
  canManageCrafts?: boolean;
  canManageWorkshops?: boolean;
  canReview?: boolean;
  canViewProvenance?: boolean;
  canDownloadDataset?: boolean;
  twoFactorEnabled?: boolean;
  twoFactorEnabledAt?: string | null;
  mustChangePassword?: boolean;
  hasPassword?: boolean;
  passwordSetAt?: string | null;
  firstLoginAt?: string | null;
  lastLoginAt?: string | null;
  createdAt?: string;
};

export type FieldProvenanceEntry = { by?: string; byName?: string; at?: string };
export type FieldProvenance = Record<string, FieldProvenanceEntry>;
export type ExtraMetadata = { fieldProvenance?: FieldProvenance } & Record<string, unknown>;

export type AddressReference = {
  version: number;
  states: string[];
  unionTerritories: string[];
  statesAndUnionTerritories: string[];
  districts?: {
    source: string;
    sourceUrl: string;
    asOf: string;
    listVersion: number;
    count: number;
    byState: Record<string, string[]>;
    normalisation: { trailingWordsStripped: string[]; description: string };
  };
  pincode: { length: number; pattern: string; description: string };
};

export type ArtisanAnswer = {
  responseId: string;
  questionId: string;
  prompt?: string | null;
  sectionCode?: string | null;
  sectionTitle?: string | null;
  sortOrder?: number;
  answerText?: string | null;
  notes?: string | null;
  interviewId: string;
  interviewTitle?: string | null;
  interviewDate?: string | null;
  answeredByName?: string | null;
};

export type ArtisanInterview = {
  interviewId: string;
  title: string;
  notes?: string | null;
  interviewDate?: string | null;
  place?: string | null;
  language?: string | null;
  status?: string | null;
  artisanCount?: number;
  coArtisans?: string[];
  media?: MediaFile[];
};

export type ArtisanQuestionnaire = {
  artisanId: string;
  answered: ArtisanAnswer[];
  total: number;
  interviews?: ArtisanInterview[];
};

export type LocationPayload = {
  latitude?: number | "";
  longitude?: number | "";
  altitude?: number | "";
  accuracy?: number | "";
  capturedAt?: string;
  address?: string;
  placeName?: string;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  pincode?: string | null;
  subjectLatitude?: number;
  subjectLongitude?: number;
};

export type Craft = {
  id: string;
  name: string;
  localName?: string | null;
  category?: string | null;
  description?: string | null;
  place?: string | null;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  workshopId?: string | null;
  workshop?: Workshop | null;
  extraMetadata?: ExtraMetadata | null;
  createdAt?: string;
  slug?: string | null;
  categoryId?: string | null;
  subCategoryId?: string | null;
  sourceSerial?: number | null;
  stateOfOrigin?: string[];
  stateOfPractice?: string[];
  giStatus?: "GI" | "NON_GI" | null;
  isCatchAll?: boolean;
  status?: "ACTIVE" | "LEGACY" | "MERGED";
  mergedIntoId?: string | null;
  craftCode?: string | null;
};

export type Artisan = {
  completeness?: CompletenessBlock | null;
  id: string;
  name: string;
  localName?: string | null;
  gender?: string | null;
  phone?: string | null;
  phoneCountryCode?: string | null;
  email?: string | null;
  place: string;
  address?: string | null;
  notes?: string | null;
  aadhaarNumber?: string | null;
  dateOfBirth?: string | null;
  craftStartDate?: string | null;
  experienceYears?: number | null;
  experienceMonths?: number | null;
  pehchanCardAvailable?: boolean;
  pehchanCardNumber?: string | null;
  aadhaarNotShared?: boolean;
  location?: {
    state?: string | null;
    district?: string | null;
    village?: string | null;
    pincode?: string | null;
  } | null;
  dos?: string | null;
  donts?: string | null;
  status: RecordStatus;
  craftId?: string | null;
  craft?: Craft | null;
  workshopId?: string | null;
  workshop?: Workshop | null;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

export type ArtisanIdentityMatch = {
  id: string;
  name: string;
  place?: string | null;
  craft?: string | null;
  workshop?: string | null;
};

export type AadhaarLookupResult = { found: boolean; artisan?: ArtisanIdentityMatch | null };

export type ArtisanIdentityConflict = {
  code: "artisan_identity_conflict";
  field: "aadhaarNumber" | "pehchanCardNumber";
  message: string;
  existingArtisan?: ArtisanIdentityMatch;
  maskedValue?: string | null;
};

export type Workshop = {
  completeness?: CompletenessBlock | null;
  id: string;
  title: string;
  workshopType?: string;
  date: string;
  startDate?: string | null;
  endDate?: string | null;
  place: string;
  description?: string | null;
  notes?: string | null;
  status: RecordStatus;
  artisans?: Array<{ artisan: Artisan }>;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: ExtraMetadata | null;
  questionnaireId?: string | null;
  questionnaire?: Questionnaire | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
  canRecord?: boolean;
  clientKey?: string | null;
};

export type WorkshopSubmissionCheck = {
  workshopId: string | null;
  title?: string | null;
  endDate?: string | null;
  isOver: boolean;
  outOfWindow: boolean;
  needsAdminApproval: boolean;
  assigned: boolean;
  canSubmit: boolean;
};

export type MediaFile = {
  id: string;
  questionnaireChips?: RecordingChips | null;
  originalFilename: string;
  mediaType: MediaType;
  mimeType: string;
  sizeBytes: number | string;
  objectKey: string;
  url?: string | null;
  caption?: string | null;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  status: RecordStatus;
  transcriptText?: string | null;
  transcriptPreview?: TranscriptPreview | null;
  transcriptSummary?: string | null;
  transcriptStatus?: string | null;
  transcriptError?: string | null;
  transcriptEditedAt?: string | null;
  transcriptEditedById?: string | null;
  uploadedBy?: User | null;
  createdAt: string;
};

export type ProductDocumentation = {
  completeness?: CompletenessBlock | null;
  id: string;
  craftName: string;
  place: string;
  artisanName: string;
  productName: string;
  localName?: string | null;
  productType: string;
  timeTakenToCompleteProduct?: string | null;
  size?: string | null;
  lengthInches?: string | number | null;
  breadthInches?: string | number | null;
  heightInches?: string | number | null;
  measurementImageId?: string | null;
  measurementAnalysis?: Record<string, unknown> | null;
  measurementAnalysisStatus?: string | null;
  costOfMaking?: string | number | null;
  sellingPrice?: string | number | null;
  marketDemand: string;
  rawMaterialsUsed?: string | null;
  mainToolsUsed?: string | null;
  productFunctionUse?: string | null;
  remarks?: string | null;
  status: RecordStatus;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  artisanId?: string | null;
  craftId?: string | null;
  workshopId?: string | null;
  workshop?: Workshop | null;
  media?: MediaFile[];
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
  clientKey?: string | null;
};

export type ToolCraftLink = {
  id: string;
  toolId: string;
  craftId: string;
  createdAt?: string;
  craft?: Craft | null;
};

export type ToolArtisanLink = {
  id: string;
  toolId: string;
  artisanId: string;
  createdAt?: string;
  artisan?: Artisan | null;
};

export type ToolDocumentation = {
  completeness?: CompletenessBlock | null;
  id: string;
  craftName: string;
  place: string;
  artisanName: string;
  toolkitName: string;
  localName?: string | null;
  englishName?: string | null;
  processUsedIn?: string | null;
  material?: string | null;
  materialFamily?: string | null;
  yearsInUse?: number | null;
  height?: string | number | null;
  width?: string | number | null;
  lengthInches?: string | number | null;
  breadthInches?: string | number | null;
  heightInches?: string | number | null;
  measurementImageId?: string | null;
  measurementAnalysis?: Record<string, unknown> | null;
  measurementAnalysisStatus?: string | null;
  thickness?: string | number | null;
  weight?: string | number | null;
  radius?: string | number | null;
  maker: string;
  traditionType: string;
  replacementCost?: string | number | null;
  suggestionsForToolImprovement?: string | null;
  remarks?: string | null;
  status: RecordStatus;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  artisanId?: string | null;
  craftId?: string | null;
  craftLinks?: ToolCraftLink[];
  artisanLinks?: ToolArtisanLink[];
  workshopId?: string | null;
  workshop?: Workshop | null;
  media?: MediaFile[];
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
  clientKey?: string | null;
};

export type Questionnaire = {
  id: string;
  title: string;
  description?: string | null;
  isActive: boolean;
  isDefault: boolean;
  sortOrder: number;
  sectionCount?: number;
  questionCount?: number;
  workshopCount?: number;
  createdAt?: string;
  createdById?: string | null;
};

export type QuestionnaireQuestion = {
  id: string;
  questionnaireId: string;
  sectionId?: string | null;
  sectionCode: string;
  sectionTitle: string;
  prompt: string;
  sortOrder: number;
  isActive: boolean;
  helpText?: string | null;
  artisanField?: string | null;
  isRequired?: boolean;
  retiredAt?: string | null;
  supersededById?: string | null;
};

export type QuestionnaireSection = {
  id: string;
  questionnaireId: string;
  code: string;
  title: string;
  sortOrder: number;
  isActive: boolean;
  questions: QuestionnaireQuestion[];
};

export type QuestionnaireResponse = {
  id: string;
  questionId: string;
  answerText?: string | null;
  notes?: string | null;
  answerSource?: string | null;
  answerOrigin?: string | null;
  question?: QuestionnaireQuestion;
  answeredBy?: User;
};

export type QuestionnaireInterview = {
  completeness?: CompletenessBlock | null;
  id: string;
  title: string;
  interviewDate?: string | null;
  place?: string | null;
  language?: string | null;
  notes?: string | null;
  status: RecordStatus;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  workshopId?: string | null;
  workshop?: Workshop | null;
  questionnaireId: string;
  questionnaire?: Questionnaire | null;
  artisans?: Array<{ artisan: Artisan }>;
  responses?: QuestionnaireResponse[];
  media?: MediaFile[];
  createdBy?: User;
  createdById: string;
  createdAt: string;
};

export type DataAccessTier = "DOWNLOAD" | "COMMENT" | "EDIT";
export type DataAccessStatus = "PENDING" | "GRANTED" | "DENIED" | "REVOKED";

export type DataAccessScopeItem = { id?: string; recordType: string; recordId: string };

export type DataAccessGrant = {
  id: string;
  ownerId: string;
  granteeId: string;
  tier: DataAccessTier;
  status: DataAccessStatus;
  allData: boolean;
  requestNote?: string | null;
  decisionNote?: string | null;
  owner?: User;
  grantee?: User;
  requestedBy?: User | null;
  decidedBy?: User | null;
  scopeItems?: DataAccessScopeItem[];
  decidedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
};

export type MyGrants = { incoming: DataAccessGrant[]; outgoing: DataAccessGrant[] };

export type TierInfo = { tier: DataAccessTier; description: string };

export type EntryComment = {
  id: string;
  recordType: string;
  recordId: string;
  authorId: string;
  body: string;
  author?: User;
  createdAt: string;
  updatedAt?: string;
};

export type RecordRevision = {
  id: string;
  recordType: string;
  recordId: string;
  editedById?: string | null;
  editedBy?: User | null;
  changes: Record<string, { old: unknown; new: unknown }>;
  createdAt: string;
};

export type TaskStatus = "OPEN" | "IN_PROGRESS" | "SUBMITTED" | "DONE" | "CANCELLED";

export type AssignedTask = {
  id: string;
  title: string;
  description?: string | null;
  status: TaskStatus;
  dueAt?: string | null;
  completedAt?: string | null;
  recordType?: string | null;
  recordId?: string | null;
  assigneeId: string;
  createdById: string;
  assignee?: User;
  createdBy?: User;
  createdAt: string;
  updatedAt: string;
};

export type WorkshopAssignment = {
  id: string;
  workshopId: string;
  userId: string;
  user?: User;
  assignedBy?: User | null;
  createdAt?: string;
  status?: "PENDING" | "GRANTED" | "DENIED" | "REVOKED";
  accessLevel?: string;
};

export const productTypes = ["FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER"];
export const marketDemandOptions = ["LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN"];
export const makerOptions = ["ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER"];
export const traditionOptions = ["TRADITIONAL", "CONTEMPORARY", "TRANSITIONAL"];
export const mediaTypes: MediaType[] = ["IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"];

export type InsightQuote = {
  original: string;
  english: string;
  speaker: string | null;
};

export type InsightTranslationStatus = "NOT_NEEDED" | "COMPLETED" | "PARTIAL" | "FAILED";

export type TranscriptInsight = {
  id: string;
  version: number;
  sourceLanguage: string | null;
  summaryLines: string[];
  quotes: InsightQuote[];
  translation: { status: InsightTranslationStatus; text: string | null; note: string | null };
  provenance: {
    provider: string;
    modelId: string;
    modelVersion: string | null;
    promptVersion: string;
    generatedAt: string;
  };
  edited: { at: string } | null;
};

export type TranscriptInsightState = "READY" | "QUEUED" | "PROCESSING" | "FAILED" | "NO_TRANSCRIPT" | "NOT_GENERATED";

export type TranscriptInsightView = {
  mediaId: string;
  state: TranscriptInsightState;
  stale: boolean;
  canEdit: boolean;
  canRegenerate: boolean;
  error: string | null;
  insight: TranscriptInsight | null;
};
