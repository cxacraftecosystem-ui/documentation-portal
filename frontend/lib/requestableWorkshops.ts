/**
 * `GET /workshops/requestable`, as the wire answers it — byte for byte the same file in both portals.
 *
 * THE ROUTE ANSWERS AN ENVELOPE, `{"items": [...], "truncated": bool}`, and it used to answer a bare
 * array. The server changed and the request panel did not: it went on handing the envelope to
 * `sortWorkshopsByOccurrence`, which spreads its argument, and Settings → "Request workshop access"
 * died with "e is not iterable" in production. Every reader of this route goes through
 * {@link readRequestableAnswer} so that cannot happen twice.
 *
 * A BARE ARRAY IS STILL ACCEPTED, for the minutes a deploy spends with the new web bundle in front of
 * the old API (and for the other portal's server until both answer the envelope). A bare array cannot
 * say whether it was cut, so it reads as `truncated: false` — the same silence the panel kept before
 * the envelope existed, and never a claim that more exist.
 *
 * ANYTHING ELSE THROWS. `apiFetch` casts rather than validates, so a third shape would otherwise reach
 * the picker as `undefined` rows and draw "no workshops" — a claim about the repository made from an
 * answer nobody understood. Throwing sends it to the panel's failed arm instead, which says the list
 * could not be loaded.
 */

export type RequestableAnswer<Row> = {
  items: Row[];
  /** The server read one row past its cap and found it: there are more workshops than were sent. */
  truncated: boolean;
};

export function readRequestableAnswer<Row>(body: unknown): RequestableAnswer<Row> {
  if (Array.isArray(body)) return { items: body as Row[], truncated: false };
  if (body && typeof body === "object" && Array.isArray((body as { items?: unknown }).items)) {
    const envelope = body as { items: Row[]; truncated?: unknown };
    // `=== true`, not truthiness: only the server's own boolean may raise the ceiling sentence.
    return { items: envelope.items, truncated: envelope.truncated === true };
  }
  throw new Error("The list of workshops came back in a shape this page does not understand.");
}

/**
 * The last clause of the ceiling sentence. The route orders by when the workshop happened, most
 * recent first, so what the cap leaves out is the oldest — and that is the one thing a reader can
 * act on: a workshop that is not listed is older than the last one shown.
 */
export const REQUESTABLE_CAP_HINT = "The most recent come first.";

/**
 * What the picker says under itself when the server capped the list, or "" when it did not.
 *
 * The wording is `selectFilter.ts::unknownTotalNoticeSentence` with nothing typed (the designer
 * portal's unit spec pins the two together), written out here so this file has no imports and can
 * stay identical in both portals. A flag and no total, so the sentence states the ceiling and never a
 * count it was not given.
 */
export function requestableCeilingSentence(shown: number, truncated: boolean): string {
  if (!truncated || shown <= 0) return "";
  return `Showing the first ${shown}. There are more than are shown. ${REQUESTABLE_CAP_HINT}`;
}
