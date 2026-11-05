export type RequestableAnswer<Row> = {
  items: Row[];
  truncated: boolean;
  nextOffset?: number | null;
};

export function readRequestableAnswer<Row>(body: unknown): RequestableAnswer<Row> {
  if (Array.isArray(body)) return { items: body as Row[], truncated: false, nextOffset: null };
  if (body && typeof body === "object" && Array.isArray((body as { items?: unknown }).items)) {
    const envelope = body as { items: Row[]; truncated?: unknown; nextOffset?: unknown };
    const nextOffset = typeof envelope.nextOffset === "number" && envelope.nextOffset > 0 ? envelope.nextOffset : null;
    return { items: envelope.items, truncated: envelope.truncated === true, nextOffset };
  }
  throw new Error("The list of workshops came back in a shape this page does not understand.");
}

export async function readEveryRequestable<Row extends { id: string }>(fetchPage: (offset: number) => Promise<unknown>): Promise<RequestableAnswer<Row>> {
  const items: Row[] = [];
  const seen = new Set<string>();
  let offset = 0;
  for (;;) {
    const answer = readRequestableAnswer<Row>(await fetchPage(offset));
    const fresh = answer.items.filter((row) => !seen.has(row.id) && Boolean(seen.add(row.id)));
    items.push(...fresh);
    if (!answer.truncated) return { items, truncated: false, nextOffset: null };
    if (!answer.nextOffset || !fresh.length || answer.nextOffset <= offset) return { items, truncated: true, nextOffset: null };
    offset = answer.nextOffset;
  }
}

export const REQUESTABLE_CAP_HINT = "The most recent come first.";

export function requestableCeilingSentence(shown: number, truncated: boolean): string {
  if (!truncated || shown <= 0) return "";
  return `Showing the first ${shown}. There are more than are shown. ${REQUESTABLE_CAP_HINT}`;
}
