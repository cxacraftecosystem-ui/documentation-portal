export type ReviewField = {
  key: string;
  label: string;
  multiline?: boolean;
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

export function reviewRecordEndpoint(recordType: string, id: string): string | null {
  const build = ENDPOINTS[recordType.toLowerCase()];
  return build ? build(id) : null;
}

export function reviewValuesFrom(record: unknown, fields: ReviewField[]): Record<string, string> {
  const source = (record ?? {}) as Record<string, unknown>;
  const values: Record<string, string> = {};
  for (const field of fields) {
    const value = source[field.key];
    values[field.key] = typeof value === "string" ? value : value === null || value === undefined ? "" : String(value);
  }
  return values;
}
