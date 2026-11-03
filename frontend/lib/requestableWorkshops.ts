export type RequestableAnswer<Row> = {
  items: Row[];
  truncated: boolean;
};

export function readRequestableAnswer<Row>(body: unknown): RequestableAnswer<Row> {
  if (Array.isArray(body)) return { items: body as Row[], truncated: false };
  if (body && typeof body === "object" && Array.isArray((body as { items?: unknown }).items)) {
    const envelope = body as { items: Row[]; truncated?: unknown };
    return { items: envelope.items, truncated: envelope.truncated === true };
  }
  throw new Error("The list of workshops came back in a shape this page does not understand.");
}

export const REQUESTABLE_CAP_HINT = "The most recent come first.";

export function requestableCeilingSentence(shown: number, truncated: boolean): string {
  if (!truncated || shown <= 0) return "";
  return `Showing the first ${shown}. There are more than are shown. ${REQUESTABLE_CAP_HINT}`;
}
