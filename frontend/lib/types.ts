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
};

export type FieldProvenanceEntry = { by?: string; byName?: string; at?: string };
export type FieldProvenance = Record<string, FieldProvenanceEntry>;
export type ExtraMetadata = { fieldProvenance?: FieldProvenance } & Record<string, unknown>;

/**
 * `GET /reference/address` — the state list the API validates writes against, served so a form's
 * dropdown and the validator cannot hold different lists (backend/app/services/address.py).
 * `statesAndUnionTerritories` is the flat list a single-group dropdown binds to; the two grouped
 * arrays are the same names split for a form that wants a labelled "Union territories" heading.
 */
export type AddressReference = {
  version: number;
  states: string[];
  unionTerritories: string[];
  statesAndUnionTerritories: string[];
  /**
   * The 795 districts, keyed by the state they belong to — the same shape and the same call as the
   * state list, because a district dropdown that fetches its own options after a state is chosen
   * stalls visibly on a field connection and can briefly disagree with what the server validates
   * against. `byState` covers every name in `statesAndUnionTerritories`, so a chosen state always
   * has options.
   *
   * OPTIONAL IN THE TYPE, not in the contract. The frontend and the API deploy separately, so there
   * is a window in which this build is talking to a backend that predates the district list — and
   * the district dropdown is a REQUIRED field, so the difference between "no options" and a crash
   * is the difference between a form somebody can still submit and a white screen. Marked here so
   * the compiler makes every reader deal with it rather than trusting the version on the other end.
   */
  districts?: {
    source: string;
    sourceUrl: string;
    /** The date the list was compiled, so an export can record which vintage it was coded against. */
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
  // Every interview this artisan belongs to (alone, in a subset, or in a larger set), with recordings.
  interviews?: ArtisanInterview[];
};

/**
 * Two answers to two different questions, in one payload. See `components/forms/LocationFields`.
 *
 * PROVENANCE — `latitude`, `longitude`, `altitude`, `accuracy`, `capturedAt`, `placeName`,
 * `address`. Where the DEVICE was. Filled automatically, never presented as the subject's address.
 *
 * STATED — `state`, `district`, `village`, `pincode`, `subjectLatitude`, `subjectLongitude`. Where
 * the SUBJECT is, said by the researcher. The geocoder may offer these; only a person writes one.
 */
export type LocationPayload = {
  latitude?: number | "";
  longitude?: number | "";
  altitude?: number | "";
  accuracy?: number | "";
  /** ISO 8601. When the device produced the fix above — provenance is not provenance without it. */
  capturedAt?: string;
  address?: string;
  placeName?: string;
  /** Canonical name from `AddressReference`; the API rejects anything off that list. */
  state?: string | null;
  /** Canonical district of `state`, from `AddressReference.districts.byState`. */
  district?: string | null;
  /** The village or hamlet. Free text — no closed list of Indian villages exists. */
  village?: string | null;
  /** Bare 6 digits, no separators — the API normalises "380 001" but stores "380001". */
  pincode?: string | null;
  /** The researcher's optional pin on the SUBJECT'S place. Both or neither; never the device fix. */
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
  // The workshop this craft was documented at. Nullable everywhere: it was added after the join
  // tables, so historical rows carry none. The API hydrates `workshop` alongside the scalar id.
  workshopId?: string | null;
  workshop?: Workshop | null;
  extraMetadata?: ExtraMetadata | null;
  createdAt?: string;
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
  /**
   * The artisan deduplication key (UNIQUE server-side): the same person documented at two workshops
   * resolves to one record through this column. It arrives in TWO shapes under the same name, which
   * is why it is typed as a plain nullable string rather than as 12 digits — the artisan record
   * itself (`GET /artisans/{id}`, i.e. the edit form) returns the FULL number, while the data
   * browser, the .xlsx report and CSV exports return it MASKED ("XXXX XXXX 9012"). Treat any value
   * as regulated personal data: never render it in a list, a card or an export view.
   */
  aadhaarNumber?: string | null;
  /**
   * Date of birth, ISO. The artisan record sheet shows an AGE, derived from this server-side on
   * every read — an age stored would be wrong within a year with nothing to say so. Both this and
   * `experienceYears` were read only from legacy metadata the record form stopped writing years
   * ago, so the sheet printed two permanently empty cells and nothing could record either fact.
   */
  dateOfBirth?: string | null;
  /**
   * The day this artisan took up the craft, ISO. The record sheet's experience figure is DERIVED
   * from it server-side on every read; a stated number of years is right on the day it is typed and
   * silently wrong from then on. Null on every row recorded before the column existed — no backfill
   * invents one, because a date computed from `experienceYears` would be indistinguishable from a
   * date somebody stated.
   */
  craftStartDate?: string | null;
  /** Years practising the craft. 0..90, matching the stage registry's own bounds. */
  experienceYears?: number | null;
  /**
   * The odd months on top of the years. 0..11 — a REMAINDER, never a total; twelve months is a year
   * the field above already holds. Null and 0 are different answers and both are kept: an artisan
   * who said "about thirty years" said nothing whatever about months. Reaches no export surface.
   */
  experienceMonths?: number | null;
  /** Does the artisan hold a PM Vishwakarma Pehchan card? Defaults to true on create. */
  pehchanCardAvailable?: boolean;
  /** Only ever set while `pehchanCardAvailable` is true — the API nulls it whenever the answer is No. */
  pehchanCardNumber?: string | null;
  // Newline-separated, numbered Do's (positive prompt) and Don'ts (negative prompt). Required on new
  // records; existing rows may be null until backfilled.
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

/**
 * The artisan already holding an identity number. Deliberately just enough to recognise a person and
 * navigate to them — the API returns it to a caller who already possesses the number they searched
 * with, and nothing more than name/place/craft/workshop is needed to answer "is this the same man?".
 */
export type ArtisanIdentityMatch = {
  id: string;
  name: string;
  place?: string | null;
  craft?: string | null;
  workshop?: string | null;
};

/**
 * `GET /artisans/lookup/aadhaar` — the artisan form's pre-flight duplicate check. It never 404s:
 * `{found: false}` is the expected, successful answer.
 */
export type AadhaarLookupResult = { found: boolean; artisan?: ArtisanIdentityMatch | null };

/**
 * The HTTP 409 `detail` from POST/PATCH /artisans when an identity number is already on another
 * record. It is an object, not a string, so it must be read off `ApiError.payload` — `ApiError.message`
 * stringifies to "[object Object]" for structured details.
 */
export type ArtisanIdentityConflict = {
  code: "artisan_identity_conflict";
  field: "aadhaarNumber" | "pehchanCardNumber";
  message: string;
  existingArtisan?: ArtisanIdentityMatch;
  maskedValue?: string | null;
};

export type Workshop = {
  id: string;
  title: string;
  /**
   * DESIGN_PROTOTYPE or OTHER. Every row recorded before this column reads OTHER, which is what it
   * implicitly was, and `GET /workshops?workshopType=` narrows the list to one kind. The token names
   * a thing this product does not model — it exists so a workshop recorded here and later adopted by
   * the sibling product can carry the same mark.
   */
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
  /**
   * Which questionnaire is in use at this workshop, chosen once by an admin
   * (`PUT /workshops/{id}/questionnaire`). `null` means "not chosen", which resolves to the default
   * instrument at read time — it does NOT mean the workshop has no questionnaire.
   */
  questionnaireId?: string | null;
  questionnaire?: Questionnaire | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
  /**
   * Idempotency key for an offline create. WRITTEN BY THE OUTBOX, NEVER BY A FORM, and absent on
   * every row nobody replayed.
   *
   * It composes with `createdId` in `lib/offline.ts` rather than replacing it: `createdId` is what
   * this browser profile learned when an answer came BACK, and it cannot guard the case where the
   * answer never arrived — which is the case this key exists for, and the only guard that survives a
   * queue restored onto another device or drained after a sign-out. Send it on POST only: the
   * server's update schemas do not declare it and every request body is `extra="forbid"`, so a key
   * on a PATCH is a 422 an outbox would re-attempt for ever.
   */
  clientKey?: string | null;
};

/**
 * Answer from `GET /workshops/{id}/submission-check` — what submitting a record into one workshop
 * would mean for the current user, asked BEFORE the record is sent.
 *
 * - `canSubmit` false: the workshop has assignments and the user is not one of them, so a create
 *   would be refused with 403. Warn at select time rather than at save time.
 * - `needsAdminApproval` true: the submission is accepted but forced to PENDING, and only an admin
 *   or master admin may approve it. Admins never see this (they are the approval authority), so an
 *   admin submitting late sees `outOfWindow` true with `needsAdminApproval` false.
 */
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
  transcriptSummary?: string | null;
  transcriptStatus?: string | null;
  transcriptError?: string | null;
  /**
   * WHEN A PERSON LAST REPLACED THE TRANSCRIPT ABOVE, and NULL MEANS "NOT STATED" — never "never
   * edited". `POST /media/{id}/transcript` has been able to replace a transcript since long before
   * these columns existed, so rows stored before migration 20260913120200 genuinely do not say.
   *
   * RENDER THREE STATES, NOT TWO. `edited={!!media.transcriptEditedAt}` collapses "not stated" into
   * "not edited" and prints "the machine said this" over text a researcher may well have typed —
   * which is the single assertion this column was added to stop being made silently. Pass
   * `transcriptEditedAt ? true : undefined` and let the badge draw nothing for undefined.
   */
  transcriptEditedAt?: string | null;
  /** Who made that edit. A bare id with no relation — an audit stamp, not a navigable edge. */
  transcriptEditedById?: string | null;
  uploadedBy?: User | null;
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
  /** Idempotency key for an offline create. See `Workshop.clientKey`. */
  clientKey?: string | null;
};

/**
 * ONE ROW OF THE `ToolCraft` JOIN TABLE — a tool and one of the crafts it is linked to.
 *
 * `ToolDocumentation.craftId` is NOT retired and still holds the FIRST of the selected crafts, which
 * is what every existing filter, index, report and carry-forward reads; `craftName` holds every
 * selected name joined ", " in the same order; and this is the table that holds ALL of them. The
 * shape mirrors `ToolArtisan` column for column, deliberately — two join tables off one parent that
 * disagree about their own shape is how a later reader comes to believe one of them means something
 * the other does not.
 *
 * THE SERVER RETURNS THESE IN `craftName` ORDER, because the join table has no ordinal of its own.
 * Nothing on this client re-sorts them: the order is the researcher's own tick order, it is the wire
 * contract for `craftIds` on the way back up, and re-deriving it here would be a second opinion
 * about a question the route already answered.
 */
export type ToolCraftLink = {
  id: string;
  toolId: string;
  craftId: string;
  createdAt?: string;
  craft?: Craft | null;
};

/**
 * One row of the `ToolArtisan` join table. Predates the craft one by three months
 * (`20260618150000_tool_artisan_links`) and is the table "Assign a tool to multiple artisans" has
 * always written; the tool form's own artisan picker now writes it too, rather than inventing a
 * second mechanism for the same fact.
 *
 * ── THE TOOL'S OWN ARTISAN COMES FIRST, AND THE SENTENCE THAT SAID OTHERWISE IS QUOTED ────
 * It read: *"Returned `createdAt asc, id asc` — 'oldest first', which is what
 * `GET /tools/{id}/artisans` already promises and what `ToolAssignmentSection` already renders."*
 * The second half was true of THAT ROUTE and said nothing about this field, which had no order at
 * all: `hydrate_relations` issues a bare `find_many(where=...)`, so the rows arrived in whatever
 * order the query plan produced.
 *
 * WHAT IS TRUE NOW: `routes/tools._order_artisan_links` sorts every encoded tool — the row whose
 * `artisanId` matches `tool.artisanId` FIRST, then the rest `createdAt asc, id asc`. The pin is the
 * load-bearing half and the tiebreak alone would not have done: `_replace_artisan_links` writes a
 * whole selection in ONE `create_many`, so every row of one save shares a `createdAt` and the tie
 * falls to a cuid. `craftLinks` above needs no pin because `craftName` records every name in tick
 * order and `_order_craft_links` reads the order back out of it; `artisanName` holds only the FIRST
 * name, so the artisan side has no such ordinal and the scalar is it.
 *
 * A CLIENT STILL PUTS THE SCALAR FIRST ITSELF, and that is not distrust of the above. Two cases the
 * server-side pin cannot cover: a tool whose `artisanId` has NO link row has nothing to pin (the
 * assignment endpoint adds links without touching the scalar, and its DELETE removes them just as
 * freely — migration 20260916090000 backfilled the historical ones, not the future ones); and
 * this bundle may be reading an API that predates the ordering, because the web deploys to Vercel
 * and the API to EC2 separately. `ToolForm.storedArtisanIds` therefore seeds element 0 from
 * `artisanId` itself, which is also what the route writes back into that column on the next save.
 */
export type ToolArtisanLink = {
  id: string;
  toolId: string;
  artisanId: string;
  createdAt?: string;
  artisan?: Artisan | null;
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
  /**
   * The third of the triple, and the ONLY height column that records its unit. `height` above is the
   * old unit-less one, kept for what is already stored — nothing in the database can say what unit
   * those values are in, so they were never copied across. The measurement panel fills THIS one.
   */
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
  /**
   * EVERY linked craft and EVERY linked artisan, where `craftId` / `artisanId` above hold only the
   * first of each.
   *
   * OPTIONAL ON THE TYPE THOUGH THE ROUTE ALWAYS SENDS THEM, and the reason is the one this
   * repository has to keep in mind in exactly one direction: **the web deploys to Vercel and the API
   * to EC2, separately** (see `forms/measurementMethods.ts`), so a newer bundle can be reading an
   * older API that has no join table to include. `[]` and "this deployment does not serve them" are
   * different facts, and a form that seeded a multi-select from `?? []` would read the second as the
   * first and open an edit form with every link unticked — one Save away from deleting them. The
   * tool form therefore falls back to the scalar `craftId` / `artisanId` when the key is ABSENT.
   */
  craftLinks?: ToolCraftLink[];
  artisanLinks?: ToolArtisanLink[];
  workshopId?: string | null;
  workshop?: Workshop | null;
  media?: MediaFile[];
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
  /** Idempotency key for an offline create. See `Workshop.clientKey`. */
  clientKey?: string | null;
};

/**
 * ONE NAMED INSTRUMENT — the parent of every section, question and interview below.
 *
 * Two exist: the 2nd Craft Toolkit Workshop's (24 sections, RESP/A..W) and the 3rd's (22 sections,
 * A..V). Their section CODES collide completely, so a section is only ever identified by its id or
 * by `(questionnaireId, code)` — never by its code alone. Anything rendering "Section A" without
 * saying which instrument it belongs to is making an unverifiable claim.
 *
 * `isDefault` is where every request that names no instrument lands, including Android builds that
 * predate the field. It is an admin decision (`PUT /questionnaires/{id}/default`), never a client's.
 */
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
  /**
   * THE PRO-FORMA'S "Help text" COLUMN. Guidance an admin typed under a question in Excel —
   * "count from the first year they worked unsupervised", "ask about the vat, not the colour".
   *
   * DECLARED HERE BECAUSE THE SERVER ALREADY SENDS IT, and a wire field that is not declared is a
   * field this client cannot read without a cast. `backend/prisma/schema.prisma:1311` holds the
   * column, `backend/app/schemas/questionnaire.py:94` accepts it on create, and the section payload
   * returns it (`backend/app/api/routes/questionnaire.py:2441`). Until this line existed,
   * `components/questionnaires/QuestionHelpText.tsx` bridged the gap with an intersection type whose
   * own docstring said to delete it the moment the field landed; it has been deleted.
   *
   * OPTIONAL, AND THAT IS NOT LAZINESS. `apiFetch` casts a response body and validates no schema
   * (`lib/api.ts`), so what arrives is whatever the deployment on the other end sends. This app is
   * shipped as a browser page against a backend that is deployed separately, and a build opened
   * against a server from before the migration receives an object with no such key. `helpText: string
   * | null` would make the type claim something the wire cannot promise, and every reader would then
   * be one `undefined` away from printing "undefined" under a question.
   */
  helpText?: string | null;
  /**
   * THE PRO-FORMA'S "Required" COLUMN — what the INSTRUMENT asks for, never what this form enforces.
   *
   * `RequiredByInstrument` (components/questionnaires/QuestionHelpText.tsx) prints a quiet word and
   * deliberately NOT `components/ui/RequiredMark`'s asterisk, because /questionnaire does not block a
   * save on it: a researcher in a courtyard with an artisan who will not answer question 54 must
   * still be able to record the other eighty. Do not wire this into a `required` attribute.
   *
   * NOT NULL WITH A DEFAULT server-side (schema.prisma:1315) — "not stated" and "not required" are
   * the same instruction to a researcher — so the only reason this is optional is the same
   * older-deployment argument as `helpText` above, and `question.isRequired` being `undefined` reads
   * as false at every call site, which is the right answer for a server that has never heard of it.
   */
  isRequired?: boolean;
  /**
   * WHEN THIS QUESTION STOPPED BEING ASKED BECAUSE ANSWERS ALREADY EXISTED. READ ONLY, ALWAYS.
   *
   * DISTINCT FROM `isActive`, WHICH HAS ANOTHER OWNER. `DELETE /questionnaire/questions/{id}`
   * soft-deletes by writing `isActive = false` and nothing else, so `isActive` alone cannot tell "a
   * professor switched this off last March" from "this was retired because answers exist". The
   * workbook re-upload reads exactly that distinction: it REACTIVATES a question it finds named in an
   * uploaded workbook and must never reactivate a retired one, or downloading a questionnaire and
   * uploading it back unchanged resurrects every question anybody ever replaced, each standing next
   * to its replacement (`backend/app/api/routes/questionnaire.py:2091-2109`).
   *
   * NOTHING IN THIS CLIENT MAY SEND IT. `QuestionnaireQuestionCreate` and `QuestionnaireQuestionUpdate`
   * both omit it on purpose and `APIModel` is `extra="forbid"`, so a PATCH carrying `retiredAt` is
   * refused with a 422 rather than ignored — see the paragraph at
   * `backend/app/schemas/questionnaire.py:107-112`. It is declared here so the builder and the
   * workbook report can SHOW a retirement, never so a form can set one.
   */
  retiredAt?: string | null;
  /**
   * The question that replaced this one when an answered question was reworded.
   *
   * A PLAIN ID STRING, not an embedded question: the column is a plain string server-side
   * (schema.prisma:1335) precisely so that reading a question costs no join, and typing it as
   * `QuestionnaireQuestion` here would invite a reader to dereference a field the wire never fills.
   * The consolidated document walks this chain so a reworded question and its replacement print as
   * one question rather than as two with the answers split between them.
   *
   * Send-side rules are `retiredAt`'s, for the same reason and out of the same paragraph.
   */
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
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  workshopId?: string | null;
  workshop?: Workshop | null;
  /**
   * Which instrument this sitting was taken on. NOT NULL server-side and never changes: it is half
   * of the one-interview-per-artisan-set uniqueness key, so the same artisans may sit once for each
   * instrument and the two sittings do not fold into one another.
   */
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

/**
 * The bare stored row's status. Kept in step with `components/tasks/types.ts` — which is the union
 * the task screens actually render — because one of these two unions describing four states and the
 * other five is how a `status` read through `AssignedTask` quietly loses the review state.
 *
 * `SUBMITTED` is the assignee's "finished" awaiting an admin's agreement; `DONE` now means somebody
 * with authority agreed. The wording for both is served (`statusLabel`), never mapped here.
 */
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
  /**
   * The row's state. `GET /workshops/{id}/assignments` returns EVERY row on the workshop — pending
   * requests, denials and revocations included — and only GRANTED confers access. A caller building
   * "who is assigned" must filter on this; taking every `userId` treats a refused request as a member.
   */
  status?: "PENDING" | "GRANTED" | "DENIED" | "REVOKED";
  accessLevel?: string;
};

export const productTypes = ["FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER"];
export const marketDemandOptions = ["LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN"];
export const makerOptions = ["ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER"];
export const traditionOptions = ["TRADITIONAL", "MODERN", "HYBRID", "UNKNOWN"];
export const mediaTypes: MediaType[] = ["IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"];
