import { ApiError, ApiUnconfiguredError, describeApiDetail } from "@/lib/api";
import type { MeasurementAnalysisResponse } from "@/lib/media";

/**
 * WHICH WAY `POST /media/analyze-measurement` FAILED — asked once, for the whole web client.
 *
 * ── THE DISTINCTION THE SERVER PAYS FOR AND THE CLIENT USED TO THROW AWAY ───────────────────────
 *
 * `components/media/GridMeasurement.tsx` had a bare `catch { … "Analysis failed — enter it manually" }`
 * around the whole call, and a single `result.available ? "Couldn't read a value" : result.message`
 * on the success path. Three genuinely different things arrived at the researcher as one sentence,
 * and each one ended with them being told to do something that cannot possibly help:
 *
 *  1. **Nobody has configured a vision provider on this deployment.** No amount of re-photographing
 *     an object in better light fixes a missing key, and nothing on the device can fix it either.
 *  2. **The provider was reached and IT failed** — a rate limit, a 500 upstream, a DNS failure on the
 *     server's side. The server is working exactly as designed and says so in the reply; the client
 *     turned that into an instruction to re-photograph a perfectly good object.
 *  3. **Nothing reached the server at all.** That is the ordinary state of this application — a
 *     courtyard with no signal — and it is the only one of the three that may put the word
 *     "connection" on the screen.
 *
 * ── THE RULE THIS FOLLOWS ───────────────────────────────────────────────────────────────────────
 *
 * **THE SERVER'S OWN SENTENCE IS PRINTED VERBATIM WHEREVER THE SERVER SPOKE, AND NO SETTING NAME IS
 * EVER RECONSTRUCTED ON THIS SIDE.** Guessing at a provider's environment variable in this file would
 * be a bundle asserting a server fact it cannot see; the day the measurement provider changes, the
 * screen would send an administrator to a setting that no longer exists.
 *
 * {@link serverSentence} is what makes "wherever the server spoke" DECIDABLE, and it is the whole of
 * the fix for the obvious-but-wrong implementation. Reading `ApiError.message` looks equivalent and
 * is not: `apiFetch` builds that message as
 * `describeApiDetail(detail, response.statusText || "The server refused the request (HTTP ${status}).")`,
 * and **`statusText` is EMPTY over HTTP/2, which every deployed request is.** So a body-less 503 from
 * a gateway in a deploy window would reach the screen as the literal string "The server refused the
 * request (HTTP 503)." — a status code wearing a sentence, under a promise that this branch shows the
 * server naming what is missing. Reading the PAYLOAD instead is what makes "quote the server" true
 * rather than usually-true.
 *
 * ── PORTED, WITH ONE STRUCTURAL DIFFERENCE, SAID OUT LOUD ───────────────────────────────────────
 *
 * The repository this came from routes the verdict through a shared `lib/failureTriage.ts` that every
 * surface quoting a refusal uses, and through a `MeasurementTimeoutError` that `analyzeMeasurementImage`
 * throws when it stops waiting. This repository has neither yet. The classification below therefore
 * reads `ApiError.status` and `ApiError.payload` DIRECTLY rather than through a shared verdict, which
 * is the same decision on the same evidence with one fewer indirection — and it is written as one
 * table so that adding the shared triage later is a substitution rather than a rewrite. Two
 * consequences are real and are not hidden:
 *
 *  * `timed-out` is reachable only from a proxy's **408**, never from this client giving up, because
 *    this client has no clock on the request. A connection that opens and then stalls (a captive
 *    portal, a lift, a tower handover mid-upload) does not reject `fetch`, so the status line reads
 *    "Analyzing…" until the researcher navigates away. The kind is kept — the sentence for it is
 *    correct when a proxy does say so — and the missing clock is a gap to close in `lib/media.ts`.
 *  * There is no `unsendable` kind, because nothing in this client refuses a file locally before
 *    sending it. A camera that did not finish writing a capture lands on `offline`, which is the
 *    honest reading of "no request completed" and is the one the shared triage would improve on.
 *
 * ── THE GUARANTEE THE TYPE CARRIES ──────────────────────────────────────────────────────────────
 *
 * {@link MeasurementRemedy} has no "take another photograph" value, and its absence is the point.
 * NOTHING THIS MODULE CAN PRODUCE IS A JUDGEMENT ABOUT WHAT IS IN THE PICTURE — its lighting, its
 * framing, whether the grid is visible. Every verdict is the deployment, the connection, or the
 * request as a request. The one genuine "the model looked and could not read it" outcome is a 2xx
 * with no numbers in it; that is `readGridAnalysis`'s to name, and it keeps Android's sentence. So
 * "the unconfigured case must not read as the researcher's fault or as a broken photograph" is
 * enforced by the compiler here rather than by a reviewer noticing.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The vocabulary
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHO OR WHAT CLEARS THIS. A second axis: the kind says what happened and this says what to do about
 * it, and collapsing the two is how six different causes came to share eight words. A caller may
 * branch on this without knowing the kinds.
 *
 * THERE IS DELIBERATELY NO "another-photograph" VALUE — see the module header.
 */
export type MeasurementRemedy =
  /** Nobody in the room can clear it. A setting, a key or a deploy, by whoever runs the server. */
  | "an-administrator"
  /** Reconnect and ask again. Nothing reached the server, so nothing was decided and nothing is owed. */
  | "a-connection"
  /** The same request, unchanged, may well work in a minute. The server was reached and did not refuse. */
  | "trying-again"
  /** The server read the request and said no. The file, or the account, has to be different. */
  | "a-different-request";

/** Which way the call failed. Exactly one is true of any failure. */
export type MeasurementFailureKind =
  /** 503, or a legacy `200`/`available: false` — this deployment has no vision provider configured. */
  | "provider-unconfigured"
  /** `200` with `status: "FAILED"` — the provider was reached and IT failed. Not the photograph. */
  | "provider-failed"
  /** This BUILD has no API address ({@link ApiUnconfiguredError}); no request was ever made. */
  | "app-unconfigured"
  /** Nothing reached the server. The only kind that may put the word "connection" on the screen. */
  | "offline"
  /** The wait ran out — today only a proxy's 408; see the module header on the missing client clock. */
  | "timed-out"
  /** The server read the request and refused it: 413 too large, 415 wrong type, 403 not permitted. */
  | "refused"
  /** The server was reached and broke: a 5xx that is not the configuration 503, or a 429. */
  | "server-failed";

export type MeasurementFailure = {
  kind: MeasurementFailureKind;
  /** What to put on the screen. A whole sentence already; never decorate it with a status code. */
  sentence: string;
  remedy: MeasurementRemedy;
  /**
   * Are these the SERVER's words rather than words written in this file?
   *
   * Carried rather than assumed, because "quote the server" is only a real rule if a caller — and a
   * test — can tell when it was kept. `false` means the reply put no usable words behind its answer
   * and the fallback below was used, which names no setting, no provider and no status code.
   */
  serverSaidIt: boolean;
  /** What the server answered with, or null when nothing answered. For logs and tests, not for screens. */
  status: number | null;
};

/* ────────────────────────────────────────────────────────────────────────────
 * The sentences written on THIS side — used only where the reply carried none
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * NOT ONE OF THESE NAMES A SETTING, A PROVIDER OR A STATUS CODE, and that is a constraint rather than
 * a style. They are for a reply that carried no words at all — a gateway's 503, a proxy's 408, a
 * `TypeError` out of `fetch` — where there is nothing to quote. The moment one starts guessing at a
 * provider key it is asserting a server fact this bundle cannot see.
 *
 * NONE OF THEM ASKS FOR ANOTHER PHOTOGRAPH, for the reason in the module header, and **each ends at
 * something that works with no connection**, because there always is one: the tape measure and the
 * box beside it.
 */
export const MEASUREMENT_FALLBACKS: Readonly<Record<MeasurementFailureKind, string>> = {
  "provider-unconfigured":
    "Reading a photograph is not switched on for this repository, so nothing here can measure the object for " +
    "you. Your photograph is fine and there is nothing to re-take — whoever administers the server can turn it " +
    "on, and nothing on this device can. Measure the object and enter the value manually meanwhile.",
  "provider-failed":
    "The service that reads these photographs was reached and did not answer usefully, so there is no " +
    "measurement to show. Nothing is wrong with your photograph. Try again in a minute, or measure the object " +
    "and enter the value manually.",
  "app-unconfigured":
    "This site was published without the address of its data service, so no photograph can be sent anywhere to " +
    "be read. An administrator needs to redeploy it. Measure the object and enter the value manually meanwhile.",
  offline:
    "No connection — reading the photo needs one, and nothing reached the server. Nothing has been queued: a " +
    "reading nobody has checked is not something to bank for later. Measure the object and enter the value " +
    "manually, or try again in signal.",
  "timed-out":
    "The photo was sent but no answer came back before the wait ran out, so there is no measurement to show and " +
    "nothing was written anywhere. Try again on a steadier connection, or measure the object and enter the " +
    "value manually.",
  refused:
    "The server would not accept this request and did not say why. Try a smaller JPEG or PNG straight from the " +
    "camera, or measure the object and enter the value manually.",
  // WORDED TO BE TRUE OF A 429 AS WELL AS A 5xx, which is why it says "busy or briefly out of order"
  // rather than "failed" — the server was reached and asked for time, explicitly or by falling over,
  // and one more kind for the difference would be a row whose only distinction is a word nobody acts
  // on differently.
  "server-failed":
    "The server was reached and did not read the photo — it is busy or briefly out of order. Nothing is wrong " +
    "with your photograph and nothing was written anywhere. Try again in a minute, or measure the object and " +
    "enter the value manually."
};

/**
 * What clears each kind. A table rather than a chain of `if`s, so a new kind cannot be added without
 * an answer — the compiler asks for the row.
 */
const REMEDY: Readonly<Record<MeasurementFailureKind, MeasurementRemedy>> = {
  "provider-unconfigured": "an-administrator",
  "app-unconfigured": "an-administrator",
  offline: "a-connection",
  "timed-out": "a-connection",
  "provider-failed": "trying-again",
  "server-failed": "trying-again",
  refused: "a-different-request"
};

/**
 * Build the one failure a caller shows.
 *
 * The choice between the server's words and ours is made ONCE, here, so no branch below can forget to
 * prefer the server's. `said` is null exactly when the reply carried nothing usable.
 */
function failure(kind: MeasurementFailureKind, said: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: said ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: said !== null, status };
}

/**
 * The same, for a sentence that came from THIS DEVICE rather than from a server.
 *
 * A SEPARATE FUNCTION SO THAT `serverSaidIt` CANNOT BE SET BY ACCIDENT. One failure here carries a
 * real, specific, already-written sentence that no server sent: `ApiUnconfiguredError`'s, composed in
 * `lib/api.ts` and about a redeploy. Passing it through {@link failure} would show the right words
 * under a flag claiming the server had said them — and `serverSaidIt` exists precisely so a test can
 * hold this module to "quote the server". A field that is sometimes a guess is worse than no field.
 */
function localFailure(kind: MeasurementFailureKind, sentence: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: sentence ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: false, status };
}

/** A non-blank string, trimmed, or null. Blank prose is the absence of a sentence, not a sentence. */
function words(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

/**
 * The sentence a failed response is ACTUALLY CARRYING, dug out of the body rather than off the error's
 * `message`. See the module header for why the two are not the same thing.
 *
 * Exported because it is the half a test has to be able to drive: "quote the server wherever the
 * server spoke" is only a rule if the absence of words is observable.
 */
export function serverSentence(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const body = error.payload;
  if (!body || typeof body !== "object") return null;
  const detail = (body as { detail?: unknown }).detail;
  if (detail === undefined || detail === null) return null;
  // The empty-string fallback is what makes "the server said nothing usable" answerable: every other
  // caller of `describeApiDetail` passes a sentence, and a sentence would be indistinguishable here
  // from one the server wrote.
  const sentence = describeApiDetail(detail, "").trim();
  return sentence ? sentence : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * A 2xx that is a failure anyway
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Is this `200` body a FAILURE rather than an answer to be read for numbers? Null when it is an answer.
 *
 * TWO BODIES ARRIVE WITH A 200 AND NEITHER IS ABOUT THE PHOTOGRAPH:
 *
 *  - **`available: false`** — the route's older shape for "no provider is configured". Kept whatever
 *    the server does today, because a web build outlives a backend deploy and the clients and the API
 *    are deployed on different days by different people. The message it carries is the one that names
 *    the missing setting. Dropping this arm puts the feature back where it started, with
 *    "unconfigured" wearing "unreadable"'s clothes for every researcher on an older server.
 *  - **`status: "FAILED"`** — the server caught an exception from the PROVIDER and returned
 *    `available: true`, `analysis: null` and a sentence naming the fault. It is passed through as 200
 *    deliberately, because the server is working exactly as designed. It is NOT an unreadable grid.
 *
 * A `FAILED` BODY IS CHECKED BEFORE THE NUMBERS AND NOT AFTER, on purpose: `analysis` is `null` on
 * that path, so a reader that looks for readings first finds none and reaches the unreadable branch
 * before anything has asked why. **Order is the whole fix.**
 */
export function measurementBodyFailure(response: MeasurementAnalysisResponse): MeasurementFailure | null {
  const said = words(response.message);
  if (response.available === false) return failure("provider-unconfigured", said, 200);
  if (response.status === "FAILED") return failure("provider-failed", said, 200);
  return null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * A thrown value
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Sort a thrown value into exactly one failure.
 *
 * THE ORDER IS LOAD-BEARING:
 *
 *  1. {@link ApiUnconfiguredError} FIRST, before anything looks at a status. It is a 503 that no
 *     server ever sent — the request was never made — and its own message names an administrator
 *     action (redeploy the site with its API address) that has nothing to do with a vision provider.
 *     Read as the provider 503 it would send an operator to add a key that changes nothing. Because
 *     it EXTENDS `ApiError` with status 503 it would otherwise fall straight through the branch
 *     below, so the guard is written out explicitly rather than left to rely on ordering.
 *  2. Then the server's answer, if there was one.
 *  3. Then the default, which is and must remain "nothing reached a server".
 *
 * A 401 LANDS ON `refused` AND THAT IS ACCEPTABLE RATHER THAN UNCONSIDERED. `apiFetch` clears the
 * token and navigates to /login before it throws, so the sentence produced here is on a screen that is
 * already being replaced; the sentence itself is the server's own, which is true. It is written down
 * rather than given a kind because a kind nothing can reach is a row that rots.
 */
export function classifyMeasurementFailure(error: unknown): MeasurementFailure {
  if (error instanceof ApiUnconfiguredError) return localFailure("app-unconfigured", words(error.message), null);

  if (error instanceof ApiError) {
    const said = serverSentence(error);
    // On this one route 503 carries a second, narrower meaning than it does anywhere else in the API:
    // *this deployment has no vision provider*, which no amount of waiting clears. Everywhere else a
    // 503 is transient and the outbox drains it; this route has no outbox — a person is standing in
    // front of the screen holding the object.
    if (error.status === 503) return failure("provider-unconfigured", said, error.status);
    // A proxy saying the request never completed. "You have no connection" sends a researcher out of
    // the building; "the connection is there and too slow for an 8 MB photograph" does not.
    if (error.status === 408) return failure("timed-out", said, error.status);
    if (error.status === 429 || error.status >= 500) return failure("server-failed", said, error.status);
    // Everything else the server DECIDED: 413 over the upload ceiling, 415 on a file it cannot decode,
    // 422 on an unknown dimension, 403 on permission. Each already carries a sentence naming the
    // limit, the type or the permission, which is the whole reason to quote rather than summarise.
    return failure("refused", said, error.status);
  }

  // A `TypeError` out of `fetch`, an abort, a DNS failure — nothing was decided by anybody. No
  // `said`, because there is no reply to quote.
  return failure("offline", null, null);
}
