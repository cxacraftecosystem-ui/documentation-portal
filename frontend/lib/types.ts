export type UserRole = "MASTER_ADMIN" | "ADMIN" | "RESEARCHER";
export type RecordStatus = "DRAFT" | "PENDING" | "APPROVED" | "REJECTED";
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
};

export type LocationPayload = {
  latitude?: number | "";
  longitude?: number | "";
  altitude?: number | "";
  accuracy?: number | "";
  address?: string;
  placeName?: string;
};

export type Craft = {
  id: string;
  name: string;
  localName?: string | null;
  category?: string | null;
  description?: string | null;
  place?: string | null;
};

export type Artisan = {
  id: string;
  name: string;
  localName?: string | null;
  gender?: string | null;
  phone?: string | null;
  email?: string | null;
  place: string;
  address?: string | null;
  notes?: string | null;
  status: RecordStatus;
  craftId?: string | null;
  craft?: Craft | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

export type Workshop = {
  id: string;
  title: string;
  date: string;
  place: string;
  description?: string | null;
  notes?: string | null;
  status: RecordStatus;
  artisans?: Array<{ artisan: Artisan }>;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

export type MediaFile = {
  id: string;
  originalFilename: string;
  mediaType: MediaType;
  mimeType: string;
  sizeBytes: number | string;
  objectKey: string;
  url?: string | null;
  caption?: string | null;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  status: RecordStatus;
  transcriptText?: string | null;
  transcriptSummary?: string | null;
  transcriptStatus?: string | null;
  transcriptError?: string | null;
  createdAt: string;
};

export type ProductDocumentation = {
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
  artisanId?: string | null;
  craftId?: string | null;
  workshopId?: string | null;
  media?: MediaFile[];
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

export type ToolDocumentation = {
  id: string;
  craftName: string;
  place: string;
  artisanName: string;
  toolkitName: string;
  localName?: string | null;
  englishName?: string | null;
  processUsedIn?: string | null;
  material?: string | null;
  yearsInUse?: number | null;
  height?: string | number | null;
  width?: string | number | null;
  lengthInches?: string | number | null;
  breadthInches?: string | number | null;
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
  artisanId?: string | null;
  craftId?: string | null;
  workshopId?: string | null;
  media?: MediaFile[];
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

export type QuestionnaireQuestion = {
  id: string;
  sectionCode: string;
  sectionTitle: string;
  prompt: string;
  sortOrder: number;
  isActive: boolean;
};

export type QuestionnaireResponse = {
  id: string;
  questionId: string;
  answerText?: string | null;
  notes?: string | null;
  question?: QuestionnaireQuestion;
  answeredBy?: User;
};

export type QuestionnaireInterview = {
  id: string;
  title: string;
  interviewDate?: string | null;
  place?: string | null;
  language?: string | null;
  notes?: string | null;
  status: RecordStatus;
  artisans?: Array<{ artisan: Artisan }>;
  responses?: QuestionnaireResponse[];
  media?: MediaFile[];
  createdBy?: User;
  createdById: string;
  createdAt: string;
};

export const productTypes = ["FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER"];
export const marketDemandOptions = ["LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN"];
export const makerOptions = ["ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER"];
export const traditionOptions = ["TRADITIONAL", "MODERN", "HYBRID", "UNKNOWN"];
export const mediaTypes: MediaType[] = ["IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"];
