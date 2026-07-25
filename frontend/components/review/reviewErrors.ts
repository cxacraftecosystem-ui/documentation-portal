import { ApiError } from "@/lib/api";

/** The `detail` of an API error response, whatever shape the server chose for it. */
function errorDetail(error: unknown): unknown {
  if (!(error instanceof ApiError)) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return null;
  return (payload as { detail: unknown }).detail;
}

/**
 * The sentence the server actually wrote, dug back out of the response body.
 *
 * `apiFetch` builds `ApiError.message` with `String(detail)`, which is right for the plain-string
 * details most routes raise and useless for the structured ones the review-edit route produces:
 * FastAPI's 422 detail is a LIST of per-field errors (an unknown column, a name blanked past its
 * `min_length=1`), and it stringifies to "[object Object]" — which tells a reviewer nothing about
 * which box they must fix.
 *
 * Same logic as `readableError` in components/forms/ArtisanForm.tsx; kept as its own module here so
 * the review editor does not import a page-sized form to reach one helper.
 */
export function readableError(error: unknown, fallback: string): string {
  const detail = errorDetail(error);
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const messages = detail
      .map((entry) => {
        if (!entry || typeof entry !== "object") return "";
        const record = entry as { msg?: unknown; loc?: unknown };
        const message = "msg" in record ? String(record.msg) : "";
        if (!message) return "";
        // `loc` is ["fields"? , "<column>"] — naming the column is the whole point of showing a 422
        // from a form with a dozen boxes in it.
        const field = Array.isArray(record.loc) ? record.loc.filter((part) => typeof part === "string").at(-1) : null;
        // Pydantic prefixes every custom validator message with "Value error, "; the reviewer only
        // needs the sentence after it.
        const cleaned = message.replace(/^Value error,\s*/, "").trim();
        return field ? `${field}: ${cleaned}` : cleaned;
      })
      .filter(Boolean);
    if (messages.length) return messages.join(" ");
  }
  if (detail && typeof detail === "object" && "message" in detail) {
    const message = String((detail as { message: unknown }).message);
    if (message.trim()) return message;
  }
  const message = error instanceof Error ? error.message : "";
  return message && message !== "[object Object]" ? message : fallback;
}
