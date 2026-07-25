/**
 * The reviewer-editable surface per record type, and where to read a record's current values from.
 *
 * This is the web half of a contract the Android app already implements — MainActivity's
 * `reviewEditableFields` / `loadReviewRecordValues`. The two clients MUST offer the same fields, so
 * a reviewer sees the same record whichever one they picked up, and edits made on a phone in the
 * field are the same edits a professor can make from a laptop. Change one, change the other.
 *
 * Chosen for this list: the name-like columns and the prose a reviewer is qualified to fix ("a
 * misspelt village or a craft name in the wrong column").
 *
 * Deliberately NOT here:
 * - `status` — that moves through Approve / Reject / Send for revision (or this endpoint's own
 *   `approve` flag), never through a field edit;
 * - the workshop, location and linked-record lists — the API refuses all of them with a 422
 *   (`_NOT_REVIEW_EDITABLE` in backend/app/api/routes/review.py) because they are separate writes
 *   with their own assignment/window checks, not column updates;
 * - the measurement and price decimals, and the validated identity fields (Aadhaar, Pehchan, phone,
 *   email) — those belong on the record's own edit screen, where the formatting, duplicate and
 *   checksum rules are enforced by a purpose-built form rather than a bare text box.
 */

export type ReviewField = {
  /** The API column name. It travels verbatim in the `fields` map and is validated against the
   *  record type's own PATCH schema, so a typo here is a 422 rather than a silent no-op. */
  key: string;
  label: string;
  multiline?: boolean;
  /** Mirrors `min_length=1` on the record's update schema — the column cannot be blanked. */
  required?: boolean;
};

const FIELDS: Record<string, ReviewField[]> = {
  artisan: [
    { key: "name", label: "Name", required: true },
    { key: "localName", label: "Local name" },
    { key: "place", label: "Place", required: true },
    { key: "address", label: "Address", multiline: true },
    { key: "notes", label: "Notes", multiline: true },
    { key: "dos", label: "Do's", multiline: true },
    { key: "donts", label: "Don'ts", multiline: true }
  ],
  workshop: [
    { key: "title", label: "Workshop title", required: true },
    { key: "place", label: "Place", required: true },
    { key: "description", label: "Description", multiline: true },
    { key: "notes", label: "Notes", multiline: true }
  ],
  product: [
    { key: "productName", label: "Product name", required: true },
    { key: "localName", label: "Local name" },
    { key: "craftName", label: "Craft name", required: true },
    { key: "artisanName", label: "Artisan name", required: true },
    { key: "place", label: "Place", required: true },
    { key: "rawMaterialsUsed", label: "Raw materials used", multiline: true },
    { key: "mainToolsUsed", label: "Main tools used", multiline: true },
    { key: "productFunctionUse", label: "Function / use", multiline: true },
    { key: "remarks", label: "Remarks", multiline: true }
  ],
  tool: [
    { key: "toolkitName", label: "Toolkit name", required: true },
    { key: "localName", label: "Local name" },
    { key: "englishName", label: "English name" },
    { key: "craftName", label: "Craft name", required: true },
    { key: "artisanName", label: "Artisan name", required: true },
    { key: "place", label: "Place", required: true },
    { key: "processUsedIn", label: "Process used in", multiline: true },
    { key: "material", label: "Material" },
    { key: "suggestionsForToolImprovement", label: "Suggestions for improvement", multiline: true },
    { key: "remarks", label: "Remarks", multiline: true }
  ],
  process: [
    { key: "name", label: "Name of the process", required: true },
    { key: "notes", label: "Notes", multiline: true }
  ],
  questionnaire: [
    { key: "title", label: "Interview title", required: true },
    { key: "place", label: "Place" },
    { key: "language", label: "Language" },
    { key: "notes", label: "Notes", multiline: true }
  ],
  media: [
    { key: "caption", label: "Caption", multiline: true },
    { key: "transcriptText", label: "Transcript", multiline: true }
  ]
};

/** The record's own GET endpoint, so the editor opens on what is actually stored. */
const ENDPOINTS: Record<string, (id: string) => string> = {
  artisan: (id) => `/artisans/${id}`,
  workshop: (id) => `/workshops/${id}`,
  product: (id) => `/products/${id}`,
  tool: (id) => `/tools/${id}`,
  process: (id) => `/processes/${id}`,
  questionnaire: (id) => `/questionnaire/interviews/${id}`,
  media: (id) => `/media/${id}`
};

export function reviewEditableFields(recordType: string): ReviewField[] {
  return FIELDS[recordType.toLowerCase()] ?? [];
}

/** Null for a record type this build has no editor for, which is how the Edit action stays hidden. */
export function reviewRecordEndpoint(recordType: string, id: string): string | null {
  const build = ENDPOINTS[recordType.toLowerCase()];
  return build ? build(id) : null;
}

/**
 * Flatten a fetched record into the editable string values. Anything absent or non-scalar reads as
 * "" so an untouched box and a null column are indistinguishable — which is right, because sending
 * "" for a nullable column is exactly how a reviewer clears it.
 */
export function reviewValuesFrom(record: unknown, fields: ReviewField[]): Record<string, string> {
  const source = (record ?? {}) as Record<string, unknown>;
  const values: Record<string, string> = {};
  for (const field of fields) {
    const value = source[field.key];
    values[field.key] = typeof value === "string" ? value : value === null || value === undefined ? "" : String(value);
  }
  return values;
}
