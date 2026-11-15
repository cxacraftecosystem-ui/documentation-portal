export type EditAccess = "FULL" | "FILL_EMPTY" | "NONE";

const ALWAYS_SENT = new Set(["formContract", "expectedUpdatedAt", "duplicateAcknowledged"]);

const DEVICE_ONLY = new Set(["capturedAt", "altitude", "accuracy"]);

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T/;

type Plain = Record<string, unknown>;

function isPlain(value: unknown): value is Plain {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function isBlankValue(value: unknown): boolean {
  if (value === null || value === undefined) return true;
  if (typeof value === "string") return value.trim() === "";
  if (Array.isArray(value)) return value.length === 0;
  if (isPlain(value)) return Object.values(value).every(isBlankValue);
  return false;
}

function digits(value: unknown): string {
  return String(value ?? "").replace(/\D/g, "");
}

function asNumber(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string" && value.trim() !== "" && Number.isFinite(Number(value))) return Number(value);
  return null;
}

function sameText(next: string, stored: string): boolean {
  const a = next.trim();
  const b = stored.trim();
  if (a === b) return true;
  if (ISO_DATE.test(a) && ISO_INSTANT.test(b)) return b.slice(0, 10) === a;
  if (ISO_INSTANT.test(a) && ISO_INSTANT.test(b)) {
    const left = Date.parse(a);
    const right = Date.parse(b);
    return Number.isFinite(left) && left === right;
  }
  return false;
}

export function sameAsStored(next: unknown, stored: unknown): boolean {
  if (isBlankValue(next) && isBlankValue(stored)) return true;
  if (isBlankValue(next) !== isBlankValue(stored)) return false;
  if (typeof next === "string" && typeof stored === "string") return sameText(next, stored);
  const left = asNumber(next);
  const right = asNumber(stored);
  if (left !== null && right !== null) return left === right;
  if (typeof next === "boolean" || typeof stored === "boolean") return next === stored;
  if (Array.isArray(next) && Array.isArray(stored)) {
    return next.length === stored.length && next.every((item, index) => sameAsStored(item, stored[index]));
  }
  if (isPlain(next) && isPlain(stored)) {
    return Object.entries(next).every(
      ([key, value]) => DEVICE_ONLY.has(key) || isBlankValue(value) || sameAsStored(value, stored[key])
    );
  }
  return false;
}

function sameField(key: string, next: unknown, record: Plain): boolean {
  if (key === "phone") return digits(next) === digits(record.phone) || (isBlankValue(next) && isBlankValue(record.phone));
  if (key === "extraMetadata") return isBlankValue(next);
  return sameAsStored(next, record[key]);
}

export function editedFields<T extends Plain>(body: T, record: object | null | undefined): Partial<T> {
  if (!record) return body;
  const stored = record as Plain;
  const out: Plain = {};
  for (const [key, value] of Object.entries(body)) {
    if (value === undefined) continue;
    if (ALWAYS_SENT.has(key) || !sameField(key, value, stored)) out[key] = value;
  }
  return out as Partial<T>;
}

export function fillsOnly(record: { editAccess?: EditAccess | null } | null | undefined): boolean {
  return record?.editAccess === "FILL_EMPTY";
}

export function viewOnly(record: { editAccess?: EditAccess | null } | null | undefined): boolean {
  return record?.editAccess === "NONE";
}

export const FILL_EMPTY_NOTE = "You can fill empty fields and add media. Filled fields stay as recorded.";

export const VIEW_ONLY_NOTE = "You can view this record. Your workshop access doesn't allow changes to it.";

export const KEPT_AFTER_REFUSAL = "Nothing was lost — your entries and media are still here. Fix it and save again.";

export type SubmissionAccess = {
  canSubmit: boolean;
  assigned?: boolean | null;
  accessLevel?: string | null;
  requestStatus?: string | null;
};

export function submissionRefusal(check: SubmissionAccess | null | undefined): string | null {
  if (!check || check.canSubmit) return null;
  if (check.requestStatus === "PENDING") return "Your access request for this workshop is waiting on an admin.";
  if (check.requestStatus === "DENIED") return "Your access request for this workshop was denied. Ask an admin to reconsider.";
  if (check.requestStatus === "REVOKED") return "Your access to this workshop was revoked. Request access again if you need it.";
  if (check.accessLevel === "VIEW") return "Your access to this workshop is View only. Ask an admin to raise it to Contribute.";
  return "You're not assigned to this workshop. Ask an admin to assign you, or pick another workshop.";
}

export function lockFilledFields(form: HTMLFormElement | null, record: object | null | undefined, names: readonly string[], reason: string): () => void {
  if (!form || !record || !fillsOnly(record as { editAccess?: EditAccess | null })) return () => undefined;
  const stored = record as Plain;
  const touched: Array<() => void> = [];
  for (const name of names) {
    if (isBlankValue(stored[name])) continue;
    form.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(`[name="${name}"]`).forEach((element) => {
      const before = { title: element.title, tabIndex: element.tabIndex, pointer: element.style.pointerEvents };
      if (element instanceof HTMLSelectElement) {
        element.style.pointerEvents = "none";
        element.tabIndex = -1;
      } else {
        const wasReadOnly = element.readOnly;
        element.readOnly = true;
        touched.push(() => {
          element.readOnly = wasReadOnly;
        });
      }
      element.setAttribute("aria-readonly", "true");
      element.dataset.filledLock = "true";
      element.title = reason;
      touched.push(() => {
        element.title = before.title;
        element.tabIndex = before.tabIndex;
        element.style.pointerEvents = before.pointer;
        element.removeAttribute("aria-readonly");
        delete element.dataset.filledLock;
      });
    });
  }
  return () => touched.forEach((undo) => undo());
}

export const FILLED_FIELD_REASON = "Filled in by the person who recorded it. Only they, an admin or someone with Edit access can change it.";

export type ResponseInput = { questionId: string; answerText?: string | null; notes?: string | null };

export function changedResponses<T extends ResponseInput>(responses: readonly T[], stored: readonly ResponseInput[] | null | undefined): T[] {
  const before = new Map((stored ?? []).map((row) => [row.questionId, row]));
  return responses.filter((response) => {
    const previous = before.get(response.questionId);
    if (!previous) return true;
    return (previous.answerText ?? "").trim() !== (response.answerText ?? "").trim() || (previous.notes ?? "") !== (response.notes ?? previous.notes ?? "");
  });
}
